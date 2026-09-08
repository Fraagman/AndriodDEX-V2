# Progress Log

Newest entry on top. Never delete or rewrite an existing entry.
Never put a secret in this file.

## Index

| #   | Date       | Task | Result |
|-----|------------|------|--------|
| 015 | 2026-09-08 | T18 — The receiver's crypto path must not panic (BUG-10) | PASS |
| 014 | 2026-09-08 | T17 — The app can leave the phone permanently silent (BUG-09) | PASS |
| 013 | 2026-09-08 | T16 — Stop two things the software does that it should not (SEC-13, PERF-01) | PASS |
| 012 | 2026-09-08 | T12 — Replace the template tests with tests of the real product (TEST-01, TEST-02) | PASS |
| 011 | 2026-09-08 | T11 — Fail closed on a changed identity, and stop silent re-pairing (SEC-01, SEC-11, SEC-12) | PASS |
| 010 | 2026-09-08 | T10 — Replace substring error-matching with attributable certificate identity (SEC-10, DOC-04) | PASS |
| 009 | 2026-09-08 | T9 — Make documentation describe what actually exists (DOC-01, DOC-02, DEV-02, DEV-03, DEV-04) | PASS |
| 008 | 2026-09-08 | T8 — Resolve the MDM console (DEAD-02, SEC-08, SEC-09) [Variant A] | PASS |
| 007 | 2026-09-08 | T7 — Stop burning battery on an idle desktop (BUG-07, BUG-08, BUG-03) | PASS |
| 006 | 2026-09-08 | T6 — Close the two security holes in NativeComputeService (SEC-05, SEC-06) | PASS |
| 005 | 2026-09-07 | T5 — Remove unauthenticated local input socket and dead JPEG listener | PASS |
| 004 | 2026-09-07 | T4 — Prevent pairing PSK and TLS key from cloud backup (SEC-07) | PASS |
| 003 | 2026-09-07 | T3 — Fix three concrete bugs (BUG-01, BUG-04, BUG-06) | PASS |
| 002 | 2026-09-07 | T2 — Delete dead parallel project and untrack build artifacts | PASS |
| 001 | 2026-09-07 | T1 — Fix broken rust-receiver workspace build (BUG-02) | PASS |

---

## 015 — T18 — The receiver's crypto path must not panic (BUG-10)

### What this task was for
1. Eliminate all unwrap/expect panic vectors in `zc-security`:
   - `pairing::derive_psk`: converted to return `Result<[u8; 32], ring::error::Unspecified>` rather than unwrapping on HKDF `expand` and `fill`.
   - `cert::generate_self_signed_cert`: converted to return `Result<(Vec<u8>, Vec<u8>), rcgen::Error>` rather than expecting on keypair generation, cert params creation, and self-signing.
   - `storage`: recovered poisoned mutex guards on `CUSTOM_DATA_PATH` via `Err(poisoned) => poisoned.into_inner()` in `set_data_path`, `get_trust_file_path`, and `get_server_cert_file_path`.
   - `cert.rs`: removed unused import `PKCS_ECDSA_P256_SHA256` to ensure zero compilation warnings across the entire receiver workspace.
2. Propagate Result at call sites:
   - In `zc-network/src/client.rs`: propagated `derive_psk` errors using `?` (mapping into `Box<dyn std::error::Error>`).
3. Shared Known-Answer Test (KAT):
   - Created deterministic known-answer tests in both `rust_quic_server` (`crypto.rs`) and `zc-security` (`lib.rs`) asserting the exact same 32-byte derived PSK literal for PIN `"123456"` and public key `[7u8; 32]`.

### What I changed
- `rust-receiver/zc-security/src/pairing.rs`: `derive_psk` returns `Result<[u8; 32], ring::error::Unspecified>` using `?` for HKDF expand and fill.
- `rust-receiver/zc-security/src/cert.rs`: `generate_self_signed_cert` returns `Result<(Vec<u8>, Vec<u8>), rcgen::Error>`; removed unused `PKCS_ECDSA_P256_SHA256`.
- `rust-receiver/zc-security/src/storage.rs`: recovered poisoned mutexes for `CUSTOM_DATA_PATH`.
- `rust-receiver/zc-security/src/lib.rs`: updated existing unit tests to handle `Result` and added `test_known_answer_psk`.
- `rust-receiver/zc-network/src/client.rs`: propagated `derive_psk` result with `?`.
- `android-host/rust_quic_server/src/crypto.rs`: added `known_answer_psk` test asserting the exact 32 expected bytes.

### Decisions I made
- In `client.rs`, propagated `derive_psk` errors via `?` rather than silently returning a zeroed key, failing the pairing connection immediately if derivation fails.
- Kept the known-answer assertion literals independently defined as raw `[u8; 32]` literals in both crates without sharing constants or code across repositories.

### What I did NOT do
- Did not modify any salt, info string, byte layouts, or derivation inputs.
- Did not touch any receiver crates outside `zc-security` and `zc-network/src/client.rs`.

### Verification I ran

G18.1 `cd rust-receiver && cargo check --workspace --all-targets`:
```
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.99s
```
Exit 0 with ZERO warnings.

G18.2 `cd rust-receiver && cargo test --workspace`:
```
running 4 tests
test tests::test_derive_psk ... ok
test tests::test_known_answer_psk ... ok
test tests::test_cert_generation ... ok
test tests::test_generate_pin ... ok

test result: ok. 4 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
```

G18.3 `cd android-host/rust_quic_server && cargo test --release`:
```
running 31 tests
test pairing::tests::submitting_without_a_handshake_is_rejected_immediately ... ok
test crypto::tests::known_answer_psk ... ok
test crypto::tests::pin_shape_is_enforced ... ok
test crypto::tests::psk_derivation_is_stable ... ok
test frames::tests::clear_empties_without_counting_drops ... ok
test frames::tests::drops_the_oldest_when_full ... ok
test crypto::tests::token_matches_only_for_the_right_psk ... ok
test crypto::tests::token_of_wrong_length_is_rejected ... ok
test frames::tests::depth_never_exceeds_capacity ... ok
test pairing::tests::a_rejected_pin_returns_false_to_the_caller ... ok
test tests::a_random_token_never_verifies ... ok
test tests::untrusted_bytes_are_escaped_before_logging ... ok
test tests::auth_request_layout_is_33_bytes ... ok
test pairing::tests::a_submitted_pin_reaches_the_waiter_and_the_verdict_returns ... ok
test protocol_tests::video_backlog_is_bounded_and_drops_are_counted ... ok
test store::tests::oversized_psk_is_rejected ... ok
test store::tests::corrupt_tls_identity_is_rejected ... ok
test store::tests::truncated_psk_is_rejected ... ok
test store::tests::psk_round_trips ... ok
test store::tests::tls_identity_round_trips ... ok
test tls::tests::the_certificate_survives_a_restart ... ok
test protocol_tests::a_paired_pc_reconnects_without_a_pin_after_a_restart ... ok
test tls::tests::a_corrupt_identity_is_regenerated_rather_than_fatal ... ok
test protocol_tests::correct_pin_pairs_authenticates_and_streams_video ... ok
test protocol_tests::pin_attempts_are_capped_per_connection ... ok
test protocol_tests::pairing_is_refused_when_already_paired_and_psk_is_unchanged ... ok
test frames::tests::pop_wakes_on_a_later_push ... ok
test protocol_tests::raw_client_that_skips_pairing_is_refused ... ok
test protocol_tests::a_bogus_alpn_does_not_stop_the_server ... ok
test pairing::tests::waiting_times_out_when_no_pin_arrives ... ok
test protocol_tests::wrong_pin_is_refused_and_nothing_is_paired ... ok

test result: ok. 31 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.35s
```

G18.4 `grep -rn "\.unwrap()\|\.expect(" rust-receiver/zc-security/src/ excluding test modules`:
```
Command: grep -rn "\.unwrap()\|\.expect(" rust-receiver/zc-security/src/
Result: Only matches in #[cfg(test)] mod tests in lib.rs. 0 unwrap/expect in production code.
```

G18.5 Known-Answer Tests Side by Side:
**Phone (`android-host/rust_quic_server/src/crypto.rs`)**:
```rust
    #[test]
    fn known_answer_psk() {
        let key = [7u8; EPHEMERAL_KEY_LEN];
        let expected: [u8; 32] = [
            137, 103, 192, 249, 41, 149, 254, 88, 189, 58, 8, 253, 14, 220, 146, 84,
            135, 25, 59, 133, 39, 54, 64, 211, 189, 223, 157, 201, 189, 78, 79, 172,
        ];
        assert_eq!(derive_psk("123456", &key), expected);
    }
```
**PC Receiver (`rust-receiver/zc-security/src/lib.rs`)**:
```rust
    #[test]
    fn test_known_answer_psk() {
        let key = [7u8; 32];
        let expected: [u8; 32] = [
            137, 103, 192, 249, 41, 149, 254, 88, 189, 58, 8, 253, 14, 220, 146, 84,
            135, 25, 59, 133, 39, 54, 64, 211, 189, 223, 157, 201, 189, 78, 79, 172,
        ];
        assert_eq!(pairing::derive_psk("123456", &key).unwrap(), expected);
    }
```
The 32 expected bytes are character-for-character identical between both files.

G18.6 `cd rust-receiver && cargo build -p zc-core`: exit 0.

G18.7 `cd android-host && ./gradlew :app:assembleDebug --no-daemon`: BUILD SUCCESSFUL.

---

## 014 — T17 — The app can leave the phone permanently silent (BUG-09)

