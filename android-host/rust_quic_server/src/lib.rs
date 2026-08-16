use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use quinn::{Endpoint, ServerConfig};
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::{Arc, Mutex};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;
use crossbeam_channel::{unbounded, Receiver, Sender};
use std::net::SocketAddr;
use std::time::Duration;

const STATE_IDLE: i32 = 0;
const STATE_PAIRING: i32 = 1;
const STATE_AUTHENTICATED: i32 = 2;
const STATE_DISCONNECTED: i32 = 3;

struct ServerContext {
    state: Arc<AtomicI32>,
    video_tx: mpsc::Sender<Vec<u8>>,
    audio_tx: mpsc::Sender<Vec<u8>>,
    input_rx: Receiver<Vec<u8>>,
    _rt: Runtime,
}

#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_quic_QuicServer_start(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
    _data_path: JString,
) -> jlong {
    let rt = Runtime::new().unwrap();
    let state = Arc::new(AtomicI32::new(STATE_IDLE));
    let state_clone = state.clone();

    let (video_tx, mut video_rx) = mpsc::channel::<Vec<u8>>(1000);
    let (audio_tx, mut audio_rx) = mpsc::channel::<Vec<u8>>(1000);
    let (input_tx, input_rx) = unbounded::<Vec<u8>>();

    rt.spawn(async move {
        let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let cert_der = cert.cert.der().to_vec();
        let key_der = cert.key_pair.serialize_der();
        let private_key = PrivateKeyDer::Pkcs8(key_der.into());
        let cert_chain = vec![CertificateDer::from(cert_der)];

        let mut server_crypto = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(cert_chain, private_key)
            .unwrap();

        server_crypto.alpn_protocols = vec![b"androiddex".to_vec(), b"androiddex-pairing".to_vec()];
        let quic_server_config = quinn::crypto::rustls::QuicServerConfig::try_from(server_crypto).unwrap();
        let server_config = ServerConfig::with_crypto(Arc::new(quic_server_config));
        
        let addr: SocketAddr = format!("0.0.0.0:{}", port).parse().unwrap();
        let endpoint = Endpoint::server(server_config, addr).unwrap();

        loop {
            state_clone.store(STATE_IDLE, Ordering::SeqCst);
            if let Some(incoming) = endpoint.accept().await {
                let conn = match incoming.await {
                    Ok(c) => c,
                    Err(_) => continue,
                };
                
                let alpn = conn.handshake_data().unwrap().downcast::<quinn::crypto::rustls::HandshakeData>().unwrap().protocol;
                
                if let Some(alpn) = alpn {
                    if alpn == b"androiddex-pairing" {
                        state_clone.store(STATE_PAIRING, Ordering::SeqCst);
                        if let Ok((mut send, mut recv)) = conn.accept_bi().await {
                            let mut buf = [0u8; 33];
                            let _ = recv.read_exact(&mut buf).await; // P + 32 bytes pubkey
                            let _ = send.write_all(b"OK").await;
                            let _ = send.finish();
                        }
                        continue;
                    } else if alpn == b"androiddex" {
                        // Auth
                        if let Ok((mut send, mut recv)) = conn.accept_bi().await {
                            let mut buf = [0u8; 33];
                            let _ = recv.read_exact(&mut buf).await; // A + 32 bytes token
                            let _ = send.write_all(b"OK").await;
                            let _ = send.finish();
                        }
                        
                        state_clone.store(STATE_AUTHENTICATED, Ordering::SeqCst);
                        
                        let mut video_stream = match conn.open_uni().await { Ok(s) => s, Err(_) => continue };
                        let mut audio_stream = match conn.open_uni().await { Ok(s) => s, Err(_) => continue };
                        let mut input_stream = match conn.accept_uni().await { Ok(s) => s, Err(_) => continue };

                        let input_tx_clone = input_tx.clone();
                        tokio::spawn(async move {
                            loop {
                                let mut len_buf = [0u8; 4];
                                if input_stream.read_exact(&mut len_buf).await.is_err() { break; }
                                let len = u32::from_le_bytes(len_buf) as usize;
                                if len > 1024 * 1024 { break; }
                                let mut data = vec![0u8; len];
                                if input_stream.read_exact(&mut data).await.is_err() { break; }
                                let _ = input_tx_clone.send(data);
                            }
                        });

                        loop {
                            tokio::select! {
                                Some(frame) = video_rx.recv() => {
                                    let len = (frame.len() as u32).to_le_bytes();
                                    if video_stream.write_all(&len).await.is_err() { break; }
                                    if video_stream.write_all(&frame).await.is_err() { break; }
                                }
                                Some(frame) = audio_rx.recv() => {
                                    let len = (frame.len() as u32).to_le_bytes();
                                    if audio_stream.write_all(&len).await.is_err() { break; }
                                    if audio_stream.write_all(&frame).await.is_err() { break; }
                                }
                                else => break,
                            }
                        }
                        state_clone.store(STATE_DISCONNECTED, Ordering::SeqCst);
                    }
                }
            }
        }
    });

    let ctx = Box::new(ServerContext {
        state,
        video_tx,
        audio_tx,
        input_rx,
        _rt: rt,
    });
    Box::into_raw(ctx) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_quic_QuicServer_pollData(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    mut buffer: JByteArray,
) -> jint {
    if handle == 0 { return 0; }
    let ctx = unsafe { &mut *(handle as *mut ServerContext) };
    if let Ok(data) = ctx.input_rx.try_recv() {
        let len = data.len();
        if len > 0 {
            // Unsafe byte casting to copy without extra allocations
            let bytes = unsafe { std::slice::from_raw_parts(data.as_ptr() as *const i8, len) };
            if env.set_byte_array_region(&mut buffer, 0, bytes).is_ok() {
                return len as jint;
            }
        }
    }
    0
}

#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_quic_QuicServer_send(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
) {
    if handle == 0 { return; }
    let ctx = unsafe { &mut *(handle as *mut ServerContext) };
    if let Ok(bytes) = env.convert_byte_array(&data) {
        let _ = ctx.video_tx.try_send(bytes);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_quic_QuicServer_sendAudio(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
) {
    if handle == 0 { return; }
    let ctx = unsafe { &mut *(handle as *mut ServerContext) };
    if let Ok(bytes) = env.convert_byte_array(&data) {
        let _ = ctx.audio_tx.try_send(bytes);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_androidhost_quic_QuicServer_connectionState(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 { return 0; }
    let ctx = unsafe { &*(handle as *const ServerContext) };
    ctx.state.load(Ordering::SeqCst)
}
