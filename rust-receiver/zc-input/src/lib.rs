use zc_protocol::protocol::{InputEvent, KeyboardEvent, MouseEvent, NavAction, NavEvent, OpenAppRequest, ScrollEvent, TextEvent, input_event};
use std::time::{SystemTime, UNIX_EPOCH};

pub const VIRTUAL_WIDTH: u32 = 1920;
pub const VIRTUAL_HEIGHT: u32 = 1080;

pub fn create_open_app_event(package_name: String) -> InputEvent {
    InputEvent {
        event: Some(input_event::Event::OpenApp(OpenAppRequest {
            package_name,
        })),
    }
}

pub fn create_nav_event(action: NavAction) -> InputEvent {
    InputEvent {
        event: Some(input_event::Event::Nav(NavEvent {
            action: action as i32,
        })),
    }
}

/// Maps a window-pixel point into the 1920x1080 wire coordinate space through the
/// same letterbox rect the renderer draws the video into (aspect-preserving fit,
/// centered). Clicks on the black bars clamp to the video edge, so the phone still
/// sees a sane coordinate instead of a wild one.
///
/// `video_width`/`video_height` are the *decoded frame* dimensions, not the wire
/// space: the phone may stream a capture whose aspect differs from 16:9, and the
/// on-screen rect must match what the shader actually draws.
pub fn letterbox_point(
    x: f64,
    y: f64,
    window_width: u32,
    window_height: u32,
    video_width: u32,
    video_height: u32,
) -> (u32, u32) {
    if window_width == 0 || window_height == 0 || video_width == 0 || video_height == 0 {
        return (0, 0);
    }
    let win_w = window_width as f64;
    let win_h = window_height as f64;
    let scale = (win_w / video_width as f64).min(win_h / video_height as f64);
    let disp_w = video_width as f64 * scale;
    let disp_h = video_height as f64 * scale;
    let ox = (win_w - disp_w) / 2.0;
    let oy = (win_h - disp_h) / 2.0;
    let rx = ((x.max(0.0) - ox).clamp(0.0, disp_w - 1.0) / disp_w) * VIRTUAL_WIDTH as f64;
    let ry = ((y.max(0.0) - oy).clamp(0.0, disp_h - 1.0) / disp_h) * VIRTUAL_HEIGHT as f64;
    (
        (rx as u32).min(VIRTUAL_WIDTH - 1),
        (ry as u32).min(VIRTUAL_HEIGHT - 1),
    )
}

