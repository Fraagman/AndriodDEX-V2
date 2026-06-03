use std::time::{SystemTime, UNIX_EPOCH};
use windows::Win32::Foundation::POINT;
use windows::Win32::UI::Input::KeyboardAndMouse::{GetAsyncKeyState, VK_LBUTTON, VK_MBUTTON, VK_RBUTTON};
use windows::Win32::UI::WindowsAndMessaging::{GetCursorPos, GetSystemMetrics, SM_CXSCREEN, SM_CYSCREEN};
use zc_protocol::protocol::{InputEvent, KeyboardEvent, MouseEvent, input_event};

pub type InputError = Box<dyn std::error::Error + Send + Sync>;

const VIRTUAL_WIDTH: u32 = 1920;
const VIRTUAL_HEIGHT: u32 = 1080;

pub struct InputCapturer {
    prev_keys: [bool; 256],
    screen_width: i32,
    screen_height: i32,
}

impl InputCapturer {
    pub fn new() -> Result<Self, InputError> {
        let (sw, sh) = unsafe {
            (GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN))
        };
        if sw <= 0 || sh <= 0 {
            return Err(format!("GetSystemMetrics returned invalid screen size: {}x{}", sw, sh).into());
        }
        println!("InputCapturer: detected screen {}x{}, scaling to {}x{}", sw, sh, VIRTUAL_WIDTH, VIRTUAL_HEIGHT);
        Ok(Self {
            prev_keys: [false; 256],
            screen_width: sw,
            screen_height: sh,
        })
    }

    pub fn poll_mouse(&mut self) -> Result<Option<MouseEvent>, InputError> {
        let mut point = POINT { x: 0, y: 0 };
        unsafe {
            if GetCursorPos(&mut point).is_err() {
                return Ok(None);
            }
        }

        // Scale real screen coordinates to 1920x1080 virtual display
        let clamped_x = point.x.max(0).min(self.screen_width - 1);
        let clamped_y = point.y.max(0).min(self.screen_height - 1);
        let vx = (clamped_x as u32 * VIRTUAL_WIDTH) / self.screen_width as u32;
        let vy = (clamped_y as u32 * VIRTUAL_HEIGHT) / self.screen_height as u32;

        let mut buttons = 0u32;
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

        Ok(Some(MouseEvent {
            x: vx,
            y: vy,
            buttons,
            timestamp,
        }))
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

    /// Polls mouse and keyboard and returns a list of InputEvent wrappers ready for serialization.
    pub fn poll_all(&mut self) -> Result<Vec<InputEvent>, InputError> {
        let mut events = Vec::new();

        // Always poll mouse position if available
        if let Some(mouse) = self.poll_mouse()? {
            events.push(InputEvent {
                event: Some(input_event::Event::Mouse(mouse)),
            });
        }

        // Poll keyboard for any state changes
        if let Some(kb) = self.poll_keyboard()? {
            events.push(InputEvent {
                event: Some(input_event::Event::Keyboard(kb)),
            });
        }

        Ok(events)
    }
}
