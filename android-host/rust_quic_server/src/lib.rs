//! QUIC server for the Android host.
//!
//! Two ALPNs are served on the same endpoint:
//!
//! * `androiddex-pair-v2` — first contact. Ephemeral X25519 key exchange + short
//!   authentication string (SAS) comparison + TLS channel binding.
//! * `androiddex-v2` — a device that already paired. Mutual challenge-response
//!   proof exchange bound to the TLS session.
//!
//! Nothing in the network path may panic: every connection is driven by a spawned task,
//! and this crate contains no `unwrap()`/`expect()` outside `#[cfg(test)]`.

mod crypto;
mod frames;
pub mod logging;
mod pairing;
#[cfg(test)]
mod protocol_tests;
mod store;
mod tls;

use std::fmt;
use std::sync::atomic::{AtomicI32, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use crossbeam_channel::{unbounded, Receiver, Sender};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use quinn::{Connection, Endpoint, SendStream, VarInt};
use tokio::runtime::Runtime;
use tokio::time::Instant;

use crypto::Psk;
use frames::FrameQueue;
use pairing::ConfirmationChannel;
use store::SecureStore;

// -- Constants shared with the Kotlin side --------------------------------------------

/// Mirrors `QuicServer.getConnectionState()` in Kotlin.
const STATE_IDLE: i32 = 0;
const STATE_PAIRING: i32 = 1;
const STATE_AUTHENTICATED: i32 = 2;
const STATE_DISCONNECTED: i32 = 3;

const ALPN_STREAM: &[u8] = b"androiddex-v2";
const ALPN_PAIRING: &[u8] = b"androiddex-pair-v2";

/// Whole-handshake budget for a pairing connection, covering the wait for the user.
const PAIRING_TIMEOUT: Duration = Duration::from_secs(60);
/// An already-paired device has no human in the loop; it gets a much shorter budget.
const AUTH_TIMEOUT: Duration = Duration::from_secs(15);

/// Two frames at 60 fps is ~33 ms of slack. Anything deeper is latency the viewer can
/// never work off, because the encoder is not going to slow down to let it drain.
const VIDEO_QUEUE_DEPTH: usize = 2;
/// Audio packets are small and cheap; a slightly deeper queue avoids gaps on a hiccup.
const AUDIO_QUEUE_DEPTH: usize = 8;

/// Ceiling on a single input event. Anything larger is malformed or hostile.
const MAX_INPUT_EVENT_BYTES: usize = 64 * 1024;

// QUIC application close codes, purely for diagnosis on the PC side.
const CLOSE_NO_ALPN: u32 = 1;
const CLOSE_BAD_ALPN: u32 = 2;
const CLOSE_NOT_PAIRED: u32 = 3;
const CLOSE_AUTH_FAILED: u32 = 4;
const CLOSE_PAIRING_FAILED: u32 = 5;
const CLOSE_ALREADY_PAIRED: u32 = 6;
const CLOSE_RATE_LIMITED: u32 = 7;

/// Cooldown between pairing attempts from the same remote IP address.
const PAIRING_COOLDOWN: Duration = Duration::from_millis(500);
/// Maximum tracked remote IPs in the pairing cooldown map.
const MAX_COOLDOWN_ENTRIES: usize = 64;

// -- Shared server state ---------------------------------------------------------------

struct Server {
    state: AtomicI32,
    video: Arc<FrameQueue>,
    audio: Arc<FrameQueue>,
    input_tx: Sender<Vec<u8>>,
    confirmations: ConfirmationChannel,
    store: SecureStore,
    /// The pairing key of the currently paired PC, mirrored from disk.
    psk: Mutex<Option<Psk>>,
    /// Number of sessions currently streaming, so state only drops to DISCONNECTED once
    /// the last one is gone.
    sessions: AtomicUsize,
    /// Only one connection streams at a time; a second one waits rather than stealing
    /// frames out of the queue.
    session_lock: tokio::sync::Mutex<()>,
    /// Remote address rate limiter for pairing attempts.
    pairing_cooldowns: Mutex<std::collections::HashMap<std::net::IpAddr, tokio::time::Instant>>,
}

impl Server {
    fn psk(&self) -> Option<Psk> {
        match self.psk.lock() {
            Ok(guard) => *guard,
            Err(poisoned) => *poisoned.into_inner(),
        }
    }

    fn set_psk(&self, psk: Psk) {
        match self.psk.lock() {
            Ok(mut guard) => *guard = Some(psk),
            Err(poisoned) => *poisoned.into_inner() = Some(psk),
        }
    }

    fn clear_psk(&self) {
        match self.psk.lock() {
            Ok(mut guard) => *guard = None,
            Err(poisoned) => *poisoned.into_inner() = None,
        }
        self.store.clear_psk();
    }

    fn is_paired(&self) -> bool {
        self.psk().is_some()
    }

    fn check_pairing_rate_limit(&self, ip: std::net::IpAddr, now: Instant) -> bool {
        let mut map = match self.pairing_cooldowns.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        if map.len() >= MAX_COOLDOWN_ENTRIES {
            map.retain(|_, last_attempt| now.saturating_duration_since(*last_attempt) < PAIRING_COOLDOWN);
        }
        if map.len() >= MAX_COOLDOWN_ENTRIES {
            if let Some(oldest_key) = map.keys().next().copied() {
                map.remove(&oldest_key);
            }
        }
        if let Some(last_attempt) = map.get(&ip) {
            if now.saturating_duration_since(*last_attempt) < PAIRING_COOLDOWN {
                return false;
            }
        }
        map.insert(ip, now);
        true
    }

    /// Returns the reported state to a resting value once nothing is streaming.
    fn settle_state(&self) {
        if self.sessions.load(Ordering::SeqCst) == 0
            && self.state.load(Ordering::SeqCst) == STATE_PAIRING
        {
            self.state.store(STATE_IDLE, Ordering::SeqCst);
        }
    }
}

/// Opaque handle handed back to Kotlin.
struct ServerContext {
    server: Arc<Server>,
    input_rx: Receiver<Vec<u8>>,
    _rt: Runtime,
}

/// `SecurityBridge` has no handle to pass, so the server is reachable globally.
/// Written exactly once, by `start()`.
static SERVER: OnceLock<Arc<Server>> = OnceLock::new();

fn global_server() -> Option<&'static Arc<Server>> {
    SERVER.get()
}

