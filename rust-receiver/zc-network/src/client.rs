use std::sync::Arc;
use quinn::{Connection, Endpoint, ClientConfig};
use rustls::client::danger::{ServerCertVerified, ServerCertVerifier, HandshakeSignatureValid};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use zc_security::storage::{load_trust_data, store_trust_data, Fingerprint};
use zc_security::pairing::{
    client_proof, derive_pairing_v2, verify_server_proof,
};
use ring::agreement::{self, EphemeralPrivateKey, UnparsedPublicKey, X25519};
use ring::digest::{self, SHA256};
use ring::rand::{SecureRandom, SystemRandom};
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
    WaitingForSas(String),
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
    let d = digest::digest(&SHA256, cert_der);
    let mut fp = [0u8; 32];
    fp.copy_from_slice(d.as_ref());
    fp
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
        crypto.alpn_protocols = vec![b"androiddex-v2".to_vec()];

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
        
        // The cert pin is now defence in depth rather than the root of trust — the SAS and the channel binding are.
        match scan_rndis_subnet(&endpoint, port, Some(fp), &status_callback).await {
            Ok((conn, _)) => {
                status_callback(ConnectionPhase::Handshaking);
                
                // Export TLS channel binding: 32 bytes via export_keying_material
                let mut binding = [0u8; 32];
                conn.export_keying_material(&mut binding, b"androiddex-auth-v2", b"")
                    .map_err(|e| format!("export_keying_material failed: {e:?}"))?;

                let rng = SystemRandom::new();
                let mut client_nonce = [0u8; 32];
                rng.fill(&mut client_nonce).map_err(|_| "CSPRNG failure generating client nonce")?;

                let (mut auth_send, mut auth_recv) = conn.open_bi().await?;

                // 1. PC sends 'C' || client_nonce (33 bytes)
                let mut req = [0u8; 33];
                req[0] = b'C';
                req[1..].copy_from_slice(&client_nonce);
                auth_send.write_all(&req).await?;

                // 2. Phone replies 'S' || server_nonce || server_proof (1 + 32 + 32 = 65 bytes)
                let mut resp = [0u8; 65];
                auth_recv.read_exact(&mut resp).await?;
                if resp[0] != b'S' {
                    status_callback(ConnectionPhase::Failed("Invalid auth response tag from phone".into()));
                    return Err("Invalid auth response tag from phone".into());
                }
                let mut server_nonce = [0u8; 32];
                server_nonce.copy_from_slice(&resp[1..33]);
                let presented_server_proof = &resp[33..65];

                // 3. PC verifies server_proof in CONSTANT TIME
                if !verify_server_proof(&psk, &client_nonce, &server_nonce, &binding, presented_server_proof) {
                    status_callback(ConnectionPhase::Failed("Server authentication proof mismatch".into()));
                    return Err("Server authentication proof mismatch".into());
                }

                // Send 'D' || client_proof (33 bytes)
                let my_client_proof = client_proof(&psk, &client_nonce, &server_nonce, &binding);
                let mut client_resp = [0u8; 33];
                client_resp[0] = b'D';
                client_resp[1..].copy_from_slice(&my_client_proof);
                auth_send.write_all(&client_resp).await?;
                auth_send.finish()?;

                status_callback(ConnectionPhase::Connected);
                return Ok(conn);
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
    
    crypto.alpn_protocols = vec![b"androiddex-pair-v2".to_vec()];

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

    // 1. PC generates an ephemeral X25519 keypair (a, A)
    let rng = SystemRandom::new();
    let my_private = EphemeralPrivateKey::generate(&X25519, &rng)
        .map_err(|_| "Failed generating ephemeral key")?;
    let mut a_bytes = [0u8; 32];
    a_bytes.copy_from_slice(
        my_private
            .compute_public_key()
            .map_err(|_| "Failed computing public key")?
            .as_ref(),
    );

    status_callback(ConnectionPhase::Handshaking);

    // 2. PC opens a bidirectional stream, sends: 'P' || A (1 + 32 bytes)
    let (mut send_stream, mut recv_stream) = conn.open_bi().await?;
    let mut req = [0u8; 33];
    req[0] = b'P';
    req[1..].copy_from_slice(&a_bytes);
    send_stream.write_all(&req).await?;

    // 3. Phone replies: 'Q' || B (1 + 32 bytes)
    let mut reply = [0u8; 33];
    recv_stream.read_exact(&mut reply).await?;
    if reply[0] != b'Q' {
        status_callback(ConnectionPhase::Failed("Expected 'Q' tag from phone".into()));
        return Err("Expected 'Q' tag from phone".into());
    }
    let mut b_bytes = [0u8; 32];
    b_bytes.copy_from_slice(&reply[1..]);

    // 5. TLS channel binding: 32 bytes via export_keying_material
    let mut binding = [0u8; 32];
    conn.export_keying_material(&mut binding, b"androiddex-pair-v2", b"")
        .map_err(|e| format!("export_keying_material failed: {e:?}"))?;

    // 4, 6-9. Compute Z, low-order check, transcript, SAS, PSK
    let peer_public = UnparsedPublicKey::new(&X25519, &b_bytes);
    let (sas, psk) = agreement::agree_ephemeral(
        my_private,
        &peer_public,
        |z_bytes| {
            if z_bytes.len() != 32 {
                return Err("invalid shared secret length".to_string());
            }
            let mut z = [0u8; 32];
            z.copy_from_slice(z_bytes);
            derive_pairing_v2(&a_bytes, &b_bytes, &binding, &z)
                .map_err(|e| format!("{e}"))
        },
    )
    .map_err(|_| "X25519 key agreement failed")??;

    // 10. Display 6-digit SAS to user
    status_callback(ConnectionPhase::WaitingForSas(sas.clone()));

    // 11-12. User confirms or rejects on phone; phone sends 'Y' on match, 'N' otherwise.
    let mut verdict = [0u8; 1];
    recv_stream.read_exact(&mut verdict).await?;
    if verdict[0] != b'Y' {
        status_callback(ConnectionPhase::Failed("Pairing rejected on phone".into()));
        return Err("Pairing rejected on phone".into());
    }

    // ONLY on 'Y' does either side persist anything.
    store_trust_data(&fp, &psk)?;

    status_callback(ConnectionPhase::Connected);
    Ok(conn)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rcgen::{CertificateParams, KeyPair};
    use zc_security::pairing::server_proof;

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

    #[test]
    fn test_protocol_v2_vectors() {
        let a = [0x11u8; 32];
        let b = [0x22u8; 32];
        let binding = [0x33u8; 32];
        let z = [0x44u8; 32];
        let client_nonce = [0x55u8; 32];
        let server_nonce = [0x66u8; 32];

        let (sas, psk) = derive_pairing_v2(&a, &b, &binding, &z).expect("derive_pairing_v2");
        let s_proof = server_proof(&psk, &client_nonce, &server_nonce, &binding);
        let c_proof = client_proof(&psk, &client_nonce, &server_nonce, &binding);

        // Assert the exact four literals from Task 20:
        assert_eq!(sas, "040666");

        let expected_psk: [u8; 32] = [
            21, 97, 41, 45, 233, 40, 231, 116, 228, 17, 232, 172, 15, 105, 128, 195,
            72, 5, 26, 98, 78, 168, 43, 225, 61, 47, 83, 80, 246, 130, 13, 206,
        ];
        assert_eq!(psk, expected_psk);

        let expected_server_proof: [u8; 32] = [
            233, 15, 167, 106, 1, 144, 225, 156, 139, 176, 114, 148, 69, 36, 248, 70,
            203, 69, 180, 56, 185, 117, 98, 194, 215, 168, 86, 78, 67, 99, 10, 241,
        ];
        assert_eq!(s_proof, expected_server_proof);

        let expected_client_proof: [u8; 32] = [
            215, 170, 107, 90, 253, 145, 225, 74, 51, 2, 152, 214, 12, 105, 81, 97,
            6, 145, 45, 71, 94, 91, 202, 76, 151, 126, 38, 228, 105, 199, 75, 183,
        ];
        assert_eq!(c_proof, expected_client_proof);

        assert!(verify_server_proof(&psk, &client_nonce, &server_nonce, &binding, &s_proof));
    }
}