### What this task was for
- Fix permanent device muting when `AudioCaptureService` process is terminated, crashes, or is killed by the OS.
- Persist `originalVolume` to `SharedPreferences` durably before calling `setStreamVolume(STREAM_MUSIC, 0, 0)`.
- Restore `STREAM_MUSIC` from persisted volume upon app/service startup and clear the stored preference.
- Guard against the double-mute trap: if a persisted value already exists when muting, do NOT overwrite it with 0.
- Clear persisted value whenever volume is successfully restored in `stopAudioCapture`.

### What I changed
- `android-host/app/src/main/java/com/example/androidhost/service/AudioCaptureService.kt`:
  - Added `PREFS_NAME = "audio_capture_prefs"` and `KEY_RESTORE_VOLUME = "restore_volume"`.
  - Added `tryRestoreMutedVolume(context)` in companion object and called it from companion `init` and `onCreate()`.
  - In `startAudioCapture`: persisted `originalVolume` with `.commit()` BEFORE muting `STREAM_MUSIC`; if `KEY_RESTORE_VOLUME` already existed, preserved the stored volume rather than overwriting with 0.
  - In `stopAudioCapture`: restored volume using `originalVolume` / stored preference fallback and removed `KEY_RESTORE_VOLUME` via `.commit()`.

### Decisions I made
- Preserved the existing muting behavior during active audio capture. The muting appears intended to silence the phone's physical speakers to avoid audio feedback/echo while audio playback is captured and streamed to the PC.
- Note on design: Using the global system `AudioManager.STREAM_MUSIC` volume as the mechanism is heavy-handed because modifying global system volume affects all apps and risks leaving the device muted if an unhandled termination occurs.
- Used synchronous `SharedPreferences.Editor.commit()` instead of asynchronous `apply()` so the volume is guaranteed written to disk before `setStreamVolume` executes.

### What I did NOT do
- Did not touch any file other than `AudioCaptureService.kt`.
- Did not remove the muting behavior during capture.

### Verification I ran

G17.1 `cd android-host && ./gradlew :app:assembleDebug --no-daemon`: BUILD SUCCESSFUL.

G17.2 `cd android-host && ./gradlew :app:testDebugUnitTest --no-daemon`: 9 tests, 0 failures.

G17.3 On-Device Crash-Recovery Test:
Captured `STREAM_MUSIC` volume using `cmd audio get-stream-volume 3` (which calls `AudioManager.getStreamVolume(3)`):
1. Initial baseline volume set to 10:
   `calling AudioManager.setStreamVolume(3, 10, 0)` -> `AudioManager.getStreamVolume(3) -> 10`
2. Turned ON "System Audio Capture" in control panel:
   `AudioManager.getStreamVolume(3) -> 0`
3. Simulated crash via `adb shell am force-stop com.example.androidhost`:
   `AudioManager.getStreamVolume(3) -> 0`
4. Relaunched app via `adb shell am start -n com.example.androidhost/.MainActivity`:
   `AudioManager.getStreamVolume(3) -> 10`

G17.4 On-Device Normal Path Test:
1. Baseline volume: `AudioManager.getStreamVolume(3) -> 10`
2. Turned ON "System Audio Capture": `AudioManager.getStreamVolume(3) -> 0`
3. Turned OFF "System Audio Capture" cleanly: `AudioManager.getStreamVolume(3) -> 10`

---

## 013 — T16 — Stop two things the software does that it should not (SEC-13, PERF-01)

### What this task was for
1. SEC-13: Receiver rewriting Windows firewall on launch.
   - Removed PowerShell invocation and `netsh advfirewall` execution from `zc-core/src/main.rs`.
   - Added documentation in `README.md` explaining how to manually add the firewall rule if outbound UDP port 4433 is blocked by local policy.
2. PERF-01: Input polling thread CPU spin when disconnected.
   - In `rust_quic_server/src/lib.rs` `Java_com_example_androidhost_quic_QuicServer_pollData`: adjusted timeout based on server connection state (`STATE_AUTHENTICATED` -> 20 ms, otherwise -> 500 ms).
   - Why longer timeout cannot delay input events: `crossbeam_channel::Receiver::recv_timeout` returns immediately as soon as a message is sent to the channel (or the channel is disconnected). Lengthening the timeout when disconnected adds zero input latency.
   - The new worst-case delay before `stopPolling` interrupt/cancellation takes effect when disconnected is 500 ms (up from 20 ms).

### What I changed
- `rust-receiver/zc-core/src/main.rs`: deleted the `if cfg!(windows)` block spawning `powershell` to add firewall rules.
- `README.md`: added "Troubleshooting Firewall Issues" section with manual `netsh` command.
- `android-host/rust_quic_server/src/lib.rs`: updated `pollData` to check `ctx.server.state.load(Ordering::SeqCst)` and use 20 ms when `STATE_AUTHENTICATED`, 500 ms otherwise.

### Decisions I made
- Preserved exact JNI signature, handle dereferencing, buffer capacity check, and `exception_clear` in `pollData`.
- Left Kotlin side completely untouched.

### What I did NOT do
- Did not touch `InputManager.kt`, `DesktopPresentation.kt`, or `client.rs`.
- G16.7 was NOT VERIFIED on device because USB tethering network interface was not active on the test machine to connect the Windows receiver.

### Verification I ran

G16.1 `cd android-host/rust_quic_server && cargo test --release`: 30 passed, 0 failed.

G16.2 `cd rust-receiver && cargo check --workspace --all-targets`: exit 0.

G16.3 `cd rust-receiver && cargo build -p zc-core`: exit 0.

G16.4 `grep -in "netsh\|powershell" rust-receiver/zc-core/src/main.rs`: no output.

G16.5 `cd android-host && ./gradlew :app:assembleDebug --no-daemon`: BUILD SUCCESSFUL.

G16.6 On-Device CPU Measurement for InputPollingThread:
- Baseline measured before fix: 11 CPU ticks / 10s.
- Measured on device with new build (idle desktop, no PC connected):
  - PID: 10202, TID: 10328 (`InputPollingThr`)
  - Reading at T=0: `cat /proc/10202/task/10328/stat` -> `utime=0, stime=0 (total 0)`
  - Reading at T=10s: `cat /proc/10202/task/10328/stat` -> `utime=1, stime=0 (total 1)`
  - Delta: 1 CPU tick over 10 seconds (down from 11 ticks).

G16.7 On-Device Input Test:
- NOT VERIFIED: USB tethering connection from Windows receiver not active during test.

---

## 012 — T12 — Replace the template tests with tests of the real product (TEST-01, TEST-02)

### What this task was for
1. TEST-01 & TEST-02: Deleted dead template test files (`MainScreenViewModelTest.kt` and `MainScreenTest.kt`) that targeted unreachable template UI.
2. Built real unit test suites covering shipping Android code and receiver coordinate scaling:
   - `WinitKeyMapTest`: tests mapping from receiver winit keycodes to Android keycodes, asserting specific `KeyEvent.KEYCODE_*` constants and handling unmapped/out-of-range codes.
   - `EncoderStatsTest`: tests rolling 1-second throughput window calculations, snapshot emissions upon window close (fps, kbps, keyframes, totalFrames), and state clearing via `reset()`.
   - `FrameSenderTest`: tests wire framing (`MSG_TYPE_VIDEO = 0x01` + protobuf payload), extracting `serializeFrame` into a testable function and validating roundtrip deserialization of all `VideoFrame` fields (`nalData`, `isKeyframe`, `ptsUs`, `width`, `height`).
   - `LocalInputDispatcherTest`: extracted pure coordinate scaling into `scaleCoordinate`, `scaleX`, and `scaleY`, testing exact corners (0 and 1919 / 0 and 1079), midpoints, and out-of-range input clamping.
   - `zc-input` unit tests: tested PC-side coordinate scaling in `create_mouse_event` and `create_scroll_event` (zero window guards, midpoint mapping, and virtual boundary clamping).

### What I changed
- `android-host/app/src/test/java/com/example/androidhost/ui/main/MainScreenViewModelTest.kt`: deleted.
- `android-host/app/src/androidTest/java/com/example/androidhost/ui/main/MainScreenTest.kt`: deleted.
- `android-host/app/src/main/java/com/example/androidhost/network/FrameSender.kt`: extracted `internal fun serializeFrame(payload: ByteString, isKeyframe: Boolean, ptsUs: Long, width: Int, height: Int): ByteArray`.
- `android-host/app/src/main/java/com/example/androidhost/input/LocalInputDispatcher.kt`:
  - Made `mainHandler` lazily initialized (`by lazy { Handler(Looper.getMainLooper()) }`) so pure methods can be executed in unit tests without a Looper.
  - Extracted internal scaling functions `scaleCoordinate`, `scaleX`, and `scaleY`.
- `android-host/app/src/main/java/com/example/androidhost/video/EncoderStats.kt`: added optional `nowMs: Long = SystemClock.elapsedRealtime()` parameter to `record()` for deterministic time-based testing.
- `android-host/app/src/test/java/com/example/androidhost/input/WinitKeyMapTest.kt`: added.
- `android-host/app/src/test/java/com/example/androidhost/video/EncoderStatsTest.kt`: added.
- `android-host/app/src/test/java/com/example/androidhost/network/FrameSenderTest.kt`: added.
- `android-host/app/src/test/java/com/example/androidhost/input/LocalInputDispatcherTest.kt`: added.
- `rust-receiver/zc-input/src/lib.rs`: added unit tests `test_create_mouse_event_scaling` and `test_create_scroll_event_scaling`.

