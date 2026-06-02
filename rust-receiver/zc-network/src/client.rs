use std::sync::{Arc, Mutex};
use quinn::{Connection, Endpoint, ClientConfig};
use rustls::client::danger::{ServerCertVerified, ServerCertVerifier, HandshakeSignatureValid};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use zc_security::storage::{load_trust_data, store_trust_data, Fingerprint, Psk};
use zc_security::pairing::{generate_pin, derive_psk};
use x25519_dalek::{EphemeralSecret, PublicKey};
use rand_core::OsRng;
use sha2::{Sha256, Digest};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::Duration;
use get_if_addrs::get_if_addrs;
use futures_util::stream::FuturesUnordered;
use futures_util::StreamExt;

pub type QuinnError = Box<dyn std::error::Error + Send + Sync>;

#[derive(Debug, Clone)]
pub enum ConnectionPhase {
    Idle,
    Scanning(String, u32),
    Found(String),
    Handshaking,
    WaitingForPin(String),
    Connected,
    Failed(String),
}

#[derive(Debug)]
struct TrustOnFirstUseVerifier {
    fingerprint: Arc<Mutex<Option<Fingerprint>>>,
}

impl ServerCertVerifier for TrustOnFirstUseVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let mut hasher = Sha256::new();
        hasher.update(end_entity.as_ref());
        let fp: [u8; 32] = hasher.finalize().into();
        *self.fingerprint.lock().unwrap() = Some(fp);
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        rustls::crypto::ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}

#[derive(Debug)]
struct PinnedCertVerifier {
    expected: Fingerprint,
}

impl ServerCertVerifier for PinnedCertVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let mut hasher = Sha256::new();
        hasher.update(end_entity.as_ref());
        let fp: [u8; 32] = hasher.finalize().into();
        if fp == self.expected {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General("Certificate fingerprint mismatch".into()))
        }
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        rustls::crypto::ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}

