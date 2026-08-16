//! End-to-end tests for the pairing and authentication handshake.
//!
//! These drive the real accept loop over a real QUIC connection on loopback, with a
//! client that speaks exactly what `rust-receiver/zc-network/src/client.rs` speaks. They
//! are the executable form of the Phase 1 verification list: a correct PIN connects, a
//! wrong PIN is refused, and a client that skips pairing entirely is refused.

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicI32, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use crossbeam_channel::{unbounded, Receiver};
use quinn::{Connection, Endpoint};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};

use crate::crypto::{auth_token, derive_psk, Psk, EPHEMERAL_KEY_LEN};
use crate::frames::FrameQueue;
use crate::pairing::PinChannel;
use crate::store::SecureStore;
use crate::{
    accept_loop, bind_endpoint, Server, ALPN_PAIRING, ALPN_STREAM, AUDIO_QUEUE_DEPTH,
    STATE_AUTHENTICATED, STATE_IDLE, VIDEO_QUEUE_DEPTH,
};

// -- Test harness -----------------------------------------------------------------------

/// A running server plus the scratch directory holding its PSK and certificate.
struct Harness {
    server: Arc<Server>,
    addr: SocketAddr,
    dir: PathBuf,
    _input_rx: Receiver<Vec<u8>>,
}

