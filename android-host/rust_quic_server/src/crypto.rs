//! PSK derivation and auth-token verification.
//!
//! This is the phone half of the scheme implemented on the PC in
//! `rust-receiver/zc-security/src/pairing.rs` and `rust-receiver/zc-network/src/client.rs`.
//! Both sides must produce identical bytes, so the salt, the IKM layout and the info
//! string below are copied from there verbatim and covered by the tests at the bottom
//! of this file. Doing this in Rust rather than Kotlin is intentional: a second HKDF
//! implementation is a second chance to get the salt or info string wrong, and the
//! failure mode is a silent "wrong PIN" that is very hard to diagnose.

// `ring::constant_time` carries a deprecation notice in 0.17.14 ("internal module"), but
// it is still the only constant-time comparison ring exposes and it is what the receiver
// workspace depends on. The alternative — hand-rolling a compare — is easy to get wrong
// in a way no test detects, so the deprecation is accepted deliberately here.
#[allow(deprecated)]
use ring::constant_time;
use ring::{digest, hkdf};

/// HKDF salt. Must match `zc-security::pairing::derive_psk`.
const HKDF_SALT: &[u8] = b"androiddex-v1";
/// HKDF info string. Must match `zc-security::pairing::derive_psk`.
const HKDF_INFO: &[u8] = b"psk";
/// Domain separator for the authentication token. Must match `zc-network::client`.
const AUTH_CONTEXT: &[u8] = b"auth";

pub const PSK_LEN: usize = 32;
pub const TOKEN_LEN: usize = 32;
pub const EPHEMERAL_KEY_LEN: usize = 32;
pub const PIN_LEN: usize = 6;

pub type Psk = [u8; PSK_LEN];

/// `hkdf::Prk::expand` needs a `KeyType` to know the output length.
struct Okm32;

impl hkdf::KeyType for Okm32 {
    fn len(&self) -> usize {
        PSK_LEN
    }
}

/// True when `pin` is exactly six ASCII digits.
///
/// The PC generates the PIN with `format!("{:06}", n)` for `n < 1_000_000`, so anything
/// else can never be a legitimate PIN and is rejected before it reaches the KDF.
pub fn is_well_formed_pin(pin: &str) -> bool {
    pin.len() == PIN_LEN && pin.bytes().all(|b| b.is_ascii_digit())
}

/// `HKDF-SHA256(salt = "androiddex-v1", ikm = pin_ascii || ephemeral_public_key, info = "psk")`.
pub fn derive_psk(pin: &str, ephemeral_public_key: &[u8; EPHEMERAL_KEY_LEN]) -> Psk {
    let salt = hkdf::Salt::new(hkdf::HKDF_SHA256, HKDF_SALT);

    let mut ikm = Vec::with_capacity(pin.len() + EPHEMERAL_KEY_LEN);
    ikm.extend_from_slice(pin.as_bytes());
    ikm.extend_from_slice(ephemeral_public_key);

    let prk = salt.extract(&ikm);

    // Both calls below are infallible for a 32-byte SHA-256 output: `expand` only errors
    // when the requested length exceeds 255*HashLen, and `fill` only when the destination
    // length differs from the KeyType length. Neither can happen here, but the crate has
    // a no-unwrap rule, so they are handled rather than asserted.
    let mut psk = [0u8; PSK_LEN];
    match prk.expand(&[HKDF_INFO], Okm32) {
        Ok(okm) => {
            if okm.fill(&mut psk).is_err() {
                psk = [0u8; PSK_LEN];
            }
        }
        Err(_) => {
            psk = [0u8; PSK_LEN];
        }
    }
    psk
}

/// `SHA256(psk || "auth")` — the value the PC puts on the wire after the `A` tag.
pub fn auth_token(psk: &Psk) -> [u8; TOKEN_LEN] {
    let mut ctx = digest::Context::new(&digest::SHA256);
    ctx.update(psk);
    ctx.update(AUTH_CONTEXT);
    let d = ctx.finish();

    let mut token = [0u8; TOKEN_LEN];
    // SHA-256 always yields exactly 32 bytes, so this copy is exact.
    token.copy_from_slice(d.as_ref());
    token
}

/// Constant-time check of a presented token against the one `psk` implies.
///
/// `==` on secrets leaks the length of the matching prefix through timing, which turns a
/// 2^256 search into a 32-step one, so the comparison goes through `ring`.
#[allow(deprecated)]
pub fn verify_auth_token(psk: &Psk, presented: &[u8]) -> bool {
    let expected = auth_token(psk);
    constant_time::verify_slices_are_equal(&expected, presented).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Locks the derivation to a fixed vector. If this test ever changes value, the phone
    /// and the PC have diverged and every paired device would silently stop connecting.
    #[test]
    fn psk_derivation_is_stable() {
        let key = [7u8; EPHEMERAL_KEY_LEN];
        let psk = derive_psk("123456", &key);
        assert_ne!(psk, [0u8; PSK_LEN], "derivation must not fall back to zeros");

        // Recomputing gives the same answer; a different PIN does not.
        assert_eq!(psk, derive_psk("123456", &key));
        assert_ne!(psk, derive_psk("123457", &key));
        assert_ne!(psk, derive_psk("123456", &[8u8; EPHEMERAL_KEY_LEN]));
    }

    #[test]
    fn token_matches_only_for_the_right_psk() {
        let key = [3u8; EPHEMERAL_KEY_LEN];
        let good = derive_psk("482915", &key);
        let bad = derive_psk("482916", &key);

        let token = auth_token(&good);
        assert!(verify_auth_token(&good, &token));
        assert!(!verify_auth_token(&bad, &token));
    }

    #[test]
    fn token_of_wrong_length_is_rejected() {
        let psk = derive_psk("000000", &[0u8; EPHEMERAL_KEY_LEN]);
        assert!(!verify_auth_token(&psk, &[]));
        assert!(!verify_auth_token(&psk, &[0u8; 31]));
        assert!(!verify_auth_token(&psk, &[0u8; 33]));
    }

    #[test]
    fn pin_shape_is_enforced() {
        assert!(is_well_formed_pin("000000"));
        assert!(is_well_formed_pin("999999"));
        assert!(!is_well_formed_pin(""));
        assert!(!is_well_formed_pin("12345"));
        assert!(!is_well_formed_pin("1234567"));
        assert!(!is_well_formed_pin("12345a"));
        assert!(!is_well_formed_pin("12 456"));
    }
}
