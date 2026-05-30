use std::collections::HashMap;
use wgpu::util::DeviceExt;
use zc_protocol::video::{TileUpdate, CursorUpdate};
use bytemuck::{Pod, Zeroable};
use std::sync::Arc;

#[repr(C)]
#[derive(Copy, Clone, Debug, Pod, Zeroable)]
struct Vertex {
    position: [f32; 2],
    tex_coords: [f32; 2],
}

#[repr(C)]
#[derive(Copy, Clone, Debug, Pod, Zeroable)]
struct TileUniforms {
    transform: [f32; 4], // scale_x, scale_y, offset_x, offset_y
}

const VERTICES: &[Vertex] = &[
    Vertex { position: [-1.0,  1.0], tex_coords: [0.0, 0.0] },
    Vertex { position: [-1.0, -1.0], tex_coords: [0.0, 1.0] },
    Vertex { position: [ 1.0,  1.0], tex_coords: [1.0, 0.0] },
    Vertex { position: [-1.0, -1.0], tex_coords: [0.0, 1.0] },
    Vertex { position: [ 1.0, -1.0], tex_coords: [1.0, 1.0] },
    Vertex { position: [ 1.0,  1.0], tex_coords: [1.0, 0.0] },
];

pub struct CachedRenderData {
    pub texture: wgpu::Texture,
    pub bind_group: wgpu::BindGroup,
    pub uniform_buffer: wgpu::Buffer,
    pub width: f32,
    pub height: f32,
}

pub struct TileCompositor {
    pub tiles: HashMap<(u32, u32), CachedRenderData>,
    pub cursor: Option<(u32, u32, CachedRenderData)>, // x, y, data
    base_cache: Option<CachedRenderData>,
    pipeline: wgpu::RenderPipeline,
    bind_group_layout: wgpu::BindGroupLayout,
    sampler: wgpu::Sampler,
    vertex_buffer: wgpu::Buffer,
    base_width: u32,
    base_height: u32,
}

impl TileCompositor {
    pub fn new(device: &wgpu::Device, target_format: wgpu::TextureFormat, base_width: u32, base_height: u32) -> Self {
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Tile Compositor Shader"),
            source: wgpu::ShaderSource::Wgsl(std::borrow::Cow::Borrowed(r#"
                struct VertexInput {
                    @location(0) position: vec2<f32>,
                    @location(1) tex_coords: vec2<f32>,
                };
                struct VertexOutput {
                    @builtin(position) clip_position: vec4<f32>,
                    @location(0) tex_coords: vec2<f32>,
                };
                struct Uniforms {
                    transform: vec4<f32>, // scale_x, scale_y, offset_x, offset_y
                };
                @group(0) @binding(0) var<uniform> uniforms: Uniforms;
                @group(0) @binding(1) var t_diffuse: texture_2d<f32>;
                @group(0) @binding(2) var s_diffuse: sampler;

                @vertex
                fn vs_main(model: VertexInput) -> VertexOutput {
                    var out: VertexOutput;
                    let scaled = model.position * vec2<f32>(uniforms.transform.x, uniforms.transform.y);
                    let offset = vec2<f32>(uniforms.transform.z, uniforms.transform.w);
                    out.clip_position = vec4<f32>(scaled + offset, 0.0, 1.0);
                    out.tex_coords = model.tex_coords;
                    return out;
                }

                @fragment
                fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
                    return textureSample(t_diffuse, s_diffuse, in.tex_coords);
                }
            "#)),
        });