impl Harness {
    /// Starts a server whose state lives in a fresh directory.
    fn fresh(name: &str) -> Self {
        let mut dir = std::env::temp_dir();
        dir.push(format!("rust_quic_server_e2e_{name}_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        Self::at(dir)
    }

    /// Starts a server on top of an existing directory, i.e. simulates an app restart.
    fn at(dir: PathBuf) -> Self {
        install_crypto_provider();

        let store = SecureStore::open(&dir).expect("open store");
        let persisted = store.load_psk();
        let (input_tx, input_rx) = unbounded();

        let server = Arc::new(Server {
            state: AtomicI32::new(STATE_IDLE),
            video: Arc::new(FrameQueue::new(VIDEO_QUEUE_DEPTH)),
            audio: Arc::new(FrameQueue::new(AUDIO_QUEUE_DEPTH)),
            input_tx,
            pins: PinChannel::new(),
            store,
            psk: Mutex::new(persisted),
            sessions: AtomicUsize::new(0),
            session_lock: tokio::sync::Mutex::new(()),
        });

        let loopback: SocketAddr = "127.0.0.1:0".parse().expect("loopback addr");
        let endpoint = bind_endpoint(&server, loopback).expect("bind endpoint");
        let addr = endpoint.local_addr().expect("local addr");

        tokio::spawn(accept_loop(server.clone(), endpoint));

        Harness { server, addr, dir, _input_rx: input_rx }
    }

    fn state(&self) -> i32 {
        self.server.state.load(Ordering::SeqCst)
    }

    /// Stands in for the user typing a PIN, on a blocking thread as Kotlin does.
    ///
    /// Waits for the server to actually be asking before submitting, which is the same
    /// thing `SecurityBridge.isAwaitingPin()` tells the PIN screen.
    fn type_pin(&self, pin: &str) -> tokio::task::JoinHandle<bool> {
        let server = self.server.clone();
        let pin = pin.to_string();
        tokio::task::spawn_blocking(move || {
            for _ in 0..600 {
                if server.pins.is_awaiting() {
                    break;
                }
                std::thread::sleep(Duration::from_millis(10));
            }
            let accepted = server.pins.submit_blocking(pin.clone());
            println!("  [phone] user typed {pin} -> verifyPin returned {accepted}");
            accepted
        })
    }

    fn cleanup(self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

fn install_crypto_provider() {
    let _ = rustls::crypto::ring::default_provider().install_default();
}

/// Accepts any certificate and records its SHA-256, mirroring the receiver's
/// trust-on-first-use verifier.
#[derive(Debug)]
struct RecordingVerifier {
    seen: Arc<Mutex<Option<[u8; 32]>>>,
}

impl ServerCertVerifier for RecordingVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let digest = ring::digest::digest(&ring::digest::SHA256, end_entity.as_ref());
        let mut fp = [0u8; 32];
        fp.copy_from_slice(digest.as_ref());
        if let Ok(mut slot) = self.seen.lock() {
            *slot = Some(fp);
        }
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        rustls::crypto::ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}

/// A stand-in for the Windows receiver.
struct TestClient {
    conn: Connection,
    fingerprint: [u8; 32],
}

impl TestClient {
    async fn connect(addr: SocketAddr, alpn: &[u8]) -> Result<Self, String> {
        install_crypto_provider();

        let seen = Arc::new(Mutex::new(None));
        let mut crypto = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(RecordingVerifier { seen: seen.clone() }))
            .with_no_client_auth();
        crypto.alpn_protocols = vec![alpn.to_vec()];

        let quic_crypto = quinn::crypto::rustls::QuicClientConfig::try_from(crypto)
            .map_err(|e| format!("client crypto: {e}"))?;

        let bind: SocketAddr = "127.0.0.1:0".parse().map_err(|e| format!("{e}"))?;
        let mut endpoint = Endpoint::client(bind).map_err(|e| format!("client bind: {e}"))?;
        endpoint.set_default_client_config(quinn::ClientConfig::new(Arc::new(quic_crypto)));

        let conn = endpoint
            .connect(addr, "localhost")
            .map_err(|e| format!("connect: {e}"))?
            .await
            .map_err(|e| format!("handshake: {e}"))?;

        // Keep the endpoint alive for as long as the connection is used.
        std::mem::forget(endpoint);

        let fingerprint = seen.lock().map_err(|_| "poisoned")?.ok_or("no certificate seen")?;
        Ok(TestClient { conn, fingerprint })
    }

    /// Sends `P` + pubkey and waits for the phone's `OK`, exactly as client.rs does.
    async fn send_pairing_hello(&self, pubkey: &[u8; EPHEMERAL_KEY_LEN]) -> Result<(), String> {
        let (mut send, mut recv) =
            self.conn.open_bi().await.map_err(|e| format!("open_bi: {e}"))?;
        send.write_all(b"P").await.map_err(|e| format!("write P: {e}"))?;
        send.write_all(pubkey).await.map_err(|e| format!("write pubkey: {e}"))?;

        let mut ok = [0u8; 2];
        recv.read_exact(&mut ok).await.map_err(|e| format!("read ack: {e}"))?;
        if &ok != b"OK" {
            return Err(format!("expected OK, got {:?}", ok));
        }
        Ok(())
    }

    /// Sends `A` + token and reports whether the phone acknowledged.
    async fn send_auth(&self, token: &[u8; 32]) -> Result<(), String> {
        let (mut send, mut recv) =
            self.conn.open_bi().await.map_err(|e| format!("open_bi: {e}"))?;
        send.write_all(b"A").await.map_err(|e| format!("write A: {e}"))?;
        send.write_all(token).await.map_err(|e| format!("write token: {e}"))?;
        send.finish().map_err(|e| format!("finish: {e}"))?;

        let mut ok = [0u8; 2];
        recv.read_exact(&mut ok).await.map_err(|e| format!("read ack: {e}"))?;
        if &ok != b"OK" {
            return Err(format!("expected OK, got {:?}", ok));
        }
        Ok(())
    }

    /// Reads one length-prefixed frame off the first stream the phone opens.
    async fn read_one_frame(&self) -> Result<Vec<u8>, String> {
        let mut stream = self.conn.accept_uni().await.map_err(|e| format!("accept_uni: {e}"))?;
        let mut len_buf = [0u8; 4];
        stream.read_exact(&mut len_buf).await.map_err(|e| format!("read len: {e}"))?;
        let len = u32::from_le_bytes(len_buf) as usize;
        let mut frame = vec![0u8; len];
        stream.read_exact(&mut frame).await.map_err(|e| format!("read frame: {e}"))?;
        Ok(frame)
    }
}

/// Deterministic stand-in for the PC's X25519 ephemeral public key.
fn ephemeral_key(seed: u8) -> [u8; EPHEMERAL_KEY_LEN] {
    [seed; EPHEMERAL_KEY_LEN]
}

fn random_token() -> [u8; 32] {
    use ring::rand::SecureRandom;
    let mut token = [0u8; 32];
    let rng = ring::rand::SystemRandom::new();
    rng.fill(&mut token).expect("system rng");
    token
}

/// Waits for `predicate` to hold, up to ~3 seconds.
async fn eventually(mut predicate: impl FnMut() -> bool) -> bool {
    for _ in 0..300 {
        if predicate() {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    false
}

// -- Phase 1 verification 1: the correct PIN ---------------------------------------------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn correct_pin_pairs_authenticates_and_streams_video() {
    println!("\n=== VERIFICATION 1: correct PIN ===");
    let h = Harness::fresh("correct_pin");
    assert!(!h.server.is_paired(), "a fresh phone has no pairing key");

    // The PC picks the PIN and shows it on screen.
    let pc_pin = "482915";
    let pubkey = ephemeral_key(0x11);
    println!("  [pc]    displays PIN {pc_pin}");

    let client = TestClient::connect(h.addr, ALPN_PAIRING).await.expect("connect");
    println!("  [pc]    connected with ALPN androiddex-pairing");

    // The user reads it off the PC and types the same digits into the phone.
    let typed = h.type_pin(pc_pin);

    client.send_pairing_hello(&pubkey).await.expect("pairing hello");
    println!("  [pc]    sent P + pubkey, received OK");

    let psk = derive_psk(pc_pin, &pubkey);
    client.send_auth(&auth_token(&psk)).await.expect("auth accepted");
    println!("  [pc]    sent A + token, received OK");

    assert!(typed.await.expect("pin task"), "verifyPin must report success");
    assert!(eventually(|| h.state() == STATE_AUTHENTICATED).await, "state must reach AUTHENTICATED");
    println!("  [phone] connectionState = {} (AUTHENTICATED)", h.state());

    // Video reaches the PC.
    h.server.video.push(vec![0x01, 0xDE, 0xAD, 0xBE, 0xEF]);
    let frame = client.read_one_frame().await.expect("video frame");
    assert_eq!(frame, vec![0x01, 0xDE, 0xAD, 0xBE, 0xEF]);
    println!("  [pc]    received a {} byte video frame -> video appears", frame.len());

    // And the key is on disk, so the next launch does not need a PIN.
    assert!(h.server.is_paired());
    assert_eq!(h.server.store.load_psk(), Some(psk), "PSK must be persisted");
    println!("  RESULT: connected, video flowing, pairing key persisted\n");

    h.cleanup();
}

// -- Phase 1 verification 2: the wrong PIN ------------------------------------------------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn wrong_pin_is_refused_and_nothing_is_paired() {
    println!("\n=== VERIFICATION 2: wrong PIN ===");
    let h = Harness::fresh("wrong_pin");

    let pc_pin = "482915";
    let mistyped = "482916";
    let pubkey = ephemeral_key(0x22);
    println!("  [pc]    displays PIN {pc_pin}");

    let client = TestClient::connect(h.addr, ALPN_PAIRING).await.expect("connect");

    // The user fat-fingers the last digit.
    let typed = h.type_pin(mistyped);

    client.send_pairing_hello(&pubkey).await.expect("pairing hello");
    println!("  [pc]    sent P + pubkey, received OK (the phone always acknowledges here)");

    // The PC derives from the PIN *it* displayed; the phone derived from what was typed.
    let pc_psk = derive_psk(pc_pin, &pubkey);
    let outcome = client.send_auth(&auth_token(&pc_psk)).await;

    println!("  [pc]    sent A + token -> {}", match &outcome {
        Ok(()) => "ACCEPTED".to_string(),
        Err(e) => format!("REFUSED ({e})"),
    });
    assert!(outcome.is_err(), "a token derived from a different PIN must not be acknowledged");

    assert!(!typed.await.expect("pin task"), "verifyPin must report failure");
    assert_ne!(h.state(), STATE_AUTHENTICATED, "state must not become AUTHENTICATED");
    assert!(!h.server.is_paired(), "no pairing key may be stored");
    assert!(h.server.store.load_psk().is_none(), "nothing may be written to disk");
    println!("  [phone] connectionState = {} (not AUTHENTICATED), no key stored", h.state());

    // Retrying with the same wrong PIN is refused again — the first rejection was not a
    // one-off race. The connection stays open only so the user can retry within the
    // five-attempt budget; see pin_attempts_are_capped_per_connection.
    let typed_again = h.type_pin(mistyped);
    let retry = client.send_auth(&auth_token(&pc_psk)).await;
    assert!(retry.is_err(), "a second wrong PIN must also be refused");
    assert!(!typed_again.await.expect("pin task"));
    assert_ne!(h.state(), STATE_AUTHENTICATED);
    println!("  [pc]    retried with the same wrong PIN -> REFUSED again");

    // Nothing was ever streamed: no unidirectional stream was opened to this peer.
    let opened = tokio::time::timeout(Duration::from_millis(300), client.conn.accept_uni()).await;
    assert!(opened.is_err(), "no video stream may be opened to an unauthenticated peer");
    println!("  [pc]    no video stream was ever opened");
    println!("  RESULT: refused\n");

    h.cleanup();
}

// -- Phase 1 verification 3: skipping the handshake ---------------------------------------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn raw_client_that_skips_pairing_is_refused() {
    println!("\n=== VERIFICATION 3: skipped handshake ===");

    // (a) Against a phone that has never been paired.
    let h = Harness::fresh("skip_unpaired");
    let client = TestClient::connect(h.addr, ALPN_STREAM).await.expect("connect");
    let outcome = client.send_auth(&random_token()).await;
    println!("  [attacker] unpaired phone, A + 32 random bytes -> {}", match &outcome {
        Ok(()) => "ACCEPTED".to_string(),
        Err(e) => format!("REFUSED ({e})"),
    });
    assert!(outcome.is_err());
    assert_ne!(h.state(), STATE_AUTHENTICATED);
    let dir = h.dir.clone();
    h.cleanup();

    // (b) Against a phone that IS paired — the case that matters, since the attacker is
    //     on the same network as a working setup.
    let _ = std::fs::remove_dir_all(&dir);
    let h = Harness::fresh("skip_paired");
    let psk: Psk = derive_psk("135790", &ephemeral_key(0x33));
    h.server.store.store_psk(&psk).expect("seed a pairing");
    h.server.set_psk(psk);

    for attempt in 1..=3 {
        let client = TestClient::connect(h.addr, ALPN_STREAM).await.expect("connect");
        let outcome = client.send_auth(&random_token()).await;
        println!("  [attacker] paired phone, guess {attempt} -> {}", match &outcome {
            Ok(()) => "ACCEPTED".to_string(),
            Err(e) => format!("REFUSED ({e})"),
        });
        assert!(outcome.is_err(), "a random token must never authenticate");
        assert_ne!(h.state(), STATE_AUTHENTICATED);
    }

    // The real token still works, proving the rejections above are not blanket failures.
    let client = TestClient::connect(h.addr, ALPN_STREAM).await.expect("connect");
    client.send_auth(&auth_token(&psk)).await.expect("the genuine token must be accepted");
    println!("  [pc]       genuine token -> ACCEPTED");
    assert!(eventually(|| h.state() == STATE_AUTHENTICATED).await);
    println!("  RESULT: forged tokens refused, genuine token accepted\n");

    h.cleanup();
}

// -- Supporting behaviour -----------------------------------------------------------------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_paired_pc_reconnects_without_a_pin_after_a_restart() {
    println!("\n=== Reconnect after restart ===");
    let h = Harness::fresh("restart");
    let dir = h.dir.clone();

