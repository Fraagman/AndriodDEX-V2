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
    let mut attempt = 1;
    loop {
        // Hardware Check: Find RNDIS adapters
        let adapters = ipconfig::get_adapters().unwrap_or_default();
        let rndis_adapters: Vec<_> = adapters.into_iter()
            .filter(|a| {
                let desc = a.description().to_lowercase();
                let f_name = a.friendly_name().to_lowercase();
                desc.contains("ndis") || desc.contains("rndis") || f_name.contains("ndis") || f_name.contains("rndis")
            })
            .collect();

        if rndis_adapters.is_empty() {
            status_callback(ConnectionPhase::Failed("Connect your phone via USB.".to_string()));
            tokio::time::sleep(Duration::from_secs(2)).await;
            continue;
        }

        // Hardware exists, check for valid IPv4
        let mut target_subnets = Vec::new();
        for adapter in rndis_adapters {
            for ip in adapter.ip_addresses() {
                if let IpAddr::V4(ipv4) = ip {
                    if !ipv4.is_loopback() && ipv4.octets()[0] != 169 {
                        let octets = ipv4.octets();
                        let subnet_base = Ipv4Addr::new(octets[0], octets[1], octets[2], 0);
                        target_subnets.push((subnet_base, *ipv4));
                    }
                }
            }
        }

        if target_subnets.is_empty() {
            status_callback(ConnectionPhase::Failed("Enable USB Tethering in Android settings.".to_string()));
            tokio::time::sleep(Duration::from_secs(2)).await;
            continue;
        }

        // We have a valid RNDIS subnet. Dispatch concurrent scans.
        for (subnet_base, local_ip) in target_subnets {
            let subnet_str = format!("{}/24", subnet_base);
            status_callback(ConnectionPhase::Scanning(subnet_str, attempt));
            
            let octets = subnet_base.octets();
            let mut futures = FuturesUnordered::new();
            
            for i in 1..255 {
                let ip = Ipv4Addr::new(octets[0], octets[1], octets[2], i);
                if ip == local_ip {
                    continue;
                }
                
                let ep = endpoint.clone();
                futures.push(async move {
                    let addr = SocketAddr::new(IpAddr::V4(ip), port);
                    if let Ok(connecting) = ep.connect(addr, "localhost") {
                        if let Ok(res) = tokio::time::timeout(Duration::from_millis(100), connecting).await {
                            match res {
                                Ok(conn) => return Some(Ok((ip, conn))),
                                Err(e) => {
                                    let e_str = e.to_string();
                                    if e_str.contains("mismatch") || e_str.contains("ertificate") {
                                        return Some(Err(e.into()));
                                    }
                                }
                            }
                        }
                    }
                    None
                });
            }
            
            while let Some(res) = futures.next().await {
                match res {
                    Some(Ok((ip, conn))) => {
                        status_callback(ConnectionPhase::Found(ip.to_string()));
                        return Ok(conn);
                    }
                    Some(Err(e)) => return Err(e),
                    None => {}
                }
            }
        }
        
        status_callback(ConnectionPhase::Failed("Open the AndroidDex app on your phone.".to_string()));
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
        
        match scan_rndis_subnet(&endpoint, port, &status_callback).await {
            Ok(conn) => {
                status_callback(ConnectionPhase::Handshaking);
                
                let mut auth_ok = false;
                if let Ok((mut auth_send, mut auth_recv)) = conn.open_bi().await {
                    let mut hasher = Sha256::new();
                    hasher.update(&psk);
                    hasher.update(b"auth");
                    let token: [u8; 32] = hasher.finalize().into();
                    
                    if auth_send.write_all(b"A").await.is_ok() 
                        && auth_send.write_all(&token).await.is_ok() 
                        && auth_send.finish().is_ok() {
                            
                        let mut ok_buf = [0u8; 2];
                        if auth_recv.read_exact(&mut ok_buf).await.is_ok() && &ok_buf == b"OK" {
                            auth_ok = true;
                        }
                    }
                }
                
                if auth_ok {
                    status_callback(ConnectionPhase::Connected);
                    return Ok(conn);
                } else {
                    status_callback(ConnectionPhase::Failed("Auth rejected. Re-pairing...".into()));
                    zc_security::storage::delete_trust_data();
                    // Fall through to pairing
                }
            }
            Err(e) => {
                let e_str = e.to_string();
                if e_str.contains("mismatch") || e_str.contains("ertificate") {
                    status_callback(ConnectionPhase::Failed("Certificate changed. Re-pairing...".into()));
                    zc_security::storage::delete_trust_data();
                    // Fall through to pairing
                } else {
                    return Err(e);
                }
            }
        }
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
