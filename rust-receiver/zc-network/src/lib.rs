pub type QuinnError = Box<dyn std::error::Error + Send + Sync>;

pub mod client;

pub use client::{connect, ConnectionPhase, ScanError};
pub use zc_security::storage::{cleanup_legacy_trust, delete_trust_data};
