use quinn::{Endpoint, ClientConfig};
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::time::Duration;

#[tokio::main]
async fn main() {
    let mut endpoint = Endpoint::client("0.0.0.0:0".parse().unwrap()).unwrap();
    
    let mut crypto = rustls::ClientConfig::builder()
        .with_safe_defaults()
        .with_custom_certificate_verifier(Arc::new(zc_security::cert::SkipServerVerification))
        .with_no_client_auth();
    
    crypto.alpn_protocols = vec![b"androiddex-pairing".to_vec()];
    
    endpoint.set_default_client_config(ClientConfig::new(Arc::new(quinn::crypto::rustls::QuicClientConfig::try_from(crypto).unwrap())));

    let addr: SocketAddr = "10.188.185.233:4433".parse().unwrap();
    println!("Connecting to {}...", addr);
    if let Ok(connecting) = endpoint.connect(addr, "localhost") {
        match tokio::time::timeout(Duration::from_millis(2000), connecting).await {
            Ok(Ok(conn)) => {
                println!("Connected to {}", addr);
            }
            Ok(Err(e)) => {
                println!("Connection error: {:?}", e);
            }
            Err(_) => {
                println!("Timeout");
            }
        }
    } else {
        println!("Connect call failed immediately");
    }
}
