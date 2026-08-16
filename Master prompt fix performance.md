# MASTER TASK: Replace the raw-RGBA pipeline with zero-copy H.264

## Rules (non-negotiable)

1. No placeholders, no TODO comments, no stub functions, no mock data. A function whose
   body is a comment describing what should go there is a stub — I will reject it.
   `media_foundation.rs` in this repo is exactly that mistake; do not repeat it.
2. Every file must compile. If you cannot implement something fully, reduce scope and
   tell me precisely what you cut — do not fake it.
3. Pin exact dependency versions. Never "latest".
4. Work in phase order. Do not start a phase until the previous one's verification passes.
5. This task WILL change the wire protocol and WILL require editing Rust. That is approved.
6. When you touch both sides of the wire, change the `.proto` first, then regenerate, then
   edit Kotlin and Rust to match. Never hand-write protobuf bytes that the other side
   was not regenerated for.

---

## Context and diagnosis

Android phone hosts a desktop shell rendered into a `VirtualDisplay`. A Windows Rust
receiver displays it and sends mouse/keyboard back over QUIC.

Measured on real hardware: **320 frames in 304 seconds — about 1 fps.** Target is 60 fps
with 10-30 ms glass-to-glass. Four separate defects, all confirmed by reading the code:

**D1 — every tile is silently discarded by the receiver.**
`rust-receiver/zc-video/src/tile_compositor.rs:241` calls
`zstd::stream::copy_decode(tile.zstd_data.as_slice(), ...)`.
`android-host/.../service/RegionDetector.kt::sendTile()` passes **uncompressed raw RGBA**
into that field. There is no zstd dependency anywhere in `android-host/app/build.gradle.kts`.
The parameter is merely _named_ `zstdData`. Every tile fails to decode and is dropped, so
only the full-keyframe path ever reaches the screen.

**D2 — the capture loop is the 1 fps cause.**
`DisplayService.captureOneFrame()` allocates `ByteArray(1920*1080*4)` — 8.29 MB — on
**every frame**. At the intended 30 fps that is 249 MB/s of garbage. Then
`RegionDetector.hashTile()` walks that buffer **one byte at a time** in Kotlin: 8.29
million loop iterations per frame. Allocation churn plus scalar hashing is the stall.

**D3 — the bandwidth is physically impossible.**
A full keyframe is 8.29 MB uncompressed and is sent every `KEYFRAME_INTERVAL_MS` (1000 ms)
_and_ whenever zero tiles changed. That alone is ~66 Mbps. Raw RGBA at 30 fps would be
~2 Gbps. No amount of tuning fixes this; the format has to change.

**D4 — the app launcher launches into the void.**
`ui/components/AppLauncher.kt` still calls `ActivityOptions.setLaunchDisplayId(displayId)`.
Launching a third-party activity onto an untrusted virtual display requires signature-level
permission. It throws no exception, so the shell opens a window chrome that stays black
forever — observed with `com.google.android.calculator`.

**Root cause behind D1-D3:** the whole tile/diff/compress design exists only because frames
are being pulled into CPU memory. If the `VirtualDisplay` renders straight into a
`MediaCodec` input `Surface`, the pixels never touch the CPU at all and the entire tile
subsystem becomes unnecessary.

---

## Phase 1 — Zero-copy H.264 capture on Android

Rewrite `android-host/.../service/DisplayService.kt` so that:

- The `VirtualDisplay` is created with `MediaCodec.createInputSurface()` as its surface.
  **Delete the `ImageReader` entirely.** Delete `captureOneFrame()`, the capture
  `HandlerThread`, the `FRAME_INTERVAL_MS` polling loop, `lastKeyframeTime`, and
  `firstFrameLogged`.
- `DesktopPresentation` continues to render onto that same surface. It should need no
  changes beyond whatever the surface handoff requires.

Create `android-host/.../video/ScreenEncoder.kt`. Configure `MediaFormat` for
`MIMETYPE_VIDEO_AVC` with all of the following — each one is a latency decision, do not
drop any:

