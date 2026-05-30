#[cfg(target_os = "windows")]
use winreg::enums::*;
#[cfg(target_os = "windows")]
use winreg::RegKey;

const KIOSK_PROFILE_ID: &str = "{D91A54F1-52CB-4AF9-94D2-0B2129BC08D1}";

pub fn is_kiosk_mode() -> bool {
    #[cfg(target_os = "windows")]
    {
        let hklm = RegKey::predef(HKEY_LOCAL_MACHINE);
        let key_path = r#"SOFTWARE\Microsoft\Windows\AssignedAccessConfiguration\Profiles"#;
        if let Ok(profiles_key) = hklm.open_subkey_with_flags(key_path, KEY_READ) {
            if profiles_key.open_subkey_with_flags(KIOSK_PROFILE_ID, KEY_READ).is_ok() {
                return true;
            }
            // fallback: check if any subkey has AllowedApps with our AppUserModelId
            for profile_name in profiles_key.enum_keys().filter_map(|k| k.ok()) {
                if profile_name.contains(KIOSK_PROFILE_ID) {
                    return true;
                }
            }
        }
    }
    false
}
