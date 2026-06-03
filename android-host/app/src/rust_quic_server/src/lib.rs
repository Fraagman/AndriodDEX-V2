use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong, jboolean};
use jni::JNIEnv;
use quinn::{Endpoint, ServerConfig};
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicI32, Ordering};
use tokio::sync::broadcast;
use std::path::PathBuf;

const STATE_IDLE: jint = 0;
const STATE_PAIRING: jint = 1;
const STATE_AUTHENTICATED: jint = 2;
const STATE_DISCONNECTED: jint = 3;

lazy_static::lazy_static! {
    static ref CONNECTION_STATE: AtomicI32 = AtomicI32::new(STATE_IDLE);
    static ref VIDEO_BROADCAST: Mutex<Option<broadcast::Sender<Vec<u8>>>> = Mutex::new(None);
    static ref AUDIO_BROADCAST: Mutex<Option<broadcast::Sender<Vec<u8>>>> = Mutex::new(None);
    static ref INPUT_QUEUE: Mutex<Option<std::sync::mpsc::Receiver<Vec<u8>>>> = Mutex::new(None);
    static ref PAIRING_WAKER: Mutex<Option<std::sync::mpsc::Sender<String>>> = Mutex::new(None);
}

fn generate_self_signed_cert() -> Result<(Vec<u8>, Vec<u8>), Box<dyn std::error::Error>> {
    let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()])?;
    let key = cert.serialize_private_key_der();
    let cert = cert.serialize_der()?;
    Ok((cert, key))
}

