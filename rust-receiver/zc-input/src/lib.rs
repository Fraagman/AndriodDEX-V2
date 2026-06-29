use zc_protocol::protocol::{InputEvent, KeyboardEvent, MouseEvent, input_event};
use std::time::{SystemTime, UNIX_EPOCH};

pub const VIRTUAL_WIDTH: u32 = 1920;
pub const VIRTUAL_HEIGHT: u32 = 1080;

pub fn create_mouse_event(
    x: f64,
    y: f64,
    window_width: u32,
    window_height: u32,
    buttons: u32,
) -> InputEvent {
    let vx = if window_width > 0 {
        ((x.max(0.0) as u32) * VIRTUAL_WIDTH) / window_width
    } else {
        0
    };
    let vy = if window_height > 0 {
        ((y.max(0.0) as u32) * VIRTUAL_HEIGHT) / window_height
    } else {
        0
    };

    let timestamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64;

    InputEvent {
        event: Some(input_event::Event::Mouse(MouseEvent {
            x: vx.min(VIRTUAL_WIDTH - 1),
            y: vy.min(VIRTUAL_HEIGHT - 1),
            buttons,
            timestamp,
        })),
    }
}

pub fn create_keyboard_event(keycode: u32, pressed: bool) -> InputEvent {
    let timestamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64;
    InputEvent {
        event: Some(input_event::Event::Keyboard(KeyboardEvent {
            keycode,
            pressed,
            modifiers: 0,
            timestamp,
        })),
    }
}
