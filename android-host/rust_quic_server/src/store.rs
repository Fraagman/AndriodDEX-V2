//! Owner-only on-disk storage for the pairing PSK and the TLS identity.
//!
//! Everything lives under the `dataPath` the Kotlin side passes to `start()`, which is
//! the app's private `filesDir`. The files are additionally created with mode 0600 (and
//! the directory 0700) so that a world-readable umask or a future `MODE_WORLD_READABLE`
//! mistake elsewhere in the app cannot expose the PSK.

use std::fs;
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};

use crate::crypto::{Psk, PSK_LEN};

const PSK_FILE: &str = "pairing.psk";
const CERT_FILE: &str = "tls_identity.bin";

/// A DER certificate chain element plus its PKCS#8 private key.
pub struct TlsIdentity {
    pub cert_der: Vec<u8>,
    pub key_der: Vec<u8>,
}

#[derive(Clone)]
pub struct SecureStore {
    dir: PathBuf,
}

impl SecureStore {
    /// Creates the directory if needed and tightens its permissions.
    pub fn open(dir: impl Into<PathBuf>) -> io::Result<Self> {
        let dir = dir.into();
        fs::create_dir_all(&dir)?;
        restrict_dir(&dir)?;
        Ok(Self { dir })
    }

    fn path(&self, name: &str) -> PathBuf {
        self.dir.join(name)
    }

    // -- PSK ---------------------------------------------------------------

    pub fn load_psk(&self) -> Option<Psk> {
        let mut file = fs::File::open(self.path(PSK_FILE)).ok()?;
        let mut psk = [0u8; PSK_LEN];
        file.read_exact(&mut psk).ok()?;

        // A trailing byte means the file is not what this code wrote. Refuse rather than
        // authenticate against a half-understood blob.
        let mut extra = [0u8; 1];
        match file.read(&mut extra) {
            Ok(0) => Some(psk),
            _ => None,
        }
    }

    pub fn store_psk(&self, psk: &Psk) -> io::Result<()> {
        write_private(&self.path(PSK_FILE), &[psk.as_slice()])
    }

    pub fn clear_psk(&self) {
        let path = self.path(PSK_FILE);
        match fs::remove_file(&path) {
            Ok(()) => crate::log_i!("cleared stored pairing key"),
            Err(e) if e.kind() == io::ErrorKind::NotFound => {}
            Err(e) => crate::log_w!("could not clear stored pairing key: {e}"),
        }
    }

    // -- TLS identity ------------------------------------------------------

    /// Layout: `u32 LE cert_len | cert DER | PKCS#8 key DER`.
    pub fn load_tls_identity(&self) -> Option<TlsIdentity> {
        let mut file = fs::File::open(self.path(CERT_FILE)).ok()?;

        let mut len_buf = [0u8; 4];
        file.read_exact(&mut len_buf).ok()?;
        let cert_len = u32::from_le_bytes(len_buf) as usize;

        // Guard against a corrupt length turning into a huge allocation.
        if cert_len == 0 || cert_len > 64 * 1024 {
            return None;
        }

        let mut cert_der = vec![0u8; cert_len];
        file.read_exact(&mut cert_der).ok()?;

        let mut key_der = Vec::new();
        file.read_to_end(&mut key_der).ok()?;
        if key_der.is_empty() {
            return None;
        }

        Some(TlsIdentity { cert_der, key_der })
    }

    pub fn store_tls_identity(&self, identity: &TlsIdentity) -> io::Result<()> {
        let len = u32::try_from(identity.cert_der.len())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "certificate too large"))?;
        write_private(
            &self.path(CERT_FILE),
            &[&len.to_le_bytes(), identity.cert_der.as_slice(), identity.key_der.as_slice()],
        )
    }

    pub fn discard_tls_identity(&self) {
        let _ = fs::remove_file(self.path(CERT_FILE));
    }
}

