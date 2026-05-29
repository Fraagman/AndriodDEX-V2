use rand::Rng;
use ring::hkdf;

pub fn generate_pin() -> String {
    let mut rng = rand::thread_rng();
    let num: u32 = rng.gen_range(0..1_000_000);
    format!("{:06}", num)
}

pub fn derive_psk(pin: &str, ephemeral_public_key: &[u8; 32]) -> [u8; 32] {
    let salt = hkdf::Salt::new(hkdf::HKDF_SHA256, b"androiddex-v1");
    let mut ikm = Vec::new();
    ikm.extend_from_slice(pin.as_bytes());
    ikm.extend_from_slice(ephemeral_public_key);
    let prk = salt.extract(&ikm);
    
    let info = [b"psk".as_slice()];
    let okm = prk.expand(&info, hkdf::HKDF_SHA256).unwrap();
    let mut psk = [0u8; 32];
    okm.fill(&mut psk).unwrap();
    psk
}
