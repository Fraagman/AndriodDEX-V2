use egui::{Color32, FontId, Pos2, Rect, Vec2, Window};
use zc_network::ConnectionPhase;
use super::theme::*;
use super::types::{UiActions, UiState};

pub fn render_dialogs(
    ctx: &egui::Context,
    ui_state: &mut UiState,
    actions: &mut UiActions,
    phase: &ConnectionPhase,
    decode_fps: u32,
    decode_us: u64,
) {
    let screen_rect = ctx.screen_rect();
    let taskbar_height = 42.0;

    // =========================================================================
    // 1. Windows 10 Settings Dialog
    // =========================================================================
    if ui_state.settings_open {
        let mut open = ui_state.settings_open;
        Window::new("Windows Settings — AndroidDEX")
            .open(&mut open)
            .resizable(true)
            .default_size(Vec2::new(720.0, 480.0))
            .min_size(Vec2::new(600.0, 400.0))
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    // Left categories rail
                    ui.vertical(|ui| {
                        ui.set_width(180.0);
                        let tabs = ["System", "Network & Internet", "Sound", "Devices & Input", "Security", "About"];
                        for tab in tabs {
                            let is_active = ui_state.active_settings_tab == tab;
                            if ui.selectable_label(is_active, tab).clicked() {
                                ui_state.active_settings_tab = tab;
                            }
                        }
                    });

                    ui.separator();

                    // Right content panel
                    ui.vertical(|ui| {
                        match ui_state.active_settings_tab {
                            "System" => {
                                ui.heading("Display & Video Engine");
                                ui.label("Resolution: 1920 x 1080 (VirtualDisplay)");
                                ui.label("Target Frame Rate: 60 FPS");
                                ui.label("Codec: H.264 (Baseline / Constrained Profile)");
                                ui.label("Hardware Encoder: Android MediaCodec");
                                ui.label("Software Decoder: Cisco OpenH264 v2.6.0");
                                ui.label("Renderer: WebGPU / DirectX 12 (wgpu)");
                                ui.separator();
                                ui.horizontal(|ui| {
                                    if ui.button("⚡ Force Keyframe Request").clicked() {
                                        actions.request_keyframe = true;
                                    }
                                    if ui.button("🖥 Toggle Fullscreen").clicked() {
                                        actions.toggle_fullscreen = true;
                                    }
                                });
                            }
                            "Network & Internet" => {
                                ui.heading("QUIC & RNDIS Adapter");
                                ui.label("Transport: QUIC over UDP (Port 4433)");
                                ui.label("Subnet: RNDIS / Ethernet 3 (10.53.207.0/24)");
                                ui.label("Host Target IP: 10.53.207.237");
                                ui.label("Local Adapter IP: 10.53.207.51");
                                ui.label("Round Trip Latency: ~1 ms (Direct USB Bus)");
                                ui.separator();
                                if ui.button("🔄 Reconnect Session").clicked() {
                                    actions.reconnect = true;
                                }
                            }
                            "Sound" => {
                                ui.heading("Audio Capture & Playback");
                                ui.label("Engine: cpal (WASAPI Audio Output)");
                                ui.label("Format: 48,000 Hz, 16-bit Stereo PCM");
                                ui.label("Resampler: rubato FftFixedIn (Drift-compensated)");
                                ui.separator();
                                ui.checkbox(&mut ui_state.is_muted, "Mute audio output");
                                ui.horizontal(|ui| {
                                    ui.label("Master Volume:");
                                    let mut vol = (ui_state.volume_level * 100.0) as u32;
                                    if ui.add(egui::Slider::new(&mut vol, 0..=100).suffix("%")).changed() {
                                        ui_state.volume_level = vol as f32 / 100.0;
                                        actions.volume = Some(ui_state.volume_level);
                                    }
                                });
                            }
                            "Devices & Input" => {
                                ui.heading("Mouse & Keyboard Input");
                                ui.label("Protocol: Zero-Copy Protocol Buffers");
                                ui.label("Input Injection: Android LocalInputDispatcher");
                                ui.label("Coordinate Normalization: 1920 x 1080 Virtual Canvas");
                                ui.label("Modifier Tracking: Hardware winit edge synchronization");
                                ui.label("Text Forwarding: Dual-Path (DOM evaluateJavascript + Keycode)");
                            }
                            "Security" => {
                                ui.heading("Security & Pairing Trust");
                                ui.label("Key Exchange: X25519 Elliptic Curve Diffie-Hellman");
                                ui.label("Authentication: Mutual TLS 1.3 with Pin/SAS Verification");
                                ui.label("Cipher: TLS_AES_128_GCM_SHA256");
                                ui.separator();
                                ui.colored_label(Color32::from_rgb(255, 180, 180), "Manage Trust Store:");
                                if ui.button("🗑 Forget Pairing & Clear Stored Keys").clicked() {
                                    actions.forget_pairing = true;
                                    ui_state.notifications.push(super::types::NotificationItem {
                                        title: "Trust Data Cleared".to_string(),
                                        message: "Stored pairing keys have been erased. Next connection will re-pair.".to_string(),
                                        time: "Just now".to_string(),
                                    });
                                }
                            }
                            _ => {
                                ui.heading("About AndroidDEX");
                                ui.label("Version: 2.0.0 (Release Build)");
                                ui.label("Edition: Windows 10 Desktop Client");
                                ui.label("Architecture: x86_64 Windows (Native Rust)");
                                ui.label("Author: AndroidDEX Open Source Project");
                            }
                        }
                    });
                });
            });
        ui_state.settings_open = open;
    }

    // =========================================================================
    // 2. Windows 10 Volume Flyout
    // =========================================================================
    if ui_state.volume_flyout_open {
        let flyout_w = 260.0;
        let flyout_h = 110.0;
        let flyout_rect = Rect::from_min_size(
            Pos2::new(screen_rect.max.x - 220.0, screen_rect.max.y - taskbar_height - flyout_h - 8.0),
            Vec2::new(flyout_w, flyout_h),
        );

        if ctx.input(|i| i.pointer.any_click()) {
            if let Some(pos) = ctx.input(|i| i.pointer.interact_pos()) {
                if !flyout_rect.contains(pos) && pos.y < screen_rect.max.y - taskbar_height {
                    ui_state.volume_flyout_open = false;
                }
            }
        }

        let painter = ctx.layer_painter(egui::LayerId::new(egui::Order::Foreground, egui::Id::new("vol_flyout")));
        draw_acrylic_panel(&painter, flyout_rect, COLOR_FLYOUT_BG, COLOR_TASKBAR_BORDER);

        painter.text(
            Pos2::new(flyout_rect.min.x + 14.0, flyout_rect.min.y + 16.0),
            egui::Align2::LEFT_CENTER,
            "Speakers (AndroidDEX Audio)",
            FontId::proportional(12.0),
            COLOR_TEXT_PRIMARY,
        );

        egui::Area::new(egui::Id::new("vol_area"))
            .fixed_pos(Pos2::new(flyout_rect.min.x + 14.0, flyout_rect.min.y + 42.0))
            .order(egui::Order::Foreground)
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    let mute_icon = if ui_state.is_muted { "🔇" } else { "🔊" };
                    if ui.button(mute_icon).clicked() {
                        ui_state.is_muted = !ui_state.is_muted;
                        actions.toggle_mute = true;
                    }
                    let mut vol_int = (ui_state.volume_level * 100.0) as u32;
                    if ui.add(egui::Slider::new(&mut vol_int, 0..=100).show_value(true)).changed() {
                        ui_state.volume_level = vol_int as f32 / 100.0;
                        actions.volume = Some(ui_state.volume_level);
                    }
                });
            });
    }

    // =========================================================================
    // 3. Windows 10 Network Flyout
    // =========================================================================
    if ui_state.network_flyout_open {
        let flyout_w = 280.0;
        let flyout_h = 140.0;
        let flyout_rect = Rect::from_min_size(
            Pos2::new(screen_rect.max.x - 260.0, screen_rect.max.y - taskbar_height - flyout_h - 8.0),
            Vec2::new(flyout_w, flyout_h),
        );

        if ctx.input(|i| i.pointer.any_click()) {
            if let Some(pos) = ctx.input(|i| i.pointer.interact_pos()) {
                if !flyout_rect.contains(pos) && pos.y < screen_rect.max.y - taskbar_height {
                    ui_state.network_flyout_open = false;
                }
            }
        }

        let painter = ctx.layer_painter(egui::LayerId::new(egui::Order::Foreground, egui::Id::new("net_flyout")));
        draw_acrylic_panel(&painter, flyout_rect, COLOR_FLYOUT_BG, COLOR_TASKBAR_BORDER);

        painter.text(
            Pos2::new(flyout_rect.min.x + 14.0, flyout_rect.min.y + 16.0),
            egui::Align2::LEFT_CENTER,
            "Ethernet 3 (RNDIS USB Tethering)",
            FontId::proportional(12.0),
            COLOR_TEXT_PRIMARY,
        );

        let (st_line, _, _) = super::overlay::phase_display_info(phase);
        painter.text(
            Pos2::new(flyout_rect.min.x + 14.0, flyout_rect.min.y + 40.0),
            egui::Align2::LEFT_CENTER,
            st_line,
            FontId::proportional(13.0),
            COLOR_ACCENT_BLUE,
        );
        painter.text(
            Pos2::new(flyout_rect.min.x + 14.0, flyout_rect.min.y + 64.0),
            egui::Align2::LEFT_CENTER,
            "Host: 10.53.207.237:4433 (QUIC / TLS 1.3)",
            FontId::proportional(11.0),
            COLOR_TEXT_SECONDARY,
        );

        egui::Area::new(egui::Id::new("net_area"))
            .fixed_pos(Pos2::new(flyout_rect.min.x + 14.0, flyout_rect.min.y + 92.0))
            .order(egui::Order::Foreground)
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    if ui.button("🔄 Reconnect").clicked() {
                        actions.reconnect = true;
                        ui_state.network_flyout_open = false;
                    }
                    if ui.button("Settings").clicked() {
                        ui_state.active_settings_tab = "Network & Internet";
                        ui_state.settings_open = true;
                        ui_state.network_flyout_open = false;
                    }
                });
            });
    }

    // =========================================================================
    // 4. Performance & Telemetry HUD Overlay
    // =========================================================================
    if ui_state.show_hud_stats {
        let hud_rect = Rect::from_min_size(
            Pos2::new(screen_rect.max.x - 170.0, 10.0),
            Vec2::new(160.0, 52.0),
        );
        let painter = ctx.layer_painter(egui::LayerId::new(egui::Order::Foreground, egui::Id::new("hud_stats")));
        draw_acrylic_panel(&painter, hud_rect, Color32::from_rgba_premultiplied(16, 16, 20, 200), Color32::from_rgb(45, 45, 50));
        painter.text(
            Pos2::new(hud_rect.min.x + 10.0, hud_rect.min.y + 14.0),
            egui::Align2::LEFT_CENTER,
            format!("{} FPS", decode_fps),
            FontId::proportional(14.0),
            if decode_fps >= 50 { Color32::GREEN } else { Color32::YELLOW },
        );
        painter.text(
            Pos2::new(hud_rect.max.x - 10.0, hud_rect.min.y + 14.0),
            egui::Align2::RIGHT_CENTER,
            format!("{:.1} ms", decode_us as f64 / 1000.0),
            FontId::proportional(12.0),
            Color32::from_rgb(180, 220, 255),
        );
        painter.text(
            Pos2::new(hud_rect.min.x + 10.0, hud_rect.min.y + 36.0),
            egui::Align2::LEFT_CENTER,
            "1080p H.264 • HW Decoder",
            FontId::proportional(10.0),
            COLOR_TEXT_MUTED,
        );
    }
}
