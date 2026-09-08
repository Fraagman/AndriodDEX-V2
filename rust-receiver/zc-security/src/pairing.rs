use rand::Rng;
use ring::{digest, hkdf, hmac};

#[derive(Debug, PartialEq, Eq)]
pub enum PairingError {
    AllZeroSharedSecret,
    CryptoError,
}

impl std::fmt::Display for PairingError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PairingError::AllZeroSharedSecret => {
                write!(f, "low-order point check failed: shared secret Z is all zeros")
            }
            PairingError::CryptoError => write!(f, "cryptographic derivation failed"),
        }
    }
}

impl std::error::Error for PairingError {}

impl From<ring::error::Unspecified> for PairingError {
    fn from(_: ring::error::Unspecified) -> Self {
        PairingError::CryptoError
    }
}

struct Okm4;

impl hkdf::KeyType for Okm4 {
    fn len(&self) -> usize {
        4
    }
}

/// Generates a legacy 6-digit PIN (kept for backward compatibility during migration).
pub fn generate_pin() -> String {
    let mut rng = rand::thread_rng();
    let num: u32 = rng.gen_range(0..1_000_000);
    format!("{:06}", num)
}

/// Derives legacy PSK (kept for backward compatibility during migration).
pub fn derive_psk(pin: &str, ephemeral_public_key: &[u8; 32]) -> Result<[u8; 32], ring::error::Unspecified> {
    let salt = hkdf::Salt::new(hkdf::HKDF_SHA256, b"androiddex-v1");
    let mut ikm = Vec::new();
    ikm.extend_from_slice(pin.as_bytes());
    ikm.extend_from_slice(ephemeral_public_key);
    let prk = salt.extract(&ikm);
    
    let info = [b"psk".as_slice()];
    let okm = prk.expand(&info, hkdf::HKDF_SHA256)?;
    let mut psk = [0u8; 32];
    okm.fill(&mut psk)?;
    Ok(psk)
}

/// Derives the 6-digit SAS string and 32-byte PSK for Protocol v2 pairing.
///
/// Steps 4-9 of Protocol v2 pairing:
/// 4. Checks that shared secret Z is not all zeros (low-order point check).
/// 6. transcript = SHA256( b"androiddex-pair-v2" || A || B || binding )
/// 7. prk = HKDF-Extract(salt = transcript, ikm = Z) [HKDF-SHA256]
/// 8. sas_bytes = HKDF-Expand(prk, info = b"sas", 4 bytes)
///    sas = u32::from_be_bytes(sas_bytes) % 1_000_000, formatted as %06d
/// 9. psk = HKDF-Expand(prk, info = b"psk", 32 bytes)
pub fn derive_pairing_v2(
    a: &[u8; 32],
    b: &[u8; 32],
    binding: &[u8; 32],
    z: &[u8; 32],
) -> Result<(String, [u8; 32]), PairingError> {
    // 4. Low-order point check: reject all-zero Z in constant time
    #[allow(deprecated)]
    if ring::constant_time::verify_slices_are_equal(z, &[0u8; 32]).is_ok() {
        return Err(PairingError::AllZeroSharedSecret);
    }

    // 6. transcript = SHA256( b"androiddex-pair-v2" || A || B || binding )
    let mut digest_ctx = digest::Context::new(&digest::SHA256);
    digest_ctx.update(b"androiddex-pair-v2");
    digest_ctx.update(a);
    digest_ctx.update(b);
    digest_ctx.update(binding);
    let transcript = digest_ctx.finish();

    // 7. prk = HKDF-Extract(salt = transcript, ikm = Z)
    let salt = hkdf::Salt::new(hkdf::HKDF_SHA256, transcript.as_ref());
    let prk = salt.extract(z);

    // 8. sas_bytes = HKDF-Expand(prk, info = b"sas", 4 bytes)
    let okm_sas = prk.expand(&[b"sas"], Okm4)?;
    let mut sas_bytes = [0u8; 4];
    okm_sas.fill(&mut sas_bytes)?;
    let sas_num = u32::from_be_bytes(sas_bytes) % 1_000_000;
    let sas = format!("{:06}", sas_num);

    // 9. psk = HKDF-Expand(prk, info = b"psk", 32 bytes)
    let okm_psk = prk.expand(&[b"psk"], hkdf::HKDF_SHA256)?;
    let mut psk = [0u8; 32];
    okm_psk.fill(&mut psk)?;

    Ok((sas, psk))
}

/// Computes the server re-authentication proof:
/// server_proof = HMAC-SHA256(psk, b"server-proof" || client_nonce || server_nonce || binding)
pub fn server_proof(
    psk: &[u8; 32],
    client_nonce: &[u8; 32],
    server_nonce: &[u8; 32],
    binding: &[u8; 32],
) -> [u8; 32] {
    let key = hmac::Key::new(hmac::HMAC_SHA256, psk);
    let mut ctx = hmac::Context::with_key(&key);
    ctx.update(b"server-proof");
    ctx.update(client_nonce);
    ctx.update(server_nonce);
    ctx.update(binding);
    let tag = ctx.sign();
    let mut out = [0u8; 32];
    out.copy_from_slice(tag.as_ref());
    out
}

/// Computes the client re-authentication proof:
/// client_proof = HMAC-SHA256(psk, b"client-proof" || client_nonce || server_nonce || binding)
pub fn client_proof(
    psk: &[u8; 32],
    client_nonce: &[u8; 32],
    server_nonce: &[u8; 32],
    binding: &[u8; 32],
) -> [u8; 32] {
    let key = hmac::Key::new(hmac::HMAC_SHA256, psk);
    let mut ctx = hmac::Context::with_key(&key);
    ctx.update(b"client-proof");
    ctx.update(client_nonce);
    ctx.update(server_nonce);
    ctx.update(binding);
    let tag = ctx.sign();
    let mut out = [0u8; 32];
    out.copy_from_slice(tag.as_ref());
    out
}

/// Verifies server_proof in constant time.
#[allow(deprecated)]
pub fn verify_server_proof(
    psk: &[u8; 32],
    client_nonce: &[u8; 32],
    server_nonce: &[u8; 32],
    binding: &[u8; 32],
    presented: &[u8],
) -> bool {
    let expected = server_proof(psk, client_nonce, server_nonce, binding);
    ring::constant_time::verify_slices_are_equal(&expected, presented).is_ok()
}

/// Verifies client_proof in constant time.
#[allow(deprecated)]
pub fn verify_client_proof(
    psk: &[u8; 32],
    client_nonce: &[u8; 32],
    server_nonce: &[u8; 32],
    binding: &[u8; 32],
    presented: &[u8],
) -> bool {
    let expected = client_proof(psk, client_nonce, server_nonce, binding);
    ring::constant_time::verify_slices_are_equal(&expected, presented).is_ok()
}
