mod renderer;
mod ui;

use winit::{
    event::{Event, WindowEvent, ElementState},
    event_loop::{ControlFlow, EventLoop},
    window::WindowBuilder,
    keyboard::{KeyCode, PhysicalKey},
};
use zc_protocol::protocol::Ping;
use zc_protocol::video::VideoFrame;
use std::collections::VecDeque;
use std::sync::{Arc, Mutex, atomic::{AtomicBool, Ordering}};
use std::time::Instant;
use prost::Message;

const INPUT_BUFFER_MAX: usize = 1000;

fn main() {
    let ping = Ping { timestamp: 0 };
    println!("{:?}", ping);

    let (frame_tx, frame_rx) = std::sync::mpsc::channel::<VideoFrame>();
    
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
    
    // Connection state
    let is_connected = Arc::new(AtomicBool::new(false));

    let rt = tokio::runtime::Runtime::new().expect("Failed to build tokio runtime");

    // Clone for the QUIC task
    let input_buffer_for_quic = input_buffer.clone();
    let is_connected_quic = is_connected.clone();
    
    let host = std::env::args().nth(1).unwrap_or_else(|| "127.0.0.1".to_string());

    let pairing_pin = Arc::new(Mutex::new(None));
    let pin_clone = pairing_pin.clone();

    rt.spawn(async move {
        match zc_network::connect(&host, 4433, move |pin| {
            println!("PAIRING PIN GENERATED: {}", pin);
            *pin_clone.lock().unwrap() = Some(pin);
        }).await {
            Ok(conn) => {
                println!("Connected to mock server or host");
                is_connected_quic.store(true, Ordering::SeqCst);

                // Spawn media receiver on multiple uni streams
                let conn_media = conn.clone();
                // audio_sender is moved in from outer scope
                let conn_media = conn.clone();

                tokio::spawn(async move {
                    loop {
                        if let Ok(mut stream) = conn_media.accept_uni().await {
                            let frame_tx_clone = frame_tx.clone();
                            let ap_clone = audio_sender.clone();
                            // Keep audio_player alive by cloning the Option (it doesn't implement Clone, wait, we can't clone it easily without Arc.
                            // But wait! audio_player just needs to be held by the parent task so it doesn't get dropped.
                            // tokio::spawn takes ownership of its environment, but it's a loop.
                            // We don't need to move `audio_player` into the inner task. We can just move it into the outer `tokio::spawn`.
                            // Let's just do `let _player_keepalive = &audio_player;` in the outer task so it's captured and moved into the outer task!
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

                                // Distinguish between Video (starts with 0x08) and Audio (starts with 0x00 big-endian length)
                                if frame_buf[0] == 8 {
                                    if let Ok(frame) = VideoFrame::decode(&frame_buf[..]) {
                                        let _ = frame_tx_clone.send(frame);
                                    }
                                } else if frame_buf[0] == 0 {
                                    // Audio frame has 4-byte BE length prefix
                                    if frame_buf.len() > 4 {
                                        let packet_len = u32::from_be_bytes(frame_buf[0..4].try_into().unwrap()) as usize;
                                        if packet_len <= frame_buf.len() - 4 {
                                            if let Ok(audio_packet) = zc_protocol::audio::AudioPacket::decode(&frame_buf[4..4+packet_len]) {
                                                if let Some(player) = ap_clone {
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
                                let mut buf = input_buffer_for_quic.lock().unwrap();
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
                                    is_connected_quic.store(false, Ordering::SeqCst);
                                    return;
                                }
                                if input_stream.write_all(&serialized).await.is_err() {
                                    eprintln!("Input stream write failed (data)");
                                    is_connected_quic.store(false, Ordering::SeqCst);
                                    return;
                                }
                            }
                        }
                    }
                    Err(e) => {
                        eprintln!("Failed to open input stream: {}", e);
                        is_connected_quic.store(false, Ordering::SeqCst);
                    }
                }
            }
            Err(e) => println!("Connection failed: {}", e),
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
                        elwt.exit();
                    }
                    WindowEvent::KeyboardInput {
                        event: key_event,
                        ..
                    } => {
                        if key_event.physical_key == PhysicalKey::Code(KeyCode::Escape) && key_event.state == ElementState::Pressed {
                            println!("Escape pressed, exiting gracefully...");
                            elwt.exit();
                        }
                    }
                    WindowEvent::CursorMoved { position, .. } => {
                        mouse_pos = (position.x, position.y);
                    }
                    WindowEvent::Resized(physical_size) => {
                        renderer.resize(*physical_size);
                    }
                    WindowEvent::RedrawRequested => {
                        while let Ok(frame) = frame_rx.try_recv() {
                            if video_decoder.is_none() {
                                video_decoder = Some(zc_video::VideoDecoder::new(renderer.device(), renderer.queue(), frame.width, frame.height));
                            }
                            if let Some(decoder) = video_decoder.as_mut() {
                                decoder.decode_and_upload(renderer.queue(), frame);
                            }
                        }

                        let time = start_time.elapsed().as_secs_f32();
                        let view_opt = video_decoder.as_ref().map(|d| &d.texture_view);

                        match renderer.get_target_view() {
                            Ok((frame, view)) => {
                                let bind_group = view_opt.map(|v| renderer.create_bind_group(v));

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
                                        renderer.draw_video(&mut rpass, bind_group.as_ref(), time);
                                    }
                                    renderer.queue().submit(std::iter::once(encoder.finish()));
                                }
                                
                                // 2. Render Overlay on top
                                {
                                    let mut encoder = renderer.device().create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("Overlay Encoder") });
                                    overlay_ui.render(
                                        &window,
                                        renderer.device(),
                                        renderer.queue(),
                                        &view,
                                        &mut encoder,
                                        is_connected.load(Ordering::SeqCst),
                                        mouse_pos,
                                        pairing_pin.lock().unwrap().clone(),
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
