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
    
    let server_config = ServerConfig::with_crypto(Arc::new(server_crypto));
    let endpoint = Endpoint::server(server_config, "0.0.0.0:4433".parse()?)?;

    println!("Mock server listening on 0.0.0.0:4433");

    while let Some(incoming) = endpoint.accept().await {
        tokio::spawn(async move {
            let connection = incoming.await.unwrap();
            println!("Client connected from {:?}", connection.remote_address());
            
            loop {
                match connection.accept_bi().await {
                    Ok((mut send, mut recv)) => {
                        tokio::spawn(async move {
                            match recv.read_to_end(1024 * 1024).await {
                                Ok(data) => {
                                    send.write_all(&data).await.unwrap();
                                    send.finish().await.unwrap();
                                }
                                Err(_) => ()
                            }
                        });
                    }
                    Err(_) => break,
                }
            }
        });
    }

    Ok(())
}
