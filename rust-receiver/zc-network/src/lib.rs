pub type QuinnError = Box<dyn std::error::Error + Send + Sync>;

pub mod client;

pub use client::{connect, ConnectionPhase, ScanError};
pub use zc_security::storage::delete_trust_data;