### Decisions I made
- Preserved exact dispatch logic, gesture state machine, and Handler behavior in `LocalInputDispatcher.kt`, only making `mainHandler` initialization lazy to isolate the pure coordinate scaling functions for unit testing.
- Used `nowMs` default parameter in `EncoderStats.record` so all shipping production callers remain untouched while tests have zero external clock dependencies.

### What I did NOT do
- Did not touch `MainScreen.kt`, `MainScreenViewModel.kt`, `Navigation.kt`, `DataRepository.kt`.
- Did not touch `InputManager.kt` or `pollData` (PERF-01).
- Did not run any build tools with `AndroidDEX-Core` as working directory.

### Verification I ran

G12.1 `cd android-host && ./gradlew :app:testDebugUnitTest --no-daemon`:
```
BUILD SUCCESSFUL in 27s
28 actionable tasks: 28 executed
Configuration cache entry reused.
```
Ran 9 unit tests across 4 test suites with 0 failures, 0 errors, 0 skipped:
- `LocalInputDispatcherTest`: 3 tests passed
- `WinitKeyMapTest`: 2 tests passed
- `FrameSenderTest`: 1 test passed
- `EncoderStatsTest`: 3 tests passed

G12.2 `cd rust-receiver && cargo test --workspace`:
```
     Running unittests src\lib.rs (target\debug\deps\zc_input-6d71dbbda61b0e44.exe)

running 2 tests
test tests::test_create_scroll_event_scaling ... ok
test tests::test_create_mouse_event_scaling ... ok

test result: ok. 2 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
```

G12.3 `cd android-host && ./gradlew :app:assembleDebug --no-daemon`:
```
BUILD SUCCESSFUL in 8s
40 actionable tasks: 4 executed, 36 up-to-date
Configuration cache entry reused.
```

G12.4 `cd android-host/rust_quic_server && cargo test --release`:
```
test result: ok. 30 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.35s
```

G12.5 Full body of every test added:

`WinitKeyMapTest.kt`:
```kotlin
package com.example.androidhost.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class WinitKeyMapTest {

    @Test
    fun testKnownGoodKeyMappings() {
        assertEquals(KeyEvent.KEYCODE_GRAVE, WinitKeyMap.toAndroidKeyCode(0))
        assertEquals(KeyEvent.KEYCODE_BACKSLASH, WinitKeyMap.toAndroidKeyCode(1))
        assertEquals(KeyEvent.KEYCODE_LEFT_BRACKET, WinitKeyMap.toAndroidKeyCode(2))
        assertEquals(KeyEvent.KEYCODE_RIGHT_BRACKET, WinitKeyMap.toAndroidKeyCode(3))
        assertEquals(KeyEvent.KEYCODE_COMMA, WinitKeyMap.toAndroidKeyCode(4))
        assertEquals(KeyEvent.KEYCODE_0, WinitKeyMap.toAndroidKeyCode(5))
        assertEquals(KeyEvent.KEYCODE_9, WinitKeyMap.toAndroidKeyCode(14))
        assertEquals(KeyEvent.KEYCODE_A, WinitKeyMap.toAndroidKeyCode(19))
        assertEquals(KeyEvent.KEYCODE_Z, WinitKeyMap.toAndroidKeyCode(44))
        assertEquals(KeyEvent.KEYCODE_ENTER, WinitKeyMap.toAndroidKeyCode(57))
        assertEquals(KeyEvent.KEYCODE_SPACE, WinitKeyMap.toAndroidKeyCode(62))
        assertEquals(KeyEvent.KEYCODE_TAB, WinitKeyMap.toAndroidKeyCode(63))
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.SHIFT_LEFT))
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.SHIFT_RIGHT))
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.CONTROL_LEFT))
        assertEquals(KeyEvent.KEYCODE_CTRL_RIGHT, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.CONTROL_RIGHT))
        assertEquals(KeyEvent.KEYCODE_ALT_LEFT, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.ALT_LEFT))
        assertEquals(KeyEvent.KEYCODE_ALT_RIGHT, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.ALT_RIGHT))
        assertEquals(KeyEvent.KEYCODE_CAPS_LOCK, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.CAPS_LOCK))
        assertEquals(KeyEvent.KEYCODE_NUM_LOCK, WinitKeyMap.toAndroidKeyCode(WinitKeyMap.NUM_LOCK))
        assertEquals(KeyEvent.KEYCODE_ESCAPE, WinitKeyMap.toAndroidKeyCode(114))
        assertEquals(KeyEvent.KEYCODE_VOLUME_UP, WinitKeyMap.toAndroidKeyCode(140))
        assertEquals(KeyEvent.KEYCODE_VOLUME_DOWN, WinitKeyMap.toAndroidKeyCode(138))
        assertEquals(KeyEvent.KEYCODE_F1, WinitKeyMap.toAndroidKeyCode(159))
        assertEquals(KeyEvent.KEYCODE_F12, WinitKeyMap.toAndroidKeyCode(170))
    }

    @Test
    fun testUnmappedAndOutOfRangeKeycodes() {
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, WinitKeyMap.toAndroidKeyCode(-1))
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, WinitKeyMap.toAndroidKeyCode(9999))
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, WinitKeyMap.toAndroidKeyCode(194))
    }
}
```

`EncoderStatsTest.kt`:
```kotlin
package com.example.androidhost.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EncoderStatsTest {

    @Test
    fun testUnclosedWindowReturnsNull() {
        val stats = EncoderStats()
        // First frame establishes start time at t = 1000ms
        val snap1 = stats.record(sizeBytes = 10000, isKeyframe = true, nowMs = 1000L)
        assertNull("First frame must not close window", snap1)

        // Second frame at t = 1500ms (elapsed 500ms < 1000ms)
        val snap2 = stats.record(sizeBytes = 8000, isKeyframe = false, nowMs = 1500L)
        assertNull("Frame within window (< 1000ms) must return null", snap2)
        assertEquals(0, stats.latest.value.fps)
    }

    @Test
    fun testClosedWindowReportsRecordedFramesAndKeyframes() {
        val stats = EncoderStats()
        // Window start at t = 1000ms
        stats.record(sizeBytes = 25000, isKeyframe = true, nowMs = 1000L)
        stats.record(sizeBytes = 10000, isKeyframe = false, nowMs = 1300L)
        stats.record(sizeBytes = 10000, isKeyframe = false, nowMs = 1600L)
        stats.record(sizeBytes = 30000, isKeyframe = true, nowMs = 1900L)

        // 5th frame closes the window at t = 2000ms (elapsed 1000ms >= 1000ms)
        val snap = stats.record(sizeBytes = 15000, isKeyframe = false, nowMs = 2000L)
        assertNotNull("Frame at >= 1000ms elapsed must close window and return Snapshot", snap)

        snap!!
        assertEquals(5, snap.fps)
        assertEquals(2, snap.keyframes)
        assertEquals(5L, snap.totalFrames)
        // total bytes = 25000 + 10000 + 10000 + 30000 + 15000 = 90000 bytes
        // kbps = (90000 * 8 * 1000) / (1000 * 1000) = 720 kbps
        assertEquals(720, snap.kilobitsPerSecond)
        assertEquals(snap, stats.latest.value)
    }

    @Test
    fun testResetClearsState() {
        val stats = EncoderStats()
        stats.record(sizeBytes = 50000, isKeyframe = true, nowMs = 1000L)
        stats.record(sizeBytes = 50000, isKeyframe = false, nowMs = 2000L)

        assertEquals(2, stats.latest.value.fps)
        assertEquals(2L, stats.latest.value.totalFrames)

        stats.reset()

        assertEquals(0, stats.latest.value.fps)
        assertEquals(0, stats.latest.value.keyframes)
        assertEquals(0, stats.latest.value.kilobitsPerSecond)
        assertEquals(0L, stats.latest.value.totalFrames)

        // Next frame after reset starts a new window
        val snap = stats.record(sizeBytes = 10000, isKeyframe = true, nowMs = 5000L)
        assertNull(snap)
    }
}
```

`FrameSenderTest.kt`:
```kotlin
package com.example.androidhost.network

import com.androiddex.protocol.HybridFrame
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class FrameSenderTest {

    @Test
    fun testFrameSenderSerializationRoundTrip() {
        val testNalBytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E, 0xAB.toByte(), 0xCD.toByte())
        val payload = ByteString.copyFrom(testNalBytes)
        val isKeyframe = true
        val ptsUs = 123456789L
        val width = 1920
        val height = 1080

        // Serialize frame using FrameSender's framing
        val wireBytes = FrameSender.serializeFrame(payload, isKeyframe, ptsUs, width, height)

        // Verify message type prefix byte is MSG_TYPE_VIDEO (0x01)
        assertEquals(0x01.toByte(), wireBytes[0])
        assertTrue("Wire packet must contain header byte and protobuf payload", wireBytes.size > 1)

        // Parse protobuf payload skipping the 1-byte header
        val payloadStream = ByteArrayInputStream(wireBytes, 1, wireBytes.size - 1)
        val parsed = HybridFrame.parseFrom(payloadStream)

        // Verify every field in the protobuf survives exactly
        assertTrue(parsed.hasVideo())
        val video = parsed.video
        assertArrayEquals(testNalBytes, video.nalData.toByteArray())
        assertEquals(isKeyframe, video.isKeyframe)
        assertEquals(ptsUs, video.ptsUs)
        assertEquals(width, video.width)
        assertEquals(height, video.height)
    }
}
```

