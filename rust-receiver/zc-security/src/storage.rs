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
    *CUSTOM_DATA_PATH.lock().unwrap() = Some(path);
}

fn get_trust_file_path() -> Option<PathBuf> {
    let mut path = if let Some(p) = CUSTOM_DATA_PATH.lock().unwrap().clone() {
        p
    } else {
        let appdata = std::env::var("APPDATA").ok()?;
        let mut p = PathBuf::from(appdata);
        p.push("AndroidDex");
        p
    };
    
    fs::create_dir_all(&path).ok()?;
    path.push("trust.bin");
    Some(path)
}

pub fn store_trust_data(cert_fingerprint: &Fingerprint, psk: &Psk) -> Result<(), io::Error> {
    let path = get_trust_file_path().ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "Storage path not found"))?;
    let mut file = fs::File::create(path)?;
    file.write_all(cert_fingerprint)?;
    file.write_all(psk)?;
    Ok(())
}

pub fn load_trust_data() -> Option<(Fingerprint, Psk)> {
    let path = get_trust_file_path()?;
    let mut file = fs::File::open(path).ok()?;
    let mut fp = [0u8; 32];
    let mut psk = [0u8; 32];
    file.read_exact(&mut fp).ok()?;
    file.read_exact(&mut psk).ok()?;
    Some((fp, psk))
}

fn get_server_cert_file_path() -> Option<PathBuf> {
    let mut path = if let Some(p) = CUSTOM_DATA_PATH.lock().unwrap().clone() {
        p
    } else {
        let appdata = std::env::var("APPDATA").ok()?;
        let mut p = PathBuf::from(appdata);
        p.push("AndroidDex");
        p
    };
    
    fs::create_dir_all(&path).ok()?;
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
