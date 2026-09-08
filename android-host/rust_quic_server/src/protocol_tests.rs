#![cfg(test)]

//! End-to-end tests for the pairing and authentication handshake (Protocol v2).
//!
//! These drive the real accept loop over a real QUIC connection on loopback, with a
//! client that speaks Protocol v2.

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicI32, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use crossbeam_channel::{unbounded, Receiver};
use quinn::{Connection, Endpoint};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};

use crate::crypto::{client_proof, derive_pairing_v2, verify_server_proof, Psk};
use crate::frames::FrameQueue;
use crate::pairing::ConfirmationChannel;
use crate::store::SecureStore;
use crate::{
    accept_loop, bind_endpoint, Server, ALPN_PAIRING, ALPN_STREAM, AUDIO_QUEUE_DEPTH,
    STATE_AUTHENTICATED, STATE_IDLE, STATE_PAIRING, VIDEO_QUEUE_DEPTH,
};

// -- Test harness -----------------------------------------------------------------------

struct Harness {
    server: Arc<Server>,
    addr: SocketAddr,
    dir: PathBuf,
    _input_rx: Receiver<Vec<u8>>,
}

impl Harness {
    fn fresh(name: &str) -> Self {
        let mut dir = std::env::temp_dir();
        dir.push(format!("rust_quic_server_v2_e2e_{name}_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        Self::at(dir)
    }

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
            confirmations: ConfirmationChannel::new(),
            store,
            psk: Mutex::new(persisted),
            sessions: AtomicUsize::new(0),
            session_lock: tokio::sync::Mutex::new(()),
            pairing_cooldowns: Mutex::new(std::collections::HashMap::new()),
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

    /// Simulates user tapping "Codes match" (true) or "They're different" (false) on the phone.
    fn confirm_pairing(&self, matched: bool) -> tokio::task::JoinHandle<bool> {
        let server = self.server.clone();
        tokio::task::spawn_blocking(move || {
            for _ in 0..600 {
                if server.confirmations.is_awaiting() {
                    break;
                }
                std::thread::sleep(Duration::from_millis(10));
            }
            let sas = server.confirmations.get_pending_sas().unwrap_or_default();
            let accepted = server.confirmations.submit_blocking(matched);
            println!("  [phone] SAS={sas} user matched={matched} -> submit returned {accepted}");
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

        std::mem::forget(endpoint);

        let fingerprint = seen.lock().map_err(|_| "poisoned")?.ok_or("no certificate seen")?;
        Ok(TestClient { conn, fingerprint })
    }

    /// Performs the PC side of Protocol v2 pairing.
    async fn pair(&self) -> Result<(String, Psk), String> {
        let rng = ring::rand::SystemRandom::new();
        let my_private = ring::agreement::EphemeralPrivateKey::generate(&ring::agreement::X25519, &rng)
            .map_err(|_| "failed to generate ephemeral key")?;
        let my_public = my_private
            .compute_public_key()
            .map_err(|_| "failed to compute public key")?;
        let mut a_bytes = [0u8; 32];
        a_bytes.copy_from_slice(my_public.as_ref());

        let (mut send, mut recv) = self.conn.open_bi().await.map_err(|e| format!("open_bi: {e}"))?;

        let mut hello = [0u8; 33];
        hello[0] = b'P';
        hello[1..].copy_from_slice(&a_bytes);
        send.write_all(&hello).await.map_err(|e| format!("write hello: {e}"))?;

        let mut reply = [0u8; 33];
        recv.read_exact(&mut reply).await.map_err(|e| format!("read reply: {e}"))?;
        if reply[0] != b'Q' {
            return Err(format!("expected 'Q' tag, got {}", reply[0]));
        }
        let mut b_bytes = [0u8; 32];
        b_bytes.copy_from_slice(&reply[1..]);

        let mut binding = [0u8; 32];
        self.conn
            .export_keying_material(&mut binding, b"androiddex-pair-v2", b"")
            .map_err(|e| format!("export_keying_material: {e:?}"))?;

        let peer_public = ring::agreement::UnparsedPublicKey::new(&ring::agreement::X25519, &b_bytes);
        let (sas, psk) = ring::agreement::agree_ephemeral(
            my_private,
            &peer_public,
            |z_bytes| {
                let mut z = [0u8; 32];
                z.copy_from_slice(z_bytes);
                derive_pairing_v2(&a_bytes, &b_bytes, &binding, &z)
                    .map_err(|e| format!("derivation failed: {e}"))
            },
        )
        .map_err(|_| "X25519 agreement failed".to_string())??;

        let mut verdict = [0u8; 1];
        recv.read_exact(&mut verdict).await.map_err(|e| format!("read verdict: {e}"))?;
        if verdict[0] == b'Y' {
            Ok((sas, psk))
        } else {
            Err(format!("pairing rejected by phone: got byte {}", verdict[0]))
        }
    }

    /// Performs the PC side of Protocol v2 re-authentication.
    async fn authenticate(&self, psk: &Psk) -> Result<[u8; 32], String> {
        let mut binding = [0u8; 32];
        self.conn
            .export_keying_material(&mut binding, b"androiddex-auth-v2", b"")
            .map_err(|e| format!("export_keying_material: {e:?}"))?;

        let rng = ring::rand::SystemRandom::new();
        let mut client_nonce = [0u8; 32];
        ring::rand::SecureRandom::fill(&rng, &mut client_nonce).map_err(|_| "rng")?;

        let (mut send, mut recv) = self.conn.open_bi().await.map_err(|e| format!("open_bi: {e}"))?;

        let mut req = [0u8; 33];
        req[0] = b'C';
        req[1..].copy_from_slice(&client_nonce);
        send.write_all(&req).await.map_err(|e| format!("write req: {e}"))?;

        let mut resp = [0u8; 65];
        recv.read_exact(&mut resp).await.map_err(|e| format!("read resp: {e}"))?;
        if resp[0] != b'S' {
            return Err(format!("expected 'S' tag, got {}", resp[0]));
        }
        let mut server_nonce = [0u8; 32];
        server_nonce.copy_from_slice(&resp[1..33]);
        let presented_server_proof = &resp[33..65];

        if !verify_server_proof(psk, &client_nonce, &server_nonce, &binding, presented_server_proof) {
            return Err("server proof verification failed".into());
        }

        let c_proof = client_proof(psk, &client_nonce, &server_nonce, &binding);
        let mut client_resp = [0u8; 33];
        client_resp[0] = b'D';
        client_resp[1..].copy_from_slice(&c_proof);
        send.write_all(&client_resp).await.map_err(|e| format!("write client proof: {e}"))?;
        send.finish().map_err(|e| format!("finish: {e}"))?;

        Ok(c_proof)
    }

    /// Sends a raw/forged client proof during re-auth.
    async fn authenticate_with_proof_override(&self, proof_override: &[u8; 32]) -> Result<(), String> {
        let (mut send, mut recv) = self.conn.open_bi().await.map_err(|e| format!("open_bi: {e}"))?;

        let rng = ring::rand::SystemRandom::new();
        let mut client_nonce = [0u8; 32];
        ring::rand::SecureRandom::fill(&rng, &mut client_nonce).map_err(|_| "rng")?;

        let mut req = [0u8; 33];
        req[0] = b'C';
        req[1..].copy_from_slice(&client_nonce);
        send.write_all(&req).await.map_err(|e| format!("write req: {e}"))?;

        let mut resp = [0u8; 65];
        recv.read_exact(&mut resp).await.map_err(|e| format!("read resp: {e}"))?;
        if resp[0] != b'S' {
            return Err(format!("expected 'S' tag, got {}", resp[0]));
        }

        let mut client_resp = [0u8; 33];
        client_resp[0] = b'D';
        client_resp[1..].copy_from_slice(proof_override);
        send.write_all(&client_resp).await.map_err(|e| format!("write proof: {e}"))?;
        send.finish().map_err(|e| format!("finish: {e}"))?;

        Ok(())
    }

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

async fn eventually(mut predicate: impl FnMut() -> bool) -> bool {
    for _ in 0..300 {
        if predicate() {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    false
}

// -- Protocol v2 tests -------------------------------------------------------------------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn pair_v2_success_and_streams_video() {
    println!("\n=== TEST: Protocol v2 pair success ===");
    let h = Harness::fresh("v2_pair_success");
    assert!(!h.server.is_paired());

    let client = TestClient::connect(h.addr, ALPN_PAIRING).await.expect("connect");
    let user_confirm = h.confirm_pairing(true);

    let (pc_sas, psk) = client.pair().await.expect("pair");
    println!("  [pc] computed SAS: {pc_sas}");

    assert!(user_confirm.await.expect("confirm task"));
    assert!(eventually(|| h.state() == STATE_AUTHENTICATED).await);

    // Video streams
    h.server.video.push(vec![0xAA, 0xBB, 0xCC]);
    let frame = client.read_one_frame().await.expect("frame");
    assert_eq!(frame, vec![0xAA, 0xBB, 0xCC]);

    // Key persisted
    assert!(h.server.is_paired());
    assert_eq!(h.server.store.load_psk(), Some(psk));

    h.cleanup();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn pair_v2_rejected_confirmation_stores_nothing() {
    println!("\n=== TEST: Protocol v2 rejected confirmation ===");
    let h = Harness::fresh("v2_pair_reject");
    assert!(!h.server.is_paired());

    let client = TestClient::connect(h.addr, ALPN_PAIRING).await.expect("connect");
    let user_reject = h.confirm_pairing(false);

    let pair_result = client.pair().await;
    assert!(pair_result.is_err(), "pair must fail when user rejects match");
    assert!(!user_reject.await.expect("reject task"));

    assert_ne!(h.state(), STATE_AUTHENTICATED);
    assert!(!h.server.is_paired());
    assert!(h.server.store.load_psk().is_none(), "nothing persisted on reject");

    h.cleanup();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn reauth_v2_success_after_restart() {
    println!("\n=== TEST: Protocol v2 re-auth after restart ===");
    let h = Harness::fresh("v2_restart");
    let dir = h.dir.clone();

    let client = TestClient::connect(h.addr, ALPN_PAIRING).await.expect("connect");
    let first_fingerprint = client.fingerprint;
    let user_confirm = h.confirm_pairing(true);
    let (_sas, psk) = client.pair().await.expect("pair");
    assert!(user_confirm.await.expect("confirm"));
    drop(client);

    // Restart server on same directory
    let h2 = Harness::at(dir);
    assert!(h2.server.is_paired(), "PSK must survive restart");

    let client2 = TestClient::connect(h2.addr, ALPN_STREAM).await.expect("reconnect");
    assert_eq!(client2.fingerprint, first_fingerprint);

    client2.authenticate(&psk).await.expect("authenticate");
    assert!(eventually(|| h2.state() == STATE_AUTHENTICATED).await);

    h2.cleanup();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn reauth_v2_wrong_psk_refused() {
    println!("\n=== TEST: Protocol v2 wrong PSK refused ===");
    let h = Harness::fresh("v2_wrong_psk");
    let genuine_psk: Psk = [0x77u8; 32];
    h.server.store.store_psk(&genuine_psk).expect("store psk");
    h.server.set_psk(genuine_psk);

    let bad_psk: Psk = [0x88u8; 32];
    let client = TestClient::connect(h.addr, ALPN_STREAM).await.expect("connect");

    // Client fails to authenticate with wrong PSK (server proof mismatch or client proof rejected)
    let auth_result = client.authenticate(&bad_psk).await;
    assert!(auth_result.is_err());
    assert_ne!(h.state(), STATE_AUTHENTICATED);

    h.cleanup();
}

/// Proves SEC-16 is fixed: a proof captured from an earlier TLS session is rejected
/// when replayed into a new session.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn reauth_v2_replayed_proof_is_refused() {
    println!("\n=== TEST: Protocol v2 replayed proof is refused (SEC-16) ===");
    let h = Harness::fresh("v2_replay");
    let psk: Psk = [0x42u8; 32];
    h.server.store.store_psk(&psk).expect("store psk");
    h.server.set_psk(psk);

    // Session 1: genuine client connects and authenticates, generating valid client_proof_1
    let client1 = TestClient::connect(h.addr, ALPN_STREAM).await.expect("session 1 connect");
    let client_proof_session1 = client1.authenticate(&psk).await.expect("session 1 auth");
    assert!(eventually(|| h.state() == STATE_AUTHENTICATED).await);
    drop(client1);

    // Wait for state to settle back
    assert!(eventually(|| h.state() != STATE_AUTHENTICATED).await);

    // Session 2: attacker attempts to replay client_proof_session1 in a fresh session
    let client2 = TestClient::connect(h.addr, ALPN_STREAM).await.expect("session 2 connect");
    let _ = client2.authenticate_with_proof_override(&client_proof_session1).await;

    // Must be rejected: new TLS session has distinct channel binding and fresh nonces
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert_ne!(h.state(), STATE_AUTHENTICATED, "replayed proof must never authenticate session");

    // Verify genuinely fresh authentication still works
    let client3 = TestClient::connect(h.addr, ALPN_STREAM).await.expect("session 3 connect");
    client3.authenticate(&psk).await.expect("genuine auth in session 3");
    assert!(eventually(|| h.state() == STATE_AUTHENTICATED).await);

    h.cleanup();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn pairing_is_refused_when_already_paired_v2() {
    println!("\n=== TEST: Refuse pairing when already paired ===");
    let h = Harness::fresh("already_paired_v2");
    let original_psk: Psk = [0x55u8; 32];
    h.server.store.store_psk(&original_psk).expect("seed pairing");
    h.server.set_psk(original_psk);
    assert!(h.server.is_paired());

    let client = TestClient::connect(h.addr, ALPN_PAIRING).await.expect("connect");
    let pair_result = client.pair().await;
    assert!(pair_result.is_err());
    assert_ne!(h.state(), STATE_PAIRING);

    assert_eq!(h.server.psk(), Some(original_psk));
    h.cleanup();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_bogus_alpn_does_not_stop_the_server_v2() {
    println!("\n=== TEST: Malformed handshake resilience ===");
    let h = Harness::fresh("bad_alpn_v2");
    let psk: Psk = [0x33u8; 32];
    h.server.store.store_psk(&psk).expect("seed pairing");
    h.server.set_psk(psk);

    for _ in 0..5 {
        let outcome = TestClient::connect(h.addr, b"unsupported-alpn").await;
        assert!(outcome.is_err());
    }

    let client = TestClient::connect(h.addr, ALPN_STREAM).await.expect("connect");
    client.authenticate(&psk).await.expect("still authenticating");
    assert!(eventually(|| h.state() == STATE_AUTHENTICATED).await);

    h.cleanup();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn video_backlog_is_bounded_and_drops_are_counted() {
    println!("\n=== TEST: Frame dropping ===");
    let h = Harness::fresh("drops");

    for i in 0..500u32 {
        h.server.video.push(i.to_le_bytes().to_vec());
    }
    assert_eq!(
        h.server.video.dropped(),
        500 - VIDEO_QUEUE_DEPTH as u64,
        "everything past the two-frame window must be dropped, not buffered"
    );

    h.cleanup();
}
