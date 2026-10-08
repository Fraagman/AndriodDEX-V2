use egui::{Color32, FontId, Pos2, Rect, Rounding, Stroke, Vec2};
use zc_network::ConnectionPhase;
use zc_protocol::protocol::NavAction;
use super::theme::*;
use super::types::{UiActions, UiState, ALL_APPS};

#[cfg(windows)]
fn get_local_time_and_date() -> (String, String) {
    unsafe {
        #[repr(C)]
        struct SystemTimeWin {
            w_year: u16,
            w_month: u16,
            w_day_of_week: u16,
            w_day: u16,
            w_hour: u16,
            w_minute: u16,
            w_second: u16,
            w_milliseconds: u16,
        }
        extern "system" {
            fn GetLocalTime(lp_system_time: *mut SystemTimeWin);
        }
        let mut st = SystemTimeWin {
            w_year: 0, w_month: 0, w_day_of_week: 0, w_day: 0,
            w_hour: 0, w_minute: 0, w_second: 0, w_milliseconds: 0,
        };
        GetLocalTime(&mut st);
        let hour_12 = if st.w_hour == 0 { 12 } else if st.w_hour > 12 { st.w_hour - 12 } else { st.w_hour };
        let am_pm = if st.w_hour >= 12 { "PM" } else { "AM" };
        let time_str = format!("{}:{:02} {}", hour_12, st.w_minute, am_pm);
        let date_str = format!("{}/{}/{}", st.w_month, st.w_day, st.w_year);
        (time_str, date_str)
    }
}

#[cfg(not(windows))]
fn get_local_time_and_date() -> (String, String) {
    ("12:00 PM".to_string(), "10/05/2026".to_string())
}

