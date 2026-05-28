use std::time::{SystemTime, UNIX_EPOCH};
use windows::Win32::Foundation::POINT;
use windows::Win32::UI::Input::KeyboardAndMouse::{GetAsyncKeyState, VK_LBUTTON, VK_MBUTTON, VK_RBUTTON};
use windows::Win32::UI::WindowsAndMessaging::GetCursorPos;
use zc_protocol::protocol::{KeyboardEvent, MouseEvent};

pub type InputError = Box<dyn std::error::Error + Send + Sync>;

pub struct InputCapturer {
    prev_keys: [bool; 256],
}

impl InputCapturer {
    pub fn new() -> Result<Self, InputError> {
        Ok(Self {
            prev_keys: [false; 256],
        })
    }

    pub fn poll_mouse(&mut self) -> Result<MouseEvent, InputError> {
        let mut point = POINT { x: 0, y: 0 };
        unsafe {
            GetCursorPos(&mut point)?;
        }

        // Map screen coordinates to 1920x1080 virtual display
        // We'll assume the primary monitor is 1920x1080 for this example,
        // or just clamp positive values.
        let vx = if point.x < 0 { 0 } else { point.x as u32 };
        let vy = if point.y < 0 { 0 } else { point.y as u32 };

        let mut buttons = 0;
        unsafe {
            if GetAsyncKeyState(VK_LBUTTON.0 as i32) < 0 {
                buttons |= 1;
            }
            if GetAsyncKeyState(VK_RBUTTON.0 as i32) < 0 {
                buttons |= 2;
            }
            if GetAsyncKeyState(VK_MBUTTON.0 as i32) < 0 {
                buttons |= 4;
            }
        }

        let timestamp = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis() as u64;

        Ok(MouseEvent {
            x: vx,
            y: vy,
            buttons,
            timestamp,
        })
    }

    pub fn poll_keyboard(&mut self) -> Result<Option<KeyboardEvent>, InputError> {
        for vk in 8..=254 {
            let state = unsafe { GetAsyncKeyState(vk) };
            let is_pressed = state < 0;
            
            if is_pressed && !self.prev_keys[vk as usize] {
                self.prev_keys[vk as usize] = true;
                return Ok(Some(KeyboardEvent {
                    keycode: vk as u32,
                    pressed: true,
                    modifiers: 0,
                    timestamp: SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis() as u64,
                }));
            } else if !is_pressed && self.prev_keys[vk as usize] {
                self.prev_keys[vk as usize] = false;
                return Ok(Some(KeyboardEvent {
                    keycode: vk as u32,
                    pressed: false,
                    modifiers: 0,
                    timestamp: SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis() as u64,
                }));
            }
        }
        Ok(None)
    }
}