/// Turns a raw handle back into a context reference.
///
/// # Safety
/// `handle` must be a value previously returned by `start()` and not yet freed. Kotlin
/// keeps it in a single `private set` field and never fabricates one.
unsafe fn context<'a>(handle: jlong) -> Option<&'a ServerContext> {
    if handle == 0 {
        None
    } else {
        Some(&*(handle as *const ServerContext))
    }
}

// -- Handshake errors ------------------------------------------------------------------

#[derive(Debug)]
enum HandshakeError {
    Timeout(&'static str),
    Stream(String),
    Protocol(String),
    NoConfirmation,
    ConfirmationRejected,
}

impl fmt::Display for HandshakeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Timeout(what) => write!(f, "timed out waiting for {what}"),
            Self::Stream(e) => write!(f, "stream error: {e}"),
            Self::Protocol(e) => write!(f, "protocol error: {e}"),
            Self::NoConfirmation => write!(f, "no confirmation was entered on the phone"),
            Self::ConfirmationRejected => write!(f, "user rejected confirmation on the phone"),
        }
    }
}

/// Runs `fut` but gives up at `deadline`.
async fn before<F: std::future::Future>(
    deadline: Instant,
    what: &'static str,
    fut: F,
) -> Result<F::Output, HandshakeError> {
    tokio::time::timeout_at(deadline, fut)
        .await
        .map_err(|_| HandshakeError::Timeout(what))
}

