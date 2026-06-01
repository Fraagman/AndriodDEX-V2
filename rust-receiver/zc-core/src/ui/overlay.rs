use egui::{Color32, FontId, Pos2, Rect, Rounding, Stroke, Vec2};
use egui_wgpu::Renderer;
use egui_wgpu::ScreenDescriptor;
use egui_winit::State;
use winit::window::Window;
use zc_network::ConnectionPhase;

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
        is_vm: bool,
        is_kiosk: bool,
    ) {
        let raw_input = self.state.take_egui_input(window);
        
        let is_connected = matches!(phase, ConnectionPhase::Connected);
        
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

        // VM Label - always visible in top-right corner if is_vm is true
        if is_vm {
            let window_size = window.inner_size();
            let label_rect = Rect::from_min_size(
                Pos2::new(window_size.width as f32 / window.scale_factor() as f32 - 40.0, 10.0),
                Vec2::new(30.0, 20.0)
            );
            
            painter.rect(
                label_rect,
                Rounding::same(4.0),
                Color32::from_rgba_premultiplied(50, 50, 0, 200),
                Stroke::new(1.0, Color32::YELLOW),
            );
            
            painter.text(
                label_rect.center(),
                egui::Align2::CENTER_CENTER,
                "VM",
                FontId::proportional(10.0),
                Color32::YELLOW,
            );
        }

        let window_size = window.inner_size();
        let banner_rect = Rect::from_min_size(
            Pos2::new(0.0, 0.0),
            Vec2::new(window_size.width as f32, window_size.height as f32)
        );

        match phase {
            ConnectionPhase::Connected => {} // Draw nothing full-screen
            ConnectionPhase::WaitingForPin(pin) => {
                painter.rect(
                    banner_rect,
                    Rounding::ZERO,
                    Color32::from_rgb(10, 10, 10),
                    Stroke::new(1.0, Color32::WHITE),
                );
                
                let formatted_pin = pin.chars().map(|c| c.to_string()).collect::<Vec<_>>().join(" ");
                painter.text(
                    banner_rect.center(),
                    egui::Align2::CENTER_CENTER,
                    format!("Your PIN: {}", formatted_pin),
                    FontId::proportional(48.0),
                    Color32::WHITE,
                );
            }
            other_phase => {
                let msg = match other_phase {
                    ConnectionPhase::Idle => "Connect USB cable and enable USB tethering.".to_string(),
                    ConnectionPhase::Scanning(subnet, attempt) => format!("Scanning {}... (attempt {})", subnet, attempt),
                    ConnectionPhase::Found(addr) => format!("Found AndroidDex phone at {}. Connecting...", addr),
                    ConnectionPhase::Handshaking => "Handshaking...".to_string(),
                    ConnectionPhase::Failed(reason) => format!("Connection failed: {}. Retrying...", reason),
                    _ => "".to_string(),
                };
                
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

            let status_text = if is_connected { "Status: Connected" } else { "Status: Disconnected" };
            let status_color = if is_connected { 
                Color32::from_rgba_premultiplied(0, 255, 0, (255.0 * alpha) as u8) 
            } else { 
                Color32::from_rgba_premultiplied(255, 0, 0, (255.0 * alpha) as u8) 
            };
            
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
                "Latency: -- ms",
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
