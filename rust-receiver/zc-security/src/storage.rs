use std::fs;
use std::io::{self, Read, Write};
use std::path::PathBuf;
use std::sync::Mutex;

pub type Fingerprint = [u8; 32];
pub type Psk = [u8; 32];

lazy_static::lazy_static! {
    pub static ref CUSTOM_DATA_PATH: Mutex<Option<PathBuf>> = Mutex::new(None);
}

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

/// Deletes any legacy trust.bin from the broken PIN-based pairing scheme (SEC-02, SEC-03).
pub fn cleanup_legacy_trust() {
    if let Some(path) = get_legacy_trust_file_path() {
        if path.exists() {
            let _ = fs::remove_file(path);
        }
    }
}

pub fn store_trust_data(cert_fingerprint: &Fingerprint, psk: &Psk) -> Result<(), io::Error> {
    cleanup_legacy_trust();
    let path = get_trust_file_path().ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "Storage path not found"))?;
    let mut file = fs::File::create(path)?;
    file.write_all(cert_fingerprint)?;
    file.write_all(psk)?;
    Ok(())
}

pub fn load_trust_data() -> Option<(Fingerprint, Psk)> {
    cleanup_legacy_trust();
    let path = get_trust_file_path()?;
    let mut file = fs::File::open(path).ok()?;
    let mut fp = [0u8; 32];
    let mut psk = [0u8; 32];
    file.read_exact(&mut fp).ok()?;
    file.read_exact(&mut psk).ok()?;
    Some((fp, psk))
}

pub fn delete_trust_data() {
    cleanup_legacy_trust();
    if let Some(path) = get_trust_file_path() {
        let _ = fs::remove_file(path);
    }
}

fn get_server_cert_file_path() -> Option<PathBuf> {
    let mut path = get_base_data_path()?;
    path.push("server_cert.bin");
    Some(path)
}

pub fn store_server_cert(cert_pem: &[u8], key_pem: &[u8]) -> Result<(), io::Error> {
    let path = get_server_cert_file_path().ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "Storage path not found"))?;
    let mut file = fs::File::create(path)?;
    let cert_len = cert_pem.len() as u32;
    file.write_all(&cert_len.to_le_bytes())?;
    file.write_all(cert_pem)?;
    file.write_all(key_pem)?;
    Ok(())
}

pub fn load_server_cert() -> Option<(Vec<u8>, Vec<u8>)> {
    let path = get_server_cert_file_path()?;
    let mut file = fs::File::open(path).ok()?;
    let mut len_buf = [0u8; 4];
    file.read_exact(&mut len_buf).ok()?;
    let cert_len = u32::from_le_bytes(len_buf) as usize;
    
    let mut cert_pem = vec![0u8; cert_len];
    file.read_exact(&mut cert_pem).ok()?;
    
    let mut key_pem = Vec::new();
    file.read_to_end(&mut key_pem).ok()?;
    
    Some((cert_pem, key_pem))
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
}