// -- JNI: lifecycle --------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_quic_QuicServer_start(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
    data_path: JString,
) -> jlong {
    let data_path: String = match env.get_string(&data_path) {
        Ok(s) => s.into(),
        Err(e) => {
            log_e!("start() rejected: dataPath is not readable ({e})");
            return 0;
        }
    };

    let port = match u16::try_from(port) {
        Ok(p) if p != 0 => p,
        _ => {
            log_e!("start() rejected: {port} is not a usable port");
            return 0;
        }
    };

    if SERVER.get().is_some() {
        log_e!("start() called twice; the endpoint is already bound");
        return 0;
    }

    let store = match SecureStore::open(&data_path) {
        Ok(s) => s,
        Err(e) => {
            log_e!("start() rejected: cannot use {data_path} for key storage ({e})");
            return 0;
        }
    };

    let rt = match Runtime::new() {
        Ok(rt) => rt,
        Err(e) => {
            log_e!("start() rejected: cannot create the tokio runtime ({e})");
            return 0;
        }
    };

    let _ = rustls::crypto::ring::default_provider().install_default();

    let persisted_psk = store.load_psk();
    if persisted_psk.is_some() {
        log_i!("a paired device is on record; pairing confirmation is not required");
    } else {
        log_i!("no paired device on record; next PC must pair with SAS confirmation");
    }

    let (input_tx, input_rx) = unbounded::<Vec<u8>>();

    let server = Arc::new(Server {
        state: AtomicI32::new(STATE_IDLE),
        video: Arc::new(FrameQueue::new(VIDEO_QUEUE_DEPTH)),
        audio: Arc::new(FrameQueue::new(AUDIO_QUEUE_DEPTH)),
        input_tx,
        confirmations: ConfirmationChannel::new(),
        store,
        psk: Mutex::new(persisted_psk),
        sessions: AtomicUsize::new(0),
        session_lock: tokio::sync::Mutex::new(()),
        pairing_cooldowns: Mutex::new(std::collections::HashMap::new()),
    });

    if SERVER.set(server.clone()).is_err() {
        log_e!("start() lost the race to publish the server");
        return 0;
    }

    rt.spawn(run_endpoint(server.clone(), port));

    let ctx = Box::new(ServerContext { server, input_rx, _rt: rt });
    Box::into_raw(ctx) as jlong
}

// -- Accept loop -----------------------------------------------------------------------

fn bind_endpoint(server: &Arc<Server>, addr: std::net::SocketAddr) -> Result<Endpoint, String> {
    let config = tls::build_server_config(&server.store, &[ALPN_STREAM, ALPN_PAIRING])?;
    Endpoint::server(config, addr).map_err(|e| format!("cannot bind {addr}: {e}"))
}

async fn run_endpoint(server: Arc<Server>, port: u16) {
    let addr = std::net::SocketAddr::from((std::net::Ipv4Addr::UNSPECIFIED, port));
    let endpoint = match bind_endpoint(&server, addr) {
        Ok(e) => e,
        Err(e) => {
            log_e!("server not started: {e}");
            return;
        }
    };

    log_i!("listening on {addr}");
    accept_loop(server, endpoint).await;
}

async fn accept_loop(server: Arc<Server>, endpoint: Endpoint) {
    server.state.store(STATE_IDLE, Ordering::SeqCst);

    while let Some(incoming) = endpoint.accept().await {
        let server = server.clone();
        tokio::spawn(async move {
            handle_incoming(server, incoming).await;
        });
    }

    log_e!("accept loop ended: the endpoint was closed");
}

async fn handle_incoming(server: Arc<Server>, incoming: quinn::Incoming) {
    let remote = incoming.remote_address();

    let conn = match incoming.await {
        Ok(c) => c,
        Err(e) => {
            log_w!("handshake from {remote} failed: {e}");
            return;
        }
    };

    let alpn = match negotiated_alpn(&conn) {
        Some(alpn) => alpn,
        None => {
            log_w!("rejecting {remote}: no ALPN was negotiated");
            conn.close(VarInt::from_u32(CLOSE_NO_ALPN), b"no alpn");
            return;
        }
    };

    match alpn.as_slice() {
        ALPN_PAIRING => pair_and_serve(server, conn, remote).await,
        ALPN_STREAM => authenticate_and_serve(server, conn, remote).await,
        other => {
            log_w!("rejecting {remote}: unsupported ALPN {}", printable(other));
            conn.close(VarInt::from_u32(CLOSE_BAD_ALPN), b"unsupported alpn");
        }
    }
}

fn negotiated_alpn(conn: &Connection) -> Option<Vec<u8>> {
    let data = conn.handshake_data()?;
    let handshake = data.downcast::<quinn::crypto::rustls::HandshakeData>().ok()?;
    handshake.protocol
}

fn printable(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|b| {
            if b.is_ascii_graphic() || *b == b' ' {
                (*b as char).to_string()
            } else {
                format!("\\x{b:02x}")
            }
        })
        .collect()
}

// -- Pairing (ALPN androiddex-pair-v2) --------------------------------------------------

async fn pair_and_serve(server: Arc<Server>, conn: Connection, remote: std::net::SocketAddr) {
    if server.is_paired() {
        log_w!("rejecting pairing attempt from {remote}: device is already paired");
        conn.close(VarInt::from_u32(CLOSE_ALREADY_PAIRED), b"already paired");
        return;
    }

    if !server.check_pairing_rate_limit(remote.ip(), Instant::now()) {
        log_w!("rejecting pairing attempt from {remote}: rate limit in effect");
        conn.close(VarInt::from_u32(CLOSE_RATE_LIMITED), b"rate limited");
        return;
    }

    log_i!("pairing attempt from {remote}");
    server.state.store(STATE_PAIRING, Ordering::SeqCst);

    let deadline = Instant::now() + PAIRING_TIMEOUT;
    let outcome = run_pairing(&server, &conn, deadline).await;

    server.confirmations.cancel().await;

    match outcome {
        Ok(psk) => {
            match server.store.store_psk(&psk) {
                Ok(()) => log_i!("paired with {remote}; the key will survive a restart"),
                Err(e) => log_e!("paired with {remote} but the key could not be saved ({e})"),
            }
            server.set_psk(psk);
            serve_session(server, conn, remote).await;
        }
        Err(e) => {
            log_w!("pairing with {remote} refused: {e}");
            conn.close(VarInt::from_u32(CLOSE_PAIRING_FAILED), b"pairing failed");
            server.settle_state();
        }
    }
}

async fn run_pairing(
    server: &Arc<Server>,
    conn: &Connection,
    deadline: Instant,
) -> Result<Psk, HandshakeError> {
    // 1. Phone generates an ephemeral X25519 keypair (b, B).
    let rng = ring::rand::SystemRandom::new();
    let my_private = ring::agreement::EphemeralPrivateKey::generate(&ring::agreement::X25519, &rng)
        .map_err(|_| HandshakeError::Protocol("failed to generate ephemeral key".into()))?;
    let my_public = my_private
        .compute_public_key()
        .map_err(|_| HandshakeError::Protocol("failed to compute public key".into()))?;

    let mut b_bytes = [0u8; crypto::EPHEMERAL_KEY_LEN];
    if my_public.as_ref().len() != crypto::EPHEMERAL_KEY_LEN {
        return Err(HandshakeError::Protocol("invalid public key length".into()));
    }
    b_bytes.copy_from_slice(my_public.as_ref());

    // 2. PC opens a bidirectional stream, sends: 'P' || A (1 + 32 bytes).
    let (mut send, mut recv) = before(deadline, "the pairing stream", conn.accept_bi())
        .await?
        .map_err(|e| HandshakeError::Stream(e.to_string()))?;

    let mut hello = [0u8; 1 + crypto::EPHEMERAL_KEY_LEN];
    before(deadline, "the pairing hello", recv.read_exact(&mut hello))
        .await?
        .map_err(|e| HandshakeError::Stream(e.to_string()))?;

    if hello[0] != b'P' {
        return Err(HandshakeError::Protocol(format!(
            "expected a 'P' tag, got {}",
            printable(&hello[..1])
        )));
    }

    let mut a_bytes = [0u8; crypto::EPHEMERAL_KEY_LEN];
    a_bytes.copy_from_slice(&hello[1..]);

    // 3. Phone replies: 'Q' || B (1 + 32 bytes).
    let mut reply = [0u8; 1 + crypto::EPHEMERAL_KEY_LEN];
    reply[0] = b'Q';
    reply[1..].copy_from_slice(&b_bytes);
    before(deadline, "the pairing reply", send.write_all(&reply))
        .await?
        .map_err(|e| HandshakeError::Stream(e.to_string()))?;

    // 5. TLS channel binding: 32 bytes via export_keying_material.
    let mut binding = [0u8; crypto::BINDING_LEN];
    if let Err(e) = conn.export_keying_material(&mut binding, b"androiddex-pair-v2", b"") {
        return Err(HandshakeError::Protocol(format!("export_keying_material failed: {e:?}")));
    }

    // 4. Compute Z = X25519(own_private, peer_public) and derive (sas, psk) via steps 6-9.
    let peer_public = ring::agreement::UnparsedPublicKey::new(&ring::agreement::X25519, &a_bytes);
    let (sas, psk) = ring::agreement::agree_ephemeral(
        my_private,
        &peer_public,
        |z_bytes| {
            if z_bytes.len() != 32 {
                return Err(HandshakeError::Protocol("invalid shared secret length".into()));
            }
            let mut z = [0u8; 32];
            z.copy_from_slice(z_bytes);
            crypto::derive_pairing_v2(&a_bytes, &b_bytes, &binding, &z)
                .map_err(|e| HandshakeError::Protocol(format!("derivation failed: {e}")))
        },
    )
    .map_err(|_| HandshakeError::Protocol("X25519 agreement failed".into()))??;

    // 10-11. Display 6-digit SAS to user and wait for confirmation.
    let remaining = deadline.saturating_duration_since(Instant::now());
    if remaining.is_zero() {
        return Err(HandshakeError::Timeout("confirmation"));
    }

    let submission = match server.confirmations.wait_for_confirmation(sas, remaining).await {
        Some(s) => s,
        None => return Err(HandshakeError::NoConfirmation),
    };

    // 12. Phone sends 'Y' on match, 'N' otherwise.
    if submission.matched() {
        before(deadline, "the confirmation response", send.write_all(b"Y"))
            .await?
            .map_err(|e| HandshakeError::Stream(e.to_string()))?;
        if let Err(e) = send.finish() {
            log_w!("stream finish error: {e}");
        }
        submission.answer(true);
        Ok(psk)
    } else {
        log_w!("user rejected the pairing confirmation ('They are different')");
        before(deadline, "the rejection response", send.write_all(b"N"))
            .await?
            .map_err(|e| HandshakeError::Stream(e.to_string()))?;
        if let Err(e) = send.finish() {
            log_w!("stream finish error: {e}");
        }
        submission.answer(false);
        Err(HandshakeError::ConfirmationRejected)
    }
}