    let pin = "246810";
    let pubkey = ephemeral_key(0x44);
    let client = TestClient::connect(h.addr, ALPN_PAIRING).await.expect("connect");
    let first_fingerprint = client.fingerprint;

    let typed = h.type_pin(pin);
    client.send_pairing_hello(&pubkey).await.expect("hello");
    let psk = derive_psk(pin, &pubkey);
    client.send_auth(&auth_token(&psk)).await.expect("auth");
    assert!(typed.await.expect("pin task"));
    drop(client);

    // Restart the app: same data directory, brand-new server.
    let h2 = Harness::at(dir);
    assert!(h2.server.is_paired(), "the pairing must survive a restart");

    let client = TestClient::connect(h2.addr, ALPN_STREAM).await.expect("reconnect");

    // Phase 2: the pinned fingerprint has to still match, or the receiver would discard
    // its trust data and force a fresh pairing on every launch.
    assert_eq!(
        client.fingerprint, first_fingerprint,
        "the certificate must be identical across restarts"
    );
    println!("  certificate fingerprint unchanged across restart");

    client.send_auth(&auth_token(&psk)).await.expect("silent re-auth");
    assert!(eventually(|| h2.state() == STATE_AUTHENTICATED).await);
    println!("  RESULT: reconnected with no PIN prompt\n");