pub fn create_mouse_event(
    x: f64,
    y: f64,
    window_width: u32,
    window_height: u32,
    video_width: u32,
    video_height: u32,
    buttons: u32,
    modifiers: u32,
) -> InputEvent {
    let (vx, vy) = letterbox_point(x, y, window_width, window_height, video_width, video_height);

    let timestamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64;

    InputEvent {
        event: Some(input_event::Event::Mouse(MouseEvent {
            x: vx,
            y: vy,
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

pub fn create_text_event(text: String) -> InputEvent {
    let timestamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64;
    InputEvent {
        event: Some(input_event::Event::Text(TextEvent {
            text,
            timestamp,
        })),
    }
}

pub fn create_scroll_event(
    x: f64,
    y: f64,
    window_width: u32,
    window_height: u32,
    video_width: u32,
    video_height: u32,
    v_scroll: f32,
    h_scroll: f32,
    modifiers: u32,
) -> InputEvent {
    let (vx, vy) = letterbox_point(x, y, window_width, window_height, video_width, video_height);

    let timestamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64;

    InputEvent {
        event: Some(input_event::Event::Scroll(ScrollEvent {
            x: vx,
            y: vy,
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

pub fn should_route_as_text(pressed: bool, text: Option<&str>, modifiers: &winit::keyboard::ModifiersState) -> bool {
    if !pressed {
        return false;
    }
    if modifiers.control_key() || modifiers.alt_key() || modifiers.super_key() {
        return false;
    }
    if let Some(s) = text {
        if s.is_empty() {
            return false;
        }
        for c in s.chars() {
            if c < '\x20' || c == '\x7F' {
                return false;
            }
        }
        true
    } else {
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use winit::keyboard::ModifiersState;

    #[test]
    fn test_letterbox_point_16_by_9_window_is_identity() {
        // Window and frame share the 16:9 aspect: the rect fills the window, so
        // the mapping degenerates to the old full-window scaling.
        let (x, y) = letterbox_point(960.0, 540.0, 1920, 1080, 1920, 1080);
        assert_eq!(x, 960);
        assert_eq!(y, 540);
        let (x, y) = letterbox_point(0.0, 0.0, 1920, 1080, 1920, 1080);
        assert_eq!(x, 0);
        assert_eq!(y, 0);
    }

    #[test]
    fn test_letterbox_point_letterboxed_window() {
        // 800x600 window, 1920x1080 frame: scale = min(800/1920, 600/1080) = 0.41666..,
        // rect = 800x450 centred at y=75. Window y=0..75 is a black bar that must
        // clamp to the top edge of the video.
        let (x, y) = letterbox_point(400.0, 300.0, 800, 600, 1920, 1080);
        assert_eq!(x, 960); // midpoint x stays the midpoint
        assert_eq!(y, 540); // (300-75)/450 = 0.5 -> 540
        let (x, y) = letterbox_point(400.0, 0.0, 800, 600, 1920, 1080);
        assert_eq!(y, 0); // top bar clamps to video top
        let (_, y) = letterbox_point(400.0, 600.0, 800, 600, 1920, 1080);
        // bottom bar clamps to the last video row: (449/450)*1080 = 1077
        assert_eq!(y, 1077);
        // Horizontal bars: a 1080x1920-tall window letterboxes on the sides instead.
        let (x, _) = letterbox_point(0.0, 540.0, 800, 600, 600, 600);
        // Square video in 800x600 window: scale = min(800/600, 600/600) = 1.0,
        // rect 600x600 centred at x=100; window x=0 is a side bar.
        assert_eq!(x, 0);
        let (x, _) = letterbox_point(400.0, 540.0, 800, 600, 600, 600);
        assert_eq!(x, 960); // (400-100)/600 = 0.5 -> 960
    }

    #[test]
    fn test_letterbox_point_zero_dimensions() {
        assert_eq!(letterbox_point(5.0, 5.0, 0, 600, 1920, 1080), (0, 0));
        assert_eq!(letterbox_point(5.0, 5.0, 800, 0, 1920, 1080), (0, 0));
        assert_eq!(letterbox_point(5.0, 5.0, 800, 600, 0, 1080), (0, 0));
        assert_eq!(letterbox_point(5.0, 5.0, 800, 600, 1920, 0), (0, 0));
    }

    #[test]
    fn test_create_mouse_event_scaling() {
        // Zero window dimension guards return 0
        let zero_w = create_mouse_event(500.0, 300.0, 0, 600, 1920, 1080, 1, 0);
        if let Some(input_event::Event::Mouse(m)) = zero_w.event {
            assert_eq!(m.x, 0);
            assert_eq!(m.y, 0);
        } else {
            panic!("Expected MouseEvent");
        }

        let zero_h = create_mouse_event(500.0, 300.0, 800, 0, 1920, 1080, 1, 0);
        if let Some(input_event::Event::Mouse(m)) = zero_h.event {
            assert_eq!(m.x, 0);
            assert_eq!(m.y, 0);
        } else {
            panic!("Expected MouseEvent");
        }

        // 16:9 window, 16:9 frame: midpoint of the window is the midpoint of the wire space
        let mid = create_mouse_event(320.0, 180.0, 640, 360, 1920, 1080, 1, 0);
        if let Some(input_event::Event::Mouse(m)) = mid.event {
            assert_eq!(m.x, 960);
            assert_eq!(m.y, 540);
        } else {
            panic!("Expected MouseEvent");
        }

        // A point beyond the window clamps to the last in-video row/column:
        // (799/800)*1920 = 1917, (449/450)*1080 = 1077.
        let clamped = create_mouse_event(1600.0, 1200.0, 800, 600, 1920, 1080, 1, 0);
        if let Some(input_event::Event::Mouse(m)) = clamped.event {
            assert_eq!(m.x, 1917);
            assert_eq!(m.y, 1077);
        } else {
            panic!("Expected MouseEvent");
        }

        // Modifiers are carried through untouched
        let mods = create_mouse_event(400.0, 300.0, 800, 600, 1920, 1080, 1, 3);
        if let Some(input_event::Event::Mouse(m)) = mods.event {
            assert_eq!(m.modifiers, 3);
        } else {
            panic!("Expected MouseEvent");
        }
    }

    #[test]
    fn test_create_scroll_event_scaling() {
        // Zero window dimension returns 0
        let zero = create_scroll_event(400.0, 300.0, 0, 0, 1920, 1080, 1.0, 0.0, 0);
        if let Some(input_event::Event::Scroll(s)) = zero.event {
            assert_eq!(s.x, 0);
            assert_eq!(s.y, 0);
            assert_eq!(s.v_scroll, 1.0);
        } else {
            panic!("Expected ScrollEvent");
        }

        // Midpoint of a 16:9 window
        let mid = create_scroll_event(320.0, 180.0, 640, 360, 1920, 1080, -1.0, 2.0, 0);
        if let Some(input_event::Event::Scroll(s)) = mid.event {
            assert_eq!(s.x, 960);
            assert_eq!(s.y, 540);
            assert_eq!(s.v_scroll, -1.0);
            assert_eq!(s.h_scroll, 2.0);
        } else {
            panic!("Expected ScrollEvent");
        }

        // Clamping beyond the window edges: same values as the mouse test
        let clamped = create_scroll_event(2000.0, 2000.0, 800, 600, 1920, 1080, 0.0, 0.0, 0);
        if let Some(input_event::Event::Scroll(s)) = clamped.event {
            assert_eq!(s.x, 1917);
            assert_eq!(s.y, 1077);
        } else {
            panic!("Expected ScrollEvent");
        }
    }

    #[test]
    fn test_routing_decision() {
        let mods_empty = ModifiersState::empty();
        let mut mods_ctrl = ModifiersState::empty();
        mods_ctrl.set(ModifiersState::CONTROL, true);

        // a plain letter routes to text
        assert!(should_route_as_text(true, Some("a"), &mods_empty));
        // an accented character routes to text
        assert!(should_route_as_text(true, Some("é"), &mods_empty));
        
        // Enter, Tab and Backspace route to keycode
        assert!(!should_route_as_text(true, Some("\r"), &mods_empty)); // Enter
        assert!(!should_route_as_text(true, Some("\t"), &mods_empty)); // Tab
        assert!(!should_route_as_text(true, Some("\x08"), &mods_empty)); // Backspace
        assert!(!should_route_as_text(true, Some("\x7F"), &mods_empty)); // DEL
        
        // Ctrl+C routes to keycode
        assert!(!should_route_as_text(true, Some("c"), &mods_ctrl));
        
        // a key release never routes to text
        assert!(!should_route_as_text(false, Some("a"), &mods_empty));
    }

    #[test]
    fn test_winit_modifiers_to_wire() {
        // Pins the wire bitmask to input.proto's `Modifier` enum: shift=1, ctrl=2,
        // alt=4, super=8. The phone decodes the same bits in
        // LocalInputDispatcher.wireModifiersToAndroidMeta.
        assert_eq!(winit_modifiers_to_wire(&ModifiersState::empty()), 0);
        let mut m = ModifiersState::empty();
        m.set(ModifiersState::SHIFT, true);
        assert_eq!(winit_modifiers_to_wire(&m), 1);
        m.set(ModifiersState::CONTROL, true);
        assert_eq!(winit_modifiers_to_wire(&m), 3);
        m.set(ModifiersState::ALT, true);
        assert_eq!(winit_modifiers_to_wire(&m), 7);
        m.set(ModifiersState::SUPER, true);
        assert_eq!(winit_modifiers_to_wire(&m), 15);
    }
}
