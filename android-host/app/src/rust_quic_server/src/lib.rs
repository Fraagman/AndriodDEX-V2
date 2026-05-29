use jni::objects::{JByteArray, JClass};
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use quinn::{Endpoint, ServerConfig};
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use std::sync::{Arc, Mutex};

lazy_static::lazy_static! {
    static ref SERVER_STATE: Mutex<Option<ServerState>> = Mutex::new(None);
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
    mut _env: JNIEnv,
    _class: JClass,
    port: jint,
) -> jlong {
    let (input_tx, input_rx) = std::sync::mpsc::channel();
    let (video_tx, mut video_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(100);

    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("Failed to build tokio runtime");

    let port = port as u16;

    rt.spawn(async move {
        // Generate self-signed cert
        let (cert_der, key_der) = generate_self_signed_cert().expect("Failed to generate cert");
        let cert = CertificateDer::from(cert_der);
        let key = PrivateKeyDer::try_from(key_der).expect("Invalid private key");

        let mut server_crypto = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![cert], key)
            .expect("Failed to build server crypto");
            
        server_crypto.alpn_protocols = vec![b"androiddex".to_vec()];

        let server_config = ServerConfig::with_crypto(Arc::new(
            quinn::crypto::rustls::QuicServerConfig::try_from(server_crypto).expect("Failed to create quic server config")
        ));

        let bind_addr = format!("0.0.0.0:{}", port).parse().unwrap();
        let endpoint = Endpoint::server(server_config, bind_addr).expect("Failed to bind endpoint");

        println!("QUIC server listening on 0.0.0.0:{}", port);

        // Accept a single connection for now
        if let Some(incoming) = endpoint.accept().await {
            match incoming.await {
                Ok(connection) => {
                    println!("QUIC connected to client");

                    // Spawn task to send video frames
                    let conn_video = connection.clone();
                    tokio::spawn(async move {
                        loop {
                            if let Some(frame_data) = video_rx.recv().await {
                                match conn_video.open_uni().await {
                                    Ok(mut stream) => {
                                        let len = frame_data.len() as u32;
                                        if stream.write_all(&len.to_le_bytes()).await.is_err() {
                                            break;
                                        }
                                        if stream.write_all(&frame_data).await.is_err() {
                                            break;
                                        }
                                    }
                                    Err(_) => break, // Connection closed
                                }
                            }
                        }
                    });

                    // Accept input streams
                    let conn_input = connection.clone();
                    let input_tx_clone = input_tx.clone();
                    tokio::spawn(async move {
                        loop {
                            match conn_input.accept_uni().await {
                                Ok(mut stream) => {
                                    tokio::spawn({
                                        let input_tx = input_tx_clone.clone();
                                        async move {
                                            loop {
                                                let mut len_buf = [0u8; 4];
                                                if stream.read_exact(&mut len_buf).await.is_err() {
                                                    break;
                                                }
                                                let len = u32::from_le_bytes(len_buf) as usize;
                                                let mut buf = vec![0u8; len];
                                                if stream.read_exact(&mut buf).await.is_err() {
                                                    break;
                                                }
                                                let _ = input_tx.send(buf);
                                            }
                                        }
                                    });
                                }
                                Err(_) => break, // Connection closed
                            }
                        }
                    });
                }
                Err(e) => {
                    println!("Connection failed: {}", e);
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