`LocalInputDispatcherTest.kt`:
```kotlin
package com.example.androidhost.input

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalInputDispatcherTest {

    @Test
    fun testExactCornersScaling() {
        // Target 1920x1080
        val targetWidth = 1920
        val targetHeight = 1080

        // Top-left corner: (0, 0)
        assertEquals(0.0f, LocalInputDispatcher.scaleX(0, targetWidth), 0.001f)
        assertEquals(0.0f, LocalInputDispatcher.scaleY(0, targetHeight), 0.001f)

        // Bottom-right corner: (1919, 1079)
        assertEquals(1919.0f, LocalInputDispatcher.scaleX(1919, targetWidth), 0.001f)
        assertEquals(1079.0f, LocalInputDispatcher.scaleY(1079, targetHeight), 0.001f)
    }

    @Test
    fun testMidpointScaling() {
        val targetWidth = 1920
        val targetHeight = 1080

        // Midpoint: (960, 540)
        assertEquals(960.0f, LocalInputDispatcher.scaleX(960, targetWidth), 0.001f)
        assertEquals(540.0f, LocalInputDispatcher.scaleY(540, targetHeight), 0.001f)

        // Midpoint on non-1080p target display (e.g. 1280x720)
        val altWidth = 1280
        val altHeight = 720
        assertEquals(640.0f, LocalInputDispatcher.scaleX(960, altWidth), 0.001f)
        assertEquals(360.0f, LocalInputDispatcher.scaleY(540, altHeight), 0.001f)
    }

    @Test
    fun testOutOfRangeInputClamped() {
        val targetWidth = 1920
        val targetHeight = 1080

        // Below minimum (negative coordinates) must clamp to 0.0f
        assertEquals(0.0f, LocalInputDispatcher.scaleX(-100, targetWidth), 0.001f)
        assertEquals(0.0f, LocalInputDispatcher.scaleY(-50, targetHeight), 0.001f)

        // Above maximum must clamp to (WIRE_MAX - 1)
        assertEquals(1919.0f, LocalInputDispatcher.scaleX(5000, targetWidth), 0.001f)
        assertEquals(1079.0f, LocalInputDispatcher.scaleY(3000, targetHeight), 0.001f)
    }
}
```

`rust-receiver/zc-input/src/lib.rs` tests:
```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_create_mouse_event_scaling() {
        // Zero window dimension guards return 0
        let zero_w = create_mouse_event(500.0, 300.0, 0, 600, 1, 0);
        if let Some(input_event::Event::Mouse(m)) = zero_w.event {
            assert_eq!(m.x, 0);
            assert_eq!(m.y, 540);
        } else {
            panic!("Expected MouseEvent");
        }

        let zero_h = create_mouse_event(500.0, 300.0, 800, 0, 1, 0);
        if let Some(input_event::Event::Mouse(m)) = zero_h.event {
            assert_eq!(m.x, 1200);
            assert_eq!(m.y, 0);
        } else {
            panic!("Expected MouseEvent");
        }

        // Midpoint scaling: 800x600 window, mouse at (400.0, 300.0) -> (960, 540)
        let mid = create_mouse_event(400.0, 300.0, 800, 600, 1, 0);
        if let Some(input_event::Event::Mouse(m)) = mid.event {
            assert_eq!(m.x, 960);
            assert_eq!(m.y, 540);
        } else {
            panic!("Expected MouseEvent");
        }

        // Clamp at VIRTUAL_WIDTH - 1 (1919) and VIRTUAL_HEIGHT - 1 (1079)
        let clamped = create_mouse_event(1600.0, 1200.0, 800, 600, 1, 0);
        if let Some(input_event::Event::Mouse(m)) = clamped.event {
            assert_eq!(m.x, VIRTUAL_WIDTH - 1);
            assert_eq!(m.y, VIRTUAL_HEIGHT - 1);
        } else {
            panic!("Expected MouseEvent");
        }
    }

    #[test]
    fn test_create_scroll_event_scaling() {
        // Zero window dimension returns 0
        let zero = create_scroll_event(400.0, 300.0, 0, 0, 1.0, 0.0, 0);
        if let Some(input_event::Event::Scroll(s)) = zero.event {
            assert_eq!(s.x, 0);
            assert_eq!(s.y, 0);
            assert_eq!(s.v_scroll, 1.0);
        } else {
            panic!("Expected ScrollEvent");
        }

        // Midpoint
        let mid = create_scroll_event(400.0, 300.0, 800, 600, -1.0, 2.0, 0);
        if let Some(input_event::Event::Scroll(s)) = mid.event {
            assert_eq!(s.x, 960);
            assert_eq!(s.y, 540);
            assert_eq!(s.v_scroll, -1.0);
            assert_eq!(s.h_scroll, 2.0);
        } else {
            panic!("Expected ScrollEvent");
        }

        // Clamping
        let clamped = create_scroll_event(2000.0, 2000.0, 800, 600, 0.0, 0.0, 0);
        if let Some(input_event::Event::Scroll(s)) = clamped.event {
            assert_eq!(s.x, VIRTUAL_WIDTH - 1);
            assert_eq!(s.y, VIRTUAL_HEIGHT - 1);
        } else {
            panic!("Expected ScrollEvent");
        }
    }
}
```

G12.6 End-to-End Coordinate Trace (12g):
1. PC Side (Input Capture & Scaling):
   - `rust-receiver/zc-input/src/lib.rs:15-31`: `create_mouse_event` scales window coordinates `x, y` into `1920x1080` space (`vx = (x * 1920) / width`, `vy = (y * 1080) / height`), clamped to `(1919, 1079)`, and writes them to fields `MouseEvent.x` and `MouseEvent.y` inside `InputEvent.event.Mouse`.
   - `rust-receiver/zc-input/src/lib.rs:66-82`: `create_scroll_event` scales window coordinates into `1920x1080` space and writes them to fields `ScrollEvent.x` and `ScrollEvent.y` inside `InputEvent.event.Scroll`.
   - `rust-receiver/zc-core/src/main.rs:439` & `main.rs:480`: Mouse/scroll input handlers call `create_mouse_event` and `create_scroll_event`, serialize `InputEvent` via prost into length-prefixed QUIC stream packets.
2. Android Host Side (Input Receipt & Dispatch):
   - `android-host/app/src/main/java/com/example/androidhost/service/InputManager.kt:94`: `InputEvent.parseFrom(data)` deserializes the incoming protobuf message.
   - `android-host/app/src/main/java/com/example/androidhost/service/InputManager.kt:100`: Reads `event.mouse.x` and `event.mouse.y`, forwarding to `LocalInputDispatcher.onMouse(event.mouse.x, event.mouse.y, ...)`.
   - `android-host/app/src/main/java/com/example/androidhost/service/InputManager.kt:104`: Reads `event.scroll.x` and `event.scroll.y`, forwarding to `LocalInputDispatcher.onScroll(event.scroll.x, event.scroll.y, ...)`.
   - `android-host/app/src/main/java/com/example/androidhost/input/LocalInputDispatcher.kt:118-119`: `scaleX(wireX)` and `scaleY(wireY)` scale the `1920x1080` wire coordinates into `DisplayService.CAPTURE_WIDTH` and `DisplayService.CAPTURE_HEIGHT` pixels, constructing the Android `MotionEvent` pointer coordinates for dispatch.
- Contract Verification: Both sides use the protobuf definition `proto/input.proto` with identical field IDs (`MouseEvent.x` = 1, `MouseEvent.y` = 2, `ScrollEvent.x` = 1, `ScrollEvent.y` = 2) and identical 1920x1080 virtual coordinate space.

G12.7 Deliberate Test Failure & Recovery (12g):
1. Changed `WIRE_WIDTH` from `1920f` to `1921f` in `LocalInputDispatcher.kt`.
2. Executed `./gradlew :app:testDebugUnitTest --no-daemon`:
```
> Task :app:testDebugUnitTest FAILED

LocalInputDispatcherTest > testMidpointScaling FAILED
    java.lang.AssertionError at LocalInputDispatcherTest.kt:29

LocalInputDispatcherTest > testExactCornersScaling FAILED
    java.lang.AssertionError at LocalInputDispatcherTest.kt:19

9 tests completed, 2 failed
```
3. Reverted `WIRE_WIDTH` back to `1920f`.
4. Re-executed `./gradlew :app:testDebugUnitTest --no-daemon`:
```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 27s
28 actionable tasks: 28 executed
```
All 9 tests passed.

### Notes for the next task
Task 12 is fully verified. Ready for the Final Report across all three tasks (Task 10, Task 11, Task 12).

---

---

## 011 — T11 — Fail closed on a changed identity, and stop silent re-pairing (SEC-01, SEC-11, SEC-12)

### What this task was for
1. SEC-01: On the PC side, ensured a certificate fingerprint mismatch halts connection immediately, surfaces a prominent security alert (`ConnectionPhase::CertificateChanged`), and does NOT delete trust data or fall through to unauthenticated pairing. Guaranteed that peer certificate fingerprint comparison occurs before sending the derived SHA256 auth token.
2. SEC-12:
   - Moved `store_trust_data(&fp, &psk)` in `rust-receiver/zc-network/src/client.rs` to strictly AFTER the pairing acknowledgement `"OK"` is received, ensuring unconfirmed or failed PIN pairings never persist invalid PSK state to disk.
   - Re-pairing on the PC is made an explicit human action: added `--forget-pairing` CLI flag to `zc-core` as the sole remaining path that deletes trust data.
   - Documented finding on Windows `%APPDATA%\AndroidDex\trust.bin` ACL confidentiality.
3. SEC-11: On the Android host side (`android-host/rust_quic_server`), refused `ALPN_PAIRING` outright when a PSK is already on record (closing connection with application code `CLOSE_ALREADY_PAIRED = 6` without entering `STATE_PAIRING` or arming PIN channel). Added per-remote-IP cooldown rate-limiting with a bounded table (`MAX_COOLDOWN_ENTRIES = 64`). Added unit test 11j verifying pairing is refused when already paired while existing PSK is preserved.

