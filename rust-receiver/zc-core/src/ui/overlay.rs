use egui::{Color32, FontId, Pos2, Rect, Rounding, Stroke, Vec2};
use egui_wgpu::Renderer;
use egui_wgpu::ScreenDescriptor;
use egui_winit::State;
use winit::window::Window;
use zc_network::ConnectionPhase;

/// Returns the status line text, RGB color tuple, and central banner message for a given ConnectionPhase.
/// Deriving both from this single function guarantees the status line and the central message come from
/// the same ConnectionPhase and cannot contradict each other.
pub fn phase_display_info(phase: &ConnectionPhase) -> (&'static str, (u8, u8, u8), String) {
    match phase {
        ConnectionPhase::Connected => ("Status: Connected", (0, 255, 0), String::new()),
        ConnectionPhase::Handshaking => ("Status: Handshaking...", (255, 200, 50), "Handshaking...".to_string()),
        ConnectionPhase::WaitingForSas(_) => ("Status: Pairing...", (100, 200, 255), "Pairing Code".to_string()),
        ConnectionPhase::PhoneForgotPairing => (
            "Status: Pairing again...",
            (255, 200, 50),
            "The phone forgot this PC, pairing again...".to_string(),
        ),
        ConnectionPhase::Scanning(subnet, attempt) => (
            "Status: Scanning...",
            (255, 200, 50),
            format!("Scanning {}... (attempt {})", subnet, attempt),
        ),
        ConnectionPhase::Found(addr) => (
            "Status: Connecting...",
            (100, 200, 255),
            format!("Found AndroidDex phone at {}. Connecting...", addr),
        ),
        ConnectionPhase::CertificateChanged => (
            "Status: Device Identity Changed",
            (255, 80, 80),
            "SECURITY WARNING: Device Identity Changed".to_string(),
        ),
        ConnectionPhase::Failed(reason) => (
            "Status: Connection Failed",
            (255, 80, 80),
            format!("Connection failed: {}. Retrying...", reason),
        ),
        ConnectionPhase::Idle => (
            "Status: Disconnected",
            (255, 80, 80),
            "Connect USB cable and enable USB tethering.".to_string(),
        ),
    }
}

pub struct OverlayUi {
    pub context: egui::Context,
    pub state: State,
    pub renderer: Renderer,
}

impl OverlayUi {
    pub fn new(
        device: &wgpu::Device,
        surface_format: wgpu::TextureFormat,
        window: &Window,
    ) -> Self {
        let context = egui::Context::default();
        let viewport_id = context.viewport_id();
        
        let state = State::new(
            context.clone(),
            viewport_id,
            window,
            Some(window.scale_factor() as f32),
            None,
        );

        let renderer = Renderer::new(device, surface_format, None, 1);

        Self {
            context,
            state,
            renderer,
        }
    }

    pub fn handle_event(&mut self, window: &Window, event: &winit::event::WindowEvent) -> bool {
        let response = self.state.on_window_event(window, event);
        response.consumed
    }

