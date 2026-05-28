pub mod protocol {
    include!(concat!(env!("OUT_DIR"), "/protocol.rs"));
}

pub mod input {
    include!(concat!(env!("OUT_DIR"), "/zc_protocol.rs")); // Assuming prost generates zc_protocol for package zc_protocol, or maybe input.rs. Wait!
    // Actually the package name determines it. Let's just include video.
}

pub mod video {
    include!(concat!(env!("OUT_DIR"), "/zc_protocol.rs"));
}
