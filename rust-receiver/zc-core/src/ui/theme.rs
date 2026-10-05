use egui::{Color32, Pos2, Rect, Rounding, Stroke, Vec2};

pub const COLOR_TASKBAR_BG: Color32 = Color32::from_rgba_premultiplied(16, 16, 18, 245);
pub const COLOR_TASKBAR_BORDER: Color32 = Color32::from_rgb(45, 45, 48);
pub const COLOR_MENU_BG: Color32 = Color32::from_rgba_premultiplied(26, 26, 28, 252);
pub const COLOR_FLYOUT_BG: Color32 = Color32::from_rgba_premultiplied(31, 31, 34, 252);
pub const COLOR_ACCENT_BLUE: Color32 = Color32::from_rgb(0, 120, 215);
pub const COLOR_ACCENT_HOVER: Color32 = Color32::from_rgb(26, 140, 235);
pub const COLOR_HOVER_BG: Color32 = Color32::from_rgba_premultiplied(255, 255, 255, 25);
pub const COLOR_ACTIVE_BG: Color32 = Color32::from_rgba_premultiplied(255, 255, 255, 45);
pub const COLOR_TEXT_PRIMARY: Color32 = Color32::from_rgb(255, 255, 255);
pub const COLOR_TEXT_SECONDARY: Color32 = Color32::from_rgb(180, 180, 180);
pub const COLOR_TEXT_MUTED: Color32 = Color32::from_rgb(120, 120, 120);

pub fn is_hovered(ctx: &egui::Context, rect: Rect) -> bool {
    ctx.input(|i| i.pointer.hover_pos().map(|p| rect.contains(p)).unwrap_or(false))
}

pub fn is_clicked(ctx: &egui::Context, rect: Rect) -> bool {
    if !is_hovered(ctx, rect) {
        return false;
    }
    ctx.input(|i| {
        i.pointer.button_clicked(egui::PointerButton::Primary)
            || i.pointer.button_released(egui::PointerButton::Primary)
            || i.pointer.any_click()
    })
}

/// Draws the iconic Windows 10 four-quadrant logo.
pub fn draw_windows_logo(painter: &egui::Painter, center: Pos2, size: f32, color: Color32) {
    let half = size * 0.5;
    let gap = 1.5;
    let sq_size = half - gap * 0.5;

    // Top-left
    let tl = Rect::from_min_size(Pos2::new(center.x - half, center.y - half), Vec2::new(sq_size, sq_size));
    // Top-right
    let tr = Rect::from_min_size(Pos2::new(center.x + gap * 0.5, center.y - half), Vec2::new(sq_size, sq_size));
    // Bottom-left
    let bl = Rect::from_min_size(Pos2::new(center.x - half, center.y + gap * 0.5), Vec2::new(sq_size, sq_size));
    // Bottom-right
    let br = Rect::from_min_size(Pos2::new(center.x + gap * 0.5, center.y + gap * 0.5), Vec2::new(sq_size, sq_size));

    painter.rect_filled(tl, Rounding::ZERO, color);
    painter.rect_filled(tr, Rounding::ZERO, color);
    painter.rect_filled(bl, Rounding::ZERO, color);
    painter.rect_filled(br, Rounding::ZERO, color);
}

/// Draws an acrylic styled card/window with Windows 10 border and background.
pub fn draw_acrylic_panel(painter: &egui::Painter, rect: Rect, bg: Color32, border_color: Color32) {
    painter.rect(
        rect,
        Rounding::same(2.0),
        bg,
        Stroke::new(1.0, border_color),
    );
}