    pub fn render(
        &mut self,
        window: &Window,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        view: &wgpu::TextureView,
        encoder: &mut wgpu::CommandEncoder,
        phase: &ConnectionPhase,
        mouse_pos: (f64, f64),
        has_video: bool,
        is_kiosk: bool,
        decode_fps: u32,
        decode_us: u64,
    ) {
        let raw_input = self.state.take_egui_input(window);
        
        let (mx, my) = mouse_pos;
        let is_hovered = mx >= 0.0 && mx <= 250.0 && my >= 0.0 && my <= 120.0;
        
        // Status rect fades in on hover regardless of connection
        let alpha = self.context.animate_bool_with_time(
            egui::Id::new("overlay_fade"),
            is_hovered,
            0.3,
        );

        self.context.begin_frame(raw_input);

        let painter = self.context.layer_painter(egui::LayerId::new(
            egui::Order::Foreground,
            egui::Id::new("overlay"),
        ));

        // Decode stats label - always visible in top-right corner if video is running
        if has_video {
            let window_size = window.inner_size();
            let label_rect = Rect::from_min_size(
                Pos2::new(window_size.width as f32 / window.scale_factor() as f32 - 120.0, 10.0),
                Vec2::new(110.0, 20.0)
            );
            
            painter.rect(
                label_rect,
                Rounding::same(4.0),
                Color32::from_rgba_premultiplied(0, 50, 0, 200),
                Stroke::new(1.0, Color32::GREEN),
            );
            
            painter.text(
                label_rect.center(),
                egui::Align2::CENTER_CENTER,
                format!("{} fps  {:.1}ms", decode_fps, decode_us as f64 / 1000.0),
                FontId::proportional(10.0),
                Color32::GREEN,
            );
        }

        let window_size = window.inner_size();
        let banner_rect = Rect::from_min_size(
            Pos2::new(0.0, 0.0),
            Vec2::new(window_size.width as f32, window_size.height as f32)
        );

        match phase {
            ConnectionPhase::Connected => {} // Draw nothing full-screen
            ConnectionPhase::WaitingForSas(sas) => {
                painter.rect(
                    banner_rect,
                    Rounding::ZERO,
                    Color32::from_rgb(10, 10, 10),
                    Stroke::new(1.0, Color32::WHITE),
                );
                
                painter.text(
                    banner_rect.center() - Vec2::new(0.0, 50.0),
                    egui::Align2::CENTER_CENTER,
                    "Pairing Code",
                    FontId::proportional(22.0),
                    Color32::from_rgb(180, 180, 180),
                );

                let formatted_sas = sas.chars().map(|c| c.to_string()).collect::<Vec<_>>().join(" ");
                painter.text(
                    banner_rect.center(),
                    egui::Align2::CENTER_CENTER,
                    formatted_sas,
                    FontId::proportional(56.0),
                    Color32::WHITE,
                );

                painter.text(
                    banner_rect.center() + Vec2::new(0.0, 55.0),
                    egui::Align2::CENTER_CENTER,
                    "Check that this 6-digit code matches the code on your phone screen.\nConfirm or reject the pairing on your phone.",
                    FontId::proportional(16.0),
                    Color32::from_rgb(220, 220, 220),
                );
            }
            ConnectionPhase::CertificateChanged => {
                painter.rect(
                    banner_rect,
                    Rounding::ZERO,
                    Color32::from_rgb(40, 10, 10),
                    Stroke::new(2.0, Color32::RED),
                );
                
                painter.text(
                    banner_rect.center() - Vec2::new(0.0, 24.0),
                    egui::Align2::CENTER_CENTER,
                    "SECURITY WARNING: Device Identity Changed",
                    FontId::proportional(28.0),
                    Color32::from_rgb(255, 80, 80),
                );

                painter.text(
                    banner_rect.center() + Vec2::new(0.0, 24.0),
                    egui::Align2::CENTER_CENTER,
                    "The device certificate does not match the paired device. The connection may be intercepted.\nTo re-pair with a new device, run the receiver with --forget-pairing.",
                    FontId::proportional(16.0),
                    Color32::from_rgb(230, 230, 230),
                );
            }
            _ => {
                let (_, _, msg) = phase_display_info(phase);
                painter.rect(
                    banner_rect,
                    Rounding::ZERO,
                    Color32::from_rgb(10, 10, 10),
                    Stroke::new(1.0, Color32::WHITE),
                );
                
                painter.text(
                    banner_rect.center(),
                    egui::Align2::CENTER_CENTER,
                    msg,
                    FontId::proportional(24.0),
                    Color32::from_rgb(245, 245, 245),
                );
            }
        }

        if alpha > 0.0 {
            let rect_height = 80.0;
            let rect = Rect::from_min_size(Pos2::new(10.0, 10.0), Vec2::new(200.0, rect_height));
            
            painter.rect(
                rect,
                Rounding::same(8.0),
                Color32::from_rgba_premultiplied(25, 25, 25, (200.0 * alpha) as u8),
                Stroke::new(1.0, Color32::from_rgba_premultiplied(255, 255, 255, (255.0 * alpha) as u8)),
            );

            painter.text(
                rect.min + Vec2::new(10.0, 10.0),
                egui::Align2::LEFT_TOP,
                "AndroidDex Receiver",
                FontId::proportional(14.0),
                Color32::from_rgba_premultiplied(255, 255, 255, (255.0 * alpha) as u8),
            );

            let (status_text, (r, g, b), _) = phase_display_info(phase);
            let status_color = Color32::from_rgba_premultiplied(
                (r as f32 * alpha) as u8,
                (g as f32 * alpha) as u8,
                (b as f32 * alpha) as u8,
                (255.0 * alpha) as u8,
            );
            
            painter.text(
                rect.min + Vec2::new(10.0, 30.0),
                egui::Align2::LEFT_TOP,
                status_text,
                FontId::proportional(14.0),
                status_color,
            );

            painter.text(
                rect.min + Vec2::new(10.0, 50.0),
                egui::Align2::LEFT_TOP,
                if decode_fps > 0 {
                    format!("Decode: {} fps, {:.1} ms", decode_fps, decode_us as f64 / 1000.0)
                } else {
                    "Decode: waiting...".to_string()
                },
                FontId::proportional(14.0),
                Color32::from_rgba_premultiplied(180, 180, 180, (255.0 * alpha) as u8),
            );
        }

        if is_kiosk {
            let window_size = window.inner_size();
            let banner_rect = Rect::from_min_size(
                Pos2::new(window_size.width as f32 / window.scale_factor() as f32 / 2.0 - 100.0, 10.0),
                Vec2::new(200.0, 30.0)
            );
            
            painter.rect(
                banner_rect,
                Rounding::same(4.0),
                Color32::from_rgb(10, 10, 10),
                Stroke::new(1.0, Color32::WHITE),
            );
            
            painter.text(
                banner_rect.center(),
                egui::Align2::CENTER_CENTER,
                "Managed Terminal",
                FontId::proportional(16.0),
                Color32::WHITE,
            );
        }

        let full_output = self.context.end_frame();
        let paint_jobs = self.context.tessellate(full_output.shapes, full_output.pixels_per_point);

        for (id, image_delta) in &full_output.textures_delta.set {
            self.renderer.update_texture(device, queue, *id, image_delta);
        }

        let screen_descriptor = ScreenDescriptor {
            size_in_pixels: [window.inner_size().width, window.inner_size().height],
            pixels_per_point: window.scale_factor() as f32,
        };

        self.renderer.update_buffers(
            device,
            queue,
            encoder,
            &paint_jobs,
            &screen_descriptor,
        );

        {
            let mut render_pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("egui_render_pass"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Load,
                        store: wgpu::StoreOp::Store,
                    },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
            });
            self.renderer.render(&mut render_pass, &paint_jobs, &screen_descriptor);
        }

        for id in &full_output.textures_delta.free {
            self.renderer.free_texture(id);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_phase_display_info_consistency() {
        // Handshaking: status and message must agree (not Disconnected)
        let (status, rgb, msg) = phase_display_info(&ConnectionPhase::Handshaking);
        assert_eq!(status, "Status: Handshaking...");
        assert_eq!(msg, "Handshaking...");
        assert_ne!(status, "Status: Disconnected");
        assert_eq!(rgb, (255, 200, 50));

        // PhoneForgotPairing: distinct phase and message
        let (status, rgb, msg) = phase_display_info(&ConnectionPhase::PhoneForgotPairing);
        assert_eq!(status, "Status: Pairing again...");
        assert_eq!(msg, "The phone forgot this PC, pairing again...");
        assert_eq!(rgb, (255, 200, 50));

        // Connected
        let (status, rgb, _) = phase_display_info(&ConnectionPhase::Connected);
        assert_eq!(status, "Status: Connected");
        assert_eq!(rgb, (0, 255, 0));

        // WaitingForSas
        let (status, rgb, _) = phase_display_info(&ConnectionPhase::WaitingForSas("123456".to_string()));
        assert_eq!(status, "Status: Pairing...");
        assert_eq!(rgb, (100, 200, 255));

        // Scanning
        let (status, rgb, msg) = phase_display_info(&ConnectionPhase::Scanning("192.168.1.0/24".to_string(), 1));
        assert_eq!(status, "Status: Scanning...");
        assert_eq!(msg, "Scanning 192.168.1.0/24... (attempt 1)");
        assert_eq!(rgb, (255, 200, 50));

        // Found
        let (status, rgb, msg) = phase_display_info(&ConnectionPhase::Found("192.168.1.2".to_string()));
        assert_eq!(status, "Status: Connecting...");
        assert_eq!(msg, "Found AndroidDex phone at 192.168.1.2. Connecting...");
        assert_eq!(rgb, (100, 200, 255));

        // CertificateChanged
        let (status, rgb, msg) = phase_display_info(&ConnectionPhase::CertificateChanged);
        assert_eq!(status, "Status: Device Identity Changed");
        assert_eq!(msg, "SECURITY WARNING: Device Identity Changed");
        assert_eq!(rgb, (255, 80, 80));

        // Failed
        let (status, rgb, msg) = phase_display_info(&ConnectionPhase::Failed("test error".to_string()));
        assert_eq!(status, "Status: Connection Failed");
        assert_eq!(msg, "Connection failed: test error. Retrying...");
        assert_eq!(rgb, (255, 80, 80));

        // Idle
        let (status, rgb, msg) = phase_display_info(&ConnectionPhase::Idle);
        assert_eq!(status, "Status: Disconnected");
        assert_eq!(msg, "Connect USB cable and enable USB tethering.");
        assert_eq!(rgb, (255, 80, 80));
    }
}

