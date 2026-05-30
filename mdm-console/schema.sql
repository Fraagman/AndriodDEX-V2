CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,
    name TEXT,
    mac_address TEXT,
    status TEXT,
    last_seen INTEGER,
    user_email TEXT
);
CREATE TABLE IF NOT EXISTS policies (
    id TEXT PRIMARY KEY,
    name TEXT,
    clipboard_enabled INTEGER,
    file_transfer_enabled INTEGER,
    max_file_size INTEGER
);
CREATE TABLE IF NOT EXISTS audit_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER,
    device_id TEXT,
    event_type TEXT,
    details TEXT
);
