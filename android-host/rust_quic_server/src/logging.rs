//! Minimal logcat bridge.
//!
//! The crate deliberately avoids a logging framework: it needs three severity levels
//! and nothing else, and `__android_log_write` is a stable NDK symbol. On non-Android
//! hosts (used when running `cargo check`/`cargo test` on the dev machine) the same
//! macros fall through to stderr.

pub const TAG: &str = "RustQuicServer";

#[cfg(target_os = "android")]
mod imp {
    use std::ffi::CString;
    use std::os::raw::{c_char, c_int};

    extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }

    pub const PRIO_INFO: i32 = 4;
    pub const PRIO_WARN: i32 = 5;
    pub const PRIO_ERROR: i32 = 6;

    pub fn write(prio: i32, tag: &str, msg: &str) {
        // Interior NULs would truncate the message; replace rather than drop the line.
        let sanitized = msg.replace('\0', "?");
        let (Ok(tag), Ok(text)) = (CString::new(tag), CString::new(sanitized)) else {
            return;
        };
        unsafe {
            __android_log_write(prio, tag.as_ptr(), text.as_ptr());
        }
    }
}

#[cfg(not(target_os = "android"))]
mod imp {
    pub const PRIO_INFO: i32 = 4;
    pub const PRIO_WARN: i32 = 5;
    pub const PRIO_ERROR: i32 = 6;

    pub fn write(prio: i32, tag: &str, msg: &str) {
        let level = match prio {
            PRIO_ERROR => "E",
            PRIO_WARN => "W",
            _ => "I",
        };
        eprintln!("{}/{}: {}", level, tag, msg);
    }
}

pub use imp::{write, PRIO_ERROR, PRIO_INFO, PRIO_WARN};

#[macro_export]
macro_rules! log_i {
    ($($arg:tt)*) => {
        $crate::logging::write($crate::logging::PRIO_INFO, $crate::logging::TAG, &format!($($arg)*))
    };
}

#[macro_export]
macro_rules! log_w {
    ($($arg:tt)*) => {
        $crate::logging::write($crate::logging::PRIO_WARN, $crate::logging::TAG, &format!($($arg)*))
    };
}

#[macro_export]
macro_rules! log_e {
    ($($arg:tt)*) => {
        $crate::logging::write($crate::logging::PRIO_ERROR, $crate::logging::TAG, &format!($($arg)*))
    };
}