        let bind_group_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::VERTEX,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        multisampled: false,
                        view_dimension: wgpu::TextureViewDimension::D2,
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 2,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                    count: None,
                },
            ],
            label: Some("Tile Compositor Bind Group Layout"),
        });

        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("Tile Compositor Pipeline Layout"),
            bind_group_layouts: &[&bind_group_layout],
            push_constant_ranges: &[],
        });

        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("Tile Compositor Pipeline"),
            layout: Some(&pipeline_layout),
            vertex: wgpu::VertexState {
                module: &shader,
                entry_point: "vs_main",
                buffers: &[
                    wgpu::VertexBufferLayout {
                        array_stride: std::mem::size_of::<Vertex>() as wgpu::BufferAddress,
                        step_mode: wgpu::VertexStepMode::Vertex,
                        attributes: &wgpu::vertex_attr_array![0 => Float32x2, 1 => Float32x2],
                    }
                ],
            },
            fragment: Some(wgpu::FragmentState {
                module: &shader,
                entry_point: "fs_main",
                targets: &[Some(wgpu::ColorTargetState {
                    format: target_format,
                    blend: Some(wgpu::BlendState::ALPHA_BLENDING),
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            primitive: wgpu::PrimitiveState {
                topology: wgpu::PrimitiveTopology::TriangleList,
                strip_index_format: None,
                front_face: wgpu::FrontFace::Ccw,
                cull_mode: Some(wgpu::Face::Back),
                polygon_mode: wgpu::PolygonMode::Fill,
                unclipped_depth: false,
                conservative: false,
            },
            depth_stencil: None,
            multisample: wgpu::MultisampleState {
                count: 1,
                mask: !0,
                alpha_to_coverage_enabled: false,
            },
            multiview: None,
        });

        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            address_mode_u: wgpu::AddressMode::ClampToEdge,
            address_mode_v: wgpu::AddressMode::ClampToEdge,
            address_mode_w: wgpu::AddressMode::ClampToEdge,
            mag_filter: wgpu::FilterMode::Nearest,
            min_filter: wgpu::FilterMode::Nearest,
            mipmap_filter: wgpu::FilterMode::Nearest,
            ..Default::default()
        });

        let vertex_buffer = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("Tile Vertex Buffer"),
            contents: bytemuck::cast_slice(VERTICES),
            usage: wgpu::BufferUsages::VERTEX,
        });

        Self {
            tiles: HashMap::new(),
            cursor: None,
            base_cache: None,
            pipeline,
            bind_group_layout,
            sampler,
            vertex_buffer,
            base_width,
            base_height,
        }
    }

    pub fn set_base_size(&mut self, width: u32, height: u32) {
        self.base_width = width;
        self.base_height = height;
    }

    fn create_cached_data(&self, device: &wgpu::Device, texture: wgpu::Texture, width: u32, height: u32) -> CachedRenderData {
        let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
        
        let uniforms = TileUniforms {
            transform: [1.0, 1.0, 0.0, 0.0],
        };
        let uniform_buffer = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("Quad Uniform Buffer"),
            contents: bytemuck::cast_slice(&[uniforms]),
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        });

        let bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            layout: &self.bind_group_layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: uniform_buffer.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::TextureView(&view),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: wgpu::BindingResource::Sampler(&self.sampler),
                },
            ],
            label: Some("Quad Bind Group"),
        });

        CachedRenderData {
            texture,
            bind_group,
            uniform_buffer,
            width: width as f32,
            height: height as f32,
        }
    }

    pub fn apply_tile(&mut self, tile: TileUpdate, device: &wgpu::Device, queue: &wgpu::Queue) {
        let mut decompressed = Vec::new();
        if let Err(e) = zstd::stream::copy_decode(tile.zstd_data.as_slice(), &mut decompressed) {
            eprintln!("Failed to decompress tile: {}", e);
            return;
        }

        let expected_size = (tile.width * tile.height * 4) as usize;
        if decompressed.len() != expected_size {
            eprintln!("Tile decompressed size mismatch. Expected {}, got {}", expected_size, decompressed.len());
            return;
        }

        let key = (tile.x, tile.y);
        let mut recreate = false;
        if let Some(cached) = self.tiles.get(&key) {
            if cached.width != tile.width as f32 || cached.height != tile.height as f32 {
                recreate = true;
            }
        } else {
            recreate = true;
        }

        if recreate {
            let texture = device.create_texture(&wgpu::TextureDescriptor {
                size: wgpu::Extent3d { width: tile.width, height: tile.height, depth_or_array_layers: 1 },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: wgpu::TextureFormat::Rgba8UnormSrgb,
                usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
                label: Some(&format!("Tile {}x{}", tile.x, tile.y)),
                view_formats: &[],
            });
            let cached = self.create_cached_data(device, texture, tile.width, tile.height);
            self.tiles.insert(key, cached);
        }

        if let Some(cached) = self.tiles.get(&key) {
            queue.write_texture(
                wgpu::ImageCopyTexture {
                    texture: &cached.texture,
                    mip_level: 0,
                    origin: wgpu::Origin3d::ZERO,
                    aspect: wgpu::TextureAspect::All,
                },
                &decompressed,
                wgpu::ImageDataLayout {
                    offset: 0,
                    bytes_per_row: Some(4 * tile.width),
                    rows_per_image: Some(tile.height),
                },
                wgpu::Extent3d { width: tile.width, height: tile.height, depth_or_array_layers: 1 },
            );
        }
    }

    pub fn update_cursor(&mut self, cursor: CursorUpdate, device: &wgpu::Device, queue: &wgpu::Queue) {
        if cursor.bitmap.is_empty() { return; }
        
        let width = cursor.width;
        let height = cursor.height;
        let expected_size = (width * height * 4) as usize;
        if cursor.bitmap.len() != expected_size { return; }

        let recreate = match &self.cursor {
            Some((_, _, cached)) => cached.width != width as f32 || cached.height != height as f32,
            None => true,
        };

        if recreate {
            let texture = device.create_texture(&wgpu::TextureDescriptor {
                size: wgpu::Extent3d { width, height, depth_or_array_layers: 1 },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: wgpu::TextureFormat::Rgba8UnormSrgb,
                usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
                label: Some("Cursor Texture"),
                view_formats: &[],
            });
            let cached = self.create_cached_data(device, texture, width, height);
            self.cursor = Some((cursor.x, cursor.y, cached));
        } else if let Some((cx, cy, _)) = &mut self.cursor {
            *cx = cursor.x;
            *cy = cursor.y;
        }

        if let Some((_, _, cached)) = &self.cursor {
            queue.write_texture(
                wgpu::ImageCopyTexture { texture: &cached.texture, mip_level: 0, origin: wgpu::Origin3d::ZERO, aspect: wgpu::TextureAspect::All },
                &cursor.bitmap,
                wgpu::ImageDataLayout { offset: 0, bytes_per_row: Some(4 * width), rows_per_image: Some(height) },
                wgpu::Extent3d { width, height, depth_or_array_layers: 1 },
            );
        }
    }

    pub fn composite<'a>(&'a mut self, base_texture: &'a wgpu::Texture, render_pass: &mut wgpu::RenderPass<'a>, device: &wgpu::Device, queue: &wgpu::Queue) {
        if self.base_width == 0 || self.base_height == 0 {
            return;
        }

        let base_width = self.base_width;
        let base_height = self.base_height;

        let recreate_base = match &self.base_cache {
            Some(cached) => cached.texture.global_id() != base_texture.global_id(),
            None => true,
        };

        if recreate_base {
            let view = base_texture.create_view(&wgpu::TextureViewDescriptor::default());
            let uniforms = TileUniforms { transform: [1.0, 1.0, 0.0, 0.0] };
            let uniform_buffer = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("Base Quad Uniform Buffer"),
                contents: bytemuck::cast_slice(&[uniforms]),
                usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            });
            let bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
                layout: &self.bind_group_layout,
                entries: &[
                    wgpu::BindGroupEntry { binding: 0, resource: uniform_buffer.as_entire_binding() },
                    wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::TextureView(&view) },
                    wgpu::BindGroupEntry { binding: 2, resource: wgpu::BindingResource::Sampler(&self.sampler) },
                ],
                label: Some("Base Quad Bind Group"),
            });
            
            // We only need to store bind_group and uniform_buffer for the base to keep it alive
            self.base_cache = Some(CachedRenderData {
                texture: device.create_texture(&wgpu::TextureDescriptor { size: wgpu::Extent3d{width:1,height:1,depth_or_array_layers:1}, mip_level_count:1, sample_count:1, dimension: wgpu::TextureDimension::D2, format: wgpu::TextureFormat::Rgba8UnormSrgb, usage: wgpu::TextureUsages::TEXTURE_BINDING, label:None, view_formats:&[] }),
                bind_group,
                uniform_buffer,
                width: base_width as f32,
                height: base_height as f32,
            });
            // We can't easily clone base_texture, so we just use the original base_texture in a new bind group and store it.
            // Wait, base_texture is passed in from outside (VideoDecoder). It is kept alive by VideoDecoder. So base_texture won't die. 
            // The bind group uses base_texture. But since base_texture belongs to VideoDecoder, it lives on!
            // Wait, storing a bind group that refers to `base_texture` in `self.base_cache` is fine, as long as `base_texture` is not destroyed. 
            // In WGPU, TextureView holds an Arc to the Texture, and BindGroup holds an Arc to the TextureView. So it's perfectly safe!
        }

        render_pass.set_pipeline(&self.pipeline);
        render_pass.set_vertex_buffer(0, self.vertex_buffer.slice(..));

        if let Some(cached) = &self.base_cache {
            // Fullscreen quad
            let uniforms = TileUniforms { transform: [1.0, 1.0, 0.0, 0.0] };
            queue.write_buffer(&cached.uniform_buffer, 0, bytemuck::cast_slice(&[uniforms]));
            render_pass.set_bind_group(0, &cached.bind_group, &[]);
            render_pass.draw(0..6, 0..1);
        }

        // Render each tile
        for (&(tx, ty), cached) in &self.tiles {
            let scale_x = cached.width / base_width as f32;
            let scale_y = cached.height / base_height as f32;
            let ndc_x = (tx as f32 + cached.width / 2.0) / base_width as f32 * 2.0 - 1.0;
            let ndc_y = 1.0 - (ty as f32 + cached.height / 2.0) / base_height as f32 * 2.0;
            
            let uniforms = TileUniforms { transform: [scale_x, scale_y, ndc_x, ndc_y] };
            queue.write_buffer(&cached.uniform_buffer, 0, bytemuck::cast_slice(&[uniforms]));
            
            render_pass.set_bind_group(0, &cached.bind_group, &[]);
            render_pass.draw(0..6, 0..1);
        }

        // Render cursor
        if let Some((cx, cy, cached)) = &self.cursor {
            let scale_x = cached.width / base_width as f32;
            let scale_y = cached.height / base_height as f32;
            let ndc_x = (*cx as f32 + cached.width / 2.0) / base_width as f32 * 2.0 - 1.0;
            let ndc_y = 1.0 - (*cy as f32 + cached.height / 2.0) / base_height as f32 * 2.0;

            let uniforms = TileUniforms { transform: [scale_x, scale_y, ndc_x, ndc_y] };
            queue.write_buffer(&cached.uniform_buffer, 0, bytemuck::cast_slice(&[uniforms]));
            
            render_pass.set_bind_group(0, &cached.bind_group, &[]);
            render_pass.draw(0..6, 0..1);
        }
    }
}
