//! The overlay's panel rects, shared by the draw code AND the pointer
//! hit-testing. Change a size here and both stay in agreement — duplicated
//! magic numbers in two places is how clicks end up eaten by an invisible
//! zone or leaking to the phone.
//!
//! All rects are in egui logical pixels (physical px / scale factor).

use egui::{Pos2, Rect, Vec2};

pub const TASKBAR_HEIGHT: f32 = 42.0;
pub const START_MENU_WIDTH: f32 = 620.0;
pub const START_MENU_MAX_HEIGHT: f32 = 510.0;
pub const ACTION_CENTER_WIDTH: f32 = 340.0;

pub fn taskbar_rect(screen: Rect) -> Rect {
    Rect::from_min_size(
        Pos2::new(screen.min.x, screen.max.y - TASKBAR_HEIGHT),
        Vec2::new(screen.width(), TASKBAR_HEIGHT),
    )
}

pub fn start_menu_rect(screen: Rect) -> Rect {
    let width = START_MENU_WIDTH.min(screen.width());
    let height = START_MENU_MAX_HEIGHT.min(screen.max.y - TASKBAR_HEIGHT);
    Rect::from_min_size(
        Pos2::new(screen.min.x, screen.max.y - TASKBAR_HEIGHT - height),
        Vec2::new(width, height),
    )
}

pub fn action_center_rect(screen: Rect) -> Rect {
    Rect::from_min_size(
        Pos2::new(screen.max.x - ACTION_CENTER_WIDTH, screen.min.y),
        Vec2::new(ACTION_CENTER_WIDTH, screen.max.y - TASKBAR_HEIGHT),
    )
}