// -- Authentication (ALPN androiddex-v2) -------------------------------------------------

async fn authenticate_and_serve(
    server: Arc<Server>,
    conn: Connection,
    remote: std::net::SocketAddr,
) {
    let Some(psk) = server.psk() else {
        log_w!("rejecting {remote}: no device is paired, so no authentication can succeed");
        conn.close(VarInt::from_u32(CLOSE_NOT_PAIRED), b"not paired");
        return;
    };

    let deadline = Instant::now() + AUTH_TIMEOUT;

    // binding = export_keying_material(32, b"androiddex-auth-v2", b"")
    let mut binding = [0u8; crypto::BINDING_LEN];
    if let Err(e) = conn.export_keying_material(&mut binding, b"androiddex-auth-v2", b"") {
        log_w!("rejecting {remote}: export_keying_material failed: {e:?}");
        conn.close(VarInt::from_u32(CLOSE_AUTH_FAILED), b"binding failed");
        return;
    }

    // 1. PC opens a bi stream, sends 'C' || client_nonce (32 random bytes).
    let (mut send, mut recv) = match before(deadline, "the auth stream", conn.accept_bi()).await {
        Ok(Ok(bi)) => bi,
        Ok(Err(e)) => {
            log_w!("rejecting {remote}: accept_bi failed: {e}");
            conn.close(VarInt::from_u32(CLOSE_AUTH_FAILED), b"stream failed");
            return;
        }
        Err(e) => {
            log_w!("rejecting {remote}: {e}");
            conn.close(VarInt::from_u32(CLOSE_AUTH_FAILED), b"timeout");
            return;
        }
    };

    let mut client_req = [0u8; 1 + crypto::NONCE_LEN];
    if let Err(e) = before(deadline, "the client nonce", recv.read_exact(&mut client_req)).await {
        log_w!("rejecting {remote}: failed to read client nonce: {e}");
        conn.close(VarInt::from_u32(CLOSE_AUTH_FAILED), b"read nonce failed");
        return;
    }

    if client_req[0] != b'C' {
        log_w!("rejecting {remote}: expected 'C' tag, got {}", printable(&client_req[..1]));
        conn.close(VarInt::from_u32(CLOSE_AUTH_FAILED), b"bad tag");
        return;
    }

    let mut client_nonce = [0u8; crypto::NONCE_LEN];
    client_nonce.copy_from_slice(&client_req[1..]);

    // 2. Phone replies 'S' || server_nonce || server_proof (1 + 32 + 32 bytes).
    let rng = ring::rand::SystemRandom::new();
    let mut server_nonce = [0u8; crypto::NONCE_LEN];
    if ring::rand::SecureRandom::fill(&rng, &mut server_nonce).is_err() {
        log_e!("rejecting {remote}: CSPRNG failure");
        conn.close(VarInt::from_u32(CLOSE_AUTH_FAILED), b"rng failure");
        return;
    }

    let s_proof = crypto::server_proof(&psk, &client_nonce, &server_nonce, &binding);

    let mut server_resp = [0u8; 1 + crypto::NONCE_LEN + crypto::PROOF_LEN];
    server_resp[0] = b'S';
    server_resp[1..1 + crypto::NONCE_LEN].copy_from_slice(&server_nonce);
    server_resp[1 + crypto::NONCE_LEN..].copy_from_slice(&s_proof);

    if let Err(e) = before(deadline, "the server proof", send.write_all(&server_resp)).await {
        log_w!("rejecting {remote}: failed to send server proof: {e}");
        conn.close(VarInt::from_u32(CLOSE_AUTH_FAILED), b"write proof failed");
        return;
    }

    // 3. PC sends 'D' || client_proof (1 + 32 bytes).
    let mut client_resp = [0u8; 1 + crypto::PROOF_LEN];
    if let Err(e) = before(deadline, "the client proof", recv.read_exact(&mut client_resp)).await {
        log_w!("rejecting {remote}: failed to read client proof: {e}");
        conn.close(VarInt::from_u32(CLOSE_AUTH_FAILED), b"read client proof failed");
        return;
    }

    if client_resp[0] != b'D' {
        log_w!("rejecting {remote}: expected 'D' tag, got {}", printable(&client_resp[..1]));
        conn.close(VarInt::from_u32(CLOSE_AUTH_FAILED), b"bad client tag");
        return;
    }

    let mut presented_proof = [0u8; crypto::PROOF_LEN];
    presented_proof.copy_from_slice(&client_resp[1..]);

    // 4. Phone verifies client_proof in CONSTANT TIME.
    if !crypto::verify_client_proof(&psk, &client_nonce, &server_nonce, &binding, &presented_proof) {
        log_w!("rejecting {remote}: client proof does not match");
        conn.close(VarInt::from_u32(CLOSE_AUTH_FAILED), b"client proof mismatch");
        return;
    }

    let _ = send.finish();
    log_i!("{remote} mutually authenticated with stored pairing key");
    serve_session(server, conn, remote).await;
}

