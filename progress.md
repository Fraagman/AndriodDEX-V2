# Progress Log

Newest entry on top. Never delete or rewrite an existing entry.
Never put a secret in this file.

## Index

| #   | Date       | Task | Result |
|-----|------------|------|--------|
| 005 | 2026-09-07 | T5 — Remove unauthenticated local input socket and dead JPEG listener | PASS |
| 004 | 2026-09-07 | T4 — Prevent pairing PSK and TLS key from cloud backup (SEC-07) | PASS |
| 003 | 2026-09-07 | T3 — Fix three concrete bugs (BUG-01, BUG-04, BUG-06) | PASS |
| 002 | 2026-09-07 | T2 — Delete dead parallel project and untrack build artifacts | PASS |
| 001 | 2026-09-07 | T1 — Fix broken rust-receiver workspace build (BUG-02) | PASS |

---

## 005 — T5 — Remove unauthenticated local input socket and dead JPEG listener

### What this task was for
Removed the unauthenticated TCP socket server on 127.0.0.1:55555 (`LocalInputServer.kt`) which allowed any app with network permissions on the device to inject synthetic mouse events into the desktop session (SEC-04). Also deleted the dead `VmDisplayBridge.kt` JPEG stream listener that opened port 8080 on all network interfaces with an operator precedence decoding bug (SEC-14), along with its package directory.

### What I changed
- `android-host/app/src/main/java/com/example/androidhost/network/LocalInputServer.kt`: deleted file (45 lines).
- `android-host/app/src/main/java/com/example/androidhost/MainActivity.kt`: removed `com.example.androidhost.network.LocalInputServer.start()` call from `onCreate`, keeping `InputManager.startPolling(filesDir.absolutePath)` intact.
- `android-host/app/src/main/java/com/example/androidhost/bridge/VmDisplayBridge.kt`: deleted file (135 lines).
- `android-host/app/src/main/java/com/example/androidhost/bridge/`: removed directory after deleting its sole member.

### Decisions I made
none

### What I did NOT do
- Did not delete `InputManager.vmOutputStream` as it was explicitly out of scope.
- Did not test QUIC input end-to-end because doing so requires running the interactive Windows receiver with an active pairing session.
- Did not touch `LocalInputDispatcher.kt`, `InputManager.kt`, or any other components outside the specified scope.

### Verification I ran

G5.1 `cd android-host && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug --no-daemon`:
```
BUILD SUCCESSFUL in 16s
40 actionable tasks: 4 executed, 36 up-to-date
Configuration cache entry reused.
```

G5.2 `cd android-host && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --no-daemon`:
```
BUILD SUCCESSFUL in 9s
28 actionable tasks: 4 executed, 24 up-to-date
Configuration cache entry reused.
```

G5.3 `git grep -nE "LocalInputServer|VmDisplayBridge" android-host/`:
Exit code 1 (no occurrences remain anywhere in android-host/).

G5.4 ON DEVICE:
Ran following sequence after installing the new APK:
```
adb install -r android-host/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.example.androidhost
adb shell am start -n com.example.androidhost/.MainActivity
(waited 5 seconds)
adb shell "cat /proc/net/tcp /proc/net/tcp6 | grep -i D903"
```
Exit code 1, zero output produced. Confirmed port 55555 (0xD903) is no longer open or listening.

G5.5 End-to-end QUIC input check:
Confirmed by grep that `LocalInputDispatcher.onMouse` still has a live caller in `InputManager.handleInputEvent` (line 90). Full end-to-end interactive QUIC input was not tested because the Windows receiver was not running.

---

## 004 — T4 — Prevent pairing PSK and TLS key from cloud backup (SEC-07)

### What this task was for
This task eliminates the security vulnerability where the host device's pairing PSK and TLS identity (private key and certificate) were eligible for automated cloud backups and device-to-device transfers. Setting `allowBackup="false"` prevents backing up these credentials to another handset, and populating explicit exclusion rules in `backup_rules.xml` and `data_extraction_rules.xml` provides defense-in-depth against accidental future re-exposure.

