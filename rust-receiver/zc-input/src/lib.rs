use zc_protocol::protocol::{InputEvent, KeyboardEvent, MouseEvent, ScrollEvent, input_event};
use std::time::{SystemTime, UNIX_EPOCH};

pub const VIRTUAL_WIDTH: u32 = 1920;
pub const VIRTUAL_HEIGHT: u32 = 1080;

pub fn create_mouse_event(
    x: f64,
    y: f64,
    window_width: u32,
    window_height: u32,
    buttons: u32,
    modifiers: u32,
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
            modifiers,
        })),
    }
}

pub fn create_keyboard_event(keycode: u32, pressed: bool, modifiers: u32) -> InputEvent {
    let timestamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64;
    InputEvent {
        event: Some(input_event::Event::Keyboard(KeyboardEvent {
            keycode,
            pressed,
            modifiers,
            timestamp,
        })),
    }
}

pub fn create_keyframe_request() -> InputEvent {
    InputEvent {
        event: Some(input_event::Event::RequestKeyframe(zc_protocol::protocol::KeyframeRequest {})),
    }
}

pub fn create_scroll_event(
    x: f64,
    y: f64,
    window_width: u32,
    window_height: u32,
    v_scroll: f32,
    h_scroll: f32,
    modifiers: u32,
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
        event: Some(input_event::Event::Scroll(ScrollEvent {
            x: vx.min(VIRTUAL_WIDTH - 1),
            y: vy.min(VIRTUAL_HEIGHT - 1),
            v_scroll,
            h_scroll,
            timestamp,
            modifiers,
        })),
    }
}

/// Converts winit modifier state into the wire modifier bitmask
/// matching the `Modifier` enum in `input.proto`.
pub fn winit_modifiers_to_wire(modifiers: &winit::keyboard::ModifiersState) -> u32 {
    let mut wire: u32 = 0;
    if modifiers.shift_key() { wire |= 1; }  // MODIFIER_SHIFT
    if modifiers.control_key() { wire |= 2; }  // MODIFIER_CTRL
    if modifiers.alt_key() { wire |= 4; }  // MODIFIER_ALT
    if modifiers.super_key() { wire |= 8; }  // MODIFIER_SUPER
    wire
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_create_mouse_event_scaling() {
        // Zero window dimension guards return 0
        let zero_w = create_mouse_event(500.0, 300.0, 0, 600, 1, 0);
        if let Some(input_event::Event::Mouse(m)) = zero_w.event {
            assert_eq!(m.x, 0);
            assert_eq!(m.y, 540);
        } else {
            panic!("Expected MouseEvent");
        }

        let zero_h = create_mouse_event(500.0, 300.0, 800, 0, 1, 0);
        if let Some(input_event::Event::Mouse(m)) = zero_h.event {
            assert_eq!(m.x, 1200);
            assert_eq!(m.y, 0);
        } else {
            panic!("Expected MouseEvent");
        }

        // Midpoint scaling: 800x600 window, mouse at (400.0, 300.0) -> (960, 540)
        let mid = create_mouse_event(400.0, 300.0, 800, 600, 1, 0);
        if let Some(input_event::Event::Mouse(m)) = mid.event {
            assert_eq!(m.x, 960);
            assert_eq!(m.y, 540);
        } else {
            panic!("Expected MouseEvent");
        }

        // Clamp at VIRTUAL_WIDTH - 1 (1919) and VIRTUAL_HEIGHT - 1 (1079)
        let clamped = create_mouse_event(1600.0, 1200.0, 800, 600, 1, 0);
        if let Some(input_event::Event::Mouse(m)) = clamped.event {
            assert_eq!(m.x, VIRTUAL_WIDTH - 1);
            assert_eq!(m.y, VIRTUAL_HEIGHT - 1);
        } else {
            panic!("Expected MouseEvent");
        }
    }

    #[test]
    fn test_create_scroll_event_scaling() {
        // Zero window dimension returns 0
        let zero = create_scroll_event(400.0, 300.0, 0, 0, 1.0, 0.0, 0);
        if let Some(input_event::Event::Scroll(s)) = zero.event {
            assert_eq!(s.x, 0);
            assert_eq!(s.y, 0);
            assert_eq!(s.v_scroll, 1.0);
        } else {
            panic!("Expected ScrollEvent");
        }

        // Midpoint
        let mid = create_scroll_event(400.0, 300.0, 800, 600, -1.0, 2.0, 0);
        if let Some(input_event::Event::Scroll(s)) = mid.event {
            assert_eq!(s.x, 960);
            assert_eq!(s.y, 540);
            assert_eq!(s.v_scroll, -1.0);
            assert_eq!(s.h_scroll, 2.0);
        } else {
            panic!("Expected ScrollEvent");
        }

        // Clamping
        let clamped = create_scroll_event(2000.0, 2000.0, 800, 600, 0.0, 0.0, 0);
        if let Some(input_event::Event::Scroll(s)) = clamped.event {
            assert_eq!(s.x, VIRTUAL_WIDTH - 1);
            assert_eq!(s.y, VIRTUAL_HEIGHT - 1);
        } else {
            panic!("Expected ScrollEvent");
        }
    }
}
