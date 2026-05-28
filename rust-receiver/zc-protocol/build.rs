use std::env;
use std::path::PathBuf;

fn main() {
    let out_dir = PathBuf::from(env::var("OUT_DIR").unwrap());
    
    let mut config = prost_build::Config::new();
    config.out_dir(out_dir);

    config.compile_protos(
        &["proto/protocol.proto", "proto/input.proto"], 
        &["proto"]
    ).expect("Failed to compile protocol.proto");
}