### What I changed
- `android-host/app/src/main/AndroidManifest.xml`: set `android:allowBackup="false"`, added `android:fullBackupContent="@xml/backup_rules"`, and added `android:dataExtractionRules="@xml/data_extraction_rules"` to `<application>`.
- `android-host/app/src/main/res/xml/backup_rules.xml`: removed the sample comment block and declared `<exclude domain="file" path="pairing.psk" />` and `<exclude domain="file" path="tls_identity.bin" />` under `<full-backup-content>`.
- `android-host/app/src/main/res/xml/data_extraction_rules.xml`: removed sample comment block and declared the same two `<exclude>` elements under both `<cloud-backup>` and `<device-transfer>`.

### Decisions I made
none

### What I did NOT do
- Did not touch any other attributes, components, or files outside the three scoped files.

### Verification I ran

G4.1 `cd android-host && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug --no-daemon`:
```
BUILD SUCCESSFUL in 8s
40 actionable tasks: 11 executed, 29 up-to-date
Configuration cache entry reused.
```

G4.2 Opening merged manifest at `android-host/app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`:
```xml
    <application
        android:allowBackup="false"
        android:appComponentFactory="androidx.core.app.CoreComponentFactory"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:debuggable="true"
        android:extractNativeLibs="false"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:networkSecurityConfig="@xml/network_security_config"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.AndroidHost" >
```
Shows `allowBackup="false"` plus both `fullBackupContent` and `dataExtractionRules` attributes intact in the merged manifest.

G4.3 ON DEVICE (`adb shell "dumpsys package com.example.androidhost | grep -i 'flags='"`):
```
    flags=0x0
    flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]
    privateFlags=[ PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION ALLOW_AUDIO_PLAYBACK_CAPTURE PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING ]
    pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]
    privatePkgFlags=[ PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION ALLOW_AUDIO_PLAYBACK_CAPTURE PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING ]
```
Confirmed: `pkgFlags` does NOT contain `ALLOW_BACKUP`.

G4.4 Secret path tracing:
Traced write-site in `android-host/rust_quic_server/src/store.rs` lines 14-15 (`const PSK_FILE: &str = "pairing.psk";` and `const CERT_FILE: &str = "tls_identity.bin";`). Traced `store.rs` path construction `self.dir.join(name)` to `SecureStore::open(&data_path)` in `rust_quic_server/src/lib.rs` line 231, which receives `dataPath` from `InputManager.startPolling(filesDir.absolutePath)` via `MainActivity.kt` line 135. The files are written directly into Android's internal `filesDir`, corresponding precisely to the backup rules domain `"file"` with exact matching filenames `pairing.psk` and `tls_identity.bin`.

### Notes for the next task
Task 5 will remove the unauthenticated local input socket server (`LocalInputServer.kt`) and dead JPEG stream listener (`VmDisplayBridge.kt`).

---

## 003 — T3 — Fix three concrete bugs (BUG-01, BUG-04, BUG-06)

### What this task was for
Fixed three concrete bugs across Android and Rust components: a guaranteed runtime `ClassCastException` crash when invoking biometric unlock from the control panel due to `MainActivity` not extending `FragmentActivity` (BUG-01); a permanent visible black square artifact rendered in the desktop video stream after keyframe requests due to an unreset burst counter (BUG-04); and an unhandled `None` panic in `rust-receiver` when connecting or resuming without capturing a server certificate fingerprint (BUG-06).

### What I changed
- `android-host/gradle/libs.versions.toml`: pinned `androidxFragment = "1.8.6"` under `[versions]` and declared `androidx-fragment-ktx = { module = "androidx.fragment:fragment-ktx", version.ref = "androidxFragment" }` under `[libraries]`.
- `android-host/app/build.gradle.kts`: explicitly added `implementation(libs.androidx.fragment.ktx)` to the dependencies block.
- `android-host/app/src/main/java/com/example/androidhost/MainActivity.kt`: changed import from `androidx.activity.ComponentActivity` to `androidx.fragment.app.FragmentActivity` and changed class declaration to extend `FragmentActivity`.
- `android-host/app/src/main/java/com/example/androidhost/screens/BiometricLockScreen.kt`: converted unsafe cast `LocalContext.current as FragmentActivity` to safe cast `as? FragmentActivity`. Added null-check in `LaunchedEffect` that assigns a clear error message to `errorMsg` and avoids invoking `biometricPrompt.authenticate` if null.
- `android-host/app/src/main/java/com/example/androidhost/DesktopShell.kt`: added `burstTick = 0` immediately after the 10-iteration burst loop in `LaunchedEffect(forceRedraw)` so the forced-redraw box disappears once the burst finishes.
- `rust-receiver/zc-network/src/client.rs`: replaced `let fp = fingerprint.lock().unwrap().unwrap();` with error handling that maps mutex lock errors and returns an `Err` from `connect()` with a message naming the cause (`"Server certificate fingerprint not captured: verify_server_cert did not run"`).