async fn scan_rndis_subnet(
    endpoint: &Endpoint,
    port: u16,
    status_callback: &(impl Fn(ConnectionPhase) + Send + Sync + Clone + 'static),
) -> Result<Connection, QuinnError> {
    let interfaces = get_if_addrs().unwrap_or_default();
    let mut private_subnets = Vec::new();
    
    for iface in interfaces {
        if let std::net::IpAddr::V4(ipv4) = iface.ip() {
            if !ipv4.is_loopback() && (ipv4.is_private() || ipv4.octets()[0] == 100) {
                let octets = ipv4.octets();
                let subnet_base = Ipv4Addr::new(octets[0], octets[1], octets[2], 0);
                private_subnets.push((subnet_base, ipv4));
            }
        }
    }
    
    if private_subnets.is_empty() {
        status_callback(ConnectionPhase::Idle);
        return Err("No private subnets found. Connect USB cable and enable USB tethering.".into());
    }

    let mut attempt = 1;
    loop {
        for (subnet_base, local_ip) in &private_subnets {
            let subnet_str = format!("{}/24", subnet_base);
            status_callback(ConnectionPhase::Scanning(subnet_str, attempt));
            
            let octets = subnet_base.octets();
            
            let mut fast_targets = vec![
                Ipv4Addr::new(octets[0], octets[1], octets[2], 1),
                Ipv4Addr::new(octets[0], octets[1], octets[2], 254),
                Ipv4Addr::new(octets[0], octets[1], octets[2], 211),
                Ipv4Addr::new(octets[0], octets[1], octets[2], 129),
            ];
            
            // Second fast path if local IP ends in .207 etc
            if octets[3] > 0 {
                fast_targets.push(Ipv4Addr::new(octets[0], octets[1], octets[2], octets[3].wrapping_add(4)));
            }
            
            for ip in &fast_targets {
                let addr = SocketAddr::new(IpAddr::V4(*ip), port);
                if let Ok(connecting) = endpoint.connect(addr, "localhost") {
                    if let Ok(Ok(conn)) = tokio::time::timeout(Duration::from_millis(200), connecting).await {
                        status_callback(ConnectionPhase::Found(ip.to_string()));
                        return Ok(conn);
                    }
                }
            }
            
            let mut all_ips = Vec::new();
            for i in 1..255 {
                let ip = Ipv4Addr::new(octets[0], octets[1], octets[2], i);
                if !fast_targets.contains(&ip) && ip != *local_ip {
                    all_ips.push(ip);
                }
            }
            
            // Full scan of the /24 subnet concurrently
            for chunk in all_ips.chunks(8) {
                let mut futures = FuturesUnordered::new();
                for &ip in chunk {
                    let ep = endpoint.clone();
                    futures.push(async move {
                        let addr = SocketAddr::new(IpAddr::V4(ip), port);
                        if let Ok(connecting) = ep.connect(addr, "localhost") {
                            if let Ok(Ok(conn)) = tokio::time::timeout(Duration::from_millis(150), connecting).await {
                                return Some((ip, conn));
                            }
                        }
                        None
                    });
                }
                
                while let Some(res) = futures.next().await {
                    if let Some((ip, conn)) = res {
                        status_callback(ConnectionPhase::Found(ip.to_string()));
                        return Ok(conn);
                    }
                }
            }
        }
        
        status_callback(ConnectionPhase::Failed("USB tethering detected, but phone is not responding. Retrying...".to_string()));
        tokio::time::sleep(Duration::from_secs(3)).await;
        attempt += 1;
    }
}

pub async fn connect(port: u16, status_callback: impl Fn(ConnectionPhase) + Send + Sync + Clone + 'static) -> Result<Connection, QuinnError> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    
    if let Some((fp, psk)) = load_trust_data() {
        let verifier = PinnedCertVerifier { expected: fp };
        let mut crypto = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(verifier))
            .with_no_client_auth();
        crypto.alpn_protocols = vec![b"androiddex".to_vec()];

        let mut client_config = ClientConfig::new(Arc::new(quinn::crypto::rustls::QuicClientConfig::try_from(crypto)?));
        let mut transport_config = quinn::TransportConfig::default();
        match std::time::Duration::from_secs(30).try_into() {
            Ok(timeout) => { transport_config.max_idle_timeout(Some(timeout)); }
            Err(e) => { eprintln!("Failed to set idle timeout: {}", e); }
        }
        transport_config.keep_alive_interval(Some(std::time::Duration::from_secs(5)));
        client_config.transport_config(Arc::new(transport_config));
        
        let mut endpoint = Endpoint::client("0.0.0.0:0".parse()?)?;
        endpoint.set_default_client_config(client_config);
        
        let conn = scan_rndis_subnet(&endpoint, port, &status_callback).await?;
        
        status_callback(ConnectionPhase::Handshaking);
        
        let (mut auth_send, mut auth_recv) = conn.open_bi().await?;
        let mut hasher = Sha256::new();
        hasher.update(&psk);
        hasher.update(b"auth");
        let token: [u8; 32] = hasher.finalize().into();
        auth_send.write_all(b"A").await?;
        auth_send.write_all(&token).await?;
        auth_send.finish()?;

        let mut ok_buf = [0u8; 2];
        auth_recv.read_exact(&mut ok_buf).await?;
        if &ok_buf != b"OK" {
            status_callback(ConnectionPhase::Failed("Auth rejected".into()));
            return Err("Auth rejected".into());
        }
        
        status_callback(ConnectionPhase::Connected);
        return Ok(conn);
    }
    
    let fingerprint = Arc::new(Mutex::new(None));
    let verifier = TrustOnFirstUseVerifier {
        fingerprint: fingerprint.clone(),
    };

    let mut crypto = rustls::ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(verifier))
        .with_no_client_auth();
    
    crypto.alpn_protocols = vec![b"androiddex-pairing".to_vec()];

    let mut client_config = ClientConfig::new(Arc::new(quinn::crypto::rustls::QuicClientConfig::try_from(crypto)?));
    let mut transport_config = quinn::TransportConfig::default();
    match std::time::Duration::from_secs(30).try_into() {
        Ok(timeout) => { transport_config.max_idle_timeout(Some(timeout)); }
        Err(e) => { eprintln!("Failed to set idle timeout: {}", e); }
    }
    transport_config.keep_alive_interval(Some(std::time::Duration::from_secs(5)));
    client_config.transport_config(Arc::new(transport_config));
    
    let mut endpoint = Endpoint::client("0.0.0.0:0".parse()?)?;
    endpoint.set_default_client_config(client_config);
    
    let conn = scan_rndis_subnet(&endpoint, port, &status_callback).await?;

    let fp = fingerprint.lock().unwrap().unwrap();
    
    let secret = EphemeralSecret::random_from_rng(OsRng);
    let public = PublicKey::from(&secret);

    status_callback(ConnectionPhase::Handshaking);

    let (mut send_stream, mut recv_stream) = conn.open_bi().await?;
    send_stream.write_all(b"P").await?;
    send_stream.write_all(public.as_bytes()).await?;

    let pin = generate_pin();
    status_callback(ConnectionPhase::WaitingForPin(pin.clone()));

    let mut buf = [0u8; 2];
    recv_stream.read_exact(&mut buf).await?;
    if &buf != b"OK" {
        status_callback(ConnectionPhase::Failed("Pairing rejected".into()));
        return Err("Pairing rejected".into());
    }

    let psk = derive_psk(&pin, public.as_bytes());
    store_trust_data(&fp, &psk)?;
    
    let (mut auth_send, mut auth_recv) = conn.open_bi().await?;
    let mut hasher = Sha256::new();
    hasher.update(&psk);
    hasher.update(b"auth");
    let token: [u8; 32] = hasher.finalize().into();
    auth_send.write_all(b"A").await?;
    auth_send.write_all(&token).await?;
    auth_send.finish()?;

    let mut ok_buf = [0u8; 2];
    auth_recv.read_exact(&mut ok_buf).await?;
    if &ok_buf != b"OK" {
        status_callback(ConnectionPhase::Failed("Auth rejected".into()));
        return Err("Auth rejected".into());
    }
    
    status_callback(ConnectionPhase::Connected);
    Ok(conn)
}
