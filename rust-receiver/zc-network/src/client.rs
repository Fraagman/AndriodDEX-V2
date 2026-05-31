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

async fn scan_rndis_subnet(endpoint: &Endpoint, port: u16) -> Result<Connection, QuinnError> {
    println!("Scanning RNDIS subnet...");
    let interfaces = get_if_addrs().unwrap_or_default();
    let mut rndis_subnet = false;
    
    for iface in interfaces {
        if let std::net::IpAddr::V4(ipv4) = iface.ip() {
            let octets = ipv4.octets();
            if octets[0] == 192 && octets[1] == 168 && octets[2] == 42 {
                rndis_subnet = true;
                break;
            }
        }
    }
    
    if !rndis_subnet {
        return Err("No RNDIS subnet (192.168.42.x) found on host. Connect USB cable and enable USB tethering.".into());
    }

    let fast_targets = vec![
        Ipv4Addr::new(192, 168, 42, 1),
        Ipv4Addr::new(192, 168, 42, 129),
    ];

    for ip in fast_targets {
        let addr = SocketAddr::new(IpAddr::V4(ip), port);
        if let Ok(connecting) = endpoint.connect(addr, "localhost") {
            if let Ok(Ok(conn)) = tokio::time::timeout(Duration::from_millis(300), connecting).await {
                println!("Connected to {}:{}", ip, port);
                return Ok(conn);
            }
        }
    }

    // Hybrid scan of the remaining /24
    let mut all_ips = Vec::new();
    for i in 2..255 {
        if i != 129 {
            all_ips.push(Ipv4Addr::new(192, 168, 42, i));
        }
    }

    // 4 concurrent probes
    for chunk in all_ips.chunks(4) {
        let mut futures = FuturesUnordered::new();
        for &ip in chunk {
            let ep = endpoint.clone();
            futures.push(async move {
                let addr = SocketAddr::new(IpAddr::V4(ip), port);
                if let Ok(connecting) = ep.connect(addr, "localhost") {
                    if let Ok(Ok(conn)) = tokio::time::timeout(Duration::from_millis(100), connecting).await {
                        return Some((ip, conn));
                    }
                }
                None
            });
        }
        
        while let Some(res) = futures.next().await {
            if let Some((ip, conn)) = res {
                println!("Connected to {}:{}", ip, port);
                return Ok(conn);
            }
        }
    }
    
    Err("RNDIS scan failed: No Android QUIC server found on 192.168.42.0/24".into())
}

pub async fn connect(port: u16, pin_callback: impl Fn(String) + Send + 'static) -> Result<Connection, QuinnError> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    
    if let Some((fp, psk)) = load_trust_data() {
        println!("Loaded trust data, performing authenticated connect...");
        let verifier = PinnedCertVerifier { expected: fp };
        let mut crypto = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(verifier))
            .with_no_client_auth();
        crypto.alpn_protocols = vec![b"androiddex".to_vec()];

        let mut client_config = ClientConfig::new(Arc::new(quinn::crypto::rustls::QuicClientConfig::try_from(crypto)?));
        let mut transport_config = quinn::TransportConfig::default();
        transport_config.max_idle_timeout(Some(std::time::Duration::from_secs(5).try_into().unwrap()));
        transport_config.keep_alive_interval(Some(std::time::Duration::from_secs(2)));
        client_config.transport_config(Arc::new(transport_config));
        
        let mut endpoint = Endpoint::client("0.0.0.0:0".parse()?)?;
        endpoint.set_default_client_config(client_config);
        
        let conn = scan_rndis_subnet(&endpoint, port).await?;
        
        let mut auth_stream = conn.open_uni().await?;
        let mut hasher = Sha256::new();
        hasher.update(&psk);
        hasher.update(b"auth");
        let token: [u8; 32] = hasher.finalize().into();
        auth_stream.write_all(b"A").await?;
        auth_stream.write_all(&token).await?;
        auth_stream.finish()?;
        
        return Ok(conn);
    }
    
    println!("No trust data found, initiating pairing...");
    
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
    transport_config.max_idle_timeout(Some(std::time::Duration::from_secs(5).try_into().unwrap()));
    transport_config.keep_alive_interval(Some(std::time::Duration::from_secs(2)));
    client_config.transport_config(Arc::new(transport_config));
    
    let mut endpoint = Endpoint::client("0.0.0.0:0".parse()?)?;
    endpoint.set_default_client_config(client_config);
    
    let conn = scan_rndis_subnet(&endpoint, port).await?;

    let fp = fingerprint.lock().unwrap().unwrap();
    
    let secret = EphemeralSecret::random_from_rng(OsRng);
    let public = PublicKey::from(&secret);

    let (mut send_stream, mut recv_stream) = conn.open_bi().await?;
    send_stream.write_all(b"P").await?;
    send_stream.write_all(public.as_bytes()).await?;

    let pin = generate_pin();
    pin_callback(pin.clone());

    let mut buf = [0u8; 2];
    recv_stream.read_exact(&mut buf).await?;
    if &buf != b"OK" {
        return Err("Pairing rejected".into());
    }

    let psk = derive_psk(&pin, public.as_bytes());
    store_trust_data(&fp, &psk)?;
    
    println!("Pairing successful, authenticating on same connection...");
    
    let mut auth_stream = conn.open_uni().await?;
    let mut hasher = Sha256::new();
    hasher.update(&psk);
    hasher.update(b"auth");
    let token: [u8; 32] = hasher.finalize().into();
    auth_stream.write_all(b"A").await?;
    auth_stream.write_all(&token).await?;
    auth_stream.finish()?;
    
    Ok(conn)
}