### Decisions I made
- Pinned `androidxFragment = "1.8.6"` in the version catalog as a modern, stable version compatible with compileSdk 36.

### What I did NOT do
- Did not touch `DisplayService.kt`, `DesktopPresentation.kt`, `ScreenEncoder.kt`, or any other file in `client.rs`'s crate.
- Did not change the burst mechanism length, 16 ms delay, or box size formula in `DesktopShell.kt`.

### Verification I ran

G3.1 `cd android-host && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug --no-daemon`:
```
BUILD SUCCESSFUL in 2m 3s
40 actionable tasks: 40 executed
Configuration cache entry stored.
```

G3.2 `cd android-host && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --no-daemon`:
```
BUILD SUCCESSFUL in 19s
28 actionable tasks: 5 executed, 1 from cache, 22 up-to-date
Configuration cache entry stored.
```

G3.3 `cd rust-receiver && cargo check --workspace --all-targets`:
```
warning: unused import: `PKCS_ECDSA_P256_SHA256`
 --> zc-security\src\cert.rs:1:41
  |
1 | use rcgen::{CertificateParams, KeyPair, PKCS_ECDSA_P256_SHA256};
  |                                         ^^^^^^^^^^^^^^^^^^^^^^
  |
  = note: `#[warn(unused_imports)]` (part of `#[warn(unused)]`) on by default

warning: `zc-security` (lib) generated 1 warning (run `cargo fix --lib -p zc-security` to apply 1 suggestion)
warning: `zc-security` (lib test) generated 1 warning (1 duplicate)
warning: unused import: `Psk`
 --> zc-network\src\client.rs:5:76
  |
5 | use zc_security::storage::{load_trust_data, store_trust_data, Fingerprint, Psk};
  |                                                                            ^^^
  |
  = note: `#[warn(unused_imports)]` (part of `#[warn(unused)]`) on by default

warning: struct `SkipServerVerification` is never constructed
 --> zc-network\src\lib.rs:8:8
  |
8 | struct SkipServerVerification;
  |        ^^^^^^^^^^^^^^^^^^^^^^
  |
  = note: `#[warn(dead_code)]` (part of `#[warn(unused)]`) on by default

warning: associated function `new` is never used
  --> zc-network\src\lib.rs:11:8
   |
