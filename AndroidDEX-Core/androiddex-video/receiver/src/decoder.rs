use shared_protocol::EncodedVideoFrame;

/// Represents a raw, decoded frame ready to be uploaded to the GPU (WGPU).
#[derive(Debug)]
pub struct DecodedFrame {
    pub width: u32,
    pub height: u32,
    /// RGBA, NV12, or YUV pixel data
    pub pixel_data: Vec<u8>, 
    pub timestamp_us: u64,
}

/// The abstraction for a hardware or software video decoder on the Receiver.
pub trait VideoDecoder {
    /// Initialize or re-initialize the decoder with a specific resolution.
    fn prepare(&mut self, width: u32, height: u32) -> Result<(), String>;

    /// Decodes an incoming network frame.
    /// This is an async-friendly trait, returning an Option since some frames (like B-frames)
    /// may require buffering before outputting a decoded surface.
    fn decode_frame(&mut self, frame: &EncodedVideoFrame) -> Result<Option<DecodedFrame>, String>;

    /// Flushes the decoder, forcing it to output any buffered frames.
    fn flush(&mut self) -> Result<(), String>;
}
