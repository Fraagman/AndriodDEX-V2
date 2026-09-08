# AndroidDEX-V2

AndroidDEX is a low-latency Android Desktop Experience system that streams an Android desktop environment to a Windows PC over USB tethering. The Android device executes the desktop UI and applications, while the Windows client provides display output and mouse/keyboard input controls.

---

## Architecture & How It Works

1. **Desktop Rendering**: The Android host application renders a multi-window Compose desktop shell into a dedicated `VirtualDisplay` via `DisplayService`.
2. **Video Encoding**: Composited frames from the `VirtualDisplay` surface feed directly into a hardware `MediaCodec` H.264 video encoder (`ScreenEncoder`).
3. **Audio Capture**: Device audio is captured using `AudioPlaybackCaptureConfiguration` with `MediaProjection` (audio capture only) via `AudioCaptureService`.
4. **Transport**: Video, audio, and input data stream over a QUIC connection (UDP port 4433) across the USB tethering (RNDIS/NDIS) subnet.
5. **Windows Receiver**: The Rust receiver discovers the USB tethering adapter, establishes a TLS-authenticated QUIC connection, decodes H.264 frames using OpenH264, and renders frames via `wgpu`.
6. **Input Injection**: Windows mouse and keyboard events are captured by `zc-input`, serialized using Protocol Buffers, and sent back over QUIC to `InputManager` and `LocalInputDispatcher` on the Android device.

---

## System Requirements

| Component | Requirement |
|---|---|
| Android Host | Android 10+ (API 29+), USB Tethering support |
| Windows Client | Windows 10 / 11 (x86_64) |
| Rust Toolchain | Rust 1.85+ stable (required for edition 2024) |
| Android Build | JDK 17, Android SDK / NDK 26.1.10909125, Gradle 9.1.0 (supplied by `./gradlew` wrapper) |

---

## Tool Installation & Setup

### 1. Rust & NDK Toolchain
```bash
# Rust toolchain
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
rustc --version
cargo --version

# cargo-ndk for compiling the embedded QUIC server
cargo install cargo-ndk
cargo ndk --version
```

### 2. Android Build Tools
- Android Studio Koala (or newer) with Android SDK and NDK installed.
- Ensure `JAVA_HOME` points to JDK 17 (e.g., Android Studio's bundled JBR).

---

## Building the Project

### Android Host App
```bash
cd android-host
./gradlew :app:assembleDebug
```
This cross-compiles `rust_quic_server` for `arm64-v8a` and `x86_64` and packages the debug APK.

### Windows Receiver
```bash
cd rust-receiver
cargo build --workspace --release
```
The compiled receiver binary will be located at `rust-receiver/target/release/zc-core.exe`.

---

## Running AndroidDEX

1. Connect the Android device to the Windows PC via USB and enable **USB Tethering** in Android Settings.
2. Install and launch the Android host app on the device:
   ```bash
   adb install -r android-host/app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.example.androidhost/.MainActivity
   ```
3. Start the Windows receiver:
   ```bash
   cd rust-receiver
   cargo run --release -p zc-core
   ```
4. Complete first-time pairing by entering the 6-digit PIN displayed by the Windows receiver into the Android app.