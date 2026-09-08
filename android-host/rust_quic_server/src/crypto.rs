//! PSK derivation, SAS formatting, and Protocol v2 mutual authentication proofs.
//!
//! Replaces legacy PIN-based KDF with Protocol v2 (ECDH + SAS + TLS channel binding).
//! Uses `ring` for X25519, HKDF-SHA256, and HMAC-SHA256.

#[allow(deprecated)]
use ring::constant_time;
use ring::{digest, hkdf, hmac};

pub const PSK_LEN: usize = 32;
pub const SAS_LEN: usize = 6;
pub const PROOF_LEN: usize = 32;
pub const NONCE_LEN: usize = 32;
pub const BINDING_LEN: usize = 32;
pub const EPHEMERAL_KEY_LEN: usize = 32;

pub type Psk = [u8; PSK_LEN];

#[derive(Debug, PartialEq, Eq)]
pub enum CryptoError {
    AllZeroSharedSecret,
    DerivationFailed,
}

impl std::fmt::Display for CryptoError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CryptoError::AllZeroSharedSecret => {
                write!(f, "low-order point check failed: shared secret Z is all zeros")
            }
            CryptoError::DerivationFailed => write!(f, "cryptographic derivation failed"),
        }
    }
}

impl std::error::Error for CryptoError {}

impl From<ring::error::Unspecified> for CryptoError {
    fn from(_: ring::error::Unspecified) -> Self {
        CryptoError::DerivationFailed
    }
}

struct Okm4;