#[no_mangle]
pub extern "C" fn Java_com_example_androidhost_quic_QuicServer_start(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
    data_path: JString,
) -> jlong {
    let (input_tx, input_rx) = std::sync::mpsc::channel();
    let (video_tx, _) = broadcast::channel::<Vec<u8>>(4); // Capacity 4 for video frames
    let (audio_tx, _) = broadcast::channel::<Vec<u8>>(16); // Capacity 16 for audio frames
    
    if let Ok(mut vb) = VIDEO_BROADCAST.lock() {
        *vb = Some(video_tx.clone());
    }
    if let Ok(mut ab) = AUDIO_BROADCAST.lock() {
        *ab = Some(audio_tx.clone());
    }
    if let Ok(mut iq) = INPUT_QUEUE.lock() {
        *iq = Some(input_rx);
    }
    CONNECTION_STATE.store(STATE_IDLE, Ordering::SeqCst);

    let rt_res = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .thread_name("quic-acceptor")
        .build();
        
    let rt = match rt_res {
        Ok(r) => r,
        Err(e) => {
            log::error!("Failed to build tokio runtime: {}", e);
            return -1;
        }
    };

    let path_str: String = match env.get_string(&data_path) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let data_path_buf = PathBuf::from(path_str);
    zc_security::storage::set_data_path(data_path_buf.clone());

    let port = port as u16;

    if let Err(e) = std::thread::Builder::new().name("quic-main".into()).spawn(move || {
        rt.block_on(async move {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Trace)
                    .with_tag("QuicServerRust"),
            );
            log::info!("Starting QUIC server background task with storage: {:?}", data_path_buf);

            let _ = rustls::crypto::ring::default_provider().install_default();

            let (cert_der, key_der) = if let Some((c, k)) = zc_security::storage::load_server_cert() {
                log::info!("Loaded existing server certificate");
                (c, k)
            } else {
                log::info!("Generating new server certificate");
                let cert_res = generate_self_signed_cert();
                if let Err(e) = cert_res {
                    log::error!("Failed to generate cert: {}", e);
                    return;
                }
                let (c, k) = cert_res.unwrap();
                if let Err(e) = zc_security::storage::store_server_cert(&c, &k) {
                    log::error!("Failed to store server cert: {}", e);
                }
                (c, k)
            };
            
            let cert = CertificateDer::from(cert_der);
            let key_res = PrivateKeyDer::try_from(key_der);
            if let Err(e) = key_res {
                log::error!("Invalid private key: {:?}", e);
                return;
            }
            let key = key_res.unwrap();

            let server_crypto_res = rustls::ServerConfig::builder()
                .with_no_client_auth()
                .with_single_cert(vec![cert], key);
                
            if let Err(e) = server_crypto_res {
                log::error!("Failed to build server crypto: {}", e);
                return;
            }
            let mut server_crypto = server_crypto_res.unwrap();
                
            server_crypto.alpn_protocols = vec![b"androiddex".to_vec(), b"androiddex-pairing".to_vec()];

            let quic_config_res = quinn::crypto::rustls::QuicServerConfig::try_from(server_crypto);
            if let Err(e) = quic_config_res {
                log::error!("Failed to create quic server config: {:?}", e);
                return;
            }
            
            let mut server_config = ServerConfig::with_crypto(Arc::new(quic_config_res.unwrap()));
            let mut transport_config = quinn::TransportConfig::default();
            
            if let Ok(timeout) = std::time::Duration::from_secs(30).try_into() {
                transport_config.max_idle_timeout(Some(timeout));
            }
            transport_config.keep_alive_interval(Some(std::time::Duration::from_secs(5)));
            server_config.transport_config(Arc::new(transport_config));

            let bind_addr_res = format!("0.0.0.0:{}", port).parse();
            if let Err(e) = bind_addr_res {
                log::error!("Failed to parse bind address: {}", e);
                return;
            }
            
            let endpoint_res = Endpoint::server(server_config, bind_addr_res.unwrap_or_else(|_| "0.0.0.0:4433".parse().unwrap()));
            if let Err(e) = endpoint_res {
                log::error!("Failed to bind endpoint: {}", e);
                return;
            }
            let endpoint = endpoint_res.unwrap();

            log::info!("QUIC server listening on 0.0.0.0:{}", port);

            // Last IP for grace window
            let mut last_auth_ip: Option<(std::net::IpAddr, std::time::Instant)> = None;

            loop {
                CONNECTION_STATE.store(STATE_IDLE, Ordering::SeqCst);
                
                if let Some(incoming) = endpoint.accept().await {
                    match incoming.await {
                        Ok(connection) => {
                            let ip = connection.remote_address().ip();
                            log::info!("Connection established to {}", ip);
                            CONNECTION_STATE.store(STATE_PAIRING, Ordering::SeqCst);

                            let mut auto_accept = false;
                            if let Some((last_ip, time)) = last_auth_ip {
                                if last_ip == ip && time.elapsed().as_secs() < 10 {
                                    log::info!("Auto-accepted reconnect from {} within grace window", ip);
                                    auto_accept = true;
                                }
                            }

                            let conn_clone = connection.clone();
                            let input_tx_clone = input_tx.clone();
                            let video_tx_clone = video_tx.clone();
                            let audio_tx_clone = audio_tx.clone();
                            
                            tokio::spawn(async move {
                                let mut authenticated = false;

                                if let Ok((mut send_stream, mut recv_stream)) = conn_clone.accept_bi().await {
                                    let mut prefix = [0u8; 1];
                                    if recv_stream.read_exact(&mut prefix).await.is_err() {
                                        CONNECTION_STATE.store(STATE_DISCONNECTED, Ordering::SeqCst);
                                        log::error!("Connection closed by peer: Failed to read prefix");
                                        return;
                                    }

                                    if prefix[0] == b'P' {
                                        log::info!("Pairing stream opened");
                                        let mut pubkey = [0u8; 32];
                                        if recv_stream.read_exact(&mut pubkey).await.is_ok() {
                                            log::info!("PIN sent to user. Waiting for PIN from user...");
                                            
                                            let (tx, rx) = std::sync::mpsc::channel();
                                            if let Ok(mut waker) = PAIRING_WAKER.lock() {
                                                *waker = Some(tx);
                                            }
                                            
                                            if let Ok(pin) = tokio::task::spawn_blocking(move || rx.recv()).await {
                                                if let Ok(pin) = pin {
                                                    log::info!("PIN received from user: verifying...");
                                                    let psk = zc_security::pairing::derive_psk(&pin, &pubkey);
                                                    let client_id = [0u8; 32];
                                                    if let Err(e) = zc_security::storage::store_trust_data(&client_id, &psk) {
                                                        log::error!("Failed to store trust data: {:?}", e);
                                                    }
                                                    let _ = send_stream.write_all(b"OK").await;
                                                    let _ = send_stream.finish();
                                                    
                                                    // Wait for the Auth packet on the NEXT bidirectional stream in this connection
                                                    if let Ok((mut auth_send, mut auth_recv)) = conn_clone.accept_bi().await {
                                                        let mut ap = [0u8; 1];
                                                        if auth_recv.read_exact(&mut ap).await.is_ok() && ap[0] == b'A' {
                                                            let mut token = [0u8; 32];
                                                            if auth_recv.read_exact(&mut token).await.is_ok() {
                                                                use sha2::{Sha256, Digest};
                                                                let mut hasher = Sha256::new();
                                                                hasher.update(&psk);
                                                                hasher.update(b"auth");
                                                                let expected: [u8; 32] = hasher.finalize().into();
                                                                if token == expected {
                                                                    authenticated = true;
                                                                    let _ = auth_send.write_all(b"OK").await;
                                                                    let _ = auth_send.finish();
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else if prefix[0] == b'A' {
                                        log::info!("Auth stream opened (reconnect)");
                                        let mut token = [0u8; 32];
                                        if recv_stream.read_exact(&mut token).await.is_ok() {
                                            if auto_accept {
                                                authenticated = true;
                                            } else if let Some((_, psk)) = zc_security::storage::load_trust_data() {
                                                use sha2::{Sha256, Digest};
                                                let mut hasher = Sha256::new();
                                                hasher.update(&psk);
                                                hasher.update(b"auth");
                                                let expected: [u8; 32] = hasher.finalize().into();
                                                if token == expected {
                                                    authenticated = true;
                                                }
                                            }
                                        }
                                        if authenticated {
                                            let _ = send_stream.write_all(b"OK").await;
                                            let _ = send_stream.finish();
                                        } else {
                                            let _ = send_stream.write_all(b"NO").await;
                                            let _ = send_stream.finish();
                                        }
                                    }
                                } else {
                                    log::error!("Connection closed by peer: Failed to accept bi stream");
                                }

                                if authenticated {
                                    log::info!("AuthSuccess received — keeping connection alive");
                                    CONNECTION_STATE.store(STATE_AUTHENTICATED, Ordering::SeqCst);
                                    
                                    log::info!("Opening video open_uni stream (Android -> PC)...");
                                    let mut video_rx = video_tx_clone.subscribe();
                                    let conn_video = conn_clone.clone();
                                    
                                    tokio::spawn(async move {
                                        let mut stream_opt = None;
                                        loop {
                                            match video_rx.recv().await {
                                                Ok(frame_data) => {
                                                    let mut stream = match stream_opt.take() {
                                                        Some(s) => s,
                                                        None => match conn_video.open_uni().await {
                                                            Ok(s) => s,
                                                            Err(_) => break,
                                                        }
                                                    };
                                                    
                                                    let len = frame_data.len() as u32;
                                                    if stream.write_all(&len.to_le_bytes()).await.is_err() || stream.write_all(&frame_data).await.is_err() {
                                                        // If it fails, we will try to open a new one on the next frame
                                                        continue;
                                                    }
                                                    stream_opt = Some(stream);
                                                }
                                                Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => {
                                                    log::warn!("Video broadcast lagged by {} frames", n);
                                                }
                                                Err(tokio::sync::broadcast::error::RecvError::Closed) => {
                                                    break;
                                                }
                                            }
                                        }
                                    });

                                    log::info!("Opening audio open_uni stream (Android -> PC)...");
                                    let mut audio_rx = audio_tx_clone.subscribe();
                                    let conn_audio = conn_clone.clone();
                                    
                                    tokio::spawn(async move {
                                        let mut stream_opt = None;
                                        loop {
                                            match audio_rx.recv().await {
                                                Ok(frame_data) => {
                                                    let mut stream = match stream_opt.take() {
                                                        Some(s) => s,
                                                        None => match conn_audio.open_uni().await {
                                                            Ok(s) => s,
                                                            Err(_) => break,
                                                        }
                                                    };
                                                    
                                                    let len = frame_data.len() as u32;
                                                    if stream.write_all(&len.to_le_bytes()).await.is_err() || stream.write_all(&frame_data).await.is_err() {
                                                        continue;
                                                    }
                                                    stream_opt = Some(stream);
                                                }
                                                Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => {
                                                    log::warn!("Audio broadcast lagged by {} frames", n);
                                                }
                                                Err(tokio::sync::broadcast::error::RecvError::Closed) => {
                                                    break;
                                                }
                                            }
                                        }
                                    });

                                    log::info!("Opening input accept_uni stream (PC -> Android)...");
                                    let conn_input = conn_clone.clone();
                                    tokio::spawn(async move {
                                        loop {
                                            if let Ok(mut stream) = conn_input.accept_uni().await {
                                                let tx = input_tx_clone.clone();
                                                tokio::spawn(async move {
                                                    loop {
                                                        let mut len_buf = [0u8; 4];
                                                        if stream.read_exact(&mut len_buf).await.is_err() { break; }
                                                        let len = u32::from_le_bytes(len_buf) as usize;
                                                        let mut buf = vec![0u8; len];
                                                        if stream.read_exact(&mut buf).await.is_err() { break; }
                                                        let _ = tx.send(buf);
                                                    }
                                                });
                                            } else {
                                                break;
                                            }
                                        }
                                    });

                                    // Wait for connection to drop
                                    let reason = conn_clone.closed().await;
                                    log::error!("Connection closed by peer: {:?}", reason);
                                } else {
                                    log::error!("Authentication failed or pairing aborted");
                                }
                                
                                CONNECTION_STATE.store(STATE_DISCONNECTED, Ordering::SeqCst);
                            });
                            
                            if connection.remote_address().ip() != std::net::Ipv4Addr::new(127, 0, 0, 1) {
                                last_auth_ip = Some((connection.remote_address().ip(), std::time::Instant::now()));
                            }
                        }
                        Err(e) => {
                            log::error!("Connection error: {} — will NOT reconnect automatically unless USB is replugged", e);
                        }
                    }
                }
            }
        });
    }) {
        log::error!("Failed to spawn quic-main thread: {}", e);
    }

    1
}

#[no_mangle]
pub extern "C" fn Java_com_example_androidhost_quic_QuicServer_connectionState(
    _env: JNIEnv,
    _class: JClass,
    _handle: jlong,
) -> jint {
    CONNECTION_STATE.load(Ordering::SeqCst)
}

#[no_mangle]
pub extern "C" fn Java_com_example_androidhost_security_SecurityBridge_verifyPin(
    mut env: JNIEnv,
    _class: JClass,
    pin: JString,
) -> jboolean {
    let pin_str: String = env.get_string(&pin).map(|s| s.into()).unwrap_or_default();
    
    if let Ok(mut waker_lock) = PAIRING_WAKER.lock() {
        if let Some(tx) = waker_lock.take() {
            let _ = tx.send(pin_str);
            return 1;
        }
    }
    0
}

#[no_mangle]
pub extern "C" fn Java_com_example_androidhost_quic_QuicServer_pollData(
    env: JNIEnv,
    _class: JClass,
    _handle: jlong,
    buffer: JByteArray,
) -> jint {
    if let Ok(mut q_lock) = INPUT_QUEUE.lock() {
        if let Some(rx) = q_lock.as_mut() {
            if let Ok(data) = rx.try_recv() {
                let len = data.len();
                if len > 0 {
                    let buf_len = match env.get_array_length(&buffer) {
                        Ok(l) => l as usize,
                        Err(_) => return -1,
                    };
                    if len <= buf_len {
                        let signed_data: Vec<i8> = data.into_iter().map(|b| b as i8).collect();
                        if env.set_byte_array_region(&buffer, 0, &signed_data).is_ok() {
                            return len as jint;
                        }
                    }
                }
            }
        }
    }
    0
}

#[no_mangle]
pub extern "C" fn Java_com_example_androidhost_quic_QuicServer_send(
    mut env: JNIEnv,
    _class: JClass,
    _handle: jlong,
    data: JByteArray,
) -> jint {
    if let Ok(broadcast_lock) = VIDEO_BROADCAST.lock() {
        if let Some(tx) = broadcast_lock.as_ref() {
            if let Ok(elements) = env.convert_byte_array(&data) {
                let len = elements.len() as jint;
                let _ = tx.send(elements.to_vec());
                return len;
            }
        }
    }
    -1
}

#[no_mangle]
pub extern "C" fn Java_com_example_androidhost_quic_QuicServer_sendAudio(
    mut env: JNIEnv,
    _class: JClass,
    _handle: jlong,
    data: JByteArray,
) -> jint {
    if let Ok(broadcast_lock) = AUDIO_BROADCAST.lock() {
        if let Some(tx) = broadcast_lock.as_ref() {
            if let Ok(elements) = env.convert_byte_array(&data) {
                let len = elements.len() as jint;
                let _ = tx.send(elements.to_vec());
                return len;
            }
        }
    }
    -1
}

#[no_mangle]
pub extern "C" fn Java_com_example_androidhost_quic_QuicServer_providePin(
    mut env: JNIEnv,
    _class: JClass,
    pin: JString,
) {
    let pin_str: String = env.get_string(&pin).map(|s| s.into()).unwrap_or_default();
    if let Ok(mut waker_lock) = PAIRING_WAKER.lock() {
        if let Some(tx) = waker_lock.take() {
            let _ = tx.send(pin_str);
        }
    }
}
