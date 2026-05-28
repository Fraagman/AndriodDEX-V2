use winit::{
    event::{Event, WindowEvent},
    event_loop::{ControlFlow, EventLoop},
    window::WindowBuilder,
};
use zc_protocol::protocol::Ping;

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
    let window = WindowBuilder::new()
        .with_decorations(false)
        .build(&event_loop)
        .unwrap();

    event_loop.run(move |event, elwt| {
        elwt.set_control_flow(ControlFlow::Wait);

        match event {
            Event::WindowEvent {
                event: WindowEvent::CloseRequested,
                window_id,
            } if window_id == window.id() => {
                elwt.exit();
            }
            _ => (),
        }
    }).unwrap();
}