// -- Streaming session ------------------------------------------------------------------

async fn serve_session(server: Arc<Server>, conn: Connection, remote: std::net::SocketAddr) {
    let _permit = server.session_lock.lock().await;

    server.video.clear();
    server.audio.clear();

    let video_stream = match conn.open_uni().await {
        Ok(s) => s,
        Err(e) => {
            log_w!("could not open the video stream to {remote}: {e}");
            return;
        }
    };
    let audio_stream = match conn.open_uni().await {
        Ok(s) => s,
        Err(e) => {
            log_w!("could not open the audio stream to {remote}: {e}");
            return;
        }
    };

    server.sessions.fetch_add(1, Ordering::SeqCst);
    server.state.store(STATE_AUTHENTICATED, Ordering::SeqCst);
    log_i!("streaming to {remote}");

    let mut video_task = tokio::spawn(pump(server.video.clone(), video_stream, "video"));
    let mut audio_task = tokio::spawn(pump(server.audio.clone(), audio_stream, "audio"));
    let mut input_task = tokio::spawn(read_input(server.clone(), conn.clone()));

    let reason = tokio::select! {
        reason = conn.closed() => format!("peer closed the connection: {reason}"),
        _ = &mut video_task => "the video stream ended".to_string(),
        _ = &mut audio_task => "the audio stream ended".to_string(),
        _ = &mut input_task => "the input stream ended".to_string(),
    };

    video_task.abort();
    audio_task.abort();
    input_task.abort();

    if server.sessions.fetch_sub(1, Ordering::SeqCst) == 1 {
        server.state.store(STATE_DISCONNECTED, Ordering::SeqCst);
    }
    log_i!(
        "session with {remote} ended ({reason}); dropped {} video / {} audio frames so far",
        server.video.dropped(),
        server.audio.dropped()
    );
}

