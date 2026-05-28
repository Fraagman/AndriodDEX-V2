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
                                        println!("Received {} bytes:", n);
                                        for chunk in buf[..n].chunks(16) {
                                            for b in chunk {
                                                print!("{:02X} ", b);
                                            }
                                            println!();
                                        }
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