/// Writes `chunks` to `path`, creating it owner-read/write only.
///
/// The write goes to a sibling temp file that is then renamed, so an interrupted write
/// cannot leave a truncated PSK or certificate behind for the next launch to load.
fn write_private(path: &Path, chunks: &[&[u8]]) -> io::Result<()> {
    let tmp = path.with_extension("tmp");

    {
        let mut opts = fs::OpenOptions::new();
        opts.write(true).create(true).truncate(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            opts.mode(0o600);
        }

        let mut file = opts.open(&tmp)?;
        // create(true) leaves an existing file's old mode in place, so set it explicitly.
        restrict_file(&tmp)?;
        for chunk in chunks {
            file.write_all(chunk)?;
        }
        file.sync_all()?;
    }

    fs::rename(&tmp, path)?;
    restrict_file(path)?;
    Ok(())
}

#[cfg(unix)]
fn restrict_dir(dir: &Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(dir, fs::Permissions::from_mode(0o700))
}

#[cfg(not(unix))]
fn restrict_dir(_dir: &Path) -> io::Result<()> {
    // Non-Android hosts are dev machines running the unit tests; the app never ships there.
    Ok(())
}

#[cfg(unix)]
fn restrict_file(path: &Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o600))
}

#[cfg(not(unix))]
fn restrict_file(_path: &Path) -> io::Result<()> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> PathBuf {
        let mut p = std::env::temp_dir();
        p.push(format!("rust_quic_server_test_{name}_{}", std::process::id()));
        let _ = fs::remove_dir_all(&p);
        p
    }

    #[test]
    fn psk_round_trips() {
        let dir = temp_dir("psk");
        let store = SecureStore::open(&dir).expect("open store");
        assert!(store.load_psk().is_none());

        let psk: Psk = [42u8; PSK_LEN];
        store.store_psk(&psk).expect("store psk");
        assert_eq!(store.load_psk(), Some(psk));

        store.clear_psk();
        assert!(store.load_psk().is_none());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn truncated_psk_is_rejected() {
        let dir = temp_dir("trunc");
        let store = SecureStore::open(&dir).expect("open store");
        fs::write(dir.join(PSK_FILE), [1u8; 16]).expect("write short file");
        assert!(store.load_psk().is_none());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn oversized_psk_is_rejected() {
        let dir = temp_dir("over");
        let store = SecureStore::open(&dir).expect("open store");
        fs::write(dir.join(PSK_FILE), [1u8; 33]).expect("write long file");
        assert!(store.load_psk().is_none());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn tls_identity_round_trips() {
        let dir = temp_dir("tls");
        let store = SecureStore::open(&dir).expect("open store");
        assert!(store.load_tls_identity().is_none());

        let identity = TlsIdentity { cert_der: vec![1, 2, 3, 4], key_der: vec![9, 8, 7] };
        store.store_tls_identity(&identity).expect("store identity");

        let loaded = store.load_tls_identity().expect("reload identity");
        assert_eq!(loaded.cert_der, identity.cert_der);
        assert_eq!(loaded.key_der, identity.key_der);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn corrupt_tls_identity_is_rejected() {
        let dir = temp_dir("tlsbad");
        let store = SecureStore::open(&dir).expect("open store");
        // Length header claims far more bytes than the file holds.
        fs::write(dir.join(CERT_FILE), [0xFF, 0xFF, 0x00, 0x00, 0x01]).expect("write");
        assert!(store.load_tls_identity().is_none());
        let _ = fs::remove_dir_all(&dir);
    }

    #[cfg(unix)]
    #[test]
    fn files_are_owner_only() {
        use std::os::unix::fs::PermissionsExt;
        let dir = temp_dir("perm");
        let store = SecureStore::open(&dir).expect("open store");
        store.store_psk(&[0u8; PSK_LEN]).expect("store psk");

        let mode = fs::metadata(dir.join(PSK_FILE)).expect("stat").permissions().mode();
        assert_eq!(mode & 0o777, 0o600, "psk file must not be group/world readable");

        let dir_mode = fs::metadata(&dir).expect("stat dir").permissions().mode();
        assert_eq!(dir_mode & 0o777, 0o700);
        let _ = fs::remove_dir_all(&dir);
    }
}
