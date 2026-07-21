/// Represents the type of codec used to encode the frame.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CodecType {
    H264,
    HEVC,
    AV1,
}

/// Represents the type of frame (Keyframe, Predictive, etc.)
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameType {
    /// IDR / I-Frame
    KeyFrame,
    /// P-Frame or B-Frame
    DeltaFrame,
}

/// The universal format for transporting an encoded video frame across the network.
/// This struct guarantees that all transports (QUIC, WebRTC) pass the same data
/// into the decoding pipeline.
#[derive(Debug, Clone)]
pub struct EncodedVideoFrame {
    pub codec: CodecType,
    pub width: u32,
    pub height: u32,
    pub timestamp_us: u64,
    pub frame_type: FrameType,
    pub sequence_number: u64,
    /// The raw NAL units from the encoder
    pub payload: Vec<u8>,
}
