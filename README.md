# AndriodDEX-V2

## TOOL INSTALLATION (Do This Once)

### Backend Tools (Windows/Linux/Mac)
```bash
# Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env

# Verify
rustc --version  # MUST show 1.80.0 or higher
cargo --version

# cargo-ndk (for Android Rust compilation)
cargo install cargo-ndk

# Verify
cargo ndk --version  # MUST show installed

# tcpdump / Wireshark (for network testing)
# Ubuntu: sudo apt-get install tcpdump
# macOS: brew install tcpdump
# Windows: Install Wireshark + npcap

# protoc (protobuf compiler)
# Ubuntu: sudo apt-get install -y protobuf-compiler
# macOS: brew install protobuf
# Windows: choco install protoc
protoc --version  # MUST show libprotoc 25+
```

### Frontend Tools
```bash
# Android Studio Koala or newer
# Download from: https://developer.android.com/studio

# Verify
./gradlew --version  # MUST show Gradle 8.7 or higher

# adb (Android Debug Bridge)
# Included with Android Studio at ~/Android/Sdk/platform-tools/
# Add to PATH

# Verify
adb devices  # MUST show your phone or emulator
adb --version
```

### Shared Tools
```bash
# Python 3 (for mock servers and hex inspection)
python3 --version  # MUST show 3.10+

# Install useful Python tools
pip install hexdump
```