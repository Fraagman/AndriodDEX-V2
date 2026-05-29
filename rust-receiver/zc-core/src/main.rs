mod renderer;

use winit::{
    event::{Event, WindowEvent},
    event_loop::{ControlFlow, EventLoop},
    window::WindowBuilder,
};
use zc_protocol::protocol::{InputEvent, Ping};
use zc_protocol::video::VideoFrame;
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::Instant;
use prost::Message;

const INPUT_BUFFER_MAX: usize = 1000;

fn main() {
    let ping = Ping { timestamp: 0 };
    println!("{:?}", ping);

    let (frame_tx, frame_rx) = std::sync::mpsc::channel::<VideoFrame>();

    // Shared state for input sending: holds serialized InputEvent bytes
    // The input polling thread pushes here; the QUIC sender drains from here.
    let input_buffer: Arc<Mutex<VecDeque<Vec<u8>>>> = Arc::new(Mutex::new(VecDeque::with_capacity(INPUT_BUFFER_MAX)));

    let rt = tokio::runtime::Runtime::new().expect("Failed to build tokio runtime");

    // Clone for the QUIC task
    let input_buffer_for_quic = input_buffer.clone();

    rt.spawn(async move {
        match zc_network::connect("127.0.0.1", 4433).await {
            Ok(conn) => {
                println!("Connected to mock server or host");

                // Spawn video receiver on a separate uni stream
                let conn_video = conn.clone();
                tokio::spawn(async move {
                    if let Ok(mut stream) = conn_video.accept_uni().await {
                        println!("Accepted video stream");
                        std::thread::spawn(move || {
                            let rt2 = tokio::runtime::Runtime::new().unwrap();
                            rt2.block_on(async {
                                loop {
                                    let mut len_buf = [0u8; 4];
                                    if stream.read_exact(&mut len_buf).await.is_err() {
                                        break;
                                    }
                                    let len = u32::from_le_bytes(len_buf) as usize;
                                    let mut frame_buf = vec![0u8; len];
                                    if stream.read_exact(&mut frame_buf).await.is_err() {
                                        break;
                                    }

                                    if let Ok(frame) = VideoFrame::decode(&frame_buf[..]) {
                                        let _ = frame_tx.send(frame);
                                    }
                                }
                            });
                        });
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
                                    return;
                                }
                                if input_stream.write_all(&serialized).await.is_err() {
                                    eprintln!("Input stream write failed (data)");
                                    return;
                                }
                            }
                        }
                    }
                    Err(e) => eprintln!("Failed to open input stream: {}", e),
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
        .with_decorations(true)
        .build(&event_loop)
        .unwrap());

    let mut renderer = pollster::block_on(renderer::Renderer::new(window.clone()));
    let mut video_decoder: Option<zc_video::VideoDecoder> = None;
    let start_time = Instant::now();

    let window_id = window.id();
    event_loop.run(move |event, elwt| {
        elwt.set_control_flow(ControlFlow::Wait);

        match event {
            Event::WindowEvent {
                event: WindowEvent::CloseRequested,
                window_id: id,
            } if id == window_id => {
                elwt.exit();
            }
            Event::WindowEvent {
                event: WindowEvent::Resized(physical_size),
                window_id: id,
            } if id == window_id => {
                renderer.resize(physical_size);
            }
            Event::WindowEvent {
                event: WindowEvent::RedrawRequested,
                window_id: id,
            } if id == window_id => {
                while let Ok(frame) = frame_rx.try_recv() {
                    if video_decoder.is_none() {
                        video_decoder = Some(zc_video::VideoDecoder::new(renderer.device(), renderer.queue(), frame.width, frame.height));
                    }
                    if let Some(decoder) = video_decoder.as_mut() {
                        decoder.decode_and_upload(renderer.queue(), frame);
                    }
                }

                let time = start_time.elapsed().as_secs_f32();
                let view = video_decoder.as_ref().map(|d| &d.texture_view);

                match renderer.render(time, view) {
                    Ok(_) => {}
                    Err(wgpu::SurfaceError::Lost | wgpu::SurfaceError::Outdated) => renderer.resize(window.inner_size()),
                    Err(wgpu::SurfaceError::OutOfMemory) => elwt.exit(),
                    Err(e) => eprintln!("{:?}", e),
                }
            }
            Event::AboutToWait => {
                window.request_redraw();
            }
            _ => (),
        }
    }).unwrap();
}
