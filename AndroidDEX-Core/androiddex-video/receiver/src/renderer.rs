use crate::decoder::DecodedFrame;
use std::time::Instant;

/// Isolates GPU texture upload and WGPU Swapchain presentation logic from the rest of the pipeline.
pub struct Renderer {
    // WGPU Device, Queue, and Surface would go here
    width: u32,
    height: u32,
}

impl Renderer {
    pub fn new(width: u32, height: u32) -> Self {
        Self { width, height }
    }

    /// Uploads the DecodedFrame pixel data to a WGPU Texture and presents it to the Swapchain.
    pub fn render(&mut self, frame: DecodedFrame) {
        let upload_start = Instant::now();
        
        // 1. Handle dynamic resolution changes
        if frame.width != self.width || frame.height != self.height {
            println!("Renderer: Resolution changed from {}x{} to {}x{}", 
                     self.width, self.height, frame.width, frame.height);
            self.width = frame.width;
            self.height = frame.height;
            // self.recreate_swapchain(self.width, self.height);
        }

        // 2. TextureUpload (WriteTexture)
        // wgpu_queue.write_texture(..., frame.pixel_data, ...)
        
        // 3. Present Swapchain
        // surface.get_current_texture().unwrap().present()

        let upload_duration = upload_start.elapsed();
        println!("Renderer: Presented frame (ts: {}) in {:?}", frame.timestamp_us, upload_duration);
    }
}
