use jni::objects::{JByteArray, JClass};
use jni::sys::{jint, jlong, jboolean};
use jni::JNIEnv;
use quinn::{Endpoint, ServerConfig};
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use std::sync::{Arc, Mutex};

lazy_static::lazy_static! {
    static ref SERVER_STATE: Mutex<Option<ServerState>> = Mutex::new(None);
    static ref PAIRING_WAKER: Mutex<Option<std::sync::mpsc::Sender<String>>> = Mutex::new(None);
}

struct ServerState {
    _rt: tokio::runtime::Runtime,
    input_rx: std::sync::mpsc::Receiver<Vec<u8>>,
    video_tx: tokio::sync::mpsc::Sender<Vec<u8>>,
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
    data_path: jni::objects::JString,
) -> jlong {
    let (input_tx, input_rx) = std::sync::mpsc::channel();
    let (video_tx, mut video_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(100);

    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("Failed to build tokio runtime");

    let port = port as u16;

    let path_str: String = env.get_string(&data_path).expect("Couldn't get data path string").into();
    let data_path_buf = std::path::PathBuf::from(path_str);
    zc_security::storage::set_data_path(data_path_buf.clone());

    rt.spawn(async move {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Trace)
                .with_tag("QuicServerRust"),
        );
        log::info!("Starting QUIC server background task with storage: {:?}", data_path_buf);

        let _ = rustls::crypto::ring::default_provider().install_default();

        // Generate self-signed cert
        let cert_res = generate_self_signed_cert();
        if let Err(e) = cert_res {
            log::error!("Failed to generate cert: {}", e);
            return;
        }
        let (cert_der, key_der) = cert_res.unwrap();
        
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
        
        let server_config = ServerConfig::with_crypto(Arc::new(quic_config_res.unwrap()));

        let bind_addr_res = format!("0.0.0.0:{}", port).parse();
        if let Err(e) = bind_addr_res {
            log::error!("Failed to parse bind address: {}", e);
            return;
        }
        
        let endpoint_res = Endpoint::server(server_config, bind_addr_res.unwrap());
        if let Err(e) = endpoint_res {
            log::error!("Failed to bind endpoint: {}", e);
            return;
        }
        let endpoint = endpoint_res.unwrap();

        log::info!("QUIC server listening on 0.0.0.0:{}", port);

        let mut video_rx_opt = Some(video_rx);
        
        loop {
            if let Some(incoming) = endpoint.accept().await {
                match incoming.await {
                    Ok(connection) => {
                        println!("QUIC connected to client");

                        let is_pairing = connection.handshake_data().unwrap().downcast::<rustls::ServerConnection>().unwrap().alpn_protocol() == Some(b"androiddex-pairing".as_slice());

                        if is_pairing {
                            log::info!("Handling pairing connection...");
                            let conn = connection.clone();
                            tokio::spawn(async move {
                                if let Ok(mut stream) = conn.accept_uni().await {
                                    let mut pubkey = [0u8; 32];
                                    if stream.read_exact(&mut pubkey).await.is_ok() {
                                        log::warn!("Received pairing request. Waiting for Java to provide PIN.");
                                        
                                        let (tx, rx) = std::sync::mpsc::channel();
                                        *PAIRING_WAKER.lock().unwrap() = Some(tx);
                                        
                                        // Wait for Java to call providePin
                                        // Since we are in a tokio task, blocking recv will block the worker thread.
                                        // It's better to use tokio::sync::oneshot, but mpsc is fine for this low-concurrency pairing thread if we don't mind blocking one worker.
                                        // To be safe, we'll just poll or use tokio channel.
                                        
                                        // We will spawn a blocking task to wait for the mpsc channel
                                        let conn_clone = conn.clone();
                                        tokio::task::spawn_blocking(move || {
                                            if let Ok(pin) = rx.recv() {
                                                let psk = zc_security::pairing::derive_psk(&pin, &pubkey);
                                                let fp = [0u8; 32]; // dummy for server
                                                let _ = zc_security::storage::store_trust_data(&fp, &psk);
                                                
                                                // Send OK response
                                                let rt = tokio::runtime::Handle::current();
                                                rt.block_on(async move {
                                                    if let Ok(mut reply) = conn_clone.open_uni().await {
                                                        let _ = reply.write_all(b"OK").await;
                                                        let _ = reply.finish();
                                                    }
                                                    Ok::<(), Box<dyn std::error::Error>>(())
                                                }).ok();
                                            }
                                        });
                                    }
                                }
                            });
                            continue;
                        }
                        
                        // Authenticate
                        let auth_res = async {
                            if let Ok(mut auth_stream) = connection.accept_uni().await {
                                let mut token = [0u8; 32];
                                if auth_stream.read_exact(&mut token).await.is_err() { return false; }
                                if let Some((_, psk)) = zc_security::storage::load_trust_data() {
                                    use sha2::{Sha256, Digest};
                                    let mut hasher = Sha256::new();
                                    hasher.update(&psk);
                                    hasher.update(b"auth");
                                    let expected: [u8; 32] = hasher.finalize().into();
                                    return token == expected;
                                }
                            }
                            false
                        }.await;

                        if !auth_res {
                            log::error!("Authentication failed");
                            continue;
                        }
                        log::info!("Client authenticated successfully");
                        


                        // For simplicity, handle one connection at a time
                        // Stop the previous tasks and start new ones
                        
                        // We will just do it sequentially for now
                        let mut video_rx = std::mem::replace(&mut video_rx_opt, None).unwrap();
                        let conn_video = connection.clone();
                        tokio::spawn(async move {
                            loop {
                                if let Some(frame_data) = video_rx.recv().await {
                                    if let Ok(mut stream) = conn_video.open_uni().await {
                                        let len = frame_data.len() as u32;
                                        let _ = stream.write_all(&len.to_le_bytes()).await;
                                        let _ = stream.write_all(&frame_data).await;
                                    }
                                }
                            }
                        });

                        let conn_input = connection.clone();
                        let input_tx_clone = input_tx.clone();
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
                    }
                    Err(e) => {
                        println!("Connection failed: {}", e);
                    }
                }
            }
        }
    });

    let state = ServerState {
        _rt: rt,
        input_rx,
        video_tx,
    };

    let mut global_state = SERVER_STATE.lock().unwrap();
    *global_state = Some(state);

    1 // Return dummy handle since we use a global singleton for simplicity
}

