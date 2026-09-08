pub mod cert;
pub mod pairing;
pub mod storage;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_pin() {
        let mut seen = std::collections::HashMap::new();
        for i in 0..1000 {
            let pin = pairing::generate_pin();
            assert_eq!(pin.len(), 6);
            assert!(pin.chars().all(|c| c.is_digit(10)));
            let first_digit = pin.chars().next().unwrap();
            *seen.entry(first_digit).or_insert(0) += 1;
            
            // Output for verification
            if i < 10 {
                println!("Generated PIN {}: {}", i + 1, pin);
            }
        }
        println!("... and 990 more.");
        println!("Leading digit distribution over 1000 PINs: {:?}", seen);
        // Ensure no "000000" bias, should see roughly equal distribution (~100 each)
        assert!(seen.len() >= 9); 
    }

    #[test]
    fn test_derive_psk() {
        let pin = "123456";
        let pk = [1u8; 32];
        let psk1 = pairing::derive_psk(pin, &pk).unwrap();
        let psk2 = pairing::derive_psk(pin, &pk).unwrap();
        assert_eq!(psk1, psk2);

        let psk3 = pairing::derive_psk("654321", &pk).unwrap();
        assert_ne!(psk1, psk3);
    }

    #[test]
    fn test_known_answer_psk() {
        let key = [7u8; 32];
        let expected: [u8; 32] = [
            137, 103, 192, 249, 41, 149, 254, 88, 189, 58, 8, 253, 14, 220, 146, 84,
            135, 25, 59, 133, 39, 54, 64, 211, 189, 223, 157, 201, 189, 78, 79, 172,
        ];
        assert_eq!(pairing::derive_psk("123456", &key).unwrap(), expected);
    }
    
    #[test]
    fn test_cert_generation() {
        let (cert_pem, key_pem) = cert::generate_self_signed_cert().unwrap();
        assert!(cert_pem.starts_with(b"-----BEGIN CERTIFICATE-----"));
        assert!(key_pem.starts_with(b"-----BEGIN PRIVATE KEY-----"));
    }
}
