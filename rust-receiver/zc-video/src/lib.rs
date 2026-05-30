use wgpu::util::DeviceExt;
use zc_protocol::video::VideoFrame;

pub struct VideoDecoder {
    texture: wgpu::Texture,
    pub texture_view: wgpu::TextureView,
    texture_size: wgpu::Extent3d,
}

impl VideoDecoder {
    pub fn width(&self) -> u32 {
        self.texture_size.width
    }

    pub fn height(&self) -> u32 {
        self.texture_size.height
    }

    pub fn new(device: &wgpu::Device, _queue: &wgpu::Queue, width: u32, height: u32) -> Self {
        let texture_size = wgpu::Extent3d {
            width,
            height,
            depth_or_array_layers: 1,
        };

        let texture = device.create_texture(&wgpu::TextureDescriptor {
            size: texture_size,
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba8UnormSrgb,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            label: Some("Video Texture"),
            view_formats: &[],
        });

        let texture_view = texture.create_view(&wgpu::TextureViewDescriptor::default());

        Self {
            texture,
            texture_view,
            texture_size,
        }
    }

    pub fn decode_and_upload(&mut self, queue: &wgpu::Queue, frame: VideoFrame) {
        let expected_size = (self.texture_size.width * self.texture_size.height * 4) as usize;
        if frame.rgba_data.len() != expected_size {
            eprintln!("Invalid frame size: expected {}, got {}", expected_size, frame.rgba_data.len());
            return;
        }

        queue.write_texture(
            wgpu::ImageCopyTexture {
                texture: &self.texture,
                mip_level: 0,
                origin: wgpu::Origin3d::ZERO,
                aspect: wgpu::TextureAspect::All,
            },
            &frame.rgba_data,
            wgpu::ImageDataLayout {
                offset: 0,
                bytes_per_row: Some(4 * self.texture_size.width),
                rows_per_image: Some(self.texture_size.height),
            },
            self.texture_size,
        );
    }
}
