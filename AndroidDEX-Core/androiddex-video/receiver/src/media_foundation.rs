use crate::decoder::{DecodedFrame, VideoDecoder};
use shared_protocol::EncodedVideoFrame;

/// A Windows-specific implementation of the VideoDecoder trait using Media Foundation.
pub struct MediaFoundationDecoder {
    is_prepared: bool,
    width: u32,
    height: u32,
    // Note: FFI bindings to IMFTransform / IMFMediaBuffer would go here
}

impl MediaFoundationDecoder {
    pub fn new() -> Self {
        Self {
            is_prepared: false,
            width: 0,
            height: 0,
        }
    }
}

impl VideoDecoder for MediaFoundationDecoder {
    fn prepare(&mut self, width: u32, height: u32) -> Result<(), String> {
        // Initialize Media Foundation components
        // e.g., MFStartup(), CoCreateInstance for H264 decoder MFT
        self.width = width;
        self.height = height;
        self.is_prepared = true;
        
        println!("MediaFoundationDecoder prepared for {}x{}", width, height);
        Ok(())
    }

    fn decode_frame(&mut self, frame: &EncodedVideoFrame) -> Result<Option<DecodedFrame>, String> {
        if !self.is_prepared {
            return Err("Decoder is not prepared".into());
        }

        // Mock implementation for the architectural pipeline validation.
        // In reality, we push `frame.payload` into IMFTransform::ProcessInput
        // and pull raw NV12/RGBA from IMFTransform::ProcessOutput.
        
        println!("MF Decoder processing frame seq {} ({} bytes)", frame.sequence_number, frame.payload.len());

        // Simulate returning a decoded frame immediately
        Ok(Some(DecodedFrame {
            width: self.width,
            height: self.height,
            pixel_data: vec![], // Empty stub for now
            timestamp_us: frame.timestamp_us,
        }))
    }

    fn flush(&mut self) -> Result<(), String> {
        // ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH)
        println!("MediaFoundationDecoder flushed.");
        Ok(())
    }
}
