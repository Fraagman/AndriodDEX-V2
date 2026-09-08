use std::sync::Arc;
use quinn::{Connection, Endpoint, ClientConfig};
use rustls::client::danger::{ServerCertVerified, ServerCertVerifier, HandshakeSignatureValid};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use zc_security::storage::{load_trust_data, store_trust_data, Fingerprint};
use zc_security::pairing::{generate_pin, derive_psk};
use x25519_dalek::{EphemeralSecret, PublicKey};
use rand_core::OsRng;
use sha2::{Sha256, Digest};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::Duration;

use futures_util::stream::FuturesUnordered;
use futures_util::StreamExt;

pub type QuinnError = Box<dyn std::error::Error + Send + Sync>;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ScanError {
    FingerprintMismatch {
        expected: Fingerprint,
        actual: Fingerprint,
    },
    HostUnreachable,
    MissingCertificate,
    Other(String),
}

impl std::fmt::Display for ScanError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ScanError::FingerprintMismatch { expected, actual } => {
                write!(f, "Certificate fingerprint mismatch: expected {:?}, got {:?}", expected, actual)
            }
            ScanError::HostUnreachable => write!(f, "Host unreachable or no response"),
            ScanError::MissingCertificate => write!(f, "Certificate missing from peer identity"),
            ScanError::Other(msg) => write!(f, "Connection error: {}", msg),
        }
    }
}

impl std::error::Error for ScanError {}

#[derive(Debug, Clone)]
pub enum ConnectionPhase {
    Idle,
    Scanning(String, u32),
    Found(String),
    Handshaking,
    WaitingForPin(String),
    Connected,
    CertificateChanged,
    Failed(String),
}

#[derive(Debug)]
pub struct AcceptAnyCertVerifier;