    h2.cleanup();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn pin_attempts_are_capped_per_connection() {
    println!("\n=== PIN attempt cap ===");
    let h = Harness::fresh("attempts");
    let pubkey = ephemeral_key(0x55);

    let client = TestClient::connect(h.addr, ALPN_PAIRING).await.expect("connect");

    // The PC displays a PIN the guesser does not know, and keeps proving the same PSK.
    let pc_pin = "999999";
    let pc_token = auth_token(&derive_psk(pc_pin, &pubkey));

    // Five wrong guesses, each with its own auth stream, as a brute-forcing user would.
    for attempt in 1..=crate::MAX_PIN_ATTEMPTS {
        let guess = format!("{attempt:06}");
        let typed = h.type_pin(&guess);
        if attempt == 1 {
            client.send_pairing_hello(&pubkey).await.expect("hello");
        }
        let outcome = client.send_auth(&pc_token).await;
        assert!(outcome.is_err(), "guess {attempt} must not be acknowledged");
        assert!(!typed.await.expect("pin task"), "guess {attempt} must be rejected");
        println!("  guess {guess} rejected");
    }

    assert!(
        eventually(|| client.conn.close_reason().is_some()).await,
        "the connection must be closed once the attempts are used up"
    );
    println!("  RESULT: connection closed after {} attempts\n", crate::MAX_PIN_ATTEMPTS);

    h.cleanup();
}

/// Phase 3: a connection that negotiates an ALPN the server does not serve used to take
/// the whole accept loop down through an `unwrap()`. It must now be a local failure.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_bogus_alpn_does_not_stop_the_server() {
    println!("\n=== Malformed handshake resilience ===");
    let h = Harness::fresh("bad_alpn");
    let psk: Psk = derive_psk("111222", &ephemeral_key(0x66));
    h.server.store.store_psk(&psk).expect("seed pairing");
    h.server.set_psk(psk);