| Key                    | Value                           | Why                                          |
| ---------------------- | ------------------------------- | -------------------------------------------- |
| `KEY_COLOR_FORMAT`     | `COLOR_FormatSurface`           | required for zero-copy                       |
| `KEY_BIT_RATE`         | 12_000_000                      | 1080p60 desktop content                      |
| `KEY_BITRATE_MODE`     | `BITRATE_MODE_CBR`              | no VBR bitrate spikes                        |
| `KEY_FRAME_RATE`       | 60                              | target                                       |
| `KEY_I_FRAME_INTERVAL` | 3                               | on-demand keyframes instead                  |
| `KEY_LOW_LATENCY`      | 1 (API 30+)                     | encoder skips lookahead                      |
| `KEY_PRIORITY`         | 0                               | realtime                                     |
| `KEY_PROFILE`          | `AVCProfileConstrainedBaseline` | **no B-frames — B-frames add reorder delay** |
| `KEY_MAX_B_FRAMES`     | 0                               | belt and braces                              |

Requirements:

- Use the **async** `MediaCodec.Callback` API. Do **not** poll `dequeueOutputBuffer` with a
  timeout — the current 10 ms timeout in the old `AndroidDEX-Core` encoder is itself added
  latency.
- Capture SPS/PPS from `BUFFER_FLAG_CODEC_CONFIG` and cache it. Prepend it to every
  keyframe so a client that connects late can start decoding immediately.
- Expose `requestKeyframe()` using
  `setParameters(Bundle().putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0))`.
  Call it whenever a new QUIC client completes pairing.
- Handle `onOutputFormatChanged` and encoder errors without crashing the service.

Verify: logcat shows encoded frames flowing at the tick rate of the shell, and there is
zero per-frame `ByteArray` allocation in the hot path. Confirm with
`adb shell dumpsys meminfo <pkg>` before and after 60 seconds of running.

---

## Phase 2 — Delete the entire tile subsystem

It is dead weight once Phase 1 lands. Remove, do not comment out:

- `android-host/.../service/RegionDetector.kt` — delete the file.
- `FrameSender.sendTileUpdate()` and `sendVideoFrame()` — delete both. Replace with a
  single `sendEncodedFrame(nal: ByteArray, isKeyframe: Boolean, ptsUs: Long)`.
- `rust-receiver/zc-video/src/tile_compositor.rs` — delete the file and its module
  declaration. Remove the `zstd` dependency from `zc-video/Cargo.toml`.
- `DisplayViewModel.updateRegionStats()` and the "Tiles:" / "Video:" overlay readouts in
  `zc-core/src/ui/overlay.rs`. Replace with fps, end-to-end latency in ms, and bitrate.

**Stop hand-writing protobuf.** `FrameSender` currently writes varints and field tags
byte by byte. Delete `writeVarint` and all manual tag arithmetic. Add
`com.google.protobuf:protobuf-javalite:3.25.5` plus the
`com.google.protobuf` Gradle plugin, generate Java classes from `proto/video.proto`, and
use the generated builders. Update `proto/video.proto` so `VideoFrame` carries
`bytes nal_data`, `bool is_keyframe`, `int64 pts_us`, `int32 width`, `int32 height` —
and delete the `TileUpdate` message.

---

## Phase 3 — Real H.264 decoder in the Rust receiver

`AndroidDEX-Core/androiddex-video/receiver/src/media_foundation.rs` is a stub whose
methods contain only comments. **Do not port it. Do not extend it. Do not reference it.**

Write a real decoder at `rust-receiver/zc-video/src/decoder.rs` using the `openh264`
crate (pin the exact version you resolve). Software decode of 1080p runs in single-digit
milliseconds, needs no system libraries, and builds cleanly on Windows — unlike a Media
Foundation FFI layer, which is out of scope for this task.

Requirements:

- `Decoder::decode(&mut self, nal: &[u8]) -> Result<Option<DecodedFrame>>` returning
  YUV420 planes with their strides. Do **not** convert to RGBA on the CPU.