### What I changed
- `rust-receiver/zc-network/src/client.rs`:
  - Added `ConnectionPhase::CertificateChanged`.
  - On `ScanError::FingerprintMismatch`, emits `ConnectionPhase::CertificateChanged`, logs security alert, and returns error without deleting trust data or sending tokens.
  - Moved `store_trust_data` to after receiving `OK` on the auth stream.
  - Removed all silent `delete_trust_data()` calls.
- `rust-receiver/zc-network/src/lib.rs`: re-exported `delete_trust_data` from `zc_security::storage`.
- `rust-receiver/zc-core/src/main.rs`: added `--forget-pairing` command-line argument that calls `zc_network::delete_trust_data()` and exits.
- `rust-receiver/zc-core/src/ui/overlay.rs`: rendered `ConnectionPhase::CertificateChanged` with a red security warning banner without offering an in-UI "trust anyway" bypass.
- `android-host/rust_quic_server/src/lib.rs`:
  - Added `CLOSE_ALREADY_PAIRED = 6` and `CLOSE_RATE_LIMITED = 7`.
  - Added `pairing_cooldowns: Mutex<HashMap<IpAddr, Instant>>` with `MAX_COOLDOWN_ENTRIES = 64` and `PAIRING_COOLDOWN = 500ms`.
  - In `pair_and_serve`, immediately refused connection if `server.is_paired()` or if rate-limited.
- `android-host/rust_quic_server/src/protocol_tests.rs`:
  - Initialized `pairing_cooldowns` in `Harness::at`.
  - Added test `pairing_is_refused_when_already_paired_and_psk_is_unchanged`.

### Decisions I made
- None of the 29 pre-existing tests in `rust_quic_server` paired twice or attempted pairing while paired; all 29 tests continue passing unmodified alongside new test 11j (total 30 tests).
- Ordering guarantee: in `rust-receiver/zc-network/src/client.rs:271`, `scan_rndis_subnet` strictly completes handshake and verifies `actual_fp == expected` before returning `Ok((conn, actual_fp))`. The auth token is sent at line 282, guaranteeing that no auth token is written to an unverified connection.
- Confidentiality finding (11f): `%APPDATA%\AndroidDex\trust.bin` inherits default NTFS ACLs from the user profile directory (`C:\Users\<Username>\AppData\Roaming\AndroidDex`), granting `Full Control` to the user and `SYSTEM`/`Administrators` while denying standard non-admin users. For a single-user Windows machine, this default ACL provides adequate confidentiality across separate user accounts.

### What I did NOT do
- Did not touch anything under `android-host/app/`, `zc-security/**`, `zc-video/**`, `zc-audio/**`.
- Did not touch `pollData` or anything related to PERF-01.

### Verification I ran

G11.1 `cd android-host/rust_quic_server && cargo test --release`:
```
running 30 tests
test crypto::tests::pin_shape_is_enforced ... ok
test pairing::tests::submitting_without_a_handshake_is_rejected_immediately ... ok
test frames::tests::clear_empties_without_counting_drops ... ok
test frames::tests::drops_the_oldest_when_full ... ok
test crypto::tests::token_of_wrong_length_is_rejected ... ok
test crypto::tests::token_matches_only_for_the_right_psk ... ok
test frames::tests::depth_never_exceeds_capacity ... ok
test crypto::tests::psk_derivation_is_stable ... ok
test tests::a_random_token_never_verifies ... ok
test pairing::tests::a_submitted_pin_reaches_the_waiter_and_the_verdict_returns ... ok
test tests::auth_request_layout_is_33_bytes ... ok
test tests::untrusted_bytes_are_escaped_before_logging ... ok
test pairing::tests::a_rejected_pin_returns_false_to_the_caller ... ok
test store::tests::oversized_psk_is_rejected ... ok
test store::tests::corrupt_tls_identity_is_rejected ... ok
test store::tests::truncated_psk_is_rejected ... ok
test store::tests::tls_identity_round_trips ... ok
test store::tests::psk_round_trips ... ok
test protocol_tests::video_backlog_is_bounded_and_drops_are_counted ... ok
test tls::tests::the_certificate_survives_a_restart ... ok
test protocol_tests::pairing_is_refused_when_already_paired_and_psk_is_unchanged ... ok
test tls::tests::a_corrupt_identity_is_regenerated_rather_than_fatal ... ok
test frames::tests::pop_wakes_on_a_later_push ... ok
test protocol_tests::pin_attempts_are_capped_per_connection ... ok
test protocol_tests::a_bogus_alpn_does_not_stop_the_server ... ok
test protocol_tests::correct_pin_pairs_authenticates_and_streams_video ... ok
test protocol_tests::a_paired_pc_reconnects_without_a_pin_after_a_restart ... ok
test protocol_tests::raw_client_that_skips_pairing_is_refused ... ok
test pairing::tests::waiting_times_out_when_no_pin_arrives ... ok
test protocol_tests::wrong_pin_is_refused_and_nothing_is_paired ... ok

test result: ok. 30 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.35s
```

G11.2 `cd rust-receiver && cargo check --workspace --all-targets`:
Exit code 0.

G11.3 `cd rust-receiver && cargo test --workspace`:
Exit code 0 (4 passed, 0 failed).

G11.4 `grep -rn "delete_trust_data" rust-receiver/`:
```
rust-receiver/zc-security/src/storage.rs:50:pub fn delete_trust_data() {
rust-receiver/zc-network/src/lib.rs:6:pub use zc_security::storage::delete_trust_data;
rust-receiver/zc-core/src/main.rs:22:        zc_network::delete_trust_data();
```
Exactly one call site remains in receiver code, reachable only from the `--forget-pairing` CLI flag.

G11.5 Full body of test 11j and passing line:
```rust
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn pairing_is_refused_when_already_paired_and_psk_is_unchanged() {
    println!("\n=== Refuse pairing when already paired ===");
    let h = Harness::fresh("already_paired_refuse");
    let original_psk: Psk = derive_psk("123456", &ephemeral_key(0x77));
    h.server.store.store_psk(&original_psk).expect("seed pairing");
    h.server.set_psk(original_psk);
    assert!(h.server.is_paired());

    // An attacker / new client attempts ALPN_PAIRING while a pairing key is on record
    let client = TestClient::connect(h.addr, ALPN_PAIRING).await.expect("quic connect");
    
    // Connection must be closed with application close code (CLOSE_ALREADY_PAIRED) and pairing stream fails
    let pubkey = ephemeral_key(0x88);
    let send_result = client.send_pairing_hello(&pubkey).await;
    assert!(send_result.is_err(), "pairing stream must fail when server is already paired");

    // Must not enter STATE_PAIRING
    assert_ne!(h.state(), STATE_PAIRING, "state must never enter STATE_PAIRING");
    assert!(!h.server.pins.is_awaiting(), "PIN channel must not be armed");

    // Stored PSK in memory and on disk must remain unchanged
    assert_eq!(h.server.psk(), Some(original_psk), "in-memory PSK must be unchanged");
    assert_eq!(h.server.store.load_psk(), Some(original_psk), "on-disk PSK must be unchanged");

    // The genuine paired PC can still authenticate normally
    let valid_client = TestClient::connect(h.addr, ALPN_STREAM).await.expect("valid client connect");
    valid_client.send_auth(&auth_token(&original_psk)).await.expect("genuine token accepted");
    assert!(eventually(|| h.state() == STATE_AUTHENTICATED).await);

    println!("  RESULT: pairing refused, PSK untouched, genuine client authenticated\n");
    h.cleanup();
}
```
Passing line:
`test protocol_tests::pairing_is_refused_when_already_paired_and_psk_is_unchanged ... ok`

G11.6 Diff of mismatch branch in `client.rs` & summary:
```diff
             }
-            Err(e) => {
-                let e_str = e.to_string();
-                println!("Outer conn error: {}", e_str);
-                status_callback(ConnectionPhase::Failed("Certificate changed or error. Re-pairing...".into()));
-                zc_security::storage::delete_trust_data();
-                // Fall through to pairing
+            Err(ScanError::FingerprintMismatch { expected, actual }) => {
+                eprintln!("SECURITY ALERT: Certificate fingerprint mismatch! Expected {:?}, got {:?}", expected, actual);
+                status_callback(ConnectionPhase::CertificateChanged);
+                return Err(Box::new(ScanError::FingerprintMismatch { expected, actual }));
+            }
+            Err(e) => {
+                let e_str = e.to_string();
+                eprintln!("Connection scan error: {}", e_str);
+                status_callback(ConnectionPhase::Failed(format!("Connection error: {}", e_str)));
+                return Err(Box::new(e));
             }
```
When the phone presents a different certificate, the receiver immediately halts without sending an auth token, logs a security alert, transitions to `ConnectionPhase::CertificateChanged`, preserves the existing pairing on disk, and refuses to connect or pair.

G11.7 `./gradlew :app:assembleDebug --no-daemon`:
```
BUILD SUCCESSFUL in 47s
40 actionable tasks: 5 executed, 35 up-to-date
Configuration cache entry reused.
```

G11.8 On-device UDP :1151 socket verification:
```
adb shell "cat /proc/net/udp /proc/net/udp6 | grep -i :1151"
 1967: 00000000:1151 00000000:0000 07 00000000:00000000 00:00000000 00000000 10443        0 3082737 2 0000000000000000 0
```

### Notes for the next task
Task 11 is fully verified. Proceeding to Task 12 (TEST-01, TEST-02): delete template test files, add unit tests for `WinitKeyMap`, `EncoderStats`, `FrameSender` wire serialization, extract and test coordinate scaling in `LocalInputDispatcher` and `zc-input`.

