use winit::{
    event::{Event, WindowEvent},
    event_loop::{ControlFlow, EventLoop},
    window::WindowBuilder,
};
use zc_protocol::protocol::Ping;

fn main() {
    let ping = Ping { timestamp: 0 };
    println!("{:?}", ping);

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
