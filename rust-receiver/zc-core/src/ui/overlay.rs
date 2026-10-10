use egui::{Color32, FontId, Pos2, Rect, Rounding, Stroke, Vec2};
use egui_wgpu::Renderer;
use egui_wgpu::ScreenDescriptor;
use egui_winit::State;
use winit::window::Window;
use zc_network::ConnectionPhase;

use super::action_center::render_action_center;
use super::dialogs::render_dialogs;
use super::start_menu::render_start_menu;
use super::taskbar::render_taskbar;
use super::theme::*;
use super::types::{UiActions, UiState};

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
    pub ui_state: UiState,
    pub last_cursor_pos: Option<(f64, f64)>,
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
            ui_state: UiState::default(),
            last_cursor_pos: None,
        }
    }

    pub fn handle_event(&mut self, window: &Window, event: &winit::event::WindowEvent) -> bool {
        let response = self.state.on_window_event(window, event);
        let wants_pointer = self.context.wants_pointer_input();
        let wants_keyboard = self.context.wants_keyboard_input();

        match event {
            winit::event::WindowEvent::CursorMoved { position, .. } => {
                self.last_cursor_pos = Some((position.x, position.y));
            }
            winit::event::WindowEvent::CursorLeft { .. } => {
                self.last_cursor_pos = None;
            }
            _ => {}
        }

        // Hit-testing in egui LOGICAL pixels through the shared layout module —
        // the same rects the draw code uses, so a panel size change can never
        // leave an invisible zone eating clicks (or leaking them to the phone).
        let size = window.inner_size();
        let scale = window.scale_factor() as f32;
        let screen_logical = Rect::from_min_size(
            Pos2::ZERO,
            Vec2::new(size.width as f32 / scale, size.height as f32 / scale),
        );
        let (cursor_x, cursor_y) = self.last_cursor_pos.unwrap_or((-1.0, -1.0));
        let cursor_logical = Pos2::new(cursor_x as f32 / scale, cursor_y as f32 / scale);

        let is_over_taskbar = super::layout::taskbar_rect(screen_logical).contains(cursor_logical);
        let is_over_start_menu = self.ui_state.start_menu_open
            && super::layout::start_menu_rect(screen_logical).contains(cursor_logical);
        let is_over_action_center = self.ui_state.action_center_open
            && super::layout::action_center_rect(screen_logical).contains(cursor_logical);

        let is_over_modal_ui = self.ui_state.volume_flyout_open
            || self.ui_state.network_flyout_open
            || self.ui_state.settings_open
            || self.ui_state.diagnostics_open;

        let hit_overlay = is_over_taskbar || is_over_start_menu || is_over_action_center || is_over_modal_ui || wants_pointer;

        match event {
            winit::event::WindowEvent::CursorMoved { .. } => {
                hit_overlay
            }
            winit::event::WindowEvent::MouseInput { .. } => {
                hit_overlay || response.consumed
            }
            winit::event::WindowEvent::MouseWheel { .. } => {
                hit_overlay || wants_pointer || response.consumed
            }
            winit::event::WindowEvent::KeyboardInput { .. } => {
                wants_keyboard || response.consumed
            }
            _ => response.consumed,
        }
    }

    pub fn render(
        &mut self,
        window: &Window,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        view: &wgpu::TextureView,
        encoder: &mut wgpu::CommandEncoder,
        phase: &ConnectionPhase,
        _mouse_pos: (f64, f64),
        has_video: bool,
        is_kiosk: bool,
        decode_fps: u32,
        decode_us: u64,
    ) -> UiActions {
        let raw_input = self.state.take_egui_input(window);
        self.context.begin_frame(raw_input);

        let mut actions = UiActions::default();

        let window_size = window.inner_size();
        let window_scale = window.scale_factor() as f32;
        let screen_w = window_size.width as f32 / window_scale;
        let screen_h = window_size.height as f32 / window_scale;
        let banner_rect = Rect::from_min_size(Pos2::new(0.0, 0.0), Vec2::new(screen_w, screen_h));

        let painter = self.context.layer_painter(egui::LayerId::new(
            egui::Order::Background,
            egui::Id::new("desktop_bg"),
        ));

        // When not connected or when waiting for video, show Windows 10 Hero Wallpaper background
        if !has_video || *phase != ConnectionPhase::Connected {
            // Dark Windows 10 ambient gradient background
            painter.rect_filled(banner_rect, Rounding::ZERO, Color32::from_rgb(12, 14, 20));

            // Windows 10 Hero light logo in background
            draw_windows_logo(
                &painter,
                banner_rect.center() - Vec2::new(0.0, 60.0),
                64.0,
                Color32::from_rgba_premultiplied(0, 120, 215, 60),
            );

            // Phase cards / Status
            match phase {
                ConnectionPhase::Connected => {
                    painter.text(
                        banner_rect.center() + Vec2::new(0.0, 20.0),
                        egui::Align2::CENTER_CENTER,
                        "Starting Video Stream...",
                        FontId::proportional(22.0),
                        Color32::WHITE,
                    );
                    painter.text(
                        banner_rect.center() + Vec2::new(0.0, 50.0),
                        egui::Align2::CENTER_CENTER,
                        "Negotiated H.264 over QUIC (10.53.207.237:4433). Waiting for first keyframe.",
                        FontId::proportional(13.0),
                        COLOR_TEXT_SECONDARY,
                    );
                }
                ConnectionPhase::WaitingForSas(sas) => {
                    let card_w = 480.0;
                    let card_h = 240.0;
                    let card_rect = Rect::from_center_size(banner_rect.center(), Vec2::new(card_w, card_h));
                    draw_acrylic_panel(&painter, card_rect, COLOR_FLYOUT_BG, COLOR_TASKBAR_BORDER);

                    painter.text(
                        Pos2::new(card_rect.center().x, card_rect.min.y + 28.0),
                        egui::Align2::CENTER_CENTER,
                        "Windows Device Pairing",
                        FontId::proportional(18.0),
                        COLOR_ACCENT_BLUE,
                    );

                    let formatted_sas = sas.chars().map(|c| c.to_string()).collect::<Vec<_>>().join("  ");
                    painter.text(
                        Pos2::new(card_rect.center().x, card_rect.min.y + 90.0),
                        egui::Align2::CENTER_CENTER,
                        formatted_sas,
                        FontId::proportional(48.0),
                        Color32::WHITE,
                    );

                    painter.text(
                        Pos2::new(card_rect.center().x, card_rect.min.y + 155.0),
                        egui::Align2::CENTER_CENTER,
                        "Verify this 6-digit code matches the code on your Android device.",
                        FontId::proportional(13.0),
                        COLOR_TEXT_SECONDARY,
                    );
                    painter.text(
                        Pos2::new(card_rect.center().x, card_rect.min.y + 180.0),
                        egui::Align2::CENTER_CENTER,
                        "Tap 'Codes match' on the Android screen to confirm pairing.",
                        FontId::proportional(12.0),
                        COLOR_TEXT_MUTED,
                    );
                }
                ConnectionPhase::CertificateChanged => {
                    let card_w = 520.0;
                    let card_h = 220.0;
                    let card_rect = Rect::from_center_size(banner_rect.center(), Vec2::new(card_w, card_h));
                    painter.rect(
                        card_rect,
                        Rounding::same(4.0),
                        Color32::from_rgb(35, 12, 12),
                        Stroke::new(1.0, Color32::RED),
                    );

                    painter.text(
                        Pos2::new(card_rect.center().x, card_rect.min.y + 32.0),
                        egui::Align2::CENTER_CENTER,
                        "🛡 Windows Security: Device Identity Changed",
                        FontId::proportional(18.0),
                        Color32::from_rgb(255, 100, 100),
                    );
                    painter.text(
                        Pos2::new(card_rect.center().x, card_rect.min.y + 80.0),
                        egui::Align2::CENTER_CENTER,
                        "The device certificate does not match the paired device.\nTo re-pair with this device, click 'Forget Pairing' below.",
                        FontId::proportional(13.0),
                        COLOR_TEXT_PRIMARY,
                    );

                    let btn_rect = Rect::from_center_size(Pos2::new(card_rect.center().x, card_rect.max.y - 40.0), Vec2::new(200.0, 36.0));
                    let btn_hovered = is_hovered(&self.context, btn_rect);
                    let btn_clicked = is_clicked(&self.context, btn_rect);
                    // Two-click confirm: forgetting the pairing is destructive and
                    // a misclick must not silently un-pair the PC (J30).
                    if btn_clicked {
                        if self.ui_state.forget_pairing_armed {
                            actions.forget_pairing = true;
                            self.ui_state.forget_pairing_armed = false;
                        } else {
                            self.ui_state.forget_pairing_armed = true;
                        }
                    }
                    // Disarm when the pointer leaves the button area.
                    if !btn_hovered && !btn_clicked {
                        self.ui_state.forget_pairing_armed = false;
                    }
                    let btn_label = if self.ui_state.forget_pairing_armed {
                        "Confirm: forget pairing?"
                    } else {
                        "Forget Pairing"
                    };
                    painter.rect_filled(btn_rect, Rounding::same(2.0), if self.ui_state.forget_pairing_armed { Color32::from_rgb(220, 60, 60) } else if btn_hovered { Color32::from_rgb(180, 40, 40) } else { Color32::from_rgb(140, 20, 20) });
                    painter.text(btn_rect.center(), egui::Align2::CENTER_CENTER, btn_label, FontId::proportional(13.0), Color32::WHITE);
                }
                _ => {
                    let (_, _, msg) = phase_display_info(phase);
                    painter.text(
                        banner_rect.center() + Vec2::new(0.0, 20.0),
                        egui::Align2::CENTER_CENTER,
                        msg,
                        FontId::proportional(18.0),
                        COLOR_TEXT_PRIMARY,
                    );
                    painter.text(
                        banner_rect.center() + Vec2::new(0.0, 50.0),
                        egui::Align2::CENTER_CENTER,
                        "Plug in USB cable and enable USB Tethering on your Android device.",
                        FontId::proportional(12.0),
                        COLOR_TEXT_MUTED,
                    );
                }
            }
        }

        // --- Render Windows 10 UI Components ---
        render_taskbar(
            &self.context,
            &mut self.ui_state,
            &mut actions,
            phase,
            has_video,
            decode_fps,
            decode_us,
        );

        render_start_menu(
            &self.context,
            &mut self.ui_state,
            &mut actions,
            phase,
            decode_fps,
            decode_us,
        );

        render_action_center(
            &self.context,
            &mut self.ui_state,
            &mut actions,
            is_kiosk,
        );

        render_dialogs(
            &self.context,
            &mut self.ui_state,
            &mut actions,
            phase,
            decode_fps,
            decode_us,
        );

        // Kiosk Mode Banner if applicable
        if is_kiosk {
            let k_rect = Rect::from_min_size(
                Pos2::new(screen_w / 2.0 - 90.0, 8.0),
                Vec2::new(180.0, 26.0),
            );
            let k_painter = self.context.layer_painter(egui::LayerId::new(egui::Order::Foreground, egui::Id::new("kiosk_banner")));
            k_painter.rect(
                k_rect,
                Rounding::same(2.0),
                Color32::from_rgb(20, 20, 24),
                Stroke::new(1.0, Color32::from_rgb(50, 50, 60)),
            );
            k_painter.text(
                k_rect.center(),
                egui::Align2::CENTER_CENTER,
                "🔒 Kiosk Terminal Locked",
                FontId::proportional(12.0),
                COLOR_TEXT_PRIMARY,
            );
        }

        let full_output = self.context.end_frame();
        let paint_jobs = self.context.tessellate(full_output.shapes, full_output.pixels_per_point);

        let screen_descriptor = ScreenDescriptor {
            size_in_pixels: [window_size.width, window_size.height],
            pixels_per_point: window_scale,
        };

        for (id, delta) in &full_output.textures_delta.set {
            self.renderer.update_texture(device, queue, *id, delta);
        }

        self.renderer.update_buffers(device, queue, encoder, &paint_jobs, &screen_descriptor);

        {
            let mut rpass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("egui_render_pass"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Load, // preserve the decoded video underneath
                        store: wgpu::StoreOp::Store,
                    },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
            });

            self.renderer.render(&mut rpass, &paint_jobs, &screen_descriptor);
        }

        for id in &full_output.textures_delta.free {
            self.renderer.free_texture(id);
        }

        // egui's own repaint deadline: when egui wants animation the delay is
        // short, and the event loop must wake for it. Otherwise no repaint is
        // requested and the loop waits for real events (frames, input, phases).
        if let Some(vo) = full_output.viewport_output.get(&self.context.viewport_id()) {
            if vo.repaint_delay < std::time::Duration::from_secs(1) {
                actions.repaint_after = Some(vo.repaint_delay);
            }
        }

        actions
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_phase_display_info_consistency() {
        // Handshaking
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
