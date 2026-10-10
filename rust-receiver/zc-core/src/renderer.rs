use winit::window::Window;
use std::sync::Arc;

pub struct Renderer {
    device: wgpu::Device,
    queue: wgpu::Queue,
    surface: wgpu::Surface<'static>,
    config: wgpu::SurfaceConfiguration,
}

/// Shows a Win32 message box and prints the reason: a receiver that cannot start
/// its GPU work must die with a visible message, not a bare panic in a console
/// that may not exist (a packaged, windowless launch).
#[cfg(windows)]
pub fn show_fatal_error(title: &str, message: &str) {
    #[repr(C)]
    struct WideStr(Vec<u16>);
    fn to_wide(s: &str) -> WideStr {
        WideStr(s.encode_utf16().chain(std::iter::once(0)).collect())
    }
    #[link(name = "user32")]
    extern "system" {
        fn MessageBoxW(hwnd: *mut u8, text: *const u16, caption: *const u16, kind: u32) -> i32;
    }
    unsafe {
        let t = to_wide(message);
        let c = to_wide(title);
        MessageBoxW(std::ptr::null_mut(), t.0.as_ptr(), c.0.as_ptr(), 0x10); // MB_ICONERROR
    }
    eprintln!("{title}: {message}");
}

#[cfg(not(windows))]
pub fn show_fatal_error(title: &str, message: &str) {
    eprintln!("{title}: {message}");
}

impl Renderer {
    pub async fn new(window: Arc<Window>) -> Result<Self, String> {
        let size = window.inner_size();
        let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
            backends: wgpu::Backends::all(),
            ..Default::default()
        });

        let surface = instance
            .create_surface(window.clone())
            .map_err(|e| format!("cannot create a wgpu surface: {e}"))?;

        // Try the preferred adapter first; fall back to a software adapter before
        // giving up (RDP sessions, VMs, and odd drivers often reject the first).
        let adapter = match instance
            .request_adapter(&wgpu::RequestAdapterOptions {
                power_preference: wgpu::PowerPreference::default(),
                compatible_surface: Some(&surface),
                force_fallback_adapter: false,
            })
            .await
        {
            Some(a) => a,
            None => instance
                .request_adapter(&wgpu::RequestAdapterOptions {
                    power_preference: wgpu::PowerPreference::default(),
                    compatible_surface: Some(&surface),
                    force_fallback_adapter: true,
                })
                .await
                .ok_or_else(|| {
                    "no compatible GPU adapter found (hardware and software fallback both failed)".to_string()
                })?,
        };

        let (device, queue) = adapter
            .request_device(
                &wgpu::DeviceDescriptor {
                    label: None,
                    required_features: wgpu::Features::empty(),
                    required_limits: wgpu::Limits::default(),
                },
                None,
            )
            .await
            .map_err(|e| format!("GPU device request failed: {e}"))?;

        let surface_caps = surface.get_capabilities(&adapter);
        let surface_format = surface_caps
            .formats
            .iter()
            .copied()
            .find(|f| f.is_srgb())
            .or_else(|| surface_caps.formats.first().copied())
            .ok_or_else(|| "the GPU adapter reports no usable surface formats".to_string())?;

        // Prefer Immediate (no vsync, lowest latency), fall back to Mailbox
        // (non-blocking vsync, ~1 frame latency), last resort Fifo (vsync, up
        // to 16ms added). Fifo is always supported per the spec.
        let present_mode = if surface_caps.present_modes.contains(&wgpu::PresentMode::Immediate) {
            wgpu::PresentMode::Immediate
        } else if surface_caps.present_modes.contains(&wgpu::PresentMode::Mailbox) {
            wgpu::PresentMode::Mailbox
        } else {
            wgpu::PresentMode::Fifo
        };

        let config = wgpu::SurfaceConfiguration {
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
            format: surface_format,
            width: size.width.max(1),
            height: size.height.max(1),
            present_mode,
            alpha_mode: surface_caps.alpha_modes.first().copied().unwrap_or(wgpu::CompositeAlphaMode::Auto),
            view_formats: vec![],
            desired_maximum_frame_latency: 1,
        };
        surface.configure(&device, &config);

        Ok(Self {
            device,
            queue,
            surface,
            config,
        })
    }

    pub fn device(&self) -> &wgpu::Device {
        &self.device
    }

    pub fn queue(&self) -> &wgpu::Queue {
        &self.queue
    }

    pub fn resize(&mut self, new_size: winit::dpi::PhysicalSize<u32>) {
        if new_size.width > 0 && new_size.height > 0 {
            self.config.width = new_size.width;
            self.config.height = new_size.height;
            self.surface.configure(&self.device, &self.config);
        }
    }

    pub fn config(&self) -> &wgpu::SurfaceConfiguration {
        &self.config
    }

    pub fn get_target_view(&self) -> Result<(wgpu::SurfaceTexture, wgpu::TextureView), wgpu::SurfaceError> {
        let output = self.surface.get_current_texture()?;
        let view = output.texture.create_view(&wgpu::TextureViewDescriptor::default());
        Ok((output, view))
    }
}