async fn pump(queue: Arc<FrameQueue>, mut stream: SendStream, kind: &'static str) {
    loop {
        let frame = queue.pop().await;
        let len = match u32::try_from(frame.len()) {
            Ok(len) => len,
            Err(_) => {
                log_w!("skipping a {kind} frame of {} bytes: too large to frame", frame.len());
                continue;
            }
        };

        if let Err(e) = stream.write_all(&len.to_le_bytes()).await {
            log_w!("{kind} stream write failed: {e}");
            break;
        }
        if let Err(e) = stream.write_all(&frame).await {
            log_w!("{kind} stream write failed: {e}");
            break;
        }
    }
    let _ = stream.finish();
}

async fn read_input(server: Arc<Server>, conn: Connection) {
    let mut stream = match conn.accept_uni().await {
        Ok(s) => s,
        Err(e) => {
            log_w!("no input stream was opened: {e}");
            return;
        }
    };

    loop {
        let mut len_buf = [0u8; 4];
        if let Err(e) = stream.read_exact(&mut len_buf).await {
            log_i!("input stream closed: {e}");
            return;
        }

        let len = u32::from_le_bytes(len_buf) as usize;
        if len == 0 {
            continue;
        }
        if len > MAX_INPUT_EVENT_BYTES {
            log_w!("input event claims {len} bytes; dropping the connection");
            return;
        }

        let mut data = vec![0u8; len];
        if let Err(e) = stream.read_exact(&mut data).await {
            log_w!("truncated input event: {e}");
            return;
        }

        if server.input_tx.send(data).is_err() {
            log_e!("input consumer is gone; stopping the input reader");
            return;
        }
    }
}

// -- JNI: media and input ---------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_quic_QuicServer_pollData(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    buffer: JByteArray,
) -> jint {
    let Some(ctx) = (unsafe { context(handle) }) else {
        return 0;
    };

    let timeout = if ctx.server.state.load(Ordering::SeqCst) == STATE_AUTHENTICATED {
        Duration::from_millis(20)
    } else {
        Duration::from_millis(500)
    };

    let Ok(data) = ctx.input_rx.recv_timeout(timeout) else {
        return 0;
    };
    if data.is_empty() {
        return 0;
    }

    let capacity = match env.get_array_length(&buffer) {
        Ok(len) if len >= 0 => len as usize,
        _ => return 0,
    };
    if data.len() > capacity {
        log_w!("dropping a {} byte input event: the Kotlin buffer holds {capacity}", data.len());
        return 0;
    }

    let signed = unsafe { std::slice::from_raw_parts(data.as_ptr() as *const i8, data.len()) };

    if env.set_byte_array_region(&buffer, 0, signed).is_err() {
        let _ = env.exception_clear();
        return 0;
    }

    data.len() as jint
}