- Upload Y, U, and V as three separate `R8Unorm` textures and do the YUV→RGB conversion
  in `zc-core/src/shader.wgsl` on the GPU. A CPU colour convert of 1080p per frame would
  reintroduce the problem this task exists to remove.
- On decode error, drop the frame and request a keyframe from the phone rather than
  panicking or showing garbage.

---

## Phase 4 — Latency tuning across the wire

- **Jitter buffer:** `rust-receiver/zc-network` currently buffers. On a LAN every buffered
  frame is directly added latency. Make the depth configurable and default it to 0.
- **QUIC streams:** open **one** long-lived unidirectional stream for video and
  length-prefix each frame on it. Do not open a new stream per frame. Flush immediately
  after each write — do not batch.
- **Presentation mode:** `zc-core/src/main.rs` must configure the wgpu surface with
  `PresentMode::Immediate`, falling back to `Mailbox` if unsupported. `Fifo` is vsync-locked
  and silently adds up to 16 ms.
- **Input path:** input events must be written to the wire the moment they arrive from
  winit. Check `INPUT_BUFFER_MAX` draining in `main.rs` and make sure nothing accumulates.
- **Scroll:** add `WindowEvent::MouseWheel` handling in `zc-core/src/main.rs` and a
  `v_scroll` / `h_scroll` field to the input proto. `LocalInputDispatcher.onScroll()`
  already exists on the Android side and is currently unreachable.
- **Modifiers:** the receiver currently hardcodes `modifiers: 0`, forcing Android to
  reconstruct shift/ctrl/alt from keypress history. That desyncs and leaves Shift stuck on.
  Send the real modifier bitmask from winit and use it directly.

---

## Phase 5 — Fix the app launcher (D4)

Third-party app launching is **removed**, permanently, by design. It cannot work without
Shizuku or Developer Options and I have ruled both out.

- `AppLauncher.kt`: delete the `setLaunchDisplayId` call, the `getLaunchIntentForPackage`
  path, the installed-app enumeration, and both failure Toasts.
- The launcher should list only the shell's own windows — Terminal, CodeServer, Files,
  Settings, whatever `DesktopShell.kt` can genuinely open.
- Add one honest line of UI text explaining that AndroidDEX runs its own workspace rather
  than mirroring phone apps. No "coming soon", no disabled greyed-out app icons.

---

## Phase 6 — Fix keyboard input into text fields

Symptom: the keyboard indicator appears in the taskbar, but typing into a text field in the
desktop shell does nothing.

Investigate and fix properly. Likely contributors, in the order I would check them:

1. The `Presentation` window on the virtual display may not be focusable, so no view ever
   holds input focus. Check window flags and whether the `ComposeView` actually receives
   focus after a pointer down.
2. Non-default displays maintain separate focus state from the default display. Verify a
   focused editor exists on the virtual display specifically.
3. `AndroidDexIME.currentInputConnection` returns a no-op connection when the IME is bound
   but no editor is focused — `LocalInputDispatcher.handleKey()` will then fall through to
   `view.dispatchKeyEvent()`, which Compose `BasicTextField` should handle. Determine which
   of the two paths is actually firing before changing either.
4. Suppress the on-screen soft keyboard on the virtual display. The PC keyboard is the
   input device; a soft keyboard popping up steals space and focus.

Report which of these it actually was. Do not "fix" all four blindly.

---

## Out of scope

- Pairing, PIN flow, `zc-security`.
- The MDM console.
- Microdroid / VM display bridge.
- Media Foundation hardware decode — `openh264` for now; we can swap later.

---

## Final deliverable

1. Files created, modified, deleted.
2. Build and install commands for both sides.
3. **Measured numbers, not estimates:** frames per second sustained over 60 seconds, and
   glass-to-glass latency. Measure latency by displaying a millisecond clock in the shell,
   photographing the phone and PC screen together, and reporting the difference.
4. Anything in Phases 1-6 you could not complete, stated plainly.
