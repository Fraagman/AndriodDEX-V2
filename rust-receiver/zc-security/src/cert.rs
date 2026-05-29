use rcgen::{CertificateParams, KeyPair, PKCS_ECDSA_P256_SHA256};

pub fn generate_self_signed_cert() -> (Vec<u8>, Vec<u8>) {
    let keypair = KeyPair::generate().expect("Failed to generate keypair");
    let params = CertificateParams::new(vec!["localhost".to_string()]).expect("Failed to create cert params");
    let cert = params.self_signed(&keypair).expect("Failed to self-sign cert");
    (cert.pem().into_bytes(), keypair.serialize_pem().into_bytes())
}