#[no_mangle]
pub extern "C" fn Java_com_example_androidhost_security_SecurityBridge_verifyPin(
    mut env: JNIEnv,
    _class: JClass,
    pin: jni::objects::JString,
) -> jboolean {
    let pin_str: String = env.get_string(&pin).expect("Couldn't get string").into();
    // For development, hardcode test PIN
    if pin_str == "000000" {
        // Also feed the PIN to the QUIC server pairing flow so it proceeds
        if let Some(tx) = PAIRING_WAKER.lock().unwrap().take() {
            let _ = tx.send(pin_str);
        }
        return 1;
    }
    
    // Attempt to verify via QUIC pairing flow
    if let Some(tx) = PAIRING_WAKER.lock().unwrap().take() {
        let _ = tx.send(pin_str.clone());
        // Since we can't synchronously verify if the PIN is correct right now (it's used to derive PSK),
        // we return 1 (true) to let the UI proceed. If the PIN is wrong, the subsequent QUIC 
        // connection authentication will fail and the connection will drop.
        return 1;
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
    let global_state = SERVER_STATE.lock().unwrap();
    if let Some(state) = global_state.as_ref() {
        if let Ok(data) = state.input_rx.try_recv() {
            let len = data.len();
            if len > 0 {
                let buf_len = env.get_array_length(&buffer).unwrap() as usize;
                if len <= buf_len {
                    // Convert to i8 safely
                    let signed_data: Vec<i8> = data.into_iter().map(|b| b as i8).collect();
                    env.set_byte_array_region(&buffer, 0, &signed_data).unwrap();
                    return len as jint;
                }
            }
        }
    }
    0
}

#[no_mangle]
pub extern "C" fn Java_com_example_androidhost_quic_QuicServer_send(
    env: JNIEnv,
    _class: JClass,
    _handle: jlong,
    data: JByteArray,
) {
    let global_state = SERVER_STATE.lock().unwrap();
    if let Some(state) = global_state.as_ref() {
        if let Ok(elements) = env.convert_byte_array(data) {
            let _ = state.video_tx.try_send(elements);
        }
    }
}

#[no_mangle]
pub extern "C" fn Java_com_example_androidhost_quic_QuicServer_providePin(
    mut env: JNIEnv,
    _class: JClass,
    pin: jni::objects::JString,
) {
    let pin_str: String = env.get_string(&pin).expect("Couldn't get string").into();
    if let Some(tx) = PAIRING_WAKER.lock().unwrap().take() {
        let _ = tx.send(pin_str);
    }
}
