//! QUIC server for the Android host.
//!
//! Two ALPNs are served on the same endpoint:
//!
//! * `androiddex-pairing` — first contact. The PC sends `P` + a 32-byte ephemeral public
//!   key and displays a six-digit PIN. The user types that PIN on the phone; both sides
//!   derive `HKDF-SHA256(salt = "androiddex-v1", ikm = pin || pubkey, info = "psk")`. The
//!   phone answers `OK`, the PC then proves it derived the same PSK by sending
//!   `A` + `SHA256(psk || "auth")` on a second bidirectional stream, and only a matching
//!   token completes pairing.
//! * `androiddex` — a device that already paired. It presents the same token; a mismatch
//!   closes the connection without a reply and without changing the reported state.
//!
//! The PC half of this lives in `rust-receiver/zc-network/src/client.rs` and
//! `rust-receiver/zc-security/src/pairing.rs`.
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

use crypto::{derive_psk, is_well_formed_pin, verify_auth_token, Psk};
use frames::FrameQueue;
use pairing::PinChannel;
use store::SecureStore;

// -- Constants shared with the Kotlin side --------------------------------------------

/// Mirrors `QuicServer.getConnectionState()` in Kotlin.
const STATE_IDLE: i32 = 0;
const STATE_PAIRING: i32 = 1;
const STATE_AUTHENTICATED: i32 = 2;
const STATE_DISCONNECTED: i32 = 3;

const ALPN_STREAM: &[u8] = b"androiddex";
const ALPN_PAIRING: &[u8] = b"androiddex-pairing";

/// Whole-handshake budget for a pairing connection, covering the wait for the user.
const PAIRING_TIMEOUT: Duration = Duration::from_secs(60);
/// A six-digit PIN is only a million guesses, so a connection gets very few tries.
const MAX_PIN_ATTEMPTS: u32 = 5;
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

// -- Shared server state ---------------------------------------------------------------

struct Server {
    state: AtomicI32,
    video: Arc<FrameQueue>,
    audio: Arc<FrameQueue>,
    input_tx: Sender<Vec<u8>>,
    pins: PinChannel,
    store: SecureStore,
    /// The pairing key of the currently paired PC, mirrored from disk.
    psk: Mutex<Option<Psk>>,
    /// Number of sessions currently streaming, so state only drops to DISCONNECTED once
    /// the last one is gone.
    sessions: AtomicUsize,
    /// Only one connection streams at a time; a second one waits rather than stealing
    /// frames out of the queue.
    session_lock: tokio::sync::Mutex<()>,
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
    NoPin,
    AttemptsExhausted,
}

