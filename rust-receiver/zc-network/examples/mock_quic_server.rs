use quinn::{Endpoint, ServerConfig};
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use std::sync::Arc;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();

    let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()])?;
    let cert_der = cert.cert.der().to_vec();
    let key_der = cert.key_pair.serialize_der();
    
    let private_key = PrivateKeyDer::Pkcs8(key_der.into());
    let cert_chain = vec![CertificateDer::from(cert_der)];

    let mut server_crypto = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(cert_chain, private_key)?;
    
    server_crypto.alpn_protocols = vec![b"androiddex-pairing".to_vec(), b"androiddex".to_vec()];
    
    let server_config = ServerConfig::with_crypto(Arc::new(quinn::crypto::rustls::QuicServerConfig::try_from(server_crypto)?));
    let endpoint = Endpoint::server(server_config, "0.0.0.0:4433".parse()?)?;

    println!("Mock server listening on 0.0.0.0:4433");

    while let Some(incoming) = endpoint.accept().await {
        tokio::spawn(async move {
            let connection = incoming.await.unwrap();
            let is_pairing = connection.handshake_data().unwrap().downcast::<quinn::crypto::rustls::HandshakeData>().unwrap().protocol == Some(b"androiddex-pairing".to_vec());
            
            println!("Client connected from {:?} (pairing: {})", connection.remote_address(), is_pairing);
            
            if is_pairing {
                // Send OK to pass pairing
                if let Ok(mut send) = connection.open_uni().await {
                    let _ = send.write_all(b"OK").await;
                    let _ = send.finish();
                }
                // Allow the client to disconnect and reconnect
                return;
            }
            
            let conn_clone = connection.clone();
            tokio::spawn(async move {
                if let Ok(mut send) = conn_clone.open_uni().await {
                    use zc_protocol::video::VideoFrame;
                    use prost::Message;

                    let width = 1920;
                    let height = 1080;
                    let mut rgba_data = vec![0u8; (width * height * 4) as usize];
                    
                    for y in 0..height {
                        for x in 0..width {
                            let idx = ((y * width + x) * 4) as usize;
                            if x < width / 2 {
                                // Red
                                rgba_data[idx] = 255;
                                rgba_data[idx+1] = 0;
                                rgba_data[idx+2] = 0;
                                rgba_data[idx+3] = 255;
                            } else {
                                // Blue
                                rgba_data[idx] = 0;
                                rgba_data[idx+1] = 0;
                                rgba_data[idx+2] = 255;
                                rgba_data[idx+3] = 255;
                            }
                        }
                    }

                    loop {
                        let frame = VideoFrame {
                            width,
                            height,
                            timestamp: 0,
                            rgba_data: rgba_data.clone(),
                            source: 1, // VM_WAYLAND
                        };
                        let mut buf = Vec::new();
                        frame.encode(&mut buf).unwrap();
                        
                        let len = buf.len() as u32;
                        if send.write_all(&len.to_le_bytes()).await.is_err() { break; }
                        if send.write_all(&buf).await.is_err() { break; }
                        
                        tokio::time::sleep(std::time::Duration::from_millis(33)).await; // ~30fps
                    }
                }
            });

            loop {
                tokio::select! {
                    Ok((mut send, mut recv)) = connection.accept_bi() => {
                        tokio::spawn(async move {
                            match recv.read_to_end(1024 * 1024).await {
                                Ok(data) => {
                                    send.write_all(&data).await.unwrap();
                                    send.finish().unwrap();
                                }
                                Err(_) => ()
                            }
                        });
                    }
                    Ok(mut recv) = connection.accept_uni() => {
                        // Decode InputEvent messages from the client
                        tokio::spawn(async move {
                            use zc_protocol::protocol::InputEvent;
                            use prost::Message;

                            println!("[INPUT STREAM] Accepted input stream from client");
                            loop {
                                let mut len_buf = [0u8; 4];
                                match recv.read_exact(&mut len_buf).await {
                                    Ok(()) => {}
                                    Err(_) => {
                                        println!("[INPUT STREAM] Stream closed");
                                        break;
                                    }
                                }
                                let len = u32::from_le_bytes(len_buf) as usize;
                                if len > 1024 * 1024 {
                                    println!("[INPUT STREAM] Frame too large: {} bytes", len);
                                    break;
                                }
                                let mut data = vec![0u8; len];
                                match recv.read_exact(&mut data).await {
                                    Ok(()) => {}
                                    Err(_) => {
                                        println!("[INPUT STREAM] Failed to read data");
                                        break;
                                    }
                                }

                                match InputEvent::decode(&data[..]) {
                                    Ok(event) => {
                                        use zc_protocol::protocol::input_event::Event;
                                        match event.event {
                                            Some(Event::Mouse(m)) => {
                                                println!("[MOUSE] x={}, y={}, buttons={}, ts={}", m.x, m.y, m.buttons, m.timestamp);
                                            }
                                            Some(Event::Keyboard(k)) => {
                                                println!("[KEYBOARD] keycode={}, pressed={}, modifiers={}, ts={}", k.keycode, k.pressed, k.modifiers, k.timestamp);
                                            }
                                            None => {
                                                println!("[INPUT] Empty InputEvent");
                                            }
                                        }
                                    }
                                    Err(e) => {
                                        println!("[INPUT] Failed to decode InputEvent: {}", e);
                                    }
                                }
                            }
                        });
                    }
                    else => break,
                }
            }
        });
    }

    Ok(())
}
