use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub type Fingerprint = [u8; 32];
pub type Psk = [u8; 32];

lazy_static::lazy_static! {
    pub static ref CUSTOM_DATA_PATH: Mutex<Option<PathBuf>> = Mutex::new(None);
}

/// Marks a DPAPI-protected trust file. `%APPDATA%` is commonly synced by OneDrive
/// Known-Folder-Move; a DPAPI blob is useless on any other machine or user, so the
/// pairing key no longer leaves the machine in a usable form.
const DPAPI_MAGIC: &[u8] = b"ADDEX-DPAPI1";

pub fn set_data_path(path: PathBuf) {
    let mut guard = match CUSTOM_DATA_PATH.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    *guard = Some(path);
}

fn get_base_data_path() -> Option<PathBuf> {
    let custom_path = match CUSTOM_DATA_PATH.lock() {
        Ok(g) => g.clone(),
        Err(poisoned) => poisoned.into_inner().clone(),
    };
    let path = if let Some(p) = custom_path {
        p
    } else {
        let appdata = std::env::var("APPDATA").ok()?;
        let mut p = PathBuf::from(appdata);
        p.push("AndroidDex");
        p
    };

    fs::create_dir_all(&path).ok()?;
    Some(path)
}

fn get_trust_file_path() -> Option<PathBuf> {
    let mut path = get_base_data_path()?;
    path.push("trust_v2.bin");
    Some(path)
}

fn get_legacy_trust_file_path() -> Option<PathBuf> {
    let mut path = get_base_data_path()?;
    path.push("trust.bin");
    Some(path)
}

fn get_server_cert_file_path() -> Option<PathBuf> {
    let mut path = get_base_data_path()?;
    path.push("server_cert.bin");
    Some(path)
}

/// Deletes trust files from schemes that no longer exist: the legacy
/// PIN-based trust.bin (SEC-02, SEC-03) and the phone-owned server_cert.bin,
/// which nothing has written or read since the phone took over the identity.
pub fn cleanup_legacy_trust() {
    for path in [get_legacy_trust_file_path(), get_server_cert_file_path()].into_iter().flatten() {
        if path.exists() {
            let _ = fs::remove_file(path);
        }
    }
}

/// Writes [path] atomically: temp file in the same directory, fsync, rename, then
/// fsync the directory so the rename itself survives a power cut.
fn write_atomic(path: &Path, bytes: &[u8]) -> io::Result<()> {
    let tmp = path.with_extension("tmp");
    {
        let mut file = fs::File::create(&tmp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
    }
    fs::rename(&tmp, path)?;
    #[cfg(unix)]
    {
        if let Some(dir) = path.parent() {
            if let Ok(d) = fs::File::open(dir) {
                let _ = d.sync_all();
            }
        }
    }
    Ok(())
}

pub fn store_trust_data(cert_fingerprint: &Fingerprint, psk: &Psk) -> Result<(), io::Error> {
    cleanup_legacy_trust();
    let path = get_trust_file_path().ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "Storage path not found"))?;
    let mut plain = Vec::with_capacity(64);
    plain.extend_from_slice(cert_fingerprint);
    plain.extend_from_slice(psk);

    let stored = protect(plain)?;
    write_atomic(&path, &stored)
}

pub fn load_trust_data() -> Option<(Fingerprint, Psk)> {
    cleanup_legacy_trust();
    let path = get_trust_file_path()?;
    let stored = fs::read(path).ok()?;

    let plain = unprotect(&stored)?;
    if plain.len() != 64 {
        return None;
    }
    let mut fp = [0u8; 32];
    let mut psk = [0u8; 32];
    fp.copy_from_slice(&plain[..32]);
    psk.copy_from_slice(&plain[32..]);
    Some((fp, psk))
}

pub fn delete_trust_data() {
    cleanup_legacy_trust();
    if let Some(path) = get_trust_file_path() {
        let _ = fs::remove_file(path);
    }
}

/// Protects the trust blob for the current user. On Windows this is DPAPI
/// (CryptProtectData); the blob can only be decrypted by the same user on the
/// same machine, so a synced or stolen file yields nothing.
#[cfg(windows)]
fn protect(plain: Vec<u8>) -> io::Result<Vec<u8>> {
    #[allow(non_snake_case)]
    #[repr(C)]
    struct DataBlob {
        cbData: u32,
        pbData: *mut u8,
    }

    #[link(name = "crypt32")]
    extern "system" {
        fn CryptProtectData(pDataIn: *const DataBlob, szDataDescr: *const u16, pOptionalEntropy: *const DataBlob, pvReserved: *mut u8, pPromptStruct: *mut u8, dwFlags: u32, pDataOut: *mut DataBlob) -> i32;
        fn LocalFree(hMem: *mut u8) -> *mut u8;
    }

    unsafe {
        let mut input = DataBlob { cbData: plain.len() as u32, pbData: plain.as_ptr() as *mut u8 };
        let mut output = DataBlob { cbData: 0, pbData: std::ptr::null_mut() };
        if CryptProtectData(&mut input, std::ptr::null(), std::ptr::null(), std::ptr::null_mut(), std::ptr::null_mut(), 0, &mut output) == 0 {
            return Err(io::Error::new(io::ErrorKind::Other, "CryptProtectData failed"));
        }
        let blob = std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
        LocalFree(output.pbData);
        let mut out = Vec::with_capacity(DPAPI_MAGIC.len() + 4 + blob.len());
        out.extend_from_slice(DPAPI_MAGIC);
        out.extend_from_slice(&(blob.len() as u32).to_le_bytes());
        out.extend_from_slice(&blob);
        Ok(out)
    }
}