impl ServerCertVerifier for AcceptAnyCertVerifier {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
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

pub fn compute_fingerprint(cert_der: &[u8]) -> Fingerprint {
    let mut hasher = Sha256::new();
    hasher.update(cert_der);
    hasher.finalize().into()
}

pub fn verify_peer_fingerprint(actual_der: &[u8], expected: &Fingerprint) -> Result<Fingerprint, ScanError> {
    let actual = compute_fingerprint(actual_der);
    if &actual == expected {
        Ok(actual)
    } else {
        Err(ScanError::FingerprintMismatch {
            expected: *expected,
            actual,
        })
    }
}

pub fn extract_peer_fingerprint(conn: &Connection) -> Result<Fingerprint, ScanError> {
    let any_identity = conn.peer_identity().ok_or(ScanError::MissingCertificate)?;
    let certs = any_identity
        .downcast_ref::<Vec<CertificateDer<'static>>>()
        .ok_or(ScanError::MissingCertificate)?;
    let cert = certs.first().ok_or(ScanError::MissingCertificate)?;
    Ok(compute_fingerprint(cert.as_ref()))
}

async fn scan_rndis_subnet(
    endpoint: &Endpoint,
    port: u16,
    expected_fp: Option<Fingerprint>,
    status_callback: &(impl Fn(ConnectionPhase) + Send + Sync + Clone + 'static),
) -> Result<(Connection, Fingerprint), ScanError> {
    let mut attempt = 1;
    loop {
        // Hardware Check: Find RNDIS adapters
        let adapters = ipconfig::get_adapters().unwrap_or_default();
        let rndis_adapters: Vec<_> = adapters.into_iter()
            .filter(|a| {
                let desc = a.description().to_lowercase();
                let f_name = a.friendly_name().to_lowercase();
                desc.find("ndis").is_some() || desc.find("rndis").is_some() || f_name.find("ndis").is_some() || f_name.find("rndis").is_some()
            })
            .collect();

        if rndis_adapters.is_empty() {
            status_callback(ConnectionPhase::Failed("Connect your phone via USB.".to_string()));
            tokio::time::sleep(Duration::from_secs(2)).await;
            continue;
        }

        // Hardware exists, check for valid IPv4
        let mut target_subnets = Vec::new();
        let mut gateways = Vec::new();
        for adapter in rndis_adapters {
            for gw in adapter.gateways() {
                if let IpAddr::V4(ipv4) = gw {
                    gateways.push(*ipv4);
                }
            }
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

        for gw in gateways {
            let addr = SocketAddr::new(IpAddr::V4(gw), port);
            if let Ok(connecting) = endpoint.connect(addr, "localhost") {
                if let Ok(res) = tokio::time::timeout(Duration::from_millis(400), connecting).await {
                    if let Ok(conn) = res {
                        let actual_fp = extract_peer_fingerprint(&conn)?;
                        if let Some(expected) = expected_fp {
                            if actual_fp != expected {
                                return Err(ScanError::FingerprintMismatch {
                                    expected,
                                    actual: actual_fp,
                                });
                            }
                        }
                        status_callback(ConnectionPhase::Found(gw.to_string()));
                        return Ok((conn, actual_fp));
                    }
                }
            }
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
                        if let Ok(res) = tokio::time::timeout(Duration::from_millis(400), connecting).await {
                            if let Ok(conn) = res {
                                match extract_peer_fingerprint(&conn) {
                                    Ok(actual_fp) => {
                                        if let Some(expected) = expected_fp {
                                            if actual_fp != expected {
                                                return Some(Err(ScanError::FingerprintMismatch {
                                                    expected,
                                                    actual: actual_fp,
                                                }));
                                            }
                                        }
                                        return Some(Ok((ip, conn, actual_fp)));
                                    }
                                    Err(e) => return Some(Err(e)),
                                }
                            }
                        }
                    }
                    None
                });
            }
            
            while let Some(res) = futures.next().await {
                match res {
                    Some(Ok((ip, conn, fp))) => {
                        status_callback(ConnectionPhase::Found(ip.to_string()));
                        return Ok((conn, fp));
                    }
                    Some(Err(ScanError::FingerprintMismatch { expected, actual })) => {
                        return Err(ScanError::FingerprintMismatch { expected, actual });
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
        let verifier = AcceptAnyCertVerifier;
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
        
        match scan_rndis_subnet(&endpoint, port, Some(fp), &status_callback).await {
            Ok((conn, _)) => {
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
                    status_callback(ConnectionPhase::Failed("Authentication rejected by phone.".into()));
                    return Err("Authentication rejected by phone.".into());
                }
            }
            Err(ScanError::FingerprintMismatch { expected, actual }) => {
                eprintln!("SECURITY ALERT: Certificate fingerprint mismatch! Expected {:?}, got {:?}", expected, actual);
                status_callback(ConnectionPhase::CertificateChanged);
                return Err(Box::new(ScanError::FingerprintMismatch { expected, actual }));
            }
            Err(e) => {
                let e_str = e.to_string();
                eprintln!("Connection scan error: {}", e_str);
                status_callback(ConnectionPhase::Failed(format!("Connection error: {}", e_str)));
                return Err(Box::new(e));
            }
        }
    }
    
    let verifier = AcceptAnyCertVerifier;
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
    
    let (conn, fp) = scan_rndis_subnet(&endpoint, port, None, &status_callback).await?;

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
    
    // Store trust data ONLY after auth OK is confirmed
    store_trust_data(&fp, &psk)?;
    
    status_callback(ConnectionPhase::Connected);
    Ok(conn)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rcgen::{CertificateParams, KeyPair};

    #[test]
    fn test_fingerprint_verification_accepts_identical_and_rejects_different() {
        let keypair1 = KeyPair::generate().expect("Failed to generate keypair 1");
        let params1 = CertificateParams::new(vec!["localhost".to_string()]).expect("Failed to create cert params 1");
        let cert1 = params1.self_signed(&keypair1).expect("Failed to self-sign cert 1");
        let cert_der_1 = cert1.der();

        let keypair2 = KeyPair::generate().expect("Failed to generate keypair 2");
        let params2 = CertificateParams::new(vec!["localhost".to_string()]).expect("Failed to create cert params 2");
        let cert2 = params2.self_signed(&keypair2).expect("Failed to self-sign cert 2");
        let cert_der_2 = cert2.der();

        let fp1 = compute_fingerprint(cert_der_1);
        let fp2 = compute_fingerprint(cert_der_2);

        assert_ne!(fp1, fp2, "Two distinct certificates must have distinct fingerprints");

        // Identity check on identical DER bytes must succeed
        let verified = verify_peer_fingerprint(cert_der_1, &fp1);
        assert!(verified.is_ok(), "Fingerprint verification must succeed for identical cert");
        assert_eq!(verified.unwrap(), fp1);

        // Verification against different certificate must reject with FingerprintMismatch
        let mismatch = verify_peer_fingerprint(cert_der_2, &fp1);
        match mismatch {
            Err(ScanError::FingerprintMismatch { expected, actual }) => {
                assert_eq!(expected, fp1);
                assert_eq!(actual, fp2);
            }
            other => panic!("Expected ScanError::FingerprintMismatch, got: {:?}", other),
        }
    }
}