    // An unsupported ALPN is refused by the TLS layer itself.
    for _ in 0..5 {
        let outcome = TestClient::connect(h.addr, b"totally-not-androiddex").await;
        assert!(outcome.is_err(), "an unknown ALPN must not produce a connection");
    }
    println!("  5 connections with an unknown ALPN refused");

    // A connection that negotiates a valid ALPN and then goes silent must not wedge
    // anything either; it is dropped without ever being served.
    for _ in 0..5 {
        let _ = TestClient::connect(h.addr, ALPN_STREAM).await.expect("connect");
    }
    println!("  5 connections that never send anything dropped");

    // The server is still serving.
    let client = TestClient::connect(h.addr, ALPN_STREAM).await.expect("connect");
    client.send_auth(&auth_token(&psk)).await.expect("still authenticating");
    assert!(eventually(|| h.state() == STATE_AUTHENTICATED).await);
    println!("  RESULT: accept loop survived and still authenticates\n");

    h.cleanup();
}

/// Phase 4: a stalled consumer must not let the queue grow without bound.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn video_backlog_is_bounded_and_drops_are_counted() {
    println!("\n=== Frame dropping ===");
    let h = Harness::fresh("drops");

    for i in 0..500u32 {
        h.server.video.push(i.to_le_bytes().to_vec());
    }
    assert_eq!(
        h.server.video.dropped(),
        500 - VIDEO_QUEUE_DEPTH as u64,
        "everything past the two-frame window must be dropped, not buffered"
    );
    println!(
        "  pushed 500 frames with nobody connected -> {} dropped, {} queued",
        h.server.video.dropped(),
        VIDEO_QUEUE_DEPTH
    );
    println!("  RESULT: backlog bounded at {VIDEO_QUEUE_DEPTH} frames\n");

    h.cleanup();
}
