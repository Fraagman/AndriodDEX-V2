//! Cross-language golden vectors for the wire messages.
//!
//! The Kotlin side (the phone) serializes `AudioPacket` with its generated class
//! and the Rust side (the receiver) decodes the prost-generated one — these tests
//! pin BOTH sides to the same byte format, which is exactly the class of drift
//! that hand-written varint serialization once introduced (D1).

use prost::Message;
use zc_protocol::audio::AudioPacket;

#[test]
fn audio_packet_golden_vector() {
    // Fixed vector: pcm_data = [0x11, 0x22], timestamp = 5.
    // Field 1 (bytes, tag 0x0A), field 2 (varint, tag 0x10).
    let packet = AudioPacket {
        pcm_data: vec![0x11, 0x22],
        timestamp: 5,
    };

    let mut bytes = Vec::new();
    packet.encode(&mut bytes).expect("encode");
    assert_eq!(
        bytes,
        vec![0x0A, 0x02, 0x11, 0x22, 0x10, 0x05],
        "AudioPacket wire bytes drifted from the shared golden vector"
    );

    // And the decode side accepts exactly those bytes.
    let decoded = AudioPacket::decode(&bytes[..]).expect("decode");
    assert_eq!(decoded.pcm_data, vec![0x11, 0x22]);
    assert_eq!(decoded.timestamp, 5);
}

#[test]
fn audio_packet_golden_vector_empty_payload() {
    // An empty payload still encodes field 1 (an empty bytes field) — and the
    // Kotlin side's generated class must produce the same shape.
    let packet = AudioPacket {
        pcm_data: Vec::new(),
        timestamp: 1,
    };
    let mut bytes = Vec::new();
    packet.encode(&mut bytes).expect("encode");
    // Empty bytes are NOT serialized (proto3 default), so only the timestamp.
    assert_eq!(bytes, vec![0x10, 0x01]);

    let decoded = AudioPacket::decode(&bytes[..]).expect("decode");
    assert!(decoded.pcm_data.is_empty());
    assert_eq!(decoded.timestamp, 1);
}
