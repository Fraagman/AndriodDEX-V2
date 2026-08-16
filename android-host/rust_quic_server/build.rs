fn main() {
    println!("cargo:rerun-if-changed=build.rs");

    // logging.rs calls __android_log_write directly rather than pulling in a logging
    // crate. That symbol lives in liblog.so, which is not linked by default.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo:rustc-link-lib=log");
    }
}