impl fmt::Display for HandshakeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Timeout(what) => write!(f, "timed out waiting for {what}"),
            Self::Stream(e) => write!(f, "stream error: {e}"),
            Self::Protocol(e) => write!(f, "protocol error: {e}"),
            Self::NoPin => write!(f, "no PIN was entered on the phone"),
            Self::AttemptsExhausted => {
                write!(f, "too many incorrect PINs ({MAX_PIN_ATTEMPTS})")
            }
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

    // rustls is built with only the ring provider, but installing it explicitly keeps the
    // behaviour independent of which crate happens to touch rustls first.
    let _ = rustls::crypto::ring::default_provider().install_default();

    let persisted_psk = store.load_psk();
    if persisted_psk.is_some() {
        log_i!("a paired device is on record; PIN entry is not required");
    } else {
        log_i!("no paired device on record; the next PC must pair with a PIN");
    }

    let (input_tx, input_rx) = unbounded::<Vec<u8>>();

    let server = Arc::new(Server {
        state: AtomicI32::new(STATE_IDLE),
        video: Arc::new(FrameQueue::new(VIDEO_QUEUE_DEPTH)),
        audio: Arc::new(FrameQueue::new(AUDIO_QUEUE_DEPTH)),
        input_tx,
        pins: PinChannel::new(),
        store,
        psk: Mutex::new(persisted_psk),
        sessions: AtomicUsize::new(0),
        session_lock: tokio::sync::Mutex::new(()),
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

/// Binds the QUIC endpoint. Split out from [`accept_loop`] so the protocol tests can bind
/// an ephemeral port and learn which one they got.
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

    // Each connection runs in its own task. A connection that misbehaves — a truncated
    // handshake, a bogus ALPN, a stalled stream — can therefore only affect itself; the
    // accept loop keeps running.
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

    // Attacker-controlled: a peer can complete a QUIC handshake and then present anything
    // at all here, so both the presence of handshake data and its type are checked.
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

/// Renders untrusted bytes for a log line without letting them corrupt it.
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

// -- Pairing (ALPN androiddex-pairing) --------------------------------------------------

async fn pair_and_serve(server: Arc<Server>, conn: Connection, remote: std::net::SocketAddr) {
    log_i!("pairing attempt from {remote}");
    server.state.store(STATE_PAIRING, Ordering::SeqCst);

    let deadline = Instant::now() + PAIRING_TIMEOUT;
    let outcome = run_pairing(&server, &conn, deadline).await;

    server.pins.cancel().await;

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
    // Step 1: `P` + the PC's 32-byte ephemeral public key.
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

    let mut ephemeral_public_key = [0u8; crypto::EPHEMERAL_KEY_LEN];
    ephemeral_public_key.copy_from_slice(&hello[1..]);

    // Step 2: the user reads the PIN off the PC and types it here. The `OK` that unblocks
    // the PC is deliberately withheld until that happens — the PC blocks on this read, so
    // answering early would let it race ahead of the user.
    let mut attempts = 0u32;
    let mut announced = false;

    loop {
        if attempts >= MAX_PIN_ATTEMPTS {
            return Err(HandshakeError::AttemptsExhausted);
        }

        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(HandshakeError::Timeout("a PIN"));
        }

        let submission = match server.pins.next_pin(remaining).await {
            Some(s) => s,
            None => return Err(HandshakeError::NoPin),
        };
        attempts += 1;

        if !is_well_formed_pin(submission.pin()) {
            log_w!("attempt {attempts}/{MAX_PIN_ATTEMPTS}: PIN is not six digits");
            submission.answer(false);
            continue;
        }

        let candidate = derive_psk(submission.pin(), &ephemeral_public_key);

        if !announced {
            before(deadline, "the pairing acknowledgement", send.write_all(b"OK"))
                .await?
                .map_err(|e| HandshakeError::Stream(e.to_string()))?;
            if let Err(e) = send.finish() {
                return Err(HandshakeError::Stream(e.to_string()));
            }
            announced = true;
        }

        // Step 3: the PC proves it derived the same PSK. This is the only thing that can
        // tell the phone whether the typed PIN was right.
        let (mut auth_send, token) = match read_auth_request(conn, deadline).await {
            Ok(v) => v,
            Err(e) => {
                submission.answer(false);
                return Err(e);
            }
        };

        if verify_auth_token(&candidate, &token) {
            before(deadline, "the auth acknowledgement", auth_send.write_all(b"OK"))
                .await?
                .map_err(|e| HandshakeError::Stream(e.to_string()))?;
            let _ = auth_send.finish();
            submission.answer(true);
            return Ok(candidate);
        }

        // Wrong PIN. No `OK` goes out: the PC's `read_exact` on the reply fails and it
        // tears the connection down. A client that stays connected may try again until
        // the attempt budget or the 60 s deadline runs out.
        log_w!("attempt {attempts}/{MAX_PIN_ATTEMPTS}: token mismatch, PIN rejected");
        submission.answer(false);
    }
}

// -- Authentication (ALPN androiddex) ---------------------------------------------------

async fn authenticate_and_serve(
    server: Arc<Server>,
    conn: Connection,
    remote: std::net::SocketAddr,
) {
    let Some(psk) = server.psk() else {
        log_w!("rejecting {remote}: no device is paired, so no token can be valid");
        conn.close(VarInt::from_u32(CLOSE_NOT_PAIRED), b"not paired");
        return;
    };

    let deadline = Instant::now() + AUTH_TIMEOUT;

    let (mut send, token) = match read_auth_request(&conn, deadline).await {
        Ok(v) => v,
        Err(e) => {
            log_w!("rejecting {remote}: {e}");
            conn.close(VarInt::from_u32(CLOSE_AUTH_FAILED), b"auth failed");
            return;
        }
    };

    if !verify_auth_token(&psk, &token) {
        log_w!("rejecting {remote}: auth token does not match the paired key");
        conn.close(VarInt::from_u32(CLOSE_AUTH_FAILED), b"auth failed");
        return;
    }

    if send.write_all(b"OK").await.is_err() {
        log_w!("{remote} authenticated but the acknowledgement could not be sent");
        return;
    }
    let _ = send.finish();

    log_i!("{remote} authenticated with the stored pairing key");
    serve_session(server, conn, remote).await;
}

/// Reads `A` + a 32-byte token off a freshly accepted bidirectional stream.
async fn read_auth_request(
    conn: &Connection,
    deadline: Instant,
) -> Result<(SendStream, [u8; crypto::TOKEN_LEN]), HandshakeError> {
    let (send, mut recv) = before(deadline, "the auth stream", conn.accept_bi())
        .await?
        .map_err(|e| HandshakeError::Stream(e.to_string()))?;

    let mut request = [0u8; 1 + crypto::TOKEN_LEN];
    before(deadline, "the auth token", recv.read_exact(&mut request))
        .await?
        .map_err(|e| HandshakeError::Stream(e.to_string()))?;

    if request[0] != b'A' {
        return Err(HandshakeError::Protocol(format!(
            "expected an 'A' tag, got {}",
            printable(&request[..1])
        )));
    }

    let mut token = [0u8; crypto::TOKEN_LEN];
    token.copy_from_slice(&request[1..]);
    Ok((send, token))
}

// -- Streaming session ------------------------------------------------------------------

async fn serve_session(server: Arc<Server>, conn: Connection, remote: std::net::SocketAddr) {
    // One streaming session at a time; a second authenticated peer waits here instead of
    // competing for frames out of the same queue.
    let _permit = server.session_lock.lock().await;

    // Whatever the encoder produced while nobody was connected is stale by definition.
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

    // Video and audio each own a task. Sharing one `select!` meant a stalled audio write
    // held the loop and video frames waited behind it.
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

/// Writes length-prefixed frames from `queue` onto `stream` until either side gives up.
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

/// Reads length-prefixed input events from the PC and hands them to the JNI poller.
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

    let Ok(data) = ctx.input_rx.try_recv() else {
        return 0;
    };
    if data.is_empty() {
        return 0;
    }

    // Kotlin passes a fixed 1 MiB buffer; an event that does not fit is dropped rather
    // than allowed to raise ArrayIndexOutOfBoundsException on the polling thread.
    let capacity = match env.get_array_length(&buffer) {
        Ok(len) if len >= 0 => len as usize,
        _ => return 0,
    };
    if data.len() > capacity {
        log_w!("dropping a {} byte input event: the Kotlin buffer holds {capacity}", data.len());
        return 0;
    }

    // SAFETY: `i8` and `u8` have identical size and alignment, and the slice is only read.
    // This avoids copying every input event into a second Vec purely to change signedness.
    let signed = unsafe { std::slice::from_raw_parts(data.as_ptr() as *const i8, data.len()) };

    if env.set_byte_array_region(&buffer, 0, signed).is_err() {
        // A failed JNI call leaves an exception pending; clearing it keeps the polling
        // thread usable instead of throwing on its next JNI call.
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

/// Submits the PIN the user typed. Blocks until the PC's auth token settles the question,
/// so Kotlin must call this off the main thread.
#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_security_SecurityBridge_nativeVerifyPin(
    mut env: JNIEnv,
    _class: JClass,
    pin: JString,
) -> jboolean {
    let Some(server) = global_server() else {
        log_w!("verifyPin called before the server was started");
        return JNI_FALSE;
    };

    let pin: String = match env.get_string(&pin) {
        Ok(s) => s.into(),
        Err(_) => {
            let _ = env.exception_clear();
            return JNI_FALSE;
        }
    };

    // Shape is checked here as well as in the pairing task so a malformed value never
    // even consumes an attempt slot on a live handshake.
    if !is_well_formed_pin(&pin) {
        return JNI_FALSE;
    }

    if server.pins.submit_blocking(pin) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// True while a PC is mid-pairing and the phone is waiting for the user to type the PIN.
#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_security_SecurityBridge_nativeIsAwaitingPin(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    match global_server() {
        Some(server) if server.pins.is_awaiting() => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

/// True when a pairing key is on record, i.e. a known PC can connect without a PIN.
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

/// Forgets the paired PC. The next connection has to go through the PIN flow again.
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
    use crypto::auth_token;

    /// The exact bytes the PC puts on the wire for an already-paired device.
    #[test]
    fn auth_request_layout_is_33_bytes() {
        let psk = derive_psk("123456", &[1u8; crypto::EPHEMERAL_KEY_LEN]);
        let token = auth_token(&psk);

        let mut wire = Vec::new();
        wire.push(b'A');
        wire.extend_from_slice(&token);
        assert_eq!(wire.len(), 1 + crypto::TOKEN_LEN);
        assert_eq!(wire[0], b'A');
        assert!(verify_auth_token(&psk, &wire[1..]));
    }

    #[test]
    fn a_random_token_never_verifies() {
        let psk = derive_psk("654321", &[9u8; crypto::EPHEMERAL_KEY_LEN]);
        // Stand-in for the "raw client that skips pairing" case.
        for seed in 0u8..64 {
            assert!(!verify_auth_token(&psk, &[seed; crypto::TOKEN_LEN]));
        }
    }

    #[test]
    fn untrusted_bytes_are_escaped_before_logging() {
        assert_eq!(printable(b"androiddex"), "androiddex");
        assert_eq!(printable(b"\x00\x1b[31m"), "\\x00\\x1b[31m");
    }
}