#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_quic_QuicServer_send(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
) {
    let Some(ctx) = (unsafe { context(handle) }) else {
        return;
    };
    match env.convert_byte_array(&data) {
        Ok(bytes) => {
            ctx.server.video.push(bytes);
        }
        Err(_) => {
            let _ = env.exception_clear();
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_quic_QuicServer_sendAudio(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
) {
    let Some(ctx) = (unsafe { context(handle) }) else {
        return;
    };
    match env.convert_byte_array(&data) {
        Ok(bytes) => {
            ctx.server.audio.push(bytes);
        }
        Err(_) => {
            let _ = env.exception_clear();
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_quic_QuicServer_connectionState(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    match unsafe { context(handle) } {
        Some(ctx) => ctx.server.state.load(Ordering::SeqCst),
        None => STATE_IDLE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_quic_QuicServer_droppedVideoFrames(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    match unsafe { context(handle) } {
        Some(ctx) => ctx.server.video.dropped() as jlong,
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_quic_QuicServer_droppedAudioFrames(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    match unsafe { context(handle) } {
        Some(ctx) => ctx.server.audio.dropped() as jlong,
        None => 0,
    }
}

// -- JNI: SecurityBridge -----------------------------------------------------------------

/// Confirms or rejects the pairing code (SAS) displayed to the user.
/// Called from Kotlin background thread.
#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_security_SecurityBridge_nativeConfirmPairing(
    _env: JNIEnv,
    _class: JClass,
    matched: jboolean,
) -> jboolean {
    let Some(server) = global_server() else {
        log_w!("confirmPairing called before the server was started");
        return JNI_FALSE;
    };

    if server.confirmations.submit_blocking(matched == JNI_TRUE) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Alias for `nativeConfirmPairing`.
#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_security_SecurityBridge_nativeConfirm(
    env: JNIEnv,
    class: JClass,
    matched: jboolean,
) -> jboolean {
    Java_com_example_androidhost_security_SecurityBridge_nativeConfirmPairing(env, class, matched)
}

/// Legacy PIN verification bridge stub. Returns false under Protocol v2.
#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_security_SecurityBridge_nativeVerifyPin(
    _env: JNIEnv,
    _class: JClass,
    _pin: JString,
) -> jboolean {
    log_w!("legacy nativeVerifyPin called; Protocol v2 requires confirmation");
    JNI_FALSE
}

/// Reads the pending 6-digit SAS code, or returns null if no pairing is awaiting confirmation.
#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_security_SecurityBridge_nativeGetPendingSas(
    env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    let Some(server) = global_server() else {
        return std::ptr::null_mut();
    };

    match server.confirmations.get_pending_sas() {
        Some(sas) => match env.new_string(sas) {
            Ok(js) => js.into_raw(),
            Err(_) => {
                let _ = env.exception_clear();
                std::ptr::null_mut()
            }
        },
        None => std::ptr::null_mut(),
    }
}

/// Alias for `nativeGetPendingSas`.
#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_security_SecurityBridge_nativeGetSas(
    env: JNIEnv,
    class: JClass,
) -> jni::sys::jstring {
    Java_com_example_androidhost_security_SecurityBridge_nativeGetPendingSas(env, class)
}

/// True while a PC is mid-pairing and the phone is waiting for the user to confirm the SAS.
#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_security_SecurityBridge_nativeIsAwaitingConfirmation(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    match global_server() {
        Some(server) if server.confirmations.is_awaiting() => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

/// Backward compatibility alias for `nativeIsAwaitingConfirmation`.
#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_security_SecurityBridge_nativeIsAwaitingPin(
    env: JNIEnv,
    class: JClass,
) -> jboolean {
    Java_com_example_androidhost_security_SecurityBridge_nativeIsAwaitingConfirmation(env, class)
}

/// True when a pairing key is on record, i.e. a known PC can connect without confirmation.
#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_security_SecurityBridge_nativeIsPaired(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    match global_server() {
        Some(server) if server.is_paired() => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

/// Forgets the paired PC. The next connection has to go through the pairing flow again.
#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_security_SecurityBridge_nativeClearPairing(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(server) = global_server() {
        server.clear_psk();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn v2_auth_request_layout_is_33_bytes() {
        let mut wire = Vec::new();
        wire.push(b'C');
        wire.extend_from_slice(&[0x55u8; 32]);
        assert_eq!(wire.len(), 1 + crypto::NONCE_LEN);
        assert_eq!(wire[0], b'C');
    }

    #[test]
    fn a_random_client_proof_never_verifies() {
        let psk: Psk = [42u8; crypto::PSK_LEN];
        let client_nonce = [0x11u8; crypto::NONCE_LEN];
        let server_nonce = [0x22u8; crypto::NONCE_LEN];
        let binding = [0x33u8; crypto::BINDING_LEN];

        for seed in 0u8..64 {
            assert!(!crypto::verify_client_proof(
                &psk,
                &client_nonce,
                &server_nonce,
                &binding,
                &[seed; crypto::PROOF_LEN]
            ));
        }
    }

    #[test]
    fn untrusted_bytes_are_escaped_before_logging() {
        assert_eq!(printable(b"androiddex-v2"), "androiddex-v2");
        assert_eq!(printable(b"\x00\x1b[31m"), "\\x00\\x1b[31m");
    }
}
