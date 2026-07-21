use crate::decoder::{DecodedFrame, VideoDecoder};
use crate::jitter_buffer::JitterBuffer;
use std::time::Instant;

/// Adapts the JitterBuffer to the active VideoDecoder (e.g. MediaFoundationDecoder).
/// Isolates codec logic from network timing and ordering.
pub struct DecoderAdapter {
    decoder: Box<dyn VideoDecoder>,
}

impl DecoderAdapter {
    pub fn new(decoder: Box<dyn VideoDecoder>) -> Self {
        Self { decoder }
    }

    /// Pulls the next ready frame from the JitterBuffer and decodes it.
    pub fn process_next(&mut self, jitter_buffer: &mut JitterBuffer) -> Option<DecodedFrame> {
        if let Some(encoded_frame) = jitter_buffer.pop() {
            let decode_start = Instant::now();
            
            // Hand off strictly codec-related operations to the backend
            match self.decoder.decode_frame(&encoded_frame) {
                Ok(Some(decoded)) => {
                    let decode_duration = decode_start.elapsed();
                    // In the future, emit `decode_duration` to diagnostics
                    println!("DecoderAdapter: Decoded frame {} in {:?}", encoded_frame.sequence_number, decode_duration);
                    return Some(decoded);
                }
                Ok(None) => {
                    // Frame buffered in decoder (e.g. B-frames)
                    return None;
                }
                Err(e) => {
                    eprintln!("DecoderAdapter: Decode error: {}", e);
                    // Trigger keyframe request to network layer here
                    return None;
                }
            }
        }
        None
    }
}