pub fn render_taskbar(
    ctx: &egui::Context,
    ui_state: &mut UiState,
    actions: &mut UiActions,
    phase: &ConnectionPhase,
    _has_video: bool,
    decode_fps: u32,
    _decode_us: u64,
) {
    let screen_rect = ctx.screen_rect();
    let taskbar_height = 42.0;
    let taskbar_rect = Rect::from_min_size(
        Pos2::new(screen_rect.min.x, screen_rect.max.y - taskbar_height),
        Vec2::new(screen_rect.width(), taskbar_height),
    );

    let painter = ctx.layer_painter(egui::LayerId::new(egui::Order::Foreground, egui::Id::new("taskbar")));

    // Background and top subtle border
    painter.rect_filled(taskbar_rect, Rounding::ZERO, COLOR_TASKBAR_BG);
    painter.line_segment(
        [
            taskbar_rect.min,
            Pos2::new(taskbar_rect.max.x, taskbar_rect.min.y),
        ],
        Stroke::new(1.0, COLOR_TASKBAR_BORDER),
    );

    // Left elements (Start button, Search bar, Task View, Pinned Apps)
    let mut current_x = taskbar_rect.min.x;
    let search_width = 180.0;
    let task_view_width = 40.0;

    // --- 1. Start Button ---
    let start_btn_width = 48.0;
    let start_btn_rect = Rect::from_min_size(
        Pos2::new(current_x, taskbar_rect.min.y),
        Vec2::new(start_btn_width, taskbar_height),
    );

    let start_hovered = is_hovered(ctx, start_btn_rect);
    let start_clicked = is_clicked(ctx, start_btn_rect);

    if start_clicked {
        ui_state.start_menu_open = !ui_state.start_menu_open;
        if ui_state.start_menu_open {
            ui_state.action_center_open = false;
        }
    }

    let start_bg = if ui_state.start_menu_open {
        COLOR_ACTIVE_BG
    } else if start_hovered {
        COLOR_HOVER_BG
    } else {
        Color32::TRANSPARENT
    };
    if start_bg != Color32::TRANSPARENT {
        painter.rect_filled(start_btn_rect, Rounding::ZERO, start_bg);
    }
    let logo_color = if start_hovered || ui_state.start_menu_open {
        COLOR_ACCENT_BLUE
    } else {
        COLOR_TEXT_PRIMARY
    };
    draw_windows_logo(&painter, start_btn_rect.center(), 16.0, logo_color);
    if start_hovered {
        egui::show_tooltip_text(ctx, egui::Id::new("start_tip"), "Start");
    }
    current_x += start_btn_width;

    // Responsive left cluster: the system tray on the right is fixed-width, so on
    // narrow windows the search box, task view and pinned apps are dropped in that
    // order (they all remain reachable through the Start menu) instead of colliding
    // with the tray.
    let right_tray_width = 4.0 + 38.0 + 72.0 + 34.0 + 65.0 + 34.0 + 40.0 + 32.0 * 3.0 + 34.0;
    let left_limit = taskbar_rect.max.x - right_tray_width - 8.0;

    // --- 2. Search Box (first to hide) ---
    let show_search = current_x + search_width + 8.0 + 44.0 <= left_limit;
    if show_search {
        let search_box_rect = Rect::from_min_size(
            Pos2::new(current_x + 4.0, taskbar_rect.min.y + 6.0),
            Vec2::new(search_width, taskbar_height - 12.0),
        );
        painter.rect(
            search_box_rect,
            Rounding::same(2.0),
            Color32::from_rgb(32, 32, 36),
            Stroke::new(1.0, Color32::from_rgb(55, 55, 60)),
        );
        painter.text(
            Pos2::new(search_box_rect.min.x + 8.0, search_box_rect.center().y),
            egui::Align2::LEFT_CENTER,
            "⌕",
            FontId::proportional(14.0),
            COLOR_TEXT_SECONDARY,
        );

        egui::Area::new(egui::Id::new("taskbar_search_area"))
            .fixed_pos(Pos2::new(search_box_rect.min.x + 24.0, search_box_rect.min.y + 2.0))
            .order(egui::Order::Foreground)
            .show(ctx, |ui| {
                ui.set_max_width(search_width - 30.0);
                let edit_resp = ui.add(
                    egui::TextEdit::singleline(&mut ui_state.search_query)
                        .hint_text("Type here to search")
                        .frame(false)
                        .text_color(COLOR_TEXT_PRIMARY)
                        .font(FontId::proportional(12.0)),
                );
                if edit_resp.changed() && !ui_state.search_query.is_empty() {
                    ui_state.start_menu_open = true;
                }
            });

        current_x += search_width + 8.0;
    }

    // --- 3. Task View Button (⧉) — keeps room for at least one pinned app ---
    if current_x + task_view_width + 44.0 <= left_limit {
        let task_view_rect = Rect::from_min_size(
            Pos2::new(current_x, taskbar_rect.min.y),
            Vec2::new(task_view_width, taskbar_height),
        );
        let tv_hovered = is_hovered(ctx, task_view_rect);
        let tv_clicked = is_clicked(ctx, task_view_rect);
        if tv_clicked {
            ui_state.diagnostics_open = !ui_state.diagnostics_open;
        }
        if tv_hovered || ui_state.diagnostics_open {
            painter.rect_filled(task_view_rect, Rounding::ZERO, COLOR_HOVER_BG);
        }
        painter.text(
            task_view_rect.center(),
            egui::Align2::CENTER_CENTER,
            "⧉",
            FontId::proportional(15.0),
            if ui_state.diagnostics_open { COLOR_ACCENT_BLUE } else { COLOR_TEXT_PRIMARY },
        );
        if tv_hovered {
            egui::show_tooltip_text(ctx, egui::Id::new("tv_tip"), "Task View & Diagnostics HUD");
        }
        current_x += task_view_width + 4.0;

        // Subtle divider
        painter.line_segment(
            [
                Pos2::new(current_x, taskbar_rect.min.y + 10.0),
                Pos2::new(current_x, taskbar_rect.max.y - 10.0),
            ],
            Stroke::new(1.0, Color32::from_rgb(50, 50, 54)),
        );
        current_x += 6.0;
    }

    // --- 4. Pinned Apps (as many as fit) ---
    for app in ALL_APPS.iter().filter(|a| a.is_pinned) {
        if current_x + 44.0 > left_limit {
            break; // window too narrow — the app stays available in the Start menu
        }
        let app_rect = Rect::from_min_size(
            Pos2::new(current_x, taskbar_rect.min.y),
            Vec2::new(44.0, taskbar_height),
        );
        let app_hovered = is_hovered(ctx, app_rect);
        let app_clicked = is_clicked(ctx, app_rect);

        if app_clicked {
            actions.launch_app = Some(app.package_name);
            ui_state.running_apps.insert(app.id);
            if app.id == "settings" {
                ui_state.settings_open = true;
            }
        }

        let is_running = ui_state.running_apps.contains(app.id);
        if app_hovered {
            painter.rect_filled(app_rect, Rounding::ZERO, COLOR_HOVER_BG);
        }

        // Draw icon
        painter.text(
            app_rect.center() - Vec2::new(0.0, 2.0),
            egui::Align2::CENTER_CENTER,
            app.icon_symbol,
            FontId::proportional(18.0),
            Color32::WHITE,
        );

        // Windows 10 active underline bar for running apps
        if is_running {
            let bar_rect = Rect::from_min_size(
                Pos2::new(app_rect.min.x + 8.0, app_rect.max.y - 3.0),
                Vec2::new(app_rect.width() - 16.0, 2.0),
            );
            painter.rect_filled(bar_rect, Rounding::same(1.0), COLOR_ACCENT_BLUE);
        }

        if app_hovered {
            egui::show_tooltip_text(ctx, egui::Id::new(format!("tip_{}", app.id)), format!("{}\n{}", app.name, app.subtitle));
        }
        current_x += 44.0;
    }

    // =========================================================================
    // Right Side System Tray (Notification Area)
    // =========================================================================
    let mut right_x = taskbar_rect.max.x;

    // --- Show Desktop Sliver (4px) ---
    right_x -= 4.0;
    let show_desktop_rect = Rect::from_min_size(
        Pos2::new(right_x, taskbar_rect.min.y),
        Vec2::new(4.0, taskbar_height),
    );
    if is_hovered(ctx, show_desktop_rect) {
        painter.rect_filled(show_desktop_rect, Rounding::ZERO, Color32::from_rgb(100, 100, 100));
        egui::show_tooltip_text(ctx, egui::Id::new("sd_tip"), "Show Desktop");
    }

    // --- Action Center Button (💬) ---
    right_x -= 38.0;
    let ac_rect = Rect::from_min_size(
        Pos2::new(right_x, taskbar_rect.min.y),
        Vec2::new(38.0, taskbar_height),
    );
    let ac_hovered = is_hovered(ctx, ac_rect);
    let ac_clicked = is_clicked(ctx, ac_rect);
    if ac_clicked {
        ui_state.action_center_open = !ui_state.action_center_open;
        if ui_state.action_center_open {
            ui_state.start_menu_open = false;
        }
    }
    if ac_hovered || ui_state.action_center_open {
        painter.rect_filled(ac_rect, Rounding::ZERO, COLOR_HOVER_BG);
    }
    painter.text(
        ac_rect.center(),
        egui::Align2::CENTER_CENTER,
        "💬",
        FontId::proportional(14.0),
        if ui_state.action_center_open { COLOR_ACCENT_BLUE } else { COLOR_TEXT_PRIMARY },
    );
    if ac_hovered {
        egui::show_tooltip_text(ctx, egui::Id::new("ac_tip"), "Action Center & Quick Actions");
    }

    // --- Clock and Date (2-line layout) ---
    let (time_str, date_str) = get_local_time_and_date();
    right_x -= 72.0;
    let clock_rect = Rect::from_min_size(
        Pos2::new(right_x, taskbar_rect.min.y),
        Vec2::new(70.0, taskbar_height),
    );
    let clock_hovered = is_hovered(ctx, clock_rect);
    let clock_clicked = is_clicked(ctx, clock_rect);
    if clock_clicked {
        ui_state.calendar_flyout_open = !ui_state.calendar_flyout_open;
    }
    if clock_hovered || ui_state.calendar_flyout_open {
        painter.rect_filled(clock_rect, Rounding::ZERO, COLOR_HOVER_BG);
    }
    painter.text(
        Pos2::new(clock_rect.center().x, clock_rect.min.y + 12.0),
        egui::Align2::CENTER_CENTER,
        time_str,
        FontId::proportional(11.0),
        COLOR_TEXT_PRIMARY,
    );
    painter.text(
        Pos2::new(clock_rect.center().x, clock_rect.min.y + 28.0),
        egui::Align2::CENTER_CENTER,
        date_str,
        FontId::proportional(10.0),
        COLOR_TEXT_SECONDARY,
    );
    if clock_hovered {
        egui::show_tooltip_text(ctx, egui::Id::new("clock_tip"), "Date and Time");
    }

    // --- Fullscreen Toggle Button (🖥) ---
    right_x -= 34.0;
    let fs_rect = Rect::from_min_size(
        Pos2::new(right_x, taskbar_rect.min.y),
        Vec2::new(32.0, taskbar_height),
    );
    let fs_hovered = is_hovered(ctx, fs_rect);
    let fs_clicked = is_clicked(ctx, fs_rect);
    if fs_clicked {
        actions.toggle_fullscreen = true;
    }
    if fs_hovered {
        painter.rect_filled(fs_rect, Rounding::ZERO, COLOR_HOVER_BG);
        egui::show_tooltip_text(ctx, egui::Id::new("fs_tip"), "Toggle Fullscreen (F11)");
    }
    painter.text(
        fs_rect.center(),
        egui::Align2::CENTER_CENTER,
        "🖥",
        FontId::proportional(13.0),
        COLOR_TEXT_PRIMARY,
    );

    // --- Performance Badge (FPS) ---
    right_x -= 65.0;
    let perf_rect = Rect::from_min_size(
        Pos2::new(right_x, taskbar_rect.min.y),
        Vec2::new(62.0, taskbar_height),
    );
    let perf_hovered = is_hovered(ctx, perf_rect);
    let perf_clicked = is_clicked(ctx, perf_rect);
    if perf_clicked {
        ui_state.diagnostics_open = !ui_state.diagnostics_open;
    }
    if perf_hovered || ui_state.diagnostics_open {
        painter.rect_filled(perf_rect, Rounding::ZERO, COLOR_HOVER_BG);
        egui::show_tooltip_text(ctx, egui::Id::new("perf_tip"), "Stream Performance & Frame Telemetry");
    }
    let fps_color = if decode_fps >= 50 {
        Color32::from_rgb(50, 205, 50)
    } else if decode_fps >= 25 {
        Color32::from_rgb(255, 200, 0)
    } else {
        Color32::from_rgb(255, 80, 80)
    };
    painter.text(
        perf_rect.center(),
        egui::Align2::CENTER_CENTER,
        format!("{} fps", decode_fps),
        FontId::proportional(11.0),
        fps_color,
    );

    // --- Volume / Audio Button (🔊 / 🔇) ---
    right_x -= 34.0;
    let vol_rect = Rect::from_min_size(
        Pos2::new(right_x, taskbar_rect.min.y),
        Vec2::new(32.0, taskbar_height),
    );
    let vol_hovered = is_hovered(ctx, vol_rect);
    let vol_clicked = is_clicked(ctx, vol_rect);
    if vol_clicked {
        ui_state.volume_flyout_open = !ui_state.volume_flyout_open;
    }
    if vol_hovered || ui_state.volume_flyout_open {
        painter.rect_filled(vol_rect, Rounding::ZERO, COLOR_HOVER_BG);
        egui::show_tooltip_text(ctx, egui::Id::new("vol_tip"), "Speakers & Audio Settings");
    }
    let vol_icon = if ui_state.is_muted || ui_state.volume_level == 0.0 {
        "🔇"
    } else {
        "🔊"
    };
    painter.text(
        vol_rect.center(),
        egui::Align2::CENTER_CENTER,
        vol_icon,
        FontId::proportional(13.0),
        COLOR_TEXT_PRIMARY,
    );

    // --- Navigation Buttons (Back / Home / Recents) ---
    // Global navigation performed on the phone through its accessibility service.
    // Without that optional service the phone ignores the events, so the buttons
    // stay visible and simply do nothing.
    let nav_buttons: [(&str, NavAction, &str); 3] = [
        ("←", NavAction::NavBack, "Back"),
        ("⌂", NavAction::NavHome, "Home"),
        ("▤", NavAction::NavRecents, "Recents"),
    ];
    for (glyph, nav_action, tip) in nav_buttons.iter() {
        right_x -= 32.0;
        let nav_rect = Rect::from_min_size(
            Pos2::new(right_x, taskbar_rect.min.y),
            Vec2::new(32.0, taskbar_height),
        );
        let nav_hovered = is_hovered(ctx, nav_rect);
        let nav_clicked = is_clicked(ctx, nav_rect);
        if nav_clicked {
            actions.nav_action = Some(*nav_action);
        }
        if nav_hovered {
            painter.rect_filled(nav_rect, Rounding::ZERO, COLOR_HOVER_BG);
            egui::show_tooltip_text(
                ctx,
                egui::Id::new(format!("nav_{}_tip", tip)),
                format!("{} (needs the AndroidDEX accessibility service on the phone)", tip),
            );
        }
        painter.text(
            nav_rect.center(),
            egui::Align2::CENTER_CENTER,
            *glyph,
            FontId::proportional(13.0),
            COLOR_TEXT_PRIMARY,
        );
    }

    // --- Network Status Icon (📶 / Ethernet) ---
    right_x -= 40.0;
    let net_rect = Rect::from_min_size(
        Pos2::new(right_x, taskbar_rect.min.y),
        Vec2::new(38.0, taskbar_height),
    );
    let net_hovered = is_hovered(ctx, net_rect);
    let net_clicked = is_clicked(ctx, net_rect);
    if net_clicked {
        ui_state.network_flyout_open = !ui_state.network_flyout_open;
    }
    if net_hovered || ui_state.network_flyout_open {
        painter.rect_filled(net_rect, Rounding::ZERO, COLOR_HOVER_BG);
        egui::show_tooltip_text(ctx, egui::Id::new("net_tip"), "Network & QUIC Connection Status");
    }
    let net_color = match phase {
        ConnectionPhase::Connected => Color32::from_rgb(0, 220, 100),
        ConnectionPhase::Handshaking | ConnectionPhase::Scanning(_, _) | ConnectionPhase::Found(_) => {
            Color32::from_rgb(255, 190, 40)
        }
        _ => Color32::from_rgb(255, 80, 80),
    };
    painter.text(
        net_rect.center(),
        egui::Align2::CENTER_CENTER,
        "🖧",
        FontId::proportional(14.0),
        net_color,
    );

    // --- Keyframe Request Button (⚡) ---
    right_x -= 34.0;
    let kf_rect = Rect::from_min_size(
        Pos2::new(right_x, taskbar_rect.min.y),
        Vec2::new(32.0, taskbar_height),
    );
    let kf_hovered = is_hovered(ctx, kf_rect);
    let kf_clicked = is_clicked(ctx, kf_rect);
    if kf_clicked {
        actions.request_keyframe = true;
    }
    if kf_hovered {
        painter.rect_filled(kf_rect, Rounding::ZERO, COLOR_HOVER_BG);
        egui::show_tooltip_text(ctx, egui::Id::new("kf_tip"), "Request Instant Video Keyframe (Refresh Display)");
    }
    painter.text(
        kf_rect.center(),
        egui::Align2::CENTER_CENTER,
        "⚡",
        FontId::proportional(14.0),
        Color32::from_rgb(255, 215, 0),
    );
}
