use std::env;
use std::path::PathBuf;
use std::process::exit;

fn main() {
    println!("cargo:rerun-if-changed=proto");

    let Ok(out_dir) = env::var("OUT_DIR") else {
        eprintln!("zc-protocol build.rs: OUT_DIR is not set; cargo is broken?");
        exit(1);
    };

    let mut config = prost_build::Config::new();
    config.out_dir(PathBuf::from(&out_dir));

    if let Err(e) = config.compile_protos(
        &["proto/protocol.proto", "proto/input.proto", "proto/video.proto", "proto/audio.proto"],
        &["proto"],
    ) {
        eprintln!("zc-protocol build.rs: protoc failed: {e}");
        eprintln!("(a missing protoc usually says \"no such file\"; check that protoc is on PATH or vendored)");
        exit(1);
    }
}