#[cfg(not(windows))]
fn protect(plain: Vec<u8>) -> io::Result<Vec<u8>> {
    // Non-Windows hosts are dev machines running the unit tests; the app never
    // ships there. Store plaintext under the same magic-free legacy layout.
    Ok(plain)
}

/// Reverses [protect]. A legacy plaintext file (64 raw bytes, written before
/// DPAPI) still loads; the next store re-encrypts it.
#[cfg(windows)]
fn unprotect(stored: &[u8]) -> Option<Vec<u8>> {
    if !stored.starts_with(DPAPI_MAGIC) {
        // Legacy plaintext layout.
        return Some(stored.to_vec());
    }

    #[allow(non_snake_case)]
    #[repr(C)]
    struct DataBlob {
        cbData: u32,
        pbData: *mut u8,
    }

    #[link(name = "crypt32")]
    extern "system" {
        fn CryptUnprotectData(pDataIn: *const DataBlob, ppszDataDescr: *mut *mut u16, pOptionalEntropy: *const DataBlob, pvReserved: *mut u8, pPromptStruct: *mut u8, dwFlags: u32, pDataOut: *mut DataBlob) -> i32;
        fn LocalFree(hMem: *mut u8) -> *mut u8;
    }

    let body = &stored[DPAPI_MAGIC.len()..];
    if body.len() < 4 {
        return None;
    }
    let len = u32::from_le_bytes([body[0], body[1], body[2], body[3]]) as usize;
    let blob = body.get(4..4 + len)?;

    unsafe {
        let mut input = DataBlob { cbData: blob.len() as u32, pbData: blob.as_ptr() as *mut u8 };
        let mut output = DataBlob { cbData: 0, pbData: std::ptr::null_mut() };
        if CryptUnprotectData(&mut input, std::ptr::null_mut(), std::ptr::null(), std::ptr::null_mut(), std::ptr::null_mut(), 0, &mut output) == 0 {
            return None;
        }
        let plain = std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
        LocalFree(output.pbData);
        Some(plain)
    }
}

#[cfg(not(windows))]
fn unprotect(stored: &[u8]) -> Option<Vec<u8>> {
    Some(stored.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    static TEST_MUTEX: Mutex<()> = Mutex::new(());

    #[test]
    fn test_v2_trust_storage_roundtrip() {
        let _guard = TEST_MUTEX.lock();
        let temp_dir = std::env::temp_dir().join(format!("androiddex_test_{}", rand::random::<u64>()));
        set_data_path(temp_dir.clone());

        let fp = [0x11u8; 32];
        let psk = [0x22u8; 32];

        assert!(store_trust_data(&fp, &psk).is_ok());

        // Must be saved in trust_v2.bin
        let v2_path = temp_dir.join("trust_v2.bin");
        assert!(v2_path.exists(), "trust_v2.bin must exist");

        let loaded = load_trust_data().expect("load trust data");
        assert_eq!(loaded.0, fp);
        assert_eq!(loaded.1, psk);

        delete_trust_data();
        assert!(!v2_path.exists(), "trust_v2.bin must be deleted");
        let _ = fs::remove_dir_all(temp_dir);
    }

    #[test]
    fn test_legacy_trust_deleted_at_startup() {
        let _guard = TEST_MUTEX.lock();
        let temp_dir = std::env::temp_dir().join(format!("androiddex_test_legacy_{}", rand::random::<u64>()));
        set_data_path(temp_dir.clone());
        fs::create_dir_all(&temp_dir).expect("create temp dir");

        // Seed a fake legacy trust.bin
        let legacy_path = temp_dir.join("trust.bin");
        fs::write(&legacy_path, [0xaau8; 64]).expect("write legacy file");
        assert!(legacy_path.exists());

        // Calling load_trust_data() or cleanup_legacy_trust() must delete it
        cleanup_legacy_trust();
        assert!(!legacy_path.exists(), "legacy trust.bin must be deleted");

        let _ = fs::remove_dir_all(temp_dir);
    }

    /// A pre-DPAPI plaintext trust file must still load (and be re-encrypted on
    /// the next store), so an upgrade never strands a pairing.
    #[test]
    fn test_legacy_plaintext_trust_still_loads() {
        let _guard = TEST_MUTEX.lock();
        let temp_dir = std::env::temp_dir().join(format!("androiddex_test_plain_{}", rand::random::<u64>()));
        set_data_path(temp_dir.clone());
        fs::create_dir_all(&temp_dir).expect("create temp dir");

        let fp = [0x33u8; 32];
        let psk = [0x44u8; 32];
        let mut plain = Vec::with_capacity(64);
        plain.extend_from_slice(&fp);
        plain.extend_from_slice(&psk);
        fs::write(temp_dir.join("trust_v2.bin"), &plain).expect("write plaintext");

        let loaded = load_trust_data().expect("plaintext must load");
        assert_eq!(loaded.0, fp);
        assert_eq!(loaded.1, psk);

        // The next store re-encrypts: the file becomes DPAPI-protected (or on
        // non-Windows dev hosts, stays plaintext under the same load path).
        store_trust_data(&fp, &psk).expect("store");
        let raw = fs::read(temp_dir.join("trust_v2.bin")).expect("read");
        if cfg!(windows) {
            assert!(raw.starts_with(DPAPI_MAGIC), "stored file must be DPAPI-protected");
        }

        let _ = fs::remove_dir_all(temp_dir);
    }
}
