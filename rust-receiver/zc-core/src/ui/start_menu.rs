use egui::{Color32, FontId, Pos2, Rect, Rounding, Stroke, Vec2};
use zc_network::ConnectionPhase;
use super::theme::*;
use super::types::{UiActions, UiState, ALL_APPS};

pub fn render_start_menu(
    ctx: &egui::Context,
    ui_state: &mut UiState,
    actions: &mut UiActions,
    phase: &ConnectionPhase,
    decode_fps: u32,
    decode_us: u64,
) {
    if !ui_state.start_menu_open {
        return;
    }

    let screen_rect = ctx.screen_rect();
    let taskbar_height = 42.0;
    let menu_width = 620.0;
    let menu_height = 510.0;

    let menu_rect = Rect::from_min_size(
        Pos2::new(0.0, screen_rect.max.y - taskbar_height - menu_height),
        Vec2::new(menu_width, menu_height),
    );

    // Click outside to dismiss
    if ctx.input(|i| i.pointer.any_click()) {
        if let Some(pos) = ctx.input(|i| i.pointer.interact_pos()) {
            if !menu_rect.contains(pos) && pos.y < screen_rect.max.y - taskbar_height {
                ui_state.start_menu_open = false;
                return;
            }
        }
    }

    let painter = ctx.layer_painter(egui::LayerId::new(egui::Order::Foreground, egui::Id::new("start_menu")));

    // Windows 10 Acrylic Menu Backdrop
    draw_acrylic_panel(&painter, menu_rect, COLOR_MENU_BG, COLOR_TASKBAR_BORDER);

    // =========================================================================
    // Column 1: Narrow Left Rail (48px) - User, Folders, Settings, Power
    // =========================================================================
    let rail_width = 48.0;
    let rail_rect = Rect::from_min_size(menu_rect.min, Vec2::new(rail_width, menu_height));
    painter.rect_filled(rail_rect, Rounding::ZERO, Color32::from_rgb(20, 20, 22));
    painter.line_segment(
        [Pos2::new(rail_rect.max.x, rail_rect.min.y), Pos2::new(rail_rect.max.x, rail_rect.max.y)],
        Stroke::new(1.0, Color32::from_rgb(38, 38, 42)),
    );

    // Hamburger icon at top
    painter.text(
        Pos2::new(rail_rect.center().x, rail_rect.min.y + 24.0),
        egui::Align2::CENTER_CENTER,
        "☰",
        FontId::proportional(16.0),
        COLOR_TEXT_PRIMARY,
    );

    // Bottom icons on rail: User, Settings, Power
    let rail_bottom = rail_rect.max.y;

    // Power Button
    let power_rect = Rect::from_min_size(Pos2::new(rail_rect.min.x, rail_bottom - 44.0), Vec2::new(rail_width, 44.0));
    let power_hovered = is_hovered(ctx, power_rect);
    let power_clicked = is_clicked(ctx, power_rect);
    if power_clicked {
        ui_state.active_settings_tab = "System";
        ui_state.settings_open = true;
        ui_state.start_menu_open = false;
    }
    if power_hovered {
        painter.rect_filled(power_rect, Rounding::ZERO, COLOR_HOVER_BG);
        egui::show_tooltip_text(ctx, egui::Id::new("pwr_tip"), "Power & Connection Controls");
    }
    painter.text(power_rect.center(), egui::Align2::CENTER_CENTER, "⏻", FontId::proportional(16.0), COLOR_TEXT_PRIMARY);

    // Settings Button
    let sett_rect = Rect::from_min_size(Pos2::new(rail_rect.min.x, rail_bottom - 88.0), Vec2::new(rail_width, 44.0));
    let sett_hovered = is_hovered(ctx, sett_rect);
    let sett_clicked = is_clicked(ctx, sett_rect);
    if sett_clicked {
        ui_state.settings_open = true;
        ui_state.start_menu_open = false;
    }
    if sett_hovered {
        painter.rect_filled(sett_rect, Rounding::ZERO, COLOR_HOVER_BG);
        egui::show_tooltip_text(ctx, egui::Id::new("stg_tip"), "Windows & Receiver Settings");
    }
    painter.text(sett_rect.center(), egui::Align2::CENTER_CENTER, "⚙", FontId::proportional(16.0), COLOR_TEXT_PRIMARY);

    // User Avatar
    let user_rect = Rect::from_min_size(Pos2::new(rail_rect.min.x, rail_bottom - 132.0), Vec2::new(rail_width, 44.0));
    let user_hovered = is_hovered(ctx, user_rect);
    if user_hovered {
        painter.rect_filled(user_rect, Rounding::ZERO, COLOR_HOVER_BG);
        egui::show_tooltip_text(ctx, egui::Id::new("usr_tip"), "AndroidDEX Host Profile");
    }
    painter.text(user_rect.center(), egui::Align2::CENTER_CENTER, "👤", FontId::proportional(16.0), COLOR_TEXT_PRIMARY);

    // =========================================================================
    // Column 2: All Apps List (240px wide)
    // =========================================================================
    let apps_width = 240.0;
    let apps_rect = Rect::from_min_size(
        Pos2::new(menu_rect.min.x + rail_width, menu_rect.min.y),
        Vec2::new(apps_width, menu_height),
    );

    painter.text(
        Pos2::new(apps_rect.min.x + 16.0, apps_rect.min.y + 20.0),
        egui::Align2::LEFT_CENTER,
        if ui_state.search_query.is_empty() { "All Apps" } else { "Search Results" },
        FontId::proportional(12.0),
        COLOR_TEXT_SECONDARY,
    );

    let query = ui_state.search_query.to_lowercase();
    let mut app_y = apps_rect.min.y + 40.0;

    for app in ALL_APPS.iter() {
        if !query.is_empty() && !app.name.to_lowercase().contains(&query) && !app.subtitle.to_lowercase().contains(&query) {
            continue;
        }

        let item_rect = Rect::from_min_size(
            Pos2::new(apps_rect.min.x + 6.0, app_y),
            Vec2::new(apps_width - 12.0, 42.0),
        );
        let app_hovered = is_hovered(ctx, item_rect);
        let app_clicked = is_clicked(ctx, item_rect);

        if app_clicked {
            actions.launch_app = Some(app.package_name);
            ui_state.running_apps.insert(app.id);
            if app.id == "settings" {
                ui_state.settings_open = true;
            } else if app.id == "diagnostics" {
                ui_state.diagnostics_open = true;
            }
            ui_state.start_menu_open = false;
        }

        if app_hovered {
            painter.rect_filled(item_rect, Rounding::same(2.0), COLOR_HOVER_BG);
        }

        // App Icon Badge
        let badge_rect = Rect::from_min_size(
            Pos2::new(item_rect.min.x + 6.0, item_rect.min.y + 7.0),
            Vec2::new(28.0, 28.0),
        );
        painter.rect_filled(badge_rect, Rounding::same(4.0), app.accent_color);
        painter.text(
            badge_rect.center(),
            egui::Align2::CENTER_CENTER,
            app.icon_symbol,
            FontId::proportional(14.0),
            Color32::WHITE,
        );

        // App Titles
        painter.text(
            Pos2::new(item_rect.min.x + 42.0, item_rect.min.y + 12.0),
            egui::Align2::LEFT_CENTER,
            app.name,
            FontId::proportional(13.0),
            COLOR_TEXT_PRIMARY,
        );
        painter.text(
            Pos2::new(item_rect.min.x + 42.0, item_rect.min.y + 27.0),
            egui::Align2::LEFT_CENTER,
            app.subtitle,
            FontId::proportional(10.0),
            COLOR_TEXT_MUTED,
        );

        app_y += 46.0;
        if app_y > menu_rect.max.y - 40.0 {
            break;
        }
    }

    // Divider between All Apps and Tiles
    painter.line_segment(
        [
            Pos2::new(apps_rect.max.x, menu_rect.min.y + 16.0),
            Pos2::new(apps_rect.max.x, menu_rect.max.y - 16.0),
        ],
        Stroke::new(1.0, Color32::from_rgb(38, 38, 42)),
    );

    // =========================================================================
    // Column 3: Windows 10 Live Tiles Grid (310px wide)
    // =========================================================================
    let tiles_x = apps_rect.max.x + 14.0;
    let tiles_y = menu_rect.min.y + 14.0;

    painter.text(
        Pos2::new(tiles_x, tiles_y + 8.0),
        egui::Align2::LEFT_CENTER,
        "Explore & Workspace",
        FontId::proportional(12.0),
        COLOR_TEXT_SECONDARY,
    );

    // --- Tile 1: Wide Live Tile (Host & Protocol) ---
    let tile1_rect = Rect::from_min_size(Pos2::new(tiles_x, tiles_y + 22.0), Vec2::new(296.0, 95.0));
    let t1_hovered = is_hovered(ctx, tile1_rect);
    let t1_clicked = is_clicked(ctx, tile1_rect);
    if t1_clicked {
        ui_state.network_flyout_open = true;
    }
    let t1_bg = if t1_hovered { COLOR_ACCENT_HOVER } else { COLOR_ACCENT_BLUE };
    painter.rect_filled(tile1_rect, Rounding::ZERO, t1_bg);
    painter.text(
        Pos2::new(tile1_rect.min.x + 14.0, tile1_rect.min.y + 20.0),
        egui::Align2::LEFT_CENTER,
        "AndroidDEX Remote Desktop",
        FontId::proportional(14.0),
        Color32::WHITE,
    );
    let host_info = match phase {
        ConnectionPhase::Connected => "Connected: 10.53.207.237:4433 (QUIC / TLS 1.3)",
        ConnectionPhase::Handshaking => "Handshaking in progress...",
        ConnectionPhase::WaitingForSas(_) => "Pairing SAS verification awaiting...",
        ConnectionPhase::Scanning(subnet, _) => &format!("Scanning subnet {}...", subnet),
        _ => "Disconnected: Connect USB tethering",
    };
    painter.text(
        Pos2::new(tile1_rect.min.x + 14.0, tile1_rect.min.y + 44.0),
        egui::Align2::LEFT_CENTER,
        host_info,
        FontId::proportional(11.0),
        Color32::from_rgb(220, 235, 255),
    );
    painter.text(
        Pos2::new(tile1_rect.min.x + 14.0, tile1_rect.max.y - 16.0),
        egui::Align2::LEFT_CENTER,
        "⚡ Zero-Copy HW Pipeline",
        FontId::proportional(11.0),
        Color32::from_rgb(180, 215, 255),
    );
    if t1_hovered {
        egui::show_tooltip_text(ctx, egui::Id::new("t1_tip"), "Click to view Network & Session details");
    }

    // --- Tile 2: Display Stream (Medium Teal) ---
    let tile2_rect = Rect::from_min_size(Pos2::new(tiles_x, tiles_y + 125.0), Vec2::new(144.0, 85.0));
    let t2_hovered = is_hovered(ctx, tile2_rect);
    let t2_clicked = is_clicked(ctx, tile2_rect);
    if t2_clicked {
        actions.request_keyframe = true;
    }
    let t2_bg = if t2_hovered { Color32::from_rgb(0, 160, 140) } else { Color32::from_rgb(0, 130, 114) };
    painter.rect_filled(tile2_rect, Rounding::ZERO, t2_bg);
    painter.text(
        Pos2::new(tile2_rect.min.x + 10.0, tile2_rect.min.y + 16.0),
        egui::Align2::LEFT_CENTER,
        "Display Pipeline",
        FontId::proportional(12.0),
        Color32::WHITE,
    );
    painter.text(
        Pos2::new(tile2_rect.min.x + 10.0, tile2_rect.min.y + 38.0),
        egui::Align2::LEFT_CENTER,
        format!("{} FPS", decode_fps),
        FontId::proportional(18.0),
        Color32::WHITE,
    );
    painter.text(
        Pos2::new(tile2_rect.min.x + 10.0, tile2_rect.max.y - 14.0),
        egui::Align2::LEFT_CENTER,
        format!("{:.1}ms • Tap Refresh", decode_us as f64 / 1000.0),
        FontId::proportional(10.0),
        Color32::from_rgb(200, 240, 235),
    );
    if t2_hovered {
        egui::show_tooltip_text(ctx, egui::Id::new("t2_tip"), "Click to force IDR Keyframe refresh");
    }

    // --- Tile 3: Audio Engine (Medium Green) ---
    let tile3_rect = Rect::from_min_size(Pos2::new(tiles_x + 152.0, tiles_y + 125.0), Vec2::new(144.0, 85.0));
    let t3_hovered = is_hovered(ctx, tile3_rect);
    let t3_clicked = is_clicked(ctx, tile3_rect);
    if t3_clicked {
        ui_state.is_muted = !ui_state.is_muted;
        actions.toggle_mute = true;
    }
    let t3_bg = if t3_hovered { Color32::from_rgb(20, 145, 75) } else { Color32::from_rgb(16, 124, 65) };
    painter.rect_filled(tile3_rect, Rounding::ZERO, t3_bg);
    painter.text(
        Pos2::new(tile3_rect.min.x + 10.0, tile3_rect.min.y + 16.0),
        egui::Align2::LEFT_CENTER,
        "Audio Engine",
        FontId::proportional(12.0),
        Color32::WHITE,
    );
    let audio_label = if ui_state.is_muted { "Muted 🔇" } else { "48 kHz PCM 🔊" };
    painter.text(
        Pos2::new(tile3_rect.min.x + 10.0, tile3_rect.min.y + 38.0),
        egui::Align2::LEFT_CENTER,
        audio_label,
        FontId::proportional(14.0),
        Color32::WHITE,
    );
    painter.text(
        Pos2::new(tile3_rect.min.x + 10.0, tile3_rect.max.y - 14.0),
        egui::Align2::LEFT_CENTER,
        "Tap to Mute/Unmute",
        FontId::proportional(10.0),
        Color32::from_rgb(200, 245, 220),
    );
    if t3_hovered {
        egui::show_tooltip_text(ctx, egui::Id::new("t3_tip"), "Click to toggle audio mute");
    }

    // --- Tile 4: Fullscreen Quick Tile (Purple) ---
    let tile4_rect = Rect::from_min_size(Pos2::new(tiles_x, tiles_y + 218.0), Vec2::new(144.0, 85.0));
    let t4_hovered = is_hovered(ctx, tile4_rect);
    let t4_clicked = is_clicked(ctx, tile4_rect);
    if t4_clicked {
        actions.toggle_fullscreen = true;
    }
    let t4_bg = if t4_hovered { Color32::from_rgb(135, 90, 195) } else { Color32::from_rgb(116, 77, 169) };
    painter.rect_filled(tile4_rect, Rounding::ZERO, t4_bg);
    painter.text(
        Pos2::new(tile4_rect.min.x + 10.0, tile4_rect.min.y + 16.0),
        egui::Align2::LEFT_CENTER,
        "Display Mode",
        FontId::proportional(12.0),
        Color32::WHITE,
    );
    painter.text(
        Pos2::new(tile4_rect.min.x + 10.0, tile4_rect.min.y + 38.0),
        egui::Align2::LEFT_CENTER,
        "Fullscreen (F11)",
        FontId::proportional(13.0),
        Color32::WHITE,
    );
    painter.text(
        Pos2::new(tile4_rect.min.x + 10.0, tile4_rect.max.y - 14.0),
        egui::Align2::LEFT_CENTER,
        "Tap to Toggle",
        FontId::proportional(10.0),
        Color32::from_rgb(230, 220, 245),
    );
    if t4_hovered {
        egui::show_tooltip_text(ctx, egui::Id::new("t4_tip"), "Toggle borderless fullscreen");
    }

    // --- Tile 5: Reconnect & Reset Trust (Orange / Red) ---
    let tile5_rect = Rect::from_min_size(Pos2::new(tiles_x + 152.0, tiles_y + 218.0), Vec2::new(144.0, 85.0));
    let t5_hovered = is_hovered(ctx, tile5_rect);
    let t5_clicked = is_clicked(ctx, tile5_rect);
    if t5_clicked {
        actions.reconnect = true;
    }
    let t5_bg = if t5_hovered { Color32::from_rgb(235, 75, 15) } else { Color32::from_rgb(216, 59, 1) };
    painter.rect_filled(tile5_rect, Rounding::ZERO, t5_bg);
    painter.text(
        Pos2::new(tile5_rect.min.x + 10.0, tile5_rect.min.y + 16.0),
        egui::Align2::LEFT_CENTER,
        "QUIC Session",
        FontId::proportional(12.0),
        Color32::WHITE,
    );
    painter.text(
        Pos2::new(tile5_rect.min.x + 10.0, tile5_rect.min.y + 38.0),
        egui::Align2::LEFT_CENTER,
        "Reconnect Now",
        FontId::proportional(13.0),
        Color32::WHITE,
    );
    painter.text(
        Pos2::new(tile5_rect.min.x + 10.0, tile5_rect.max.y - 14.0),
        egui::Align2::LEFT_CENTER,
        "Tap to reset connection",
        FontId::proportional(10.0),
        Color32::from_rgb(255, 220, 210),
    );
    if t5_hovered {
        egui::show_tooltip_text(ctx, egui::Id::new("t5_tip"), "Reconnect QUIC stream to Android Host");
    }

    // --- Tile 6: Wide Security & Pairing Tile (Dark Slate) ---
    let tile6_rect = Rect::from_min_size(Pos2::new(tiles_x, tiles_y + 311.0), Vec2::new(296.0, 75.0));
    let t6_hovered = is_hovered(ctx, tile6_rect);
    let t6_clicked = is_clicked(ctx, tile6_rect);
    if t6_clicked {
        actions.forget_pairing = true;
    }
    let t6_bg = if t6_hovered { Color32::from_rgb(60, 60, 65) } else { Color32::from_rgb(45, 45, 50) };
    painter.rect_filled(tile6_rect, Rounding::ZERO, t6_bg);
    painter.text(
        Pos2::new(tile6_rect.min.x + 14.0, tile6_rect.min.y + 18.0),
        egui::Align2::LEFT_CENTER,
        "🔑 Pairing & Security Trust",
        FontId::proportional(13.0),
        Color32::WHITE,
    );
    painter.text(
        Pos2::new(tile6_rect.min.x + 14.0, tile6_rect.min.y + 38.0),
        egui::Align2::LEFT_CENTER,
        "Click to Forget Pairing (--forget-pairing)",
        FontId::proportional(11.0),
        Color32::from_rgb(255, 180, 180),
    );
    painter.text(
        Pos2::new(tile6_rect.min.x + 14.0, tile6_rect.max.y - 14.0),
        egui::Align2::LEFT_CENTER,
        "Clears stored X25519 pairing PSK and triggers fresh SAS code",
        FontId::proportional(9.5),
        COLOR_TEXT_MUTED,
    );
    if t6_hovered {
        egui::show_tooltip_text(ctx, egui::Id::new("t6_tip"), "Clear stored trust data to pair a new device");
    }
}
