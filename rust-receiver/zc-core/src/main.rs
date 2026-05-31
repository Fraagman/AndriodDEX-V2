mod renderer;
mod ui;
mod kiosk;

use winit::{
    event::{Event, WindowEvent, ElementState},
    event_loop::{ControlFlow, EventLoop},
    window::WindowBuilder,
    keyboard::{KeyCode, PhysicalKey},
};
use zc_protocol::protocol::Ping;
use zc_protocol::video::{VideoFrame, HybridFrame, hybrid_frame};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex, atomic::{AtomicBool, Ordering}};
use std::time::Instant;
use prost::Message;

const INPUT_BUFFER_MAX: usize = 1000;

fn main() {
    if cfg!(windows) {
        let exe_path = std::env::current_exe().unwrap_or_else(|_| std::path::PathBuf::from("AndroidDex.exe"));
        let exe_str = exe_path.to_str().unwrap_or("AndroidDex.exe");
        let command_str = format!("netsh advfirewall firewall add rule name=\"AndroidDex QUIC\" dir=out action=allow protocol=udp localport=any remoteport=4433 program=\"{}\" enable=yes", exe_str);
        
        let _ = std::process::Command::new("powershell")
            .args(&["-Command", &command_str])
            .spawn();
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

    // Clone for the QUIC task
    let input_buffer_for_quic = input_buffer.clone();
    let phase_for_quic = connection_phase.clone();
    let is_connected_quic = is_connected.clone();
    
    rt.spawn(async move {
        loop {
            let phase_clone = phase_for_quic.clone();
            let frame_tx_loop = frame_tx.clone();
            let audio_sender_loop = audio_sender.clone();
            let input_buffer_loop = input_buffer_for_quic.clone();
            let is_connected_loop = is_connected_quic.clone();

            match zc_network::connect(4433, move |phase| {
                *phase_clone.lock().unwrap() = phase.clone();
                if matches!(phase, zc_network::ConnectionPhase::Connected) {
                    println!("ConnectionPhase updated to Connected");
                }
            }).await {
                Ok(conn) => {
                    println!("Connected to Android server");
                    is_connected_loop.store(true, Ordering::SeqCst);

                    let conn_media = conn.clone();

                    tokio::spawn(async move {
                        loop {
                            if let Ok(mut stream) = conn_media.accept_uni().await {
                                let frame_tx_inner = frame_tx_loop.clone();
                                let ap_inner = audio_sender_loop.clone();
                                tokio::spawn(async move {
                                    let mut len_buf = [0u8; 4];
                                    if stream.read_exact(&mut len_buf).await.is_err() {
                                        return;
                                    }
                                    let len = u32::from_le_bytes(len_buf) as usize;
                                    let mut frame_buf = vec![0u8; len];
                                    if stream.read_exact(&mut frame_buf).await.is_err() {
                                        return;
                                    }

                                    if frame_buf.is_empty() { return; }

                                    if frame_buf[0] != 0 {
                                        if let Ok(frame) = HybridFrame::decode(&frame_buf[..]) {
                                            let _ = frame_tx_inner.send(frame);
                                        } else {
                                            eprintln!("Failed to decode HybridFrame");
                                        }
                                    } else if frame_buf[0] == 0 {
                                        // Audio frame has 4-byte BE length prefix
                                        if frame_buf.len() > 4 {
                                            let packet_len = u32::from_be_bytes(frame_buf[0..4].try_into().unwrap()) as usize;
                                            if packet_len <= frame_buf.len() - 4 {
                                                if let Ok(audio_packet) = zc_protocol::audio::AudioPacket::decode(&frame_buf[4..4+packet_len]) {
                                                    if let Some(player) = ap_inner {
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
                                });
                            } else {
                                break;
                            }
                        }
                    });

                    // Open a dedicated unidirectional stream for INPUT events (PC -> Android)
                    match conn.open_uni().await {
                        Ok(mut input_stream) => {
                            println!("Opened input stream to server");

                            // Drain the buffer and send input events continuously
                            loop {
                                let events: Vec<Vec<u8>> = {
                                    let mut buf = input_buffer_loop.lock().unwrap();
                                    buf.drain(..).collect()
                                };

                                if events.is_empty() {
                                    tokio::time::sleep(std::time::Duration::from_millis(8)).await;
                                    continue;
                                }

                                for serialized in events {
                                    let len = serialized.len() as u32;
                                    if input_stream.write_all(&len.to_le_bytes()).await.is_err() {
                                        eprintln!("Input stream write failed (length)");
                                        is_connected_loop.store(false, Ordering::SeqCst);
                                        break;
                                    }
                                    if input_stream.write_all(&serialized).await.is_err() {
                                        eprintln!("Input stream write failed (data)");
                                        is_connected_loop.store(false, Ordering::SeqCst);
                                        break;
                                    }
                                }
                                if !is_connected_loop.load(Ordering::SeqCst) {
                                    break;
                                }
                            }
                        }
                        Err(e) => {
                            eprintln!("Failed to open input stream: {}", e);
                            is_connected_loop.store(false, Ordering::SeqCst);
                        }
                    }
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

    // Spawn the input polling thread: reads real mouse/keyboard and pushes to the buffer
    let input_buffer_for_poll = input_buffer.clone();
    std::thread::spawn(move || {
        let mut capturer = match zc_input::InputCapturer::new() {
            Ok(c) => c,
            Err(e) => {
                eprintln!("Failed to create InputCapturer: {}", e);
                return;
            }
        };

        loop {
            match capturer.poll_all() {
                Ok(events) => {
                    let mut buf = input_buffer_for_poll.lock().unwrap();
                    for event in events {
                        let mut serialized = Vec::new();
                        if event.encode(&mut serialized).is_ok() {
                            if buf.len() >= INPUT_BUFFER_MAX {
                                buf.pop_front(); // drop oldest event
                            }
                            buf.push_back(serialized);
                        }
                    }
                }
                Err(e) => {
                    eprintln!("Input poll error: {}", e);
                }
            }
            std::thread::sleep(std::time::Duration::from_millis(8)); // ~120Hz polling
        }
    });

    let event_loop = EventLoop::new().unwrap();

    let window = std::sync::Arc::new(WindowBuilder::new()
        .with_title("AndroidDex Receiver")
        .with_decorations(true)
        .build(&event_loop)
        .unwrap());

    let mut renderer = pollster::block_on(renderer::Renderer::new(window.clone()));
    
    // Initialize egui overlay
    let mut overlay_ui = ui::overlay::OverlayUi::new(
        renderer.device(),
        renderer.config().format,
        &window,
    );

    let mut video_decoder: Option<zc_video::VideoDecoder> = None;
    let start_time = Instant::now();
    let mut mouse_pos = (0.0, 0.0);
    let mut last_is_vm = false;
    let is_kiosk = kiosk::is_kiosk_mode();

    let mut tile_compositor = zc_video::TileCompositor::new(
        renderer.device(),
        renderer.config().format,
        0,
        0,
    );

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
                    }
                    WindowEvent::CursorMoved { position, .. } => {
                        mouse_pos = (position.x, position.y);
                    }
                    WindowEvent::Resized(physical_size) => {
                        renderer.resize(*physical_size);
                    }
                    WindowEvent::RedrawRequested => {
                        let inner_size = window.inner_size();
                        if inner_size.width == 0 || inner_size.height == 0 {
                            return;
                        }
                        while let Ok(hybrid) = frame_rx.try_recv() {
                            match hybrid.payload {
                                Some(hybrid_frame::Payload::Video(frame)) => {
                                    last_is_vm = frame.source == 1;
                                    
                                    tile_compositor.set_base_size(frame.width, frame.height);

                                    let recreate = match video_decoder.as_ref() {
                                        Some(decoder) => decoder.width() != frame.width || decoder.height() != frame.height,
                                        None => true,
                                    };
                                    
                                    if recreate {
                                        video_decoder = Some(zc_video::VideoDecoder::new(renderer.device(), renderer.queue(), frame.width, frame.height));
                                    }
                                    
                                    if let Some(decoder) = video_decoder.as_mut() {
                                        decoder.decode_and_upload(renderer.queue(), frame);
                                    }
                                }
                                Some(hybrid_frame::Payload::Tile(tile)) => {
                                    tile_compositor.apply_tile(tile, renderer.device(), renderer.queue());
                                }
                                Some(hybrid_frame::Payload::Cursor(cursor)) => {
                                    tile_compositor.update_cursor(cursor, renderer.device(), renderer.queue());
                                }
                                None => {}
                            }
                        }

                        let _time = start_time.elapsed().as_secs_f32();
                        let view_opt = video_decoder.as_ref().map(|d| &d.texture_view);

                        match renderer.get_target_view() {
                            Ok((frame, view)) => {
                                let _bind_group = view_opt.map(|v| renderer.create_bind_group(v));

                                // 1. Render Video
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
                                        if let Some(decoder) = video_decoder.as_ref() {
                                            tile_compositor.composite(&decoder.texture, &mut rpass, renderer.device(), renderer.queue());
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
                                        last_is_vm,
                                        is_kiosk,
                                    );
                                    renderer.queue().submit(std::iter::once(encoder.finish()));
                                }
                                
                                frame.present();
                            }
                            Err(wgpu::SurfaceError::Lost | wgpu::SurfaceError::Outdated) => renderer.resize(window.inner_size()),
                            Err(wgpu::SurfaceError::OutOfMemory) => elwt.exit(),
                            Err(e) => eprintln!("{:?}", e),
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
