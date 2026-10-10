use std::collections::HashSet;
use egui::Color32;
use zc_protocol::protocol::NavAction;

/// Actions requested by the UI that must be processed by the main loop.
#[derive(Default, Clone, Debug)]
pub struct UiActions {
    /// Request an immediate H.264 IDR keyframe over QUIC.
    pub request_keyframe: bool,
    /// Toggle window fullscreen state.
    pub toggle_fullscreen: bool,
    /// Toggle audio mute state.
    pub toggle_mute: bool,
    /// Adjust audio volume (0.0 to 1.0).
    pub volume: Option<f32>,
    /// Drop connection and reconnect immediately.
    pub reconnect: bool,
    /// Clear saved pairing keys and certificate trust.
    pub forget_pairing: bool,
    /// Launch an Android workspace app via package name.
    pub launch_app: Option<&'static str>,
    /// Global navigation (back / home / recents) performed on the phone.
    pub nav_action: Option<NavAction>,
    /// Toggle kiosk mode locking.
    pub toggle_kiosk: bool,
    /// Exit the application cleanly.
    pub exit_app: bool,
    /// egui wants a repaint after this delay (an animation or a scheduled clock
    /// update). The event loop must wake for it: with the render loop otherwise
    /// event-driven, the only alternatives are a frozen clock or a 100% CPU spin.
    pub repaint_after: Option<std::time::Duration>,
}

/// Metadata for an app accessible from the Windows 10 Taskbar and Start Menu.
#[allow(dead_code)]
#[derive(Clone, Debug)]
pub struct AppMeta {
    pub id: &'static str,
    pub name: &'static str,
    pub category: &'static str,
    pub subtitle: &'static str,
    pub package_name: &'static str,
    pub icon_symbol: &'static str,
    pub accent_color: Color32,
    pub is_pinned: bool,
}

pub const ALL_APPS: &[AppMeta] = &[
    AppMeta {
        id: "browser",
        name: "Web Browser",
        category: "Internet & Networking",
        subtitle: "Android Web Explorer",
        package_name: "com.androiddex.browser",
        icon_symbol: "🌐",
        accent_color: Color32::from_rgb(0, 120, 215), // Windows Blue
        is_pinned: true,
    },
    AppMeta {
        id: "files",
        name: "File Explorer",
        category: "System Tools",
        subtitle: "Device Files & Storage",
        package_name: "com.androiddex.files",
        icon_symbol: "📁",
        accent_color: Color32::from_rgb(242, 200, 17), // Explorer Gold
        is_pinned: true,
    },
    AppMeta {
        id: "terminal",
        name: "Command Terminal",
        category: "Development",
        subtitle: "Linux & Android Shell",
        package_name: "com.androiddex.terminal",
        icon_symbol: "📟",
        accent_color: Color32::from_rgb(16, 124, 65), // Terminal Green
        is_pinned: true,
    },
    AppMeta {
        id: "settings",
        name: "Settings",
        category: "System Tools",
        subtitle: "Host & Display Configuration",
        package_name: "com.androiddex.settings",
        icon_symbol: "⚙",
        accent_color: Color32::from_rgb(116, 77, 169), // Windows Purple
        is_pinned: true,
    },
];

// Note: the phone's AppRegistry defines exactly the five apps above. Entries for
// packages the phone does not register (e.g. com.androiddex.security) were removed:
// the phone validates openApp requests against its registry and rejects unknown ones.


#[derive(Clone, Debug)]
pub struct NotificationItem {
    pub title: String,
    pub message: String,
    pub time: String,
}

/// Persistent state of the Windows 10 UI.
#[derive(Clone, Debug)]
pub struct UiState {
    pub start_menu_open: bool,
    pub action_center_open: bool,
    pub settings_open: bool,
    pub diagnostics_open: bool,
    pub volume_flyout_open: bool,
    pub network_flyout_open: bool,
    pub calendar_flyout_open: bool,
    pub search_query: String,
    pub is_muted: bool,
    pub volume_level: f32,
    pub show_hud_stats: bool,
    pub notifications: Vec<NotificationItem>,
    pub running_apps: HashSet<&'static str>,
    pub active_settings_tab: &'static str,
    /// First click on the destructive "Forget Pairing" button arms it; the
    /// second click confirms. Reset when the pointer leaves the button.
    pub forget_pairing_armed: bool,
}

impl Default for UiState {
    fn default() -> Self {
        // No seeded notification: "AndroidDEX Ready — link active" before anything
        // is connected was a lie on every launch. Real state is visible in the
        // status line and the phase banner instead.
        Self {
            start_menu_open: false,
            action_center_open: false,
            settings_open: false,
            diagnostics_open: false,
            volume_flyout_open: false,
            network_flyout_open: false,
            calendar_flyout_open: false,
            search_query: String::new(),
            is_muted: false,
            volume_level: 1.0,
            show_hud_stats: true,
            notifications: Vec::new(),
            running_apps: HashSet::new(),
            active_settings_tab: "System",
            forget_pairing_armed: false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_app_registry_uniqueness() {
        let mut ids = HashSet::new();
        let mut pkgs = HashSet::new();
        for app in ALL_APPS {
            assert!(ids.insert(app.id), "Duplicate app id: {}", app.id);
            assert!(pkgs.insert(app.package_name), "Duplicate package name: {}", app.package_name);
            assert!(!app.name.is_empty(), "App name must not be empty");
            assert!(!app.icon_symbol.is_empty(), "App icon must not be empty");
        }
        // Must mirror the phone's AppRegistry: browser, files, terminal, settings.
        // VS Code was removed "for now" (summary.md §3.4); re-add both sides together.
        assert!(ALL_APPS.len() >= 4, "Must have the phone-registered apps");
    }

    #[test]
    fn test_ui_state_defaults() {
        let state = UiState::default();
        assert!(!state.start_menu_open);
        assert!(!state.action_center_open);
        assert!(!state.settings_open);
        assert!(!state.is_muted);
        assert_eq!(state.volume_level, 1.0);
        assert!(state.show_hud_stats);
        assert!(state.notifications.is_empty(), "no seeded notification: the startup 'link active' one was a lie");
        assert_eq!(state.active_settings_tab, "System");
    }

    #[test]
    fn test_ui_actions_default() {
        let actions = UiActions::default();
        assert!(!actions.request_keyframe);
        assert!(!actions.toggle_fullscreen);
        assert!(!actions.toggle_mute);
        assert!(actions.volume.is_none());
        assert!(!actions.reconnect);
        assert!(!actions.forget_pairing);
        assert!(actions.launch_app.is_none());
        assert!(!actions.exit_app);
    }
}