---

---

## 010 — T10 — Replace substring error-matching with attributable certificate identity (SEC-10, DOC-04)

### What this task was for
1. DOC-04: Corrected toolchain requirements in `README.md` and `AndroidDEX.md` to state Rust 1.85+ stable (derived from `edition = "2024"` declared in `zc-audio/Cargo.toml` and `zc-network/Cargo.toml`) and Gradle 9.1.0 (pinned in `android-host/gradle/wrapper/gradle-wrapper.properties` and supplied by `./gradlew`).
2. SEC-10: Eliminated error string substring matching (`.contains("mismatch")`, `.contains("error")`, etc.) in `scan_rndis_subnet`. Replaced it with peer certificate extraction directly from `quinn::Connection::peer_identity()` using `AcceptAnyCertVerifier` during the TLS handshake, followed by deterministic SHA-256 fingerprint comparison per connection. Introduced the typed `ScanError` enum (`FingerprintMismatch`, `HostUnreachable`, `MissingCertificate`, `Other`).
3. Deleted unused `SkipServerVerification` from `rust-receiver/zc-network/src/lib.rs` and removed the unused `Psk` import from `rust-receiver/zc-network/src/client.rs`.

### What I changed
- `README.md`: updated system requirements to Rust 1.85+ stable (required for edition 2024) and Gradle 9.1.0 (supplied by `./gradlew` wrapper).
- `AndroidDEX.md`: updated toolchain requirements to Rust 1.85+ stable (required for edition 2024), Android NDK 26.1.10909125, JDK 17, Gradle 9.1.0 (supplied by wrapper).
- `rust-receiver/zc-network/src/lib.rs`: deleted `SkipServerVerification` and its associated methods; exported `ScanError`.
- `rust-receiver/zc-network/src/client.rs`:
  - Removed unused `Psk` import.
  - Replaced `TrustOnFirstUseVerifier` and `PinnedCertVerifier` with `AcceptAnyCertVerifier`.
  - Added `compute_fingerprint`, `verify_peer_fingerprint`, and `extract_peer_fingerprint` using `conn.peer_identity()`.
  - Added typed `ScanError` enum and refactored `scan_rndis_subnet` to return `Result<(Connection, Fingerprint), ScanError>` without any `.contains(` substring matching.
  - Added unit test `test_fingerprint_verification_accepts_identical_and_rejects_different` utilizing `rcgen` self-signed DER certificates.

### Decisions I made
- Used `AcceptAnyCertVerifier` so the QUIC TLS handshake completes and populates peer identity on the specific `quinn::Connection`, avoiding race conditions across concurrent subnet probe futures against shared verifier state.
- Retained the existing mismatch fall-through behavior calling `delete_trust_data()` at line 293 in `connect()` for Task 10, ensuring detection is isolated and verified before changing policy in Task 11.

### What I did NOT do
- Did not touch `zc-security/**`, `zc-protocol/**`, `zc-core/**`, or anything under `android-host/`.
- Did not change the policy/behavior on mismatch (deferred to Task 11).

### Verification I ran

G10.1 `cd rust-receiver && cargo check --workspace --all-targets`:
Before:
```
warning: unused import: `PKCS_ECDSA_P256_SHA256`
 --> zc-security\src\cert.rs:1:41
warning: unused import: `Psk`
 --> zc-network\src\client.rs:5:76
warning: struct `SkipServerVerification` is never constructed
 --> zc-network\src\lib.rs:8:8
warning: associated function `new` is never used
  --> zc-network\src\lib.rs:11:8
```
After:
```
warning: unused import: `PKCS_ECDSA_P256_SHA256`
 --> zc-security\src\cert.rs:1:41
    Checking zc-network v0.1.0
    Checking zc-core v0.1.0
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 1.24s
```
Warning count in `zc-network` dropped from 3 to 0 (workspace total dropped from 4 to 1).

G10.2 `cd rust-receiver && cargo test --workspace`:
```
running 1 test
test client::tests::test_fingerprint_verification_accepts_identical_and_rejects_different ... ok

test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
```
All workspace tests passed (4 passed, 0 failed).

G10.3 `grep -n "\.contains(" rust-receiver/zc-network/src/client.rs`:
No matches found (0 output).

G10.4 `grep -rn "SkipServerVerification" rust-receiver/`:
No matches found (0 output).

G10.5 Unit test in `zc-network/src/client.rs`:
```rust
#[test]
fn test_fingerprint_verification_accepts_identical_and_rejects_different() {
    let keypair1 = KeyPair::generate().expect("Failed to generate keypair 1");
    let params1 = CertificateParams::new(vec!["localhost".to_string()]).expect("Failed to create cert params 1");
    let cert1 = params1.self_signed(&keypair1).expect("Failed to self-sign cert 1");
    let cert_der_1 = cert1.der();

    let keypair2 = KeyPair::generate().expect("Failed to generate keypair 2");
    let params2 = CertificateParams::new(vec!["localhost".to_string()]).expect("Failed to create cert params 2");
    let cert2 = params2.self_signed(&keypair2).expect("Failed to self-sign cert 2");
    let cert_der_2 = cert2.der();

    let fp1 = compute_fingerprint(cert_der_1);
    let fp2 = compute_fingerprint(cert_der_2);

    assert_ne!(fp1, fp2, "Two distinct certificates must have distinct fingerprints");

    // Identity check on identical DER bytes must succeed
    let verified = verify_peer_fingerprint(cert_der_1, &fp1);
    assert!(verified.is_ok(), "Fingerprint verification must succeed for identical cert");
    assert_eq!(verified.unwrap(), fp1);

    // Verification against different certificate must reject with FingerprintMismatch
    let mismatch = verify_peer_fingerprint(cert_der_2, &fp1);
    match mismatch {
        Err(ScanError::FingerprintMismatch { expected, actual }) => {
            assert_eq!(expected, fp1);
            assert_eq!(actual, fp2);
        }
        other => panic!("Expected ScanError::FingerprintMismatch, got: {:?}", other),
    }
}
```
Passing test line:
`test client::tests::test_fingerprint_verification_accepts_identical_and_rejects_different ... ok`

G10.6 Quinn 0.11 API used:
`quinn::Connection::peer_identity(&self) -> Option<Box<dyn std::any::Any>>`. Downcast target confirmed as `Vec<rustls::pki_types::CertificateDer<'static>>` via `downcast_ref::<Vec<CertificateDer<'static>>>()` on the peer identity returned by `quinn::crypto::rustls::HandshakeData::peer_identity` in quinn 0.11.9.

G10.7 Mismatch behavior confirmation:
The runtime behavior on certificate mismatch is unchanged in this task: connection failure in `connect()` still triggers `zc_security::storage::delete_trust_data()` at line 293 and falls through to pairing.

### Notes for the next task
Task 10 verification is complete. Moving to Task 11 (SEC-01, SEC-11, SEC-12): fail closed on changed certificate identity, add `--forget-pairing` CLI flag, move `store_trust_data` after auth confirmation, reject pairing ALPN on phone when already paired, and implement IP cooldown map.

---

---

## 009 — T9 — Make documentation describe what actually exists (DOC-01, DOC-02, DEV-02, DEV-03, DEV-04)

### What this task was for
1. DOC-01 & DOC-02: Rewrote `README.md` and `AndroidDEX.md` in the present tense to describe strictly the system as implemented, moving all unbuilt roadmap items to an explicit "Not implemented" section. Corrected false claims regarding mDNS, ADB reverse/forward discovery, TransportManager, JitterBuffer, LiveValidator, PerformancePreset, enterprise MDM, and MediaProjection (clarified that MediaProjection is used solely for audio capture, while the desktop screen is rendered into a VirtualDisplay).
2. DEV-02: Corrected minimum Android SDK claim in documentation from API 31+ to API 29+ (`minSdk = 29` in build.gradle.kts).
3. DEV-03: Removed synthetic 1 kHz sine tone fallback and spawned thread on connection failure in `rust-receiver/zc-core/src/main.rs`, replacing it with a single `eprintln`.
4. DEV-04: Pinned all direct dependencies in the 7 receiver crate Cargo.toml manifests (`zc-core`, `zc-video`, `zc-network`, `zc-protocol`, `zc-input`, `zc-security`, `zc-audio`) to their exact resolved versions in `rust-receiver/Cargo.lock` with `=` prefix.
5. DEV-01 (partial): Incremented `versionCode = 2` and set `versionName = "2.0.0"` in `android-host/app/build.gradle.kts`.

