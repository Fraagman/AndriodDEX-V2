//! The server's TLS identity.
//!
//! The receiver pins this certificate's SHA-256 on first connect (see
//! `rust-receiver/zc-security/src/storage.rs`). Generating a fresh self-signed
//! certificate on every launch therefore invalidated the pin every time. The identity is
//! now generated once and reloaded from `dataPath` afterwards; it is regenerated only
//! when the file is missing or cannot be turned into a working rustls config.

use std::sync::Arc;

use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};

use crate::store::{SecureStore, TlsIdentity};

/// Subject name. The receiver dials `"localhost"` and validates by fingerprint, not name.
const SUBJECT: &str = "localhost";

/// Builds the QUIC server config, reusing the persisted identity when it is usable.
pub fn build_server_config(
    store: &SecureStore,
    alpn: &[&[u8]],
) -> Result<quinn::ServerConfig, String> {
    if let Some(stored) = store.load_tls_identity() {
        match server_crypto(&stored, alpn) {
            Ok(crypto) => {
                crate::log_i!("reusing the persisted TLS identity; pinned fingerprint is stable");
                return finish(crypto);
            }
            Err(e) => {
                crate::log_w!("persisted TLS identity is unusable ({e}); regenerating it");
                store.discard_tls_identity();
            }
        }
    } else {
        crate::log_i!("no persisted TLS identity yet; generating one");
    }

    let generated = generate_identity()?;

    // A failure to persist is not fatal for this run, but it does mean the next launch
    // presents a different certificate and every paired PC has to pair again, so it is
    // logged at error level rather than swallowed.
    if let Err(e) = store.store_tls_identity(&generated) {
        crate::log_e!("could not persist the TLS identity ({e}); pairing will not survive a restart");
    }

    let crypto = server_crypto(&generated, alpn)?;
    finish(crypto)
}

fn finish(crypto: rustls::ServerConfig) -> Result<quinn::ServerConfig, String> {
    let quic_crypto = quinn::crypto::rustls::QuicServerConfig::try_from(crypto)
        .map_err(|e| format!("rustls config is not QUIC-compatible: {e}"))?;
    Ok(quinn::ServerConfig::with_crypto(Arc::new(quic_crypto)))
}

/// Turns a stored DER cert/key pair into a rustls config.
///
/// This doubles as the "is the persisted identity parseable?" check: rustls parses the
/// key here and verifies that it matches the certificate's public key.
fn server_crypto(identity: &TlsIdentity, alpn: &[&[u8]]) -> Result<rustls::ServerConfig, String> {
    let chain = vec![CertificateDer::from(identity.cert_der.clone())];
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(identity.key_der.clone()));

    let mut crypto = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(chain, key)
        .map_err(|e| format!("{e}"))?;

    crypto.alpn_protocols = alpn.iter().map(|p| p.to_vec()).collect();
    Ok(crypto)
}

fn generate_identity() -> Result<TlsIdentity, String> {
    let generated = rcgen::generate_simple_self_signed(vec![SUBJECT.to_string()])
        .map_err(|e| format!("certificate generation failed: {e}"))?;

    Ok(TlsIdentity {
        cert_der: generated.cert.der().to_vec(),
        key_der: generated.key_pair.serialize_der(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn temp_dir(name: &str) -> PathBuf {
        let mut p = std::env::temp_dir();
        p.push(format!("rust_quic_server_tls_{name}_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&p);
        p
    }

    /// The whole point of Phase 2: two starts, one certificate.
    #[test]
    fn the_certificate_survives_a_restart() {
        let dir = temp_dir("stable");
        let store = SecureStore::open(&dir).expect("open store");

        build_server_config(&store, &[b"androiddex"]).expect("first start");
        let first = store.load_tls_identity().expect("identity persisted").cert_der;

        build_server_config(&store, &[b"androiddex"]).expect("second start");
        let second = store.load_tls_identity().expect("identity still there").cert_der;

        assert_eq!(first, second, "the certificate must not change between launches");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_corrupt_identity_is_regenerated_rather_than_fatal() {
        let dir = temp_dir("corrupt");
        let store = SecureStore::open(&dir).expect("open store");

        build_server_config(&store, &[b"androiddex"]).expect("first start");
        let original = store.load_tls_identity().expect("identity").cert_der;

        // Well-formed envelope, garbage DER inside: load_tls_identity succeeds but rustls
        // cannot use it, which is the "unparseable" branch.
        store
            .store_tls_identity(&TlsIdentity { cert_der: vec![0xAA; 16], key_der: vec![0xBB; 16] })
            .expect("write garbage");

        build_server_config(&store, &[b"androiddex"]).expect("recovers");
        let replacement = store.load_tls_identity().expect("identity").cert_der;

        assert_ne!(replacement, vec![0xAA; 16], "garbage must have been replaced");
        assert_ne!(replacement, original);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
