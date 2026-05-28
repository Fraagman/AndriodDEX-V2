use quinn::{Endpoint, ServerConfig};
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use std::sync::Arc;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let cert = rcgen::generate_simple_self_signed(vec!["localhost".into()])?;
    let cert_der = cert.cert.der().to_vec();
    let key_der = cert.key_pair.serialize_der();
    
    let private_key = PrivateKeyDer::Pkcs8(key_der.into());
    let cert_chain = vec![CertificateDer::from(cert_der)];

    let mut server_crypto = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(cert_chain, private_key)?;
    
    server_crypto.alpn_protocols = vec![b"androiddex".to_vec()];
    
    let server_config = ServerConfig::with_crypto(Arc::new(quinn::crypto::rustls::QuicServerConfig::try_from(server_crypto)?));
    let endpoint = Endpoint::server(server_config, "0.0.0.0:4433".parse()?)?;

    println!("Mock server listening on 0.0.0.0:4433");

    while let Some(incoming) = endpoint.accept().await {
        tokio::spawn(async move {
            let connection = incoming.await.unwrap();
            println!("Client connected from {:?}", connection.remote_address());
            
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
                        tokio::spawn(async move {
                            let mut buf = vec![0u8; 8192];
                            loop {
                                match recv.read(&mut buf).await {
                                    Ok(Some(n)) => {
                                        println!("Received {} bytes", n);
                                    }
                                    Ok(None) => break,
                                    Err(_) => break,
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
