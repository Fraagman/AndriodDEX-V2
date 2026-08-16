mod renderer;
mod ui;
mod kiosk;

use winit::{
    event::{Event, WindowEvent, ElementState, MouseScrollDelta},
    event_loop::{ControlFlow, EventLoop},
    window::WindowBuilder,
    keyboard::{KeyCode, PhysicalKey},
};
use zc_protocol::protocol::Ping;
use zc_protocol::video::{HybridFrame, hybrid_frame};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex, atomic::{AtomicBool, Ordering}};
use prost::Message;

const INPUT_BUFFER_MAX: usize = 1000;

fn main() {
    if cfg!(windows) {
        let exe_path = std::env::current_exe().unwrap_or_else(|_| std::path::PathBuf::from("AndroidDex.exe"));
        let exe_str = exe_path.to_str().unwrap_or("AndroidDex.exe");
        let command_str = format!("netsh advfirewall firewall add rule name=\"AndroidDex QUIC\" dir=out action=allow protocol=udp localport=any remoteport=4433 program=\"{}\" enable=yes", exe_str);
        
        if let Ok(output) = std::process::Command::new("powershell")
            .args(&["-Command", &command_str])
            .output() {
            if !output.status.success() {
                eprintln!("Failed to add firewall rule. Please run as administrator if connection fails.");
            }
        }
    }

    let ping = Ping { timestamp: 0 };
    println!("{:?}", ping);

    let (frame_tx, frame_rx) = std::sync::mpsc::channel::<HybridFrame>();
    
    // Initialize audio player on main thread so stream lives forever
    let (_audio_player, audio_sender) = match zc_audio::AudioPlayer::new() {
        Ok((player, sender)) => (Some(player), Some(sender)),
        Err(e) => {
            eprintln!("Failed to initialize audio player: {}", e);
            (None, None)
        }
    };

    // Shared state for input sending: holds serialized InputEvent bytes
    // The input polling thread pushes here; the QUIC sender drains from here.
    let input_buffer: Arc<Mutex<VecDeque<Vec<u8>>>> = Arc::new(Mutex::new(VecDeque::with_capacity(INPUT_BUFFER_MAX)));
    
    let connection_phase = Arc::new(Mutex::new(zc_network::ConnectionPhase::Idle));
    let is_connected = Arc::new(AtomicBool::new(false));

    let rt = tokio::runtime::Runtime::new().expect("Failed to build tokio runtime");

    let window_for_callback: Arc<Mutex<Option<Arc<winit::window::Window>>>> = Arc::new(Mutex::new(None));

    // Clone for the QUIC task
    let input_buffer_for_quic = input_buffer.clone();
    let phase_for_quic = connection_phase.clone();
    let is_connected_quic = is_connected.clone();
    let window_for_quic = window_for_callback.clone();
    
    rt.spawn(async move {
        loop {
            let phase_clone = phase_for_quic.clone();
            let frame_tx_loop = frame_tx.clone();
            let audio_sender_loop = audio_sender.clone();
            let input_buffer_loop = input_buffer_for_quic.clone();
            let is_connected_loop = is_connected_quic.clone();
            let window_for_callback_loop = window_for_quic.clone();

            match zc_network::connect(4433, move |phase| {
                *phase_clone.lock().unwrap() = phase.clone();
                if let Some(w) = window_for_callback_loop.lock().unwrap().as_ref() {
                    w.request_redraw();
                }
                if matches!(phase, zc_network::ConnectionPhase::Connected) {
                    println!("ConnectionPhase updated to Connected");
                }
            }).await {
                Ok(conn) => {
                    println!("Connected to Android server");
                    is_connected_loop.store(true, Ordering::SeqCst);

                    // All three futures run as siblings inside tokio::select!
                    // When ANY exits, we close the connection and loop back.
                    let exit_reason = tokio::select! {
                        biased;

                        // Future 1: Connection closed by peer or QUIC timeout
                        reason = conn.closed() => {
                            format!("Connection closed by peer: {:?}", reason)
                        }

                        // Future 2: Video/audio receiver worker
                        reason = async {
                            loop {
                                match conn.accept_uni().await {
                                    Err(e) => {
                                        return format!("accept_uni failed: {}", e);
                                    }
                                    Ok(mut stream) => {

                                        let frame_tx_inner = frame_tx_loop.clone();
                                        let ap_inner = audio_sender_loop.clone();

                                        // Process this stream inline (no detached spawn)
                                        // Loop on this stream as long as sender keeps it open
                                        loop {
                                            let mut len_buf = [0u8; 4];
                                            if stream.read_exact(&mut len_buf).await.is_err() {
                                                break; // Stream closed or error, break inner loop to accept next stream
                                            }
                                            let len = u32::from_le_bytes(len_buf) as usize;
                                            if len == 0 { continue; }
                                            
                                            let mut frame_buf = vec![0u8; len];
                                            if stream.read_exact(&mut frame_buf).await.is_err() {
                                                break; // Stream closed midway
                                            }

                                            if frame_buf.is_empty() { continue; }

                                            let msg_type = frame_buf[0];
                                            let payload = &frame_buf[1..];

                                            if msg_type == 0x01 {
                                                // Video
                                                if let Ok(frame) = HybridFrame::decode(payload) {
                                                    let _ = frame_tx_inner.send(frame);
                                                } else {
                                                    eprintln!("Failed to decode HybridFrame");
                                                }
                                            } else if msg_type == 0x02 {
                                                // Audio
                                                if let Ok(audio_packet) = zc_protocol::audio::AudioPacket::decode(payload) {
                                                    if let Some(player) = ap_inner.as_ref() {
                                                        let pcm_bytes = &audio_packet.pcm_data;
                                                        if pcm_bytes.len() % 2 == 0 {
                                                            let mut i16_samples = Vec::with_capacity(pcm_bytes.len() / 2);
                                                            for chunk in pcm_bytes.chunks_exact(2) {
                                                                let sample = i16::from_le_bytes([chunk[0], chunk[1]]);
                                                                i16_samples.push(sample);
                                                            }
                                                            player.play_pcm(&i16_samples);
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } => { reason }

                        // Future 3: Input sender worker
                        reason = async {
                            match conn.open_uni().await {
                                Ok(mut input_stream) => {
                                    println!("Opened input stream to server");
                                    loop {
                                        let events: Vec<Vec<u8>> = {
                                            let mut buf = input_buffer_loop.lock().unwrap();
                                            buf.drain(..).collect()
                                        };

                                        if events.is_empty() {
                                            // Phase 4: reduced from 8ms to 1ms for lower input latency
                                            tokio::time::sleep(std::time::Duration::from_millis(1)).await;
                                            continue;
                                        }

                                        for serialized in events {
                                            let len = serialized.len() as u32;
                                            if input_stream.write_all(&len.to_le_bytes()).await.is_err() {
                                                return "Input stream write failed (length)".to_string();
                                            }
                                            if input_stream.write_all(&serialized).await.is_err() {
                                                return "Input stream write failed (data)".to_string();
                                            }
                                        }
                                    }
                                }
                                Err(e) => {
                                    format!("Failed to open input stream: {}", e)
                                }
                            }
                        } => { reason }
                    };

                    // One of the three futures exited. Tear down everything.
                    eprintln!("Connection ended: {}", exit_reason);
                    conn.close(0u32.into(), b"worker_exited");
                    is_connected_loop.store(false, Ordering::SeqCst);
                }
                Err(e) => {
                    println!("Connection failed: {}. Falling back to mock 1kHz audio...", e);
                    if let Some(ref ap) = audio_sender_loop {
                        let ap = ap.clone();
                        std::thread::spawn(move || {
                            let mut phase: f32 = 0.0;
                            let phase_inc = 1000.0 * 2.0 * std::f32::consts::PI / 48000.0;
                            // Only play fallback for 3 seconds before next connection attempt
                            for _ in 0..60 {
                                let mut buffer = Vec::with_capacity(4800);
                                for _ in 0..2400 {
                                    let sample = (phase.sin() * 30000.0) as i16;
                                    buffer.push(sample); // Left
                                    buffer.push(sample); // Right
                                    phase += phase_inc;
                                    if phase > 2.0 * std::f32::consts::PI {
                                        phase -= 2.0 * std::f32::consts::PI;
                                    }
                                }
                                ap.play_pcm(&buffer);
                                std::thread::sleep(std::time::Duration::from_millis(50));
                            }
                        });
                    }
                }
            }
            tokio::time::sleep(std::time::Duration::from_secs(3)).await;
        }
    });

    let input_buffer_for_poll = input_buffer.clone();

    let event_loop = EventLoop::new().unwrap();

    let window = std::sync::Arc::new(WindowBuilder::new()
        .with_title("AndroidDex Receiver")
        .with_decorations(true)
        .build(&event_loop)
        .unwrap());

    *window_for_callback.lock().unwrap() = Some(window.clone());

    let mut renderer = pollster::block_on(renderer::Renderer::new(window.clone()));
    
    // Initialize egui overlay
    let mut overlay_ui = ui::overlay::OverlayUi::new(
        renderer.device(),
        renderer.config().format,
        &window,
    );

    // --- H.264 decoder and YUV render pipeline ---
    let mut h264_decoder = match zc_video::Decoder::new() {
        Ok(d) => Some(d),
        Err(e) => {
            eprintln!("Failed to initialize H.264 decoder: {}", e);
            None
        }
    };

    // YUV texture state — created on first decoded frame
    struct YuvTextures {
        y_texture: wgpu::Texture,
        u_texture: wgpu::Texture,
        v_texture: wgpu::Texture,
        bind_group: wgpu::BindGroup,
        width: u32,
        height: u32,
    }
    let mut yuv_textures: Option<YuvTextures> = None;

    // Create the YUV shader pipeline
    let yuv_shader = renderer.device().create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("YUV Shader"),
        source: wgpu::ShaderSource::Wgsl(std::borrow::Cow::Borrowed(include_str!("shader.wgsl"))),
    });

    let yuv_bind_group_layout = renderer.device().create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
        label: Some("YUV Bind Group Layout"),
        entries: &[
            wgpu::BindGroupLayoutEntry {
                binding: 0,
                visibility: wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Texture {
                    multisampled: false,
                    view_dimension: wgpu::TextureViewDimension::D2,
                    sample_type: wgpu::TextureSampleType::Float { filterable: true },
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
                ty: wgpu::BindingType::Texture {
                    multisampled: false,
                    view_dimension: wgpu::TextureViewDimension::D2,
                    sample_type: wgpu::TextureSampleType::Float { filterable: true },
                },
                count: None,
            },
            wgpu::BindGroupLayoutEntry {
                binding: 3,
                visibility: wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                count: None,
            },
        ],
    });

    let yuv_pipeline_layout = renderer.device().create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
        label: Some("YUV Pipeline Layout"),
        bind_group_layouts: &[&yuv_bind_group_layout],
        push_constant_ranges: &[],
    });

    let yuv_pipeline = renderer.device().create_render_pipeline(&wgpu::RenderPipelineDescriptor {
        label: Some("YUV Render Pipeline"),
        layout: Some(&yuv_pipeline_layout),
        vertex: wgpu::VertexState {
            module: &yuv_shader,
            entry_point: "vs_main",
            buffers: &[], // Full-screen triangle from vertex_index, no buffer needed
        },
        fragment: Some(wgpu::FragmentState {
            module: &yuv_shader,
            entry_point: "fs_main",
            targets: &[Some(wgpu::ColorTargetState {
                format: renderer.config().format,
                blend: None,
                write_mask: wgpu::ColorWrites::ALL,
            })],
        }),
        primitive: wgpu::PrimitiveState {
            topology: wgpu::PrimitiveTopology::TriangleList,
            strip_index_format: None,
            front_face: wgpu::FrontFace::Ccw,
            cull_mode: None,
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

    let yuv_sampler = renderer.device().create_sampler(&wgpu::SamplerDescriptor {
        address_mode_u: wgpu::AddressMode::ClampToEdge,
        address_mode_v: wgpu::AddressMode::ClampToEdge,
        address_mode_w: wgpu::AddressMode::ClampToEdge,
        mag_filter: wgpu::FilterMode::Linear,
        min_filter: wgpu::FilterMode::Linear,
        mipmap_filter: wgpu::FilterMode::Nearest,
        ..Default::default()
    });

    // Helper to create a R8Unorm texture of the given size
    let create_r8_texture = |device: &wgpu::Device, label: &str, w: u32, h: u32| -> wgpu::Texture {
        device.create_texture(&wgpu::TextureDescriptor {
            label: Some(label),
            size: wgpu::Extent3d { width: w, height: h, depth_or_array_layers: 1 },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::R8Unorm,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            view_formats: &[],
        })
    };

    let mut mouse_pos = (0.0, 0.0);
    let is_kiosk = kiosk::is_kiosk_mode();
    let mut is_focused = true;
    let mut mouse_buttons = 0u32;
    let mut current_modifiers = winit::keyboard::ModifiersState::empty();

    // Stats for the overlay
    let mut decode_fps_counter = 0u32;
    let mut decode_fps_last_time = std::time::Instant::now();
    let mut decode_fps_display = 0u32;
    let mut last_decode_us = 0u64;
    let mut has_video = false;

    let window_id = window.id();
    event_loop.run(move |event, elwt| {
        elwt.set_control_flow(ControlFlow::Wait);

        match &event {
            Event::WindowEvent { window_id: id, event: w_event } if *id == window_id => {
                if overlay_ui.handle_event(&window, w_event) {
                    return; // event consumed by egui
                }

                match w_event {
                    WindowEvent::CloseRequested => {
                        if !is_kiosk {
                            elwt.exit();
                        }
                    }
                    WindowEvent::Focused(focused) => {
                        is_focused = *focused;
                    }
                    WindowEvent::ModifiersChanged(modifiers) => {
                        current_modifiers = modifiers.state();
                    }
                    WindowEvent::MouseInput { state, button, .. } => {
                        if is_focused {
                            let bit = match button {
                                winit::event::MouseButton::Left => 1,
                                winit::event::MouseButton::Right => 2,
                                winit::event::MouseButton::Middle => 4,
                                _ => 0,
                            };
                            if *state == ElementState::Pressed {
                                mouse_buttons |= bit;
                            } else {
                                mouse_buttons &= !bit;
                            }
                            let inner_size = window.inner_size();
                            let wire_mods = zc_input::winit_modifiers_to_wire(&current_modifiers);
                            let ev = zc_input::create_mouse_event(mouse_pos.0, mouse_pos.1, inner_size.width, inner_size.height, mouse_buttons, wire_mods);
                            let mut serialized = Vec::new();
                            if ev.encode(&mut serialized).is_ok() {
                                let mut buf = input_buffer_for_poll.lock().unwrap();
                                if buf.len() >= INPUT_BUFFER_MAX { buf.pop_front(); }
                                buf.push_back(serialized);
                            }
                        }
                    }
                    WindowEvent::MouseWheel { delta, .. } => {
                        if is_focused {
                            let (v_scroll, h_scroll) = match delta {
                                MouseScrollDelta::LineDelta(h, v) => (*v, *h),
                                MouseScrollDelta::PixelDelta(pos) => {
                                    // Convert pixel delta to line delta (approximate)
                                    (pos.y as f32 / 120.0, pos.x as f32 / 120.0)
                                }
                            };
                            let inner_size = window.inner_size();
                            let wire_mods = zc_input::winit_modifiers_to_wire(&current_modifiers);
                            let ev = zc_input::create_scroll_event(
                                mouse_pos.0, mouse_pos.1,
                                inner_size.width, inner_size.height,
                                v_scroll, h_scroll, wire_mods,
                            );
                            let mut serialized = Vec::new();
                            if ev.encode(&mut serialized).is_ok() {
                                let mut buf = input_buffer_for_poll.lock().unwrap();
                                if buf.len() >= INPUT_BUFFER_MAX { buf.pop_front(); }
                                buf.push_back(serialized);
                            }
                        }
                    }
                    WindowEvent::KeyboardInput {
                        event: key_event,
                        ..
                    } => {
                        if key_event.physical_key == PhysicalKey::Code(KeyCode::Escape) && key_event.state == ElementState::Pressed {
                            if !is_kiosk {
                                println!("Escape pressed, exiting gracefully...");
                                elwt.exit();
                            }
                        }
                        if is_focused {
                            let mut keycode = 0u32;
                            if let winit::keyboard::PhysicalKey::Code(code) = key_event.physical_key {
                                keycode = code as u32;
                            }
                            let wire_mods = zc_input::winit_modifiers_to_wire(&current_modifiers);
                            let ev = zc_input::create_keyboard_event(keycode, key_event.state == ElementState::Pressed, wire_mods);
                            let mut serialized = Vec::new();
                            if ev.encode(&mut serialized).is_ok() {
                                let mut buf = input_buffer_for_poll.lock().unwrap();
                                if buf.len() >= INPUT_BUFFER_MAX { buf.pop_front(); }
                                buf.push_back(serialized);
                            }
                        }
                    }
                    WindowEvent::CursorMoved { position, .. } => {
                        mouse_pos = (position.x, position.y);
                        if is_focused {
                            let inner_size = window.inner_size();
                            let wire_mods = zc_input::winit_modifiers_to_wire(&current_modifiers);
                            let ev = zc_input::create_mouse_event(mouse_pos.0, mouse_pos.1, inner_size.width, inner_size.height, mouse_buttons, wire_mods);
                            let mut serialized = Vec::new();
                            if ev.encode(&mut serialized).is_ok() {
                                let mut buf = input_buffer_for_poll.lock().unwrap();
                                if buf.len() >= INPUT_BUFFER_MAX { buf.pop_front(); }
                                buf.push_back(serialized);
                            }
                        }
                    }
                    WindowEvent::Resized(physical_size) => {
                        renderer.resize(*physical_size);
                    }
                    WindowEvent::RedrawRequested => {
                        let inner_size = window.inner_size();
                        if inner_size.width == 0 || inner_size.height == 0 {
                            return;
                        }

                        // Drain all pending frames, decode and upload the latest
                        while let Ok(hybrid) = frame_rx.try_recv() {
                            match hybrid.payload {
                                Some(hybrid_frame::Payload::Video(frame)) => {
                                    if let Some(ref mut decoder) = h264_decoder {
                                        let decode_start = std::time::Instant::now();
                                        match decoder.decode(&frame.nal_data) {
                                            Ok(Some(decoded)) => {
                                                last_decode_us = decode_start.elapsed().as_micros() as u64;
                                                has_video = true;

                                                let w = decoded.width;
                                                let h = decoded.height;
                                                let cw = (w + 1) / 2; // chroma width
                                                let ch = (h + 1) / 2; // chroma height

                                                // Recreate textures if size changed
                                                let needs_recreate = match &yuv_textures {
                                                    Some(t) => t.width != w || t.height != h,
                                                    None => true,
                                                };

                                                if needs_recreate {
                                                    let y_tex = create_r8_texture(renderer.device(), "Y Plane", w, h);
                                                    let u_tex = create_r8_texture(renderer.device(), "U Plane", cw, ch);
                                                    let v_tex = create_r8_texture(renderer.device(), "V Plane", cw, ch);

                                                    let y_view = y_tex.create_view(&wgpu::TextureViewDescriptor::default());
                                                    let u_view = u_tex.create_view(&wgpu::TextureViewDescriptor::default());
                                                    let v_view = v_tex.create_view(&wgpu::TextureViewDescriptor::default());

                                                    let bind_group = renderer.device().create_bind_group(&wgpu::BindGroupDescriptor {
                                                        label: Some("YUV Bind Group"),
                                                        layout: &yuv_bind_group_layout,
                                                        entries: &[
                                                            wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(&y_view) },
                                                            wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::TextureView(&u_view) },
                                                            wgpu::BindGroupEntry { binding: 2, resource: wgpu::BindingResource::TextureView(&v_view) },
                                                            wgpu::BindGroupEntry { binding: 3, resource: wgpu::BindingResource::Sampler(&yuv_sampler) },
                                                        ],
                                                    });

                                                    yuv_textures = Some(YuvTextures {
                                                        y_texture: y_tex,
                                                        u_texture: u_tex,
                                                        v_texture: v_tex,
                                                        bind_group,
                                                        width: w,
                                                        height: h,
                                                    });
                                                }

                                                // Upload YUV planes
                                                if let Some(ref textures) = yuv_textures {
                                                    // Y plane
                                                    renderer.queue().write_texture(
                                                        wgpu::ImageCopyTexture {
                                                            texture: &textures.y_texture,
                                                            mip_level: 0,
                                                            origin: wgpu::Origin3d::ZERO,
                                                            aspect: wgpu::TextureAspect::All,
                                                        },
                                                        &decoded.y,
                                                        wgpu::ImageDataLayout {
                                                            offset: 0,
                                                            bytes_per_row: Some(w),
                                                            rows_per_image: Some(h),
                                                        },
                                                        wgpu::Extent3d { width: w, height: h, depth_or_array_layers: 1 },
                                                    );
                                                    // U plane
                                                    renderer.queue().write_texture(
                                                        wgpu::ImageCopyTexture {
                                                            texture: &textures.u_texture,
                                                            mip_level: 0,
                                                            origin: wgpu::Origin3d::ZERO,
                                                            aspect: wgpu::TextureAspect::All,
                                                        },
                                                        &decoded.u,
                                                        wgpu::ImageDataLayout {
                                                            offset: 0,
                                                            bytes_per_row: Some(cw),
                                                            rows_per_image: Some(ch),
                                                        },
                                                        wgpu::Extent3d { width: cw, height: ch, depth_or_array_layers: 1 },
                                                    );
                                                    // V plane
                                                    renderer.queue().write_texture(
                                                        wgpu::ImageCopyTexture {
                                                            texture: &textures.v_texture,
                                                            mip_level: 0,
                                                            origin: wgpu::Origin3d::ZERO,
                                                            aspect: wgpu::TextureAspect::All,
                                                        },
                                                        &decoded.v,
                                                        wgpu::ImageDataLayout {
                                                            offset: 0,
                                                            bytes_per_row: Some(cw),
                                                            rows_per_image: Some(ch),
                                                        },
                                                        wgpu::Extent3d { width: cw, height: ch, depth_or_array_layers: 1 },
                                                    );
                                                }

                                                // FPS counter
                                                decode_fps_counter += 1;
                                                let now = std::time::Instant::now();
                                                if now.duration_since(decode_fps_last_time).as_secs() >= 1 {
                                                    decode_fps_display = decode_fps_counter;
                                                    decode_fps_counter = 0;
                                                    decode_fps_last_time = now;
                                                }
                                            }
                                            Ok(None) => { /* NAL consumed, no picture */ }
                                            Err(e) => {
                                                eprintln!("H.264 decode error: {}", e);
                                                let ev = zc_input::create_keyframe_request();
                                                let mut serialized = Vec::new();
                                                if prost::Message::encode(&ev, &mut serialized).is_ok() {
                                                    let mut buf = input_buffer_for_poll.lock().unwrap();
                                                    if buf.len() >= INPUT_BUFFER_MAX { buf.pop_front(); }
                                                    buf.push_back(serialized);
                                                }
                                            }
                                        }
                                    }
                                }
                                // Tile and Cursor variants no longer exist in the proto,
                                // but handle gracefully if they ever appear.
                                _ => {}
                            }
                        }

                        match renderer.get_target_view() {
                            Ok((frame, view)) => {
                                // 1. Render decoded video (YUV→RGB via shader)
                                {
                                    let mut encoder = renderer.device().create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("Render Encoder") });
                                    {
                                        let mut rpass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                                            label: Some("Video Render Pass"),
                                            color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                                                view: &view,
                                                resolve_target: None,
                                                ops: wgpu::Operations {
                                                    load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                                                    store: wgpu::StoreOp::Store,
                                                },
                                            })],
                                            depth_stencil_attachment: None,
                                            timestamp_writes: None,
                                            occlusion_query_set: None,
                                        });
                                        if let Some(ref textures) = yuv_textures {
                                            rpass.set_pipeline(&yuv_pipeline);
                                            rpass.set_bind_group(0, &textures.bind_group, &[]);
                                            rpass.draw(0..3, 0..1); // Full-screen triangle
                                        }
                                    }
                                    renderer.queue().submit(std::iter::once(encoder.finish()));
                                }
                                
                                // 2. Render Overlay on top
                                {
                                    let mut encoder = renderer.device().create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("Overlay Encoder") });
                                    let phase = connection_phase.lock().unwrap().clone();
                                    overlay_ui.render(
                                        &window,
                                        renderer.device(),
                                        renderer.queue(),
                                        &view,
                                        &mut encoder,
                                        &phase,
                                        mouse_pos,
                                        has_video,
                                        is_kiosk,
                                        decode_fps_display,
                                        last_decode_us,
                                    );
                                    renderer.queue().submit(std::iter::once(encoder.finish()));
                                }
                                
                                frame.present();
                            }
                            Err(wgpu::SurfaceError::Lost | wgpu::SurfaceError::Outdated) => {
                                eprintln!("SurfaceError: Lost or Outdated, resizing...");
                                renderer.resize(window.inner_size());
                            }
                            Err(wgpu::SurfaceError::OutOfMemory) => {
                                eprintln!("SurfaceError: OutOfMemory, exiting...");
                                elwt.exit();
                            }
                            Err(e) => eprintln!("SurfaceError: {:?}", e),
                        }
                    }
                    _ => (),
                }
            }
            Event::AboutToWait => {
                window.request_redraw();
            }
            _ => (),
        }
    }).unwrap();
}
