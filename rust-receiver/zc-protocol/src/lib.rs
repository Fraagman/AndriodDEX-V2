pub mod protocol {
    include!(concat!(env!("OUT_DIR"), "/protocol.rs"));
}

pub mod video {
    include!(concat!(env!("OUT_DIR"), "/zc_protocol.rs"));
}

pub mod audio {
    include!(concat!(env!("OUT_DIR"), "/zc_audio.rs"));
}