impl hkdf::KeyType for Okm4 {
    fn len(&self) -> usize {
        4
    }
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
    a: &[u8; EPHEMERAL_KEY_LEN],
    b: &[u8; EPHEMERAL_KEY_LEN],
    binding: &[u8; BINDING_LEN],
    z: &[u8; 32],
) -> Result<(String, Psk), CryptoError> {
    // 4. Low-order point check: reject all-zero Z
    #[allow(deprecated)]
    if constant_time::verify_slices_are_equal(z, &[0u8; 32]).is_ok() {
        return Err(CryptoError::AllZeroSharedSecret);
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
    let mut psk = [0u8; PSK_LEN];
    okm_psk.fill(&mut psk)?;

    Ok((sas, psk))
}

/// Computes server_proof:
/// server_proof = HMAC-SHA256(psk, b"server-proof" || client_nonce || server_nonce || binding)
pub fn server_proof(
    psk: &Psk,
    client_nonce: &[u8; NONCE_LEN],
    server_nonce: &[u8; NONCE_LEN],
    binding: &[u8; BINDING_LEN],
) -> [u8; PROOF_LEN] {
    let key = hmac::Key::new(hmac::HMAC_SHA256, psk);
    let mut ctx = hmac::Context::with_key(&key);
    ctx.update(b"server-proof");
    ctx.update(client_nonce);
    ctx.update(server_nonce);
    ctx.update(binding);
    let tag = ctx.sign();
    let mut out = [0u8; PROOF_LEN];
    out.copy_from_slice(tag.as_ref());
    out
}

/// Computes client_proof:
/// client_proof = HMAC-SHA256(psk, b"client-proof" || client_nonce || server_nonce || binding)
pub fn client_proof(
    psk: &Psk,
    client_nonce: &[u8; NONCE_LEN],
    server_nonce: &[u8; NONCE_LEN],
    binding: &[u8; BINDING_LEN],
) -> [u8; PROOF_LEN] {
    let key = hmac::Key::new(hmac::HMAC_SHA256, psk);
    let mut ctx = hmac::Context::with_key(&key);
    ctx.update(b"client-proof");
    ctx.update(client_nonce);
    ctx.update(server_nonce);
    ctx.update(binding);
    let tag = ctx.sign();
    let mut out = [0u8; PROOF_LEN];
    out.copy_from_slice(tag.as_ref());
    out
}

/// Verifies client_proof in constant time.
#[allow(deprecated)]
pub fn verify_client_proof(
    psk: &Psk,
    client_nonce: &[u8; NONCE_LEN],
    server_nonce: &[u8; NONCE_LEN],
    binding: &[u8; BINDING_LEN],
    presented: &[u8],
) -> bool {
    let expected = client_proof(psk, client_nonce, server_nonce, binding);
    constant_time::verify_slices_are_equal(&expected, presented).is_ok()
}

/// Verifies server_proof in constant time.
#[allow(deprecated)]
pub fn verify_server_proof(
    psk: &Psk,
    client_nonce: &[u8; NONCE_LEN],
    server_nonce: &[u8; NONCE_LEN],
    binding: &[u8; BINDING_LEN],
    presented: &[u8],
) -> bool {
    let expected = server_proof(psk, client_nonce, server_nonce, binding);
    constant_time::verify_slices_are_equal(&expected, presented).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_answer_v2_vectors() {
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

        // Constant time verification checks
        assert!(verify_server_proof(&psk, &client_nonce, &server_nonce, &binding, &s_proof));
        assert!(verify_client_proof(&psk, &client_nonce, &server_nonce, &binding, &c_proof));

        let mut bad_server_proof = s_proof;
        bad_server_proof[0] ^= 1;
        assert!(!verify_server_proof(&psk, &client_nonce, &server_nonce, &binding, &bad_server_proof));

        let mut bad_client_proof = c_proof;
        bad_client_proof[0] ^= 1;
        assert!(!verify_client_proof(&psk, &client_nonce, &server_nonce, &binding, &bad_client_proof));
    }

    #[test]
    fn zero_shared_secret_is_rejected() {
        let a = [0x11u8; 32];
        let b = [0x22u8; 32];
        let binding = [0x33u8; 32];
        let z = [0x00u8; 32];

        let result = derive_pairing_v2(&a, &b, &binding, &z);
        assert_eq!(result, Err(CryptoError::AllZeroSharedSecret));
    }

    #[test]
    fn sas_format_is_always_six_digits() {
        assert_eq!(format!("{:06}", 0), "000000");
        assert_eq!(format!("{:06}", 40666), "040666");
        assert_eq!(format!("{:06}", 999999), "999999");
        assert_eq!(format!("{:06}", 40666).len(), SAS_LEN);
    }

    #[test]
    fn different_channel_bindings_yield_different_sas_and_psk() {
        let a = [0x11u8; 32];
        let b = [0x22u8; 32];
        let z = [0x44u8; 32];
        let binding1 = [0x33u8; 32];
        let mut binding2 = [0x33u8; 32];
        binding2[0] ^= 0xff;

        let (sas1, psk1) = derive_pairing_v2(&a, &b, &binding1, &z).expect("derive 1");
        let (sas2, psk2) = derive_pairing_v2(&a, &b, &binding2, &z).expect("derive 2");

        assert_ne!(sas1, sas2, "channel binding change must alter SAS");
        assert_ne!(psk1, psk2, "channel binding change must alter PSK");
    }

    #[test]
    fn different_nonces_yield_different_proofs() {
        let psk = [0x42u8; 32];
        let binding = [0x33u8; 32];
        let cn1 = [0x55u8; 32];
        let mut cn2 = [0x55u8; 32];
        cn2[0] ^= 0x01;
        let sn = [0x66u8; 32];

        let s_proof1 = server_proof(&psk, &cn1, &sn, &binding);
        let s_proof2 = server_proof(&psk, &cn2, &sn, &binding);
        let c_proof1 = client_proof(&psk, &cn1, &sn, &binding);
        let c_proof2 = client_proof(&psk, &cn2, &sn, &binding);

        assert_ne!(s_proof1, s_proof2);
        assert_ne!(c_proof1, c_proof2);
    }

    #[test]
    fn verify_proofs_reject_wrong_lengths() {
        let psk = [0x42u8; 32];
        let binding = [0x33u8; 32];
        let cn = [0x55u8; 32];
        let sn = [0x66u8; 32];
        let s_proof = server_proof(&psk, &cn, &sn, &binding);

        // Truncated proof
        assert!(!verify_server_proof(&psk, &cn, &sn, &binding, &s_proof[..16]));
        // Overlong proof
        let mut long_proof = s_proof.to_vec();
        long_proof.push(0x00);
        assert!(!verify_server_proof(&psk, &cn, &sn, &binding, &long_proof));
    }
}
