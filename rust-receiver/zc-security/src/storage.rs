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
