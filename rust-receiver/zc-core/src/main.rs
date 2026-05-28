mod renderer;

use winit::{
    event::{Event, WindowEvent},
    event_loop::{ControlFlow, EventLoop},
    window::WindowBuilder,
};
use zc_protocol::protocol::Ping;
use zc_protocol::video::VideoFrame;
use std::time::Instant;
use prost::Message;

fn main() {
    let ping = Ping { timestamp: 0 };
    println!("{:?}", ping);

    let (frame_tx, frame_rx) = std::sync::mpsc::channel::<VideoFrame>();

    let rt = tokio::runtime::Runtime::new().expect("Failed to build tokio runtime");
    let handle = rt.handle().clone();
    
    rt.spawn(async move {
        match zc_network::connect("127.0.0.1", 4433).await {
            Ok(conn) => {
                println!("Connected to mock server or host");
                if let Ok(mut stream) = conn.accept_uni().await {
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
                
                // Existing bidirectional stream for input etc (disabled for video mock test)
            }
            Err(e) => println!("Connection failed: {}", e),
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
                    Err(wgpu::SurfaceError::Lost) => renderer.resize(window.inner_size()),
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