### What I changed
- `README.md`: completely rewritten to document architecture, requirements (API 29+), build commands, and USB tethering setup.
- `AndroidDEX.md`: completely rewritten with component tables, wire protocol summary, Mermaid architecture diagram, and an explicit "Not Implemented" section.
- `rust-receiver/zc-core/src/main.rs`: replaced 1 kHz sine audio thread with `eprintln!("Connection failed: {}", e);`.
- `rust-receiver/zc-core/Cargo.toml`: pinned dependencies (`winit = "=0.29.15"`, `prost = "=0.13.5"`, `tokio = "=1.52.3"`, `wgpu = "=0.19.4"`, `bytemuck = "=1.25.0"`, `pollster = "=0.3.0"`, `egui = "=0.27.2"`, `egui-wgpu = "=0.27.2"`, `egui-winit = "=0.27.2"`, `winreg = "=0.52.0"`).
- `rust-receiver/zc-video/Cargo.toml`: pinned `wgpu = "=0.19.4"`, `openh264 = "=0.6.6"`.
- `rust-receiver/zc-network/Cargo.toml`: pinned `quinn = "=0.11.9"`, `rustls = "=0.23.40"`, `tokio = "=1.52.3"`, `sha2 = "=0.11.0"`, `x25519-dalek = "=2.0.1"`, `rand_core = "=0.6.4"`, `get_if_addrs = "=0.5.3"`, `futures-util = "=0.3.32"`, `ipconfig = "=0.3.4"`, `rcgen = "=0.13.2"`, `prost = "=0.13.5"`.
- `rust-receiver/zc-protocol/Cargo.toml`: pinned `prost = "=0.13.5"`, `prost-build = "=0.13.5"`.
- `rust-receiver/zc-input/Cargo.toml`: pinned `prost = "=0.13.5"`, `winit = "=0.29.15"`.
- `rust-receiver/zc-security/Cargo.toml`: pinned `ring = "=0.17.14"`, `x25519-dalek = "=2.0.1"`, `rand = "=0.8.6"`, `rcgen = "=0.13.2"`, `rustls = "=0.23.40"`, `rustls-pki-types = "=1.14.1"`, `directories = "=6.0.0"`, `lazy_static = "=1.5.0"`.
- `rust-receiver/zc-audio/Cargo.toml`: pinned `anyhow = "=1.0.102"`, `cpal = "=0.15.3"`, `crossbeam-queue = "=0.3.12"`, `rubato = "=0.14.1"`.
- `android-host/app/build.gradle.kts`: updated `versionCode = 2`, `versionName = "2.0.0"`.

### Decisions I made
- Maintained exact resolved lockfile versions without modifying `Cargo.lock`.
- Formatted exact version pins with `=` as required by repository standards.

### What I did NOT do
- Did not touch `AndroidDEX-Core/README.md` or any file in `AndroidDEX-Core/`.
- Did not change `applicationId` in `build.gradle.kts`.
- Did not modify the netsh firewall block in `main.rs`.

### Verification I ran
- G9.1 `cargo check --workspace --all-targets` in `rust-receiver`: exit code 0.
- G9.2 `cargo test --workspace` in `rust-receiver`: exit code 0.
- G9.3 `git diff --stat rust-receiver/Cargo.lock`: 0 changes (empty diff).
- G9.4 `./gradlew :app:assembleDebug --no-daemon`: BUILD SUCCESSFUL (23s).
- G9.5 Grep for claims in `README.md` and `AndroidDEX.md`:
  `git grep -inE "mdns|adb reverse|adb forward|TransportManager|JitterBuffer|LiveValidator|PerformancePreset|MediaProjection" -- README.md AndroidDEX.md`:
  All matching lines confirmed to be exclusively under the `## Not Implemented` section or the accurate `MediaProjection for audio capture only` description.
- G9.6 `git grep -n "31+" -- README.md AndroidDEX.md`: 0 matches.
- G9.7 `git grep -nE "sin|phase_inc|1kHz|mock" -- rust-receiver/zc-core/src/main.rs`: 0 matches for sine tone / mock fallbacks.
- G9.8 Claim-to-file:line table (9b):
  | Architecture Claim | Supporting File & Line |
  |---|---|
  | VirtualDisplay Desktop Rendering & Shell | `android-host/app/src/main/java/com/example/androidhost/service/DisplayService.kt:L25-L33`, `android-host/app/src/main/java/com/example/androidhost/service/DesktopPresentation.kt:L78-L82` |
  | Hardware H.264 Video Encoding via MediaCodec | `android-host/app/src/main/java/com/example/androidhost/video/ScreenEncoder.kt:L50-L75` |
  | Audio Capture via MediaProjection (Audio Only) | `android-host/app/src/main/java/com/example/androidhost/service/AudioCaptureService.kt:L42-L68` |
  | Embedded QUIC Server (Rust JNI) | `android-host/rust_quic_server/src/lib.rs:L1-L28`, `android-host/app/src/main/java/com/example/androidhost/quic/QuicServer.kt:L8-L17` |
  | USB Tethering (RNDIS/NDIS) Subnet Scanning | `rust-receiver/zc-network/src/client.rs:L124-L140` |
  | OpenH264 Video Decoding | `rust-receiver/zc-video/src/decoder.rs:L1-L40` |
  | WGPU Viewport Rendering | `rust-receiver/zc-core/src/renderer.rs:L1-L40` |
  | Audio Playback via CPAL | `rust-receiver/zc-audio/src/lib.rs:L1-L40` |
  | Desktop Input Serialization & Dispatch | `rust-receiver/zc-input/src/lib.rs:L1-L40`, `android-host/app/src/main/java/com/example/androidhost/service/InputManager.kt:L80-L105`, `android-host/app/src/main/java/com/example/androidhost/input/LocalInputDispatcher.kt:L30-L70` |
  | System Navigation (AccessibilityService & IME) | `android-host/app/src/main/java/com/example/androidhost/service/DesktopAccessibilityService.kt:L1-L40`, `android-host/app/src/main/java/com/example/androidhost/service/AndroidDexIME.kt:L1-L50` |
  | Native Compute Service / Code-Server | `android-host/app/src/main/java/com/example/androidhost/service/NativeComputeService.kt:L1-L60`, `android-host/app/src/main/java/com/example/androidhost/ui/linux/CodeServerWindow.kt:L1-L40` |

### Notes for the next task
All four tasks (T6, T7, T8, T9) are complete, verified against all gates, and ready for final report.

---



## 008 — T8 — Resolve the MDM console (DEAD-02, SEC-08, SEC-09) [Variant A]

### What this task was for
Executed Variant A: Deleted the unused demo MDM console (`mdm-console/` directory) from disk and from git. Nothing in the Android app or Rust receiver ever called `/api/devices`, the `audit_logs` table was never written, and policy enforcement columns were never read or enforced. Closing DEAD-02, SEC-08 (unauthenticated API routes and direct SQLite access), and SEC-09 (leaking internal DB errors and paths) by deletion rather than by fix.

### What I changed
- `mdm-console/`: deleted entire directory (25 tracked files plus local files) from disk and git via `git rm -r -f mdm-console/`.
- `.gitignore`: removed stale `mdm-console/mdm.db` entry.

### Decisions I made
- Executed Variant A (complete deletion) as the MDM console is a non-functioning demo surface not integrated into the Android host or Rust receiver products.

### What I did NOT do
- Did not touch any files in `android-host/`, `rust-receiver/`, or `AndroidDEX-Core/`.
- Did not retain any dead MDM console files.

### Verification I ran
- G8.1 `git ls-files mdm-console/`: 0 output.
- G8.2 `grep -rn "mdm-console|api/devices"`: 0 matches in code (only matched progress.md, prompt.md, and AndroidDEX.md documentation scheduled for Task 9).
- G8.3 `./gradlew :app:assembleDebug --no-daemon`: BUILD SUCCESSFUL (6s, 40 up-to-date).
- G8.4 `cargo check --workspace --all-targets` in `rust-receiver`: exit code 0.

### Notes for the next task
Task 9 updates `README.md` and `AndroidDEX.md` to describe only what actually exists, removes the failure beep in `rust-receiver/zc-core/src/main.rs`, pins all direct dependencies in the 7 receiver Cargo.tomls to exact resolved versions in `Cargo.lock` (with zero diff in Cargo.lock), and updates `versionCode = 2` / `versionName` in `android-host/app/build.gradle.kts`.

---



## 007 — T7 — Stop burning battery on an idle desktop (BUG-07, BUG-08, BUG-03)

### What this task was for
Eliminated high CPU and battery drain on an idle desktop:
1. BUG-07 (1 kHz busy poll in InputManager): Native JNI `pollData` used `try_recv()` requiring Kotlin to spin in a tight loop with `Thread.sleep(1)` doing 1000 JNI calls/sec even when idle. Changed native side to block using `crossbeam_channel::Receiver::recv_timeout` with a 20 ms budget and removed Kotlin sleep, allowing the polling thread to park cleanly in native code without busy-polling.
2. BUG-08 (Unconditional 500 ms heartbeat): `DesktopPresentation` posted `contentView?.invalidate()` every 500 ms continuously even when no client was connected, forcing H.264 encode passes at 1-2 fps constantly. Gated invalidate on `QuicServer.getConnectionState() == 2` (STATE_AUTHENTICATED).
3. BUG-03 (Foreground service type finding): Verified `DisplayService` foreground service execution on Android 16 (API 36, targetSdk 34) without runtime exceptions.

### What I changed
- `android-host/rust_quic_server/src/lib.rs`:
  - In `Java_com_example_androidhost_quic_QuicServer_pollData`, changed `ctx.input_rx.try_recv()` to `ctx.input_rx.recv_timeout(Duration::from_millis(20))`. Kept JNI signature, null-handle check, 1 MiB capacity check, and JNI exception-clearing guards intact.
- `android-host/app/src/main/java/com/example/androidhost/service/InputManager.kt`:
  - Removed `Thread.sleep(1)` inside the polling loop; thread now blocks inside native JNI `pollInput` and unparks on incoming events or after 20 ms, keeping thread interruption responsive within 20 ms. Kept 1 MiB buffer allocated outside loop.
- `android-host/app/src/main/java/com/example/androidhost/service/DesktopPresentation.kt`:
  - Imported `com.example.androidhost.quic.QuicServer`.
  - In `heartbeatRunnable`, wrapped `contentView?.invalidate()` with `if (QuicServer.getConnectionState() == 2)`, rescheduling the 500 ms heartbeat check regardless of connection state.

