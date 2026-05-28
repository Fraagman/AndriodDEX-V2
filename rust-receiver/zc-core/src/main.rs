mod renderer;

use winit::{
    event::{Event, WindowEvent},
    event_loop::{ControlFlow, EventLoop},
    window::WindowBuilder,
};
use zc_protocol::protocol::Ping;
use std::time::Instant;

fn main() {
    let ping = Ping { timestamp: 0 };
    println!("{:?}", ping);

    let rt = tokio::runtime::Runtime::new().expect("Failed to build tokio runtime");
    let handle = rt.handle().clone();
    
    rt.spawn(async move {
        match zc_network::connect("192.168.42.129", 4433).await {
            Ok(conn) => {
                println!("Connected to Android host");
                if let Ok(mut stream) = conn.open_uni().await {
                    std::thread::spawn(move || {
                        use prost::Message;
                        let mut capturer = zc_input::InputCapturer::new().unwrap();
                        
                        loop {
                            let mut buf = Vec::new();
                            
                            if let Ok(mouse) = capturer.poll_mouse() {
                                buf.push(0u8); // 0 for MouseEvent
                                let mut encoded = Vec::new();
                                mouse.encode(&mut encoded).unwrap();
                                buf.extend_from_slice(&(encoded.len() as u32).to_le_bytes());
                                buf.extend_from_slice(&encoded);
                            }
                            
                            if let Ok(Some(kbd)) = capturer.poll_keyboard() {
                                buf.push(1u8); // 1 for KeyboardEvent
                                let mut encoded = Vec::new();
                                kbd.encode(&mut encoded).unwrap();
                                buf.extend_from_slice(&(encoded.len() as u32).to_le_bytes());
                                buf.extend_from_slice(&encoded);
                            }
                            
                            if !buf.is_empty() {
                                let res = handle.block_on(async {
                                    stream.write_all(&buf).await
                                });
                                if res.is_err() {
                                    println!("Connection lost, stopping input thread.");
                                    break;
                                }
                            }
                            
                            std::thread::sleep(std::time::Duration::from_millis(8));
                        }
                    });
                }
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
                let time = start_time.elapsed().as_secs_f32();
                match renderer.render(time) {
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
