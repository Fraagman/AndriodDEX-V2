use rcgen::{CertificateParams, KeyPair};

pub fn generate_self_signed_cert() -> Result<(Vec<u8>, Vec<u8>), rcgen::Error> {
    let keypair = KeyPair::generate()?;
    let params = CertificateParams::new(vec!["localhost".to_string()])?;
    let cert = params.self_signed(&keypair)?;
    Ok((cert.pem().into_bytes(), keypair.serialize_pem().into_bytes()))
}