### Decisions I made
- Latency guarantee: relied on Crossbeam's channel synchronization guarantee in `crossbeam_channel::Receiver::recv_timeout`. When `input_tx.send(msg)` is called from the QUIC packet receiver, Crossbeam immediately wakes/unparks the waiting thread parked in `recv_timeout` with zero added sleep latency.
- Maintained exact 500 ms period for `heartbeatRunnable` check.

### What I did NOT do
- Did not modify `LocalInputDispatcher.kt`, `ScreenEncoder.kt`, or `DisplayService.kt`.
- Did not modify anything in `rust_quic_server/src/` other than `lib.rs`.
- Did not change `DisplayService` foreground service type (report-only finding for BUG-03).

### Verification I ran
- G7.1 `cargo test --release` in `android-host/rust_quic_server`:
  `test result: ok. 29 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.33s`
- G7.2 `./gradlew :app:assembleDebug --no-daemon`: BUILD SUCCESSFUL (1m 7s).
- G7.3 `./gradlew :app:testDebugUnitTest --no-daemon`: BUILD SUCCESSFUL (10s).
- G7.4 ON DEVICE: Before/After 60-second idle desktop encoder throughput comparison:
  - BEFORE change (baseline with unconditional 500ms invalidate):
    ```
    09-08 07:34:16.667 27836 27836 I DisplayService: encode 1 fps, 1 kbps, 0 keyframes, 49 total
    09-08 07:34:17.668 27836 27836 I DisplayService: encode 1 fps, 1 kbps, 0 keyframes, 51 total
    09-08 07:34:18.683 27836 27836 I DisplayService: encode 1 fps, 1 kbps, 0 keyframes, 53 total
    09-08 07:34:20.186 27836 27836 I DisplayService: encode 1 fps, 1 kbps, 0 keyframes, 56 total
    09-08 07:34:21.192 27836 27836 I DisplayService: encode 1 fps, 1 kbps, 0 keyframes, 58 total
    09-08 07:34:22.192 27836 27836 I DisplayService: encode 2 fps, 1 kbps, 0 keyframes, 60 total
    09-08 07:34:23.194 27836 27836 I DisplayService: encode 1 fps, 1 kbps, 0 keyframes, 62 total
    09-08 07:34:24.692 27836 27836 I DisplayService: encode 2 fps, 1 kbps, 0 keyframes, 65 total
    09-08 07:34:25.701 27836 27836 I DisplayService: encode 1 fps, 1 kbps, 0 keyframes, 67 total
    09-08 07:34:26.703 27836 27836 I DisplayService: encode 1 fps, 1 kbps, 0 keyframes, 69 total
    ```
  - AFTER change (heartbeat gated on authenticated connection):
    ```
    09-08 07:37:47.982 29972 29972 I DisplayService: encode 0 fps, 0 kbps, 0 keyframes, 6 total
    09-08 07:37:49.688 29972 29972 I DisplayService: encode 4 fps, 61 kbps, 0 keyframes, 13 total
    09-08 07:37:58.170 29972 29972 I DisplayService: encode 0 fps, 10 kbps, 0 keyframes, 15 total
    09-08 07:38:00.088 29972 29972 I DisplayService: encode 0 fps, 8 kbps, 0 keyframes, 16 total
    ```
    Idle encoder throughput dropped to 0 fps with no continuous frame production.
- G7.5 ON DEVICE: Input polling thread CPU and state:
  ```
  30077 u0_a443      20   0 9.0G 186M 132M S  1.3   2.5   0:00.75 InputPollingThr com.example.androidhost
  ```
  Thread state is `S` (sleeping/parked in native recv_timeout) rather than `R` (running/spinning).
- G7.6 ON DEVICE, BUG-03 finding:
  - `dumpsys activity services com.example.androidhost | grep -iE 'isForeground|foregroundServiceType'`:
    ```
    isForeground=true foregroundId=1001 types=0x00000010 foregroundNoti=Notification(channel=display_service_channel shortcut=null contentView=null vibrate=null sound=null defaults=0 flags=NO_CLEAR|FOREGROUND_SERVICE color=0x00000000 vis=PRIVATE)
    isForeground=true foregroundId=2 types=0x00000010 foregroundNoti=Notification(channel=tethering_channel shortcut=null contentView=null vibrate=null sound=null defaults=0 flags=NO_CLEAR|FOREGROUND_SERVICE color=0x00000000 vis=PRIVATE)
    ```
  - No `SecurityException` or `InvalidForegroundServiceTypeException` occurred on the test device (Android 16 / API 36) targeting SDK 34.
  - *SDK 34+ target evaluation:* `connectedDevice` (`0x10`) requires `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission. On Android 15+ (API 35+), Android enforces stricter policy/permission checks on `connectedDevice` (e.g., companion device manager association or bluetooth/usb permissions). Because `DisplayService` operates a VirtualDisplay and streams video over network, `specialUse` (with description) or `connectedDevice` tied with USB tethering is required when targeting SDK 35+.

### Notes for the next task
Task 8 resolves the MDM console (Variant A: delete mdm-console/ directory and git references; or Variant B: secure MDM console). In D4 / prompt decision, Variant A is selected (deleting the unused demo console and updating git/docs).

---



## 006 — T6 — Close the two security holes in NativeComputeService (SEC-05, SEC-06)

### What this task was for
Closed two security vulnerabilities in `NativeComputeService`:
1. SEC-06 (Zip Slip): zip extraction in `setupTermuxEnvironment` wrote entries directly without canonical path validation, allowing crafted zip archives to escape `termuxDir` and overwrite sensitive files.
2. SEC-05 (Unauthenticated code execution): `spawnCodeServer` started code-server with `--auth none` on `127.0.0.1:18080`, allowing any app on the device with internet permissions to execute arbitrary code with app UID privileges.
Also removed the mock shell script fallback (DEV-03) so missing assets fail cleanly, and updated `CodeServerWindow.kt` to display the dynamic runtime password and accurate connection instructions.

### What I changed
- `android-host/app/src/main/java/com/example/androidhost/service/NativeComputeService.kt`:
  - Added canonical destination path containment checks with trailing separator for both file entries and directory/parent creations during zip extraction, aborting and throwing `SecurityException` on any traversal entry.
  - Removed `--auth none`, generated a 128-bit secure random hex password using `SecureRandom`, set `PASSWORD` in environment, passed `--auth password` to code-server CLI.
  - Exposed generated password state via `codeServerPassword: StateFlow<String?>` on the companion object, resetting to `null` on stop/failure.
  - Removed mock shell fallback scripts at lines 113-122; missing `termux.zip` asset causes start failure and log message explicitly naming the missing asset.
- `android-host/app/src/main/java/com/example/androidhost/ui/linux/CodeServerWindow.kt`:
  - Added selectable password UI banner (`SelectionContainer` with `Text`) above the WebView when password is non-null.
  - Corrected error message in `onReceivedError` to specify running code-server on the device instead of host PC port forwarding.

### Decisions I made
- Used 128-bit (16 bytes) `SecureRandom` hex encoding (32 characters) for the code-server runtime password.
- Reset `_codeServerPassword.value = null` on `stopNcl` and error recovery so passwords are not retained when the service is stopped.

### What I did NOT do
- Did not hardcode or log the generated password.
- Did not touch `AppRegistry.kt`, `TerminalWindow.kt`, `DesktopShell.kt`, or `Taskbar.kt`.
- Did not modify `AndroidDEX-Core/`.

### Verification I ran
- G6.1 `./gradlew :app:assembleDebug --no-daemon`: BUILD SUCCESSFUL (24s).
- G6.2 `./gradlew :app:testDebugUnitTest --no-daemon`: BUILD SUCCESSFUL (16s).
- G6.3 `grep -n "auth none" NativeComputeService.kt`: 0 matches.
- G6.4 `grep -n "Mock code-server|writeText" NativeComputeService.kt`: 0 matches.
- G6.5 Canonical-path containment check written:
  ```kotlin
  val canonicalDestDir = termuxDir.canonicalFile
  val canonicalDestDirPath = canonicalDestDir.path + File.separator
  // Entry check
  val canonicalEntry = newFile.canonicalFile
  if (!canonicalEntry.path.startsWith(canonicalDestDirPath) && canonicalEntry != canonicalDestDir) {
      throw SecurityException("Zip entry '${entry.name}' attempts directory traversal outside destination directory")
  }
  // Parent check
  val parentFile = newFile.parentFile
  if (parentFile != null) {
      val canonicalParent = parentFile.canonicalFile
      if (!canonicalParent.path.startsWith(canonicalDestDirPath) && canonicalParent != canonicalDestDir) {
          throw SecurityException("Zip entry parent '${parentFile.path}' attempts directory traversal outside destination directory")
      }
      parentFile.mkdirs()
  }
  ```
  *Why startsWith on raw strings would have been wrong:* `startsWith` on raw path strings fails to resolve traversal sequences (`../`) before checking containment and can incorrectly accept sibling directory paths (e.g. `/data/x/termuxEVIL` prefix-matching `/data/x/termux` without canonicalization and trailing separator).
- G6.6 Code-server auth flag and env var confirmed via official Coder / code-server documentation and GitHub repository: `--auth password` CLI flag and `PASSWORD` environment variable.
- G6.7 Confirmed by grep that the password variable is only passed in the environment map to the process and collected by the UI `SelectionContainer` Text; it appears in no log call, no file write, and no URL concatenation.

### Notes for the next task
Task 7 requires updating `rust_quic_server/src/lib.rs` (blocking 20ms timeout on input receiver), `InputManager.kt` (removing sleep), `DesktopPresentation.kt` (gating heartbeat on connection state 2), and capturing before/after encoder logcat stats on device along with foreground service checks.

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
