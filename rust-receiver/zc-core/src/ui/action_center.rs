use egui::{Color32, FontId, Pos2, Rect, Rounding, Stroke, Vec2};
use super::theme::*;
use super::types::{UiActions, UiState};

pub fn render_action_center(
    ctx: &egui::Context,
    ui_state: &mut UiState,
    actions: &mut UiActions,
    is_kiosk: bool,
) {
    if !ui_state.action_center_open {
        return;
    }

    let screen_rect = ctx.screen_rect();
    let taskbar_height = 42.0;
    let ac_width = 340.0;
    let ac_height = screen_rect.height() - taskbar_height;

    let ac_rect = Rect::from_min_size(
        Pos2::new(screen_rect.max.x - ac_width, screen_rect.min.y),
        Vec2::new(ac_width, ac_height),
    );

    // Dismiss on outside click
    if ctx.input(|i| i.pointer.any_click()) {
        if let Some(pos) = ctx.input(|i| i.pointer.interact_pos()) {
            if !ac_rect.contains(pos) && pos.y < screen_rect.max.y - taskbar_height {
                ui_state.action_center_open = false;
                return;
            }
        }
    }

    let painter = ctx.layer_painter(egui::LayerId::new(egui::Order::Foreground, egui::Id::new("action_center")));

    // Background & left border
    draw_acrylic_panel(&painter, ac_rect, COLOR_FLYOUT_BG, COLOR_TASKBAR_BORDER);

    // Header
    painter.text(
        Pos2::new(ac_rect.min.x + 16.0, ac_rect.min.y + 24.0),
        egui::Align2::LEFT_CENTER,
        "Action Center",
        FontId::proportional(15.0),
        COLOR_TEXT_PRIMARY,
    );

    let clear_rect = Rect::from_min_size(
        Pos2::new(ac_rect.max.x - 76.0, ac_rect.min.y + 12.0),
        Vec2::new(64.0, 24.0),
    );
    let clear_hovered = is_hovered(ctx, clear_rect);
    let clear_clicked = is_clicked(ctx, clear_rect);
    if clear_clicked {
        ui_state.notifications.clear();
    }
    if clear_hovered {
        painter.rect_filled(clear_rect, Rounding::same(2.0), COLOR_HOVER_BG);
    }
    painter.text(
        clear_rect.center(),
        egui::Align2::CENTER_CENTER,
        "Clear all",
        FontId::proportional(11.0),
        COLOR_ACCENT_BLUE,
    );

    // Notifications List
    let mut notif_y = ac_rect.min.y + 48.0;
    if ui_state.notifications.is_empty() {
        painter.text(
            Pos2::new(ac_rect.center().x, notif_y + 40.0),
            egui::Align2::CENTER_CENTER,
            "No new notifications",
            FontId::proportional(13.0),
            COLOR_TEXT_MUTED,
        );
    } else {
        for notif in ui_state.notifications.iter().take(4) {
            let card_rect = Rect::from_min_size(
                Pos2::new(ac_rect.min.x + 12.0, notif_y),
                Vec2::new(ac_width - 24.0, 60.0),
            );
            painter.rect(
                card_rect,
                Rounding::same(2.0),
                Color32::from_rgb(22, 22, 25),
                Stroke::new(1.0, Color32::from_rgb(45, 45, 50)),
            );
            painter.text(
                Pos2::new(card_rect.min.x + 10.0, card_rect.min.y + 14.0),
                egui::Align2::LEFT_CENTER,
                &notif.title,
                FontId::proportional(12.0),
                COLOR_ACCENT_BLUE,
            );
            painter.text(
                Pos2::new(card_rect.max.x - 10.0, card_rect.min.y + 14.0),
                egui::Align2::RIGHT_CENTER,
                &notif.time,
                FontId::proportional(10.0),
                COLOR_TEXT_MUTED,
            );
            painter.text(
                Pos2::new(card_rect.min.x + 10.0, card_rect.min.y + 36.0),
                egui::Align2::LEFT_CENTER,
                &notif.message,
                FontId::proportional(11.0),
                COLOR_TEXT_SECONDARY,
            );

            notif_y += 68.0;
        }
    }

    // =========================================================================
    // Bottom: 4x2 Quick Action Tiles
    // =========================================================================
    let qa_bottom = ac_rect.max.y - 12.0;
    let qa_y = qa_bottom - (2.0 * 56.0 + 8.0);
    painter.line_segment(
        [Pos2::new(ac_rect.min.x + 12.0, qa_y - 10.0), Pos2::new(ac_rect.max.x - 12.0, qa_y - 10.0)],
        Stroke::new(1.0, Color32::from_rgb(40, 40, 45)),
    );

    let tile_w = (ac_width - 24.0 - 18.0) / 4.0;
    let tile_h = 56.0;

    struct QuickAction {
        id: &'static str,
        label: &'static str,
        icon: &'static str,
        active: bool,
    }

    let quick_actions = [
        QuickAction { id: "qa_fs", label: "Fullscreen", icon: "🖥", active: false },
        QuickAction { id: "qa_kf", label: "Keyframe", icon: "⚡", active: false },
        QuickAction { id: "qa_mute", label: "Mute", icon: "🔊", active: ui_state.is_muted },
        QuickAction { id: "qa_hud", label: "Stats HUD", icon: "📊", active: ui_state.show_hud_stats },
        QuickAction { id: "qa_kiosk", label: "Kiosk", icon: "🔒", active: is_kiosk },
        QuickAction { id: "qa_reconn", label: "Reconnect", icon: "🔄", active: false },
        QuickAction { id: "qa_sec", label: "Security", icon: "🛡", active: false },
        QuickAction { id: "qa_sett", label: "Settings", icon: "⚙", active: ui_state.settings_open },
    ];

    for (i, qa) in quick_actions.iter().enumerate() {
        let col = i % 4;
        let row = i / 4;
        let q_x = ac_rect.min.x + 12.0 + (col as f32) * (tile_w + 6.0);
        let q_y = qa_y + (row as f32) * (tile_h + 6.0);
        let q_rect = Rect::from_min_size(Pos2::new(q_x, q_y), Vec2::new(tile_w, tile_h));

        let q_hovered = is_hovered(ctx, q_rect);
        let q_clicked = is_clicked(ctx, q_rect);
        if q_clicked {
            match qa.id {
                "qa_fs" => actions.toggle_fullscreen = true,
                "qa_kf" => actions.request_keyframe = true,
                "qa_mute" => {
                    ui_state.is_muted = !ui_state.is_muted;
                    actions.toggle_mute = true;
                }
                "qa_hud" => ui_state.show_hud_stats = !ui_state.show_hud_stats,
                "qa_kiosk" => actions.toggle_kiosk = true,
                "qa_reconn" => actions.reconnect = true,
                "qa_sec" => {
                    ui_state.active_settings_tab = "Security";
                    ui_state.settings_open = true;
                }
                "qa_sett" => ui_state.settings_open = true,
                _ => {}
            }
        }

        let bg_color = if qa.active {
            COLOR_ACCENT_BLUE
        } else if q_hovered {
            COLOR_HOVER_BG
        } else {
            Color32::from_rgb(26, 26, 30)
        };

        painter.rect(
            q_rect,
            Rounding::same(2.0),
            bg_color,
            Stroke::new(1.0, Color32::from_rgb(45, 45, 50)),
        );

        painter.text(
            Pos2::new(q_rect.center().x, q_rect.min.y + 18.0),
            egui::Align2::CENTER_CENTER,
            qa.icon,
            FontId::proportional(16.0),
            if qa.active { Color32::WHITE } else { COLOR_TEXT_PRIMARY },
        );
        painter.text(
            Pos2::new(q_rect.center().x, q_rect.max.y - 12.0),
            egui::Align2::CENTER_CENTER,
            qa.label,
            FontId::proportional(9.5),
            if qa.active { Color32::WHITE } else { COLOR_TEXT_SECONDARY },
        );
    }
}
