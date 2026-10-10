pub mod pairing;
pub mod storage;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_protocol_v2_vectors() {
        let a = [0x11u8; 32];
        let b = [0x22u8; 32];
        let binding = [0x33u8; 32];
        let z = [0x44u8; 32];
        let client_nonce = [0x55u8; 32];
        let server_nonce = [0x66u8; 32];

        let (sas, psk) = pairing::derive_pairing_v2(&a, &b, &binding, &z).expect("derive_pairing_v2 failed");
        let s_proof = pairing::server_proof(&psk, &client_nonce, &server_nonce, &binding);
        let c_proof = pairing::client_proof(&psk, &client_nonce, &server_nonce, &binding);

        println!("=== PROTOCOL V2 TEST VECTORS ===");
        println!("SAS: {}", sas);
        println!("PSK: {:?}", psk);
        println!("server_proof: {:?}", s_proof);
        println!("client_proof: {:?}", c_proof);
        println!("================================");

        // 20c: Assert hardcoded literals
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
        assert!(pairing::verify_server_proof(&psk, &client_nonce, &server_nonce, &binding, &s_proof));
        assert!(pairing::verify_client_proof(&psk, &client_nonce, &server_nonce, &binding, &c_proof));

        let mut bad_server_proof = s_proof;
        bad_server_proof[0] ^= 1;
        assert!(!pairing::verify_server_proof(&psk, &client_nonce, &server_nonce, &binding, &bad_server_proof));

        let mut bad_client_proof = c_proof;
        bad_client_proof[0] ^= 1;
        assert!(!pairing::verify_client_proof(&psk, &client_nonce, &server_nonce, &binding, &bad_client_proof));
    }

    #[test]
    fn test_zero_shared_secret_rejected() {
        let a = [0x11u8; 32];
        let b = [0x22u8; 32];
        let binding = [0x33u8; 32];
        let z = [0x00u8; 32];

        let result = pairing::derive_pairing_v2(&a, &b, &binding, &z);
        assert_eq!(result, Err(pairing::PairingError::AllZeroSharedSecret));
    }
}
