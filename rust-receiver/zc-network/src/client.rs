use std::sync::{Arc, Mutex};
use quinn::{Connection, Endpoint, ClientConfig};
use rustls::client::danger::{ServerCertVerified, ServerCertVerifier, HandshakeSignatureValid};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use zc_security::storage::{load_trust_data, store_trust_data, Fingerprint, Psk};
use zc_security::pairing::{generate_pin, derive_psk};
use x25519_dalek::{EphemeralSecret, PublicKey};
use rand_core::OsRng;
use sha2::{Sha256, Digest};


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

pub async fn connect(host: &str, port: u16, pin_callback: impl Fn(String) + Send + 'static) -> Result<Connection, QuinnError> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    if let Some((fp, psk)) = load_trust_data() {
        println!("Loaded trust data, performing authenticated connect...");
        return connect_authenticated(host, port, fp, psk).await;
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

    let client_config = ClientConfig::new(Arc::new(quinn::crypto::rustls::QuicClientConfig::try_from(crypto)?));
    let mut endpoint = Endpoint::client("0.0.0.0:0".parse()?)?;
    endpoint.set_default_client_config(client_config);

    let server_addr = format!("{}:{}", host, port).parse()?;
    
    let pairing_conn = endpoint
        .connect(server_addr, "localhost")?
        .await?;

    let fp = fingerprint.lock().unwrap().unwrap();
    
    let secret = EphemeralSecret::random_from_rng(OsRng);
    let public = PublicKey::from(&secret);

    let mut stream = pairing_conn.open_uni().await?;
    stream.write_all(public.as_bytes()).await?;
    stream.finish()?;

    let pin = generate_pin();
    pin_callback(pin.clone());

    let mut recv_stream = pairing_conn.accept_uni().await?;
    let mut buf = [0u8; 2];
    recv_stream.read_exact(&mut buf).await?;
    if &buf != b"OK" {
        return Err("Pairing rejected".into());
    }

    pairing_conn.close(0u32.into(), b"paired");

    let psk = derive_psk(&pin, public.as_bytes());
    store_trust_data(&fp, &psk)?;
    
    println!("Pairing successful, reconnecting...");
    
    connect_authenticated(host, port, fp, psk).await
}

pub async fn connect_authenticated(host: &str, port: u16, fp: Fingerprint, psk: Psk) -> Result<Connection, QuinnError> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let verifier = PinnedCertVerifier { expected: fp };

    let mut crypto = rustls::ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(verifier))
        .with_no_client_auth();
    
    crypto.alpn_protocols = vec![b"androiddex".to_vec()];

    let client_config = ClientConfig::new(Arc::new(quinn::crypto::rustls::QuicClientConfig::try_from(crypto)?));
    let mut endpoint = Endpoint::client("0.0.0.0:0".parse()?)?;
    endpoint.set_default_client_config(client_config);

    let server_addr = format!("{}:{}", host, port).parse()?;
    
    let conn = endpoint
        .connect(server_addr, "localhost")?
        .await?;

    let mut auth_stream = conn.open_uni().await?;
    let mut hasher = Sha256::new();
    hasher.update(&psk);
    hasher.update(b"auth");
    let token: [u8; 32] = hasher.finalize().into();
    auth_stream.write_all(&token).await?;
    auth_stream.finish()?;

    Ok(conn)
}