10 | impl SkipServerVerification {
   | --------------------------- associated function in this implementation
11 |     fn new() -> Arc<Self> {
   |        ^^^

warning: `zc-network` (lib) generated 3 warnings (run `cargo fix --lib -p zc-network` to apply 1 suggestion)
warning: `zc-network` (lib test) generated 3 warnings (3 duplicates)
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 1.18s
```

G3.4 ON DEVICE:
```
adb install -r android-host/app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -n com.example.androidhost/.MainActivity
— on the phone, navigated from PinEntryScreen ("Open control panel") to ControlPanel and tapped "Lock Session" —
adb shell "logcat -d | grep -iE 'ClassCastException|FATAL|AndroidRuntime'"
Output:
09-07 22:49:21.666 31512 31512 I adbd : adbd service requested 'shell,v2,raw:logcat -d | grep -iE 'ClassCastException|FATAL|AndroidRuntime''
```
Zero ClassCastException and zero FATAL occurred; biometric authentication callback was registered cleanly.

G3.5 `git grep -n "as FragmentActivity" android-host/app/src/`:
Exit code 1 (no unsafe cast remains).

### Notes for the next task
Task 4 will secure the pairing PSK and TLS identity from cloud backup and device transfer by updating `AndroidManifest.xml`, `backup_rules.xml`, and `data_extraction_rules.xml`.

---

## 002 — T2 — Delete dead parallel project and untrack build artifacts

### What this task was for
This task eliminates the redundant parallel Compose template project at the repository root and cleans up the git index. The root build files and `app/` template were completely disconnected from the actual application in `android-host/`. Additionally, large binary build artifacts (MSIX, crash logs, profiling dumps, and IDE settings) were untracked from git and ignored in `.gitignore` so they will not be re-committed.

### What I changed
- `app/`: deleted directory (32 tracked files in git index and residual build directory on disk via `cmd /c rmdir /s /q app`).
- `settings.gradle.kts`: deleted file (10 lines).
- `build.gradle.kts`: deleted file (23 lines).
- `gradle/`: deleted directory containing wrapper and version catalog (3 tracked files).
- `gradlew`, `gradlew.bat`: deleted root wrapper scripts (2 tracked files).
- `gradle.properties`: deleted file (21 lines).
- `android/`: removed empty directory (0 files).
- `frontend/`: removed empty directory tree (0 files, verified via `(Get-ChildItem -Recurse -File frontend).Count`).
- `input_test_sender.py`: deleted file (88 lines).
- `receiver.py`: deleted file (106 lines).
- `Prompt fix idle regression.md`: removed from git tracking (staged deletion).
- `mdm-console/payload.json`: deleted file (15 lines).
- `rust-receiver/hello/`: deleted crate directory (2 tracked files).
- `rust-receiver/Cargo.toml`: edited to remove `"hello"` from `members`.
- `release/androiddex.msix`: untracked from git via `git rm --cached` (file retained on disk).
- `gfxinfo.txt`: untracked from git via `git rm --cached` (file retained on disk).
- `android-host/.kotlin/errors/*.log`: untracked 3 crash logs from git via `git rm -r --cached`.
- `android-host/.idea/**`: untracked 11 IDE state files from git via `git rm -r --cached`.
- `protoc/**`: untracked 17 files from git via `git rm -r --cached` (files retained on disk; verified `android-host/app/build.gradle.kts` resolves protoc via Maven and `AndroidDEX-Core/build_all.bat` contains no reference).
- `.gitignore`: updated with entries for `/release/`, `/gfxinfo.txt`, `android-host/.kotlin/`, `android-host/.idea/`, and `/protoc/`, preserving all existing rules.

### Decisions I made
- Deleted `app/build/` intermediate files using `cmd /c rmdir /s /q app` to bypass Windows long-path limitations in PowerShell.

### What I did NOT do
- Did not delete `protoc/`, `release/androiddex.msix`, or `gfxinfo.txt` from disk; untracked only from git index using `git rm --cached`.
- Did not touch `android-host/app/**`, `android-host/rust_quic_server/**`, `rust-receiver/zc-*/**`, `AndroidDEX-Core/**`, or `mdm-console/**` (except `payload.json`).
- Did not touch `android-host/gradle/`, `android-host/gradlew`, or `android-host/build.gradle.kts`.

### Verification I ran

G2.1 `cd android-host && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug --no-daemon`:
```
BUILD SUCCESSFUL in 7s
40 actionable tasks: 40 up-to-date
Configuration cache entry reused.
```

G2.2 `cd rust-receiver && cargo check --workspace --all-targets`:
```
warning: unused import: `PKCS_ECDSA_P256_SHA256`
 --> zc-security\src\cert.rs:1:41
  |
1 | use rcgen::{CertificateParams, KeyPair, PKCS_ECDSA_P256_SHA256};
  |                                         ^^^^^^^^^^^^^^^^^^^^^^
  |
  = note: `#[warn(unused_imports)]` (part of `#[warn(unused)]`) on by default

warning: `zc-security` (lib) generated 1 warning (run `cargo fix --lib -p zc-security` to apply 1 suggestion)
warning: `zc-security` (lib test) generated 1 warning (1 duplicate)
warning: unused import: `Psk`
 --> zc-network\src\client.rs:5:76
  |
5 | use zc_security::storage::{load_trust_data, store_trust_data, Fingerprint, Psk};
  |                                                                            ^^^
  |
  = note: `#[warn(unused_imports)]` (part of `#[warn(unused)]`) on by default

warning: struct `SkipServerVerification` is never constructed
 --> zc-network\src\lib.rs:8:8
  |
8 | struct SkipServerVerification;
  |        ^^^^^^^^^^^^^^^^^^^^^^
  |
  = note: `#[warn(dead_code)]` (part of `#[warn(unused)]`) on by default

warning: associated function `new` is never used
  --> zc-network\src\lib.rs:11:8
   |
10 | impl SkipServerVerification {
   | --------------------------- associated function in this implementation
11 |     fn new() -> Arc<Self> {
   |        ^^^

warning: `zc-network` (lib) generated 3 warnings (run `cargo fix --lib -p zc-network` to apply 1 suggestion)
warning: `zc-network` (lib test) generated 3 warnings (3 duplicates)
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.41s
```

G2.3 `git ls-files | wc -l`:
```
Lines Words Characters Property
----- ----- ---------- --------
  247
```
Arithmetic check:
Starting tracked files: 326 (325 prior to commit f3db9e2 + 1 for AndroidDEX.md).
Files untracked (33):
- android-host/.idea/: 11
- android-host/.kotlin/errors/: 3
- gfxinfo.txt: 1
- protoc/: 17
- release/androiddex.msix: 1
Files deleted (46):
- app/: 32
- build.gradle.kts: 1
- gradle.properties: 1
- gradle/: 3
- gradlew: 1
- gradlew.bat: 1
- input_test_sender.py: 1
- mdm-console/payload.json: 1
- Prompt fix idle regression.md: 1
- receiver.py: 1
- rust-receiver/hello/: 2
- settings.gradle.kts: 1
Total removed from index = 33 untracked + 46 deleted = 79.
326 - 79 = 247 tracked files remaining.

G2.4 `git ls-files release/ gfxinfo.txt protoc/ android-host/.idea/ android-host/.kotlin/`:
No output produced.

G2.5 `Test-Path release\androiddex.msix, gfxinfo.txt`:
```
True
True
```

### Notes for the next task
Task 3 will address BUG-01 (safe cast / FragmentActivity in BiometricLockScreen), BUG-04 (burstTick reset in DesktopShell), and BUG-06 (unwrap panic in rust-receiver client.rs).

---

## 001 — T1 — Fix broken rust-receiver workspace build (BUG-02)

### What this task was for
This task resolves a broken build in the `rust-receiver` workspace where stale scratch and test files caused `cargo check --workspace` to fail. These unused files relied on obsolete APIs from earlier versions of dependencies (such as removed `rustls` builder methods, nonexistent verifier structs, and outdated Protobuf schemas). Deleting these unreferenced files restores a clean compilation and test baseline for the receiver workspace.

### What I changed
- `rust-receiver/zc-network/src/bin/test_quic.rs`: deleted file (37 lines). Verified unreferenced across repository via `grep_search` for `test_quic`.
- `rust-receiver/zc-network/examples/mock_quic_server.rs`: deleted file (194 lines). Verified unreferenced across repository via `grep_search` for `mock_quic_server`.
- `rust-receiver/zc-network/src/psk_test.rs`: deleted file (9 lines). Verified unreferenced across repository via `grep_search` for `psk_test` (no `mod psk_test;` or cargo entries existed).
- `progress.md`: created file at repository root with logging skeleton and entry 001.

### Decisions I made
none

### What I did NOT do
- Did not edit `rust-receiver/zc-network/Cargo.toml` because it declared no `[[bin]]` or `[[example]]` stanzas pointing to the deleted files.
- Did not touch `rust-receiver/zc-network/src/client.rs`, `rust-receiver/zc-network/src/lib.rs`, `rust-receiver/zc-security/**`, `rust-receiver/zc-protocol/**`, or anything under `android-host/`.
- Did not attempt to repair or retain any of the three scratch files as instructed.

### Verification I ran

G1.1 `cd rust-receiver && cargo check --workspace` (exit 0):
```
warning: unused import: `PKCS_ECDSA_P256_SHA256`
 --> zc-security\src\cert.rs:1:41
  |
1 | use rcgen::{CertificateParams, KeyPair, PKCS_ECDSA_P256_SHA256};
  |                                         ^^^^^^^^^^^^^^^^^^^^^^
  |
  = note: `#[warn(unused_imports)]` (part of `#[warn(unused)]`) on by default

warning: `zc-security` (lib) generated 1 warning (run `cargo fix --lib -p zc-security` to apply 1 suggestion)
warning: unused import: `Psk`
 --> zc-network\src\client.rs:5:76
  |
5 | use zc_security::storage::{load_trust_data, store_trust_data, Fingerprint, Psk};
  |                                                                            ^^^
  |
  = note: `#[warn(unused_imports)]` (part of `#[warn(unused)]`) on by default

warning: struct `SkipServerVerification` is never constructed
 --> zc-network\src\lib.rs:8:8
  |
8 | struct SkipServerVerification;
  |        ^^^^^^^^^^^^^^^^^^^^^^
  |
  = note: `#[warn(dead_code)]` (part of `#[warn(unused)]`) on by default

warning: associated function `new` is never used
  --> zc-network\src\lib.rs:11:8
   |
10 | impl SkipServerVerification {
   | --------------------------- associated function in this implementation
11 |     fn new() -> Arc<Self> {
   |        ^^^

warning: `zc-network` (lib) generated 3 warnings (run `cargo fix --lib -p zc-network` to apply 1 suggestion)
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.38s
```

G1.2 `cd rust-receiver && cargo check --workspace --all-targets` (exit 0):
```
warning: unused import: `PKCS_ECDSA_P256_SHA256`
 --> zc-security\src\cert.rs:1:41
  |
1 | use rcgen::{CertificateParams, KeyPair, PKCS_ECDSA_P256_SHA256};
  |                                         ^^^^^^^^^^^^^^^^^^^^^^
  |
  = note: `#[warn(unused_imports)]` (part of `#[warn(unused)]`) on by default

warning: `zc-security` (lib) generated 1 warning (run `cargo fix --lib -p zc-security` to apply 1 suggestion)
warning: `zc-security` (lib test) generated 1 warning (1 duplicate)
warning: unused import: `Psk`
 --> zc-network\src\client.rs:5:76
  |
5 | use zc_security::storage::{load_trust_data, store_trust_data, Fingerprint, Psk};
  |                                                                            ^^^
  |
  = note: `#[warn(unused_imports)]` (part of `#[warn(unused)]`) on by default

warning: struct `SkipServerVerification` is never constructed
 --> zc-network\src\lib.rs:8:8
  |
8 | struct SkipServerVerification;
  |        ^^^^^^^^^^^^^^^^^^^^^^
  |
  = note: `#[warn(dead_code)]` (part of `#[warn(unused)]`) on by default

warning: associated function `new` is never used
  --> zc-network\src\lib.rs:11:8
   |
10 | impl SkipServerVerification {
   | --------------------------- associated function in this implementation
11 |     fn new() -> Arc<Self> {
   |        ^^^

warning: `zc-network` (lib) generated 3 warnings (run `cargo fix --lib -p zc-network` to apply 1 suggestion)
warning: `zc-network` (lib test) generated 3 warnings (3 duplicates)
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.32s
```

G1.3 `cd rust-receiver && cargo test --workspace` (exit 0):
```
warning: unused import: `PKCS_ECDSA_P256_SHA256`
 --> zc-security\src\cert.rs:1:41
  |
1 | use rcgen::{CertificateParams, KeyPair, PKCS_ECDSA_P256_SHA256};
  |                                         ^^^^^^^^^^^^^^^^^^^^^^
  |
  = note: `#[warn(unused_imports)]` (part of `#[warn(unused)]`) on by default

warning: `zc-security` (lib) generated 1 warning (run `cargo fix --lib -p zc-security` to apply 1 suggestion)
   Compiling zc-input v0.1.0 (C:\Users\Asus\Documents\GitHub\AndriodDEX-V2\rust-receiver\zc-input)
   Compiling zc-audio v0.1.0 (C:\Users\Asus\Documents\GitHub\AndriodDEX-V2\rust-receiver\zc-audio)
   Compiling zc-video v0.1.0 (C:\Users\Asus\Documents\GitHub\AndriodDEX-V2\rust-receiver\zc-video)
   Compiling zc-security v0.1.0 (C:\Users\Asus\Documents\GitHub\AndriodDEX-V2\rust-receiver\zc-security)
   Compiling zc-protocol v0.1.0 (C:\Users\Asus\Documents\GitHub\AndriodDEX-V2\rust-receiver\zc-protocol)
   Compiling hello v0.1.0 (C:\Users\Asus\Documents\GitHub\AndriodDEX-V2\rust-receiver\hello)
warning: unused import: `Psk`
 --> zc-network\src\client.rs:5:76
  |
5 | use zc_security::storage::{load_trust_data, store_trust_data, Fingerprint, Psk};
  |                                                                            ^^^
  |
  = note: `#[warn(unused_imports)]` (part of `#[warn(unused)]`) on by default

warning: struct `SkipServerVerification` is never constructed
 --> zc-network\src\lib.rs:8:8
  |
8 | struct SkipServerVerification;
  |        ^^^^^^^^^^^^^^^^^^^^^^
  |
  = note: `#[warn(dead_code)]` (part of `#[warn(unused)]`) on by default

warning: associated function `new` is never used
  --> zc-network\src\lib.rs:11:8
   |
10 | impl SkipServerVerification {
   | --------------------------- associated function in this implementation
11 |     fn new() -> Arc<Self> {
   |        ^^^

   Compiling zc-network v0.1.0 (C:\Users\Asus\Documents\GitHub\AndriodDEX-V2\rust-receiver\zc-network)
warning: `zc-network` (lib) generated 3 warnings (run `cargo fix --lib -p zc-network` to apply 1 suggestion)
   Compiling zc-core v0.1.0 (C:\Users\Asus\Documents\GitHub\AndriodDEX-V2\rust-receiver\zc-core)
warning: `zc-network` (lib test) generated 3 warnings (3 duplicates)
warning: `zc-security` (lib test) generated 1 warning (1 duplicate)
    Finished `test` profile [unoptimized + debuginfo] target(s) in 4.37s
     Running unittests src\main.rs (target\debug\deps\hello-7bd2f350c1a57330.exe)

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s

     Running unittests src\lib.rs (target\debug\deps\zc_audio-518787143e3eef64.exe)

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s

     Running unittests src\main.rs (target\debug\deps\zc_core-86016e335755df03.exe)

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s

     Running unittests src\lib.rs (target\debug\deps\zc_input-6d71dbbda61b0e44.exe)

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s

     Running unittests src\lib.rs (target\debug\deps\zc_network-12896c28a18308b8.exe)

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s

     Running unittests src\lib.rs (target\debug\deps\zc_protocol-dac81db3e40e4801.exe)

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s

     Running unittests src\lib.rs (target\debug\deps\zc_security-1c8ed2f47e20d32c.exe)

running 3 tests
test tests::test_derive_psk ... ok
test tests::test_cert_generation ... ok
test tests::test_generate_pin ... ok

test result: ok. 3 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s

     Running unittests src\lib.rs (target\debug\deps\zc_video-d2b8df5e6cd247d7.exe)

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s

   Doc-tests zc_audio

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s

   Doc-tests zc_input

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s

   Doc-tests zc_network

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s

   Doc-tests zc_protocol

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s

   Doc-tests zc_security

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s

   Doc-tests zc_video

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
```

G1.4 `cd rust-receiver && cargo build -p zc-core` (exit 0):
```
warning: unused import: `PKCS_ECDSA_P256_SHA256`
 --> zc-security\src\cert.rs:1:41
  |
1 | use rcgen::{CertificateParams, KeyPair, PKCS_ECDSA_P256_SHA256};
  |                                         ^^^^^^^^^^^^^^^^^^^^^^
  |
  = note: `#[warn(unused_imports)]` (part of `#[warn(unused)]`) on by default

warning: `zc-security` (lib) generated 1 warning (run `cargo fix --lib -p zc-security` to apply 1 suggestion)
warning: unused import: `Psk`
 --> zc-network\src\client.rs:5:76
  |
5 | use zc_security::storage::{load_trust_data, store_trust_data, Fingerprint, Psk};
  |                                                                            ^^^
  |
  = note: `#[warn(unused_imports)]` (part of `#[warn(unused)]`) on by default

warning: struct `SkipServerVerification` is never constructed
 --> zc-network\src\lib.rs:8:8
  |
8 | struct SkipServerVerification;
  |        ^^^^^^^^^^^^^^^^^^^^^^
  |
  = note: `#[warn(dead_code)]` (part of `#[warn(unused)]`) on by default

warning: associated function `new` is never used
  --> zc-network\src\lib.rs:11:8
   |
10 | impl SkipServerVerification {
   | --------------------------- associated function in this implementation
11 |     fn new() -> Arc<Self> {
   |        ^^^

warning: `zc-network` (lib) generated 3 warnings (run `cargo fix --lib -p zc-network` to apply 1 suggestion)
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.37s
```

G1.5 `Test-Path rust-receiver\zc-network\src\bin\test_quic.rs, rust-receiver\zc-network\examples\mock_quic_server.rs, rust-receiver\zc-network\src\psk_test.rs`:
```
False
False
False
```

### Notes for the next task
All 5 gate verification checks passed cleanly. Task 2 will remove the dead parallel project, untrack build artifacts, remove the `hello` member from `rust-receiver/Cargo.toml`, and update `.gitignore`.
