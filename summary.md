# AndroidDEX-V2 — Full Session Handoff (summary.md)

> Written for the next AI agent taking over this conversation. Read this top to bottom;
> it contains every decision, root cause, fix, build/test command, quirk, and pending
> item from the entire session. The user expects the same working style: **no fake
> data, no untested claims, verify everything on hardware before calling it done,
> report failures honestly.**

---

## 1. Project & architecture (what this codebase is)

A phone-as-PC rig: an Android app renders a "Windows-10-style" desktop inside a
`VirtualDisplay`, H.264-encodes it, and streams it over QUIC to a Rust receiver on
the PC. PC mouse/keyboard flow back over the same QUIC connection.

**Three codebases in the repo (`C:\Users\Asus\Documents\GitHub\AndriodDEX-V2`):**

| Path | What it is |
|---|---|
| `android-host/` | Kotlin/Compose Android app (`com.androiddex.host`). Root package `com.example.androidhost`. Includes `rust_quic_server/` (Rust JNI lib cross-compiled to arm64 `.so` by Gradle task `buildRustQuicServer`). |
| `rust-receiver/` | PC-side Rust workspace. `zc-core` (winit + wgpu + egui receiver binary), `zc-video` (openh264 wrapper), `zc-input`, `zc-protocol` (protobuf), `zc-network`, `zc-audio`. |
| `packaging/` | Windows kiosk assigned-access XML etc. |

**Streaming pipeline (phone side):**
- `DisplayService` (foreground service) → `ScreenEncoder` (MediaCodec H.264, surface
  input, CBR 12 Mbps, 60fps config, I-frame 1s) → `createVirtualDisplay("AndroidDex", 1920x1080, dpi 160)` whose surface IS the encoder input.
- `DesktopPresentation` (a `Presentation` on the virtual display) hosts `DesktopShell`
  (Compose): wallpaper + app windows (`WindowChrome`).
- Apps are **pseudo-packages** (`com.androiddex.terminal/files/settings/browser`) —
  Compose windows inside the shell, NOT real Android apps. Registry: `ui/components/AppRegistry.kt` (VS Code was removed "for now").
- `FrameSender` wraps NALs in protobuf `HybridFrame`, prepends cached SPS/PPS to
  keyframes; `rust_quic_server` JNI pushes to per-kind bounded queues
  (video depth 2, drop-oldest) and writes LE-u32-length-prefixed frames on uni streams.
- Wire framing: `[u32 len LE][type byte 0x01=video / 0x02=audio][protobuf]`.
  PC→phone input: protobuf `InputEvent` (mouse/keyboard/scroll/text/open_app/nav).
- Phone input injection: `LocalInputDispatcher` — direct `View.dispatchTouchEvent`/
  `dispatchKeyEvent` into the Presentation's ComposeView (NOT system injection).
  Coordinates scaled from wire 1920x1080 space into `DisplayService.CAPTURE_WIDTH/HEIGHT`.

**Receiver (PC):** winit window "AndroidDex Receiver", wgpu renders decoded YUV,
egui draws a Windows-10-style **taskbar + Start menu + action center + dialogs**
(`zc-core/src/ui/{overlay,taskbar,start_menu,action_center,dialogs,types,theme}.rs`).
The egui taskbar is THE taskbar now (the phone-side streamed taskbar was removed — see §5.3).

---

## 2. Environment, device, tooling, and hard-won gotchas

- **Device:** OPPO CPH2619 (OnePlus/OPPO, Android 15+, targetSdk 36), serial `2c0f6edc`.
  Qualcomm encoder `c2.qti.avc.encoder`. Phone connects via USB tethering
  (receiver finds phone at the tethered IP; QUIC on UDP **4433**).
- **IMPORTANT (as of session end): the phone is NOT connected.** Last APK is built but
  NOT installed. See §7 checklist.
- **Build phone:** `cd android-host && ./gradlew.bat installDebug` (debug variant only;
  `assembleDebug` to just build).
- **Build receiver:** `cd rust-receiver && cargo build` — **must `taskkill //F //IM zc-core.exe`
  first**, the running exe locks the binary (Windows).
- **Run receiver:** `./target/debug/zc-core.exe` (debug build decodes 1080p fine).
  Escape exits. It reconnects in a loop (3 s backoff).
- **Launch app:** `adb shell am start -n com.androiddex.host/com.example.androidhost.MainActivity`
- **Debug harness:** `adb shell am start -n com.androiddex.host/com.example.androidhost.DebugDesktopActivity`
  (debug build only) — hosts `DesktopShellContent` on display 0 so `adb input`/`uiautomator`
  can drive it. Caveats: density is 3.0 there (drag math divides by density, ok), and its
  coordinate mapping is approximate — the real surface is the Presentation.
  Once the system wrongly routed it onto the virtual display; fix was `adb shell am stack remove <taskId>`.
- **Screencap:** only display 0 works (`adb exec-out screencap -p` or `adb shell screencap`).
  Virtual-display screencap FAILS on this build (`-d <id>` → "not valid"; large physical
  display id overflows the parser). To see the streamed desktop, capture the PC receiver
  window instead (PowerShell `PrintWindow` with flag 2, window title "AndroidDex Receiver" —
  works for the GPU-rendered winit window; script pattern used all session).
- **Git Bash path mangling:** use `export MSYS_NO_PATHCONV=1` for adb commands with
  device-side absolute paths.
- **Logcat filters that matter:** `DisplayService: encode`, `ScreenEncoder`,
  `RustQuicServer`, `ShellViewModel`, `InputManager`, crash buffer `adb logcat -d -b crash`.
- **Git state:** ALL session work is UNCOMMITTED on `main`. Recent base commits:
  `6a42fd8 UI created`, `3dbbcef Input from clicks…`, etc. Do not commit unless asked.

---

## 3. Chronological log of everything done

### 3.1 Video pipeline: "0 fps on receiver / Native:18 decode errors" (fixed & verified)

Symptoms: receiver connected, frames flowed, but every frame failed with
`OpenH264 … Native:18` (and 2/16).

**Diagnosis (with evidence, not guesses):**
- Dumped received NALs to disk → bitstream was PERFECT (SPS/PPS/IDR Annex-B intact) →
  transport and phone packaging were fine.
- openh264 0.6.6 `Native:N` = raw DECODING_STATE bitmask: 2=`dsRefLost`, 16=`dsNoParamSets`,
  18=both.
- Root cause: after any reference loss (phone drops oldest of a 2-deep queue under load),
  **OpenH264 never self-recovers** — even when clean IDRs arrive later — and the receiver
  reused one decoder instance across the error and across reconnects.

**Fix (`rust-receiver/zc-core/src/main.rs`):**
- `need_keyframe` state: skip P-frames until an IDR; on any decode error, re-enter it and
  send a keyframe request to the phone (phone honors `PARAMETER_KEY_REQUEST_SYNC_FRAME`).
- On the next keyframe after an error: **recreate the decoder from scratch**.
- Backlog catch-up: when frames queue up, decode only from the newest keyframe in the batch
  (`rposition` on `is_keyframe`), so latency can't balloon.

**Verified:** 0 errors over 30 s idle; stress test (Settings app in shell, 25 fps / 4 Mbps)
triggered 3 reference losses and each recovered in <1 s (previously: permanent error spam).

### 3.2 Section A of BUG-REPORT.md — 13 items (all done & device-verified)

The user's rule: verify every claimed bug first; fix properly; also fix any additional
high-severity bugs found; skip claims that aren't real. Verdicts & fixes:

| # | Verdict | Fix shipped |
|---|---|---|
| A1 (P0) | Valid — `showLauncher` never set true | Taskbar's Start button toggles launcher (later superseded by §5.3: phone taskbar deleted, receiver Start menu is the launcher) |
| A2 (P0) | Valid — `Taskbar` never composed | Wired with all callbacks (later removed in §5.3 by user request) |
| A3 (P1) | Valid — dp treated as px at density 2.0 | **`CAPTURE_DPI = 320 → 160`** in `DisplayService` (density 1.0, 1dp=1px). Verified via `dumpsys display` (density 160). |
| A4 (P2) | Valid — drag never persisted | `ShellViewModel.moveWindow(id,dx,dy)` writes bounds; WindowChrome reads bounds only (no local offset state). Density-converted: **drag deltas are physical px, bounds are dp-units — divide by `LocalDensity.current.density`** (bug found during testing: windows flew 3× fast on density-3 debug display). |
| A5 (P2) | Valid — no clamp/z-order | Clamp keeps ≥100px of title bar on-screen (range built with minOf/maxOf to survive tiny resolutions); `raiseWindow` (list order = z-order); `focusedId` StateFlow = last non-minimized; raise on press + drag start; focused/unfocused title tint. |
| A6 (P2) | Valid + extras | `openApp` validates against `AppRegistry` (rejects unknown — closes exported-broadcast attack), caps at 16 windows, title from registry (was raw package name). |
| A7 (P2) | Valid | Duplicate openApp un-minimizes + raises instead of early-return. |
| A8 | Valid, **report's fix FAILED** | `KEY_REPEAT_PREVIOUS_FRAME_AFTER` is **ignored by `c2.qti.avc.encoder`** (measured: 2 frames/38 s). Key stays in `buildFormat` (harmless). Real fix evolved → final form in §5.3: 1 Hz `frameTick` + 1-px keepalive pixel. |
| A9 | Bug valid, **report's fix impossible** | `getActivityIcon` can't work (pseudo-packages). Material vector icons via `material-icons-extended` (already a dep). |
| A10 | Valid | `displayId` param removed through the whole chain. |
| A11 | Partially valid (grid-click claim was WRONG — cells consume taps) | Scrim is its own Box (dismiss-on-tap only there) + `BackHandler` (no-op in Presentation). |
| A12 | Partially valid (focusable-flag clearing was a no-op) | Only `FLAG_DIM_BEHIND` cleared now. Input verified unaffected (direct dispatch). |
| A13 | Partially valid (manual `onResume()` is REQUIRED — Dialog never calls it) | `dismiss()` order: `onPause()` → `super.dismiss()` (framework STOP) → ON_DESTROY + `store.clear()`. |

**Extra bugs found & fixed:** E1 duplicate `OPEN_APP` receivers (MainActivity's removed;
DisplayService keeps the one); E2 raw package as window title; E3 dead `framesSent` poll
in DesktopShell; E4 exported receivers accepted arbitrary strings (closed by A6 validation).

**Verification evidence:** uiautomator bounds dumps, screencap pixel scans, adb taps/swipes
via DebugDesktopActivity, logcat assertions (rejection logs, moveWindow traces), receiver
window captures. User also live-tested at **58 fps with 0 decode errors** (Browser open).

### 3.3 Taskbar consolidation — "remove the old component" (user request)

User showed receiver screenshot; wanted ONE taskbar — the receiver's egui one (user calls
it "Evo"). The phone streamed taskbar was duplicating it (old behind, new on top).

**Done:**
- **Deleted** phone `ui/components/Taskbar.kt` and `AppLauncher.kt`; DesktopShell is now
  wallpaper + windows only (plus keepalive, below).
- **Ported navigation to the receiver:** new proto messages in
  `rust-receiver/zc-protocol/proto/input.proto` — `NavEvent { NavAction action = 1; }`,
  `enum NavAction { NAV_BACK=0; NAV_HOME=1; NAV_RECENTS=2; }`, `oneof` field `nav = 7`.
  `zc-input::create_nav_event`; three buttons (← ⌂ ▤) in `taskbar.rs` right tray;
  `main.rs` handles `actions.nav_action`; phone `InputManager` NAV case calls
  `DesktopAccessibilityService.performBack/Home/Recents()` (**requires the optional
  accessibility service enabled on the phone; otherwise no-op — by design**).
- Removed phantom receiver apps (`com.androiddex.security`, `com.androiddex.diagnostics`)
  that the phone would reject; removed the `"diagnostics"` special-case in start_menu.rs.
- Maximized windows now use full `CAPTURE_HEIGHT` (no 48px taskbar reserve).
- **Encoder liveness (critical):** with the phone taskbar gone, a static desktop starved
  the encoder (clock recomposition alone wasn't enough — only *content* changes emit
  buffers). Fix: `DisplayService.frameTick` (`MutableStateFlow<Long>`, bumped 1 Hz by a
  handler started/stopped with the pipeline) collected in `DesktopShell`, passed to
  `Windows10Wallpaper(keepAliveTick)` which draws a **1-px rect with alternating alpha
  0x01/0x02** — imperceptible, real pixel change → steady ~1 fps at idle → joining
  clients get IDR ≤1 s. Verified in logcat.

### 3.4 VS Code removal ("for now")

Removed from `AppRegistry.kt` and receiver `types.rs` ALL_APPS. `CodeServerWindow.kt`
file still exists but is unreachable (safe). Restore = re-add both entries.

### 3.5 Responsive taskbar (short/narrow windows merged icons)

Fixed-width left cluster + right tray collided under ~835px. `taskbar.rs` now:
- `right_tray_width` computed; `left_limit` = max.x − tray − 8.
- Hide order: search box → task view → pinned apps (each stays in Start menu).
- Start menu width/height clamped to screen (`620.0f32.min(...)`, `510.0f32.min(...)`
  — note the `f32` suffix; plain float literals failed type inference).

### 3.6 Three problems round (browser input, crash, phone app redesign)

**P2 — CRASH (found in `adb logcat -d -b crash`):**
`IllegalArgumentException: Window type mismatch. Window Context's type is 2037, while
LayoutParams' type is 2` from `androidx.compose.ui.window.Dialog` → **any Compose
Dialog/AlertDialog opened inside the Presentation crashes the app on Android 15**
(killing DisplayService → "connected becomes idle"). FilesApp's 3 dialogs were the triggers.
**Fix:** new `ui/components/ShellDialog.kt` — an in-composition modal (scrim + Card, no
window creation, crash impossible by construction). All FilesApp dialogs converted.

**P1 — browser page clicks dead from PC (URL bar worked, page didn't):**
Pointer events went into the root ComposeView and had to survive Compose gesture routing
(window raise-on-press, scrollables) to reach the AndroidView WebView — they didn't.
**Fix:** direct routing — `LocalInputDispatcher.registerWebViewBridge` also arms a pointer
slot; when the cursor is inside the WebView's bounds, events are dispatched straight into
the WebView with view-local coordinates (clicks/drags/hover/scroll/right-click). BrowserApp
registers the bridge at creation (not only on first touch).

**P3 — phone app redesign:** `ControlPanel` in `MainActivity.kt` rebuilt: header, live
status card (dot + text + frames), three telemetry tiles (encoder fps / bitrate / dropped),
Session card (Lock + System Audio), scrollable, modern dark palette. Removed
`InputSetupPanel` from it.

### 3.7 FINAL round — "can't type anywhere from receiver" (no device; code-verified)

The P1 fix introduced regressions (admitted openly to the user):
1. **Stale slot:** after Browser close/minimize the dead WebView stayed armed; its stale
   bounds swallowed every press → no Compose field ever registered → typing dead everywhere.
2. **Occlusion theft:** a *covered* (but alive) WebView still passed the bounds check and
   stole clicks meant for the window above it.

**Fix (phone-side only; receiver untouched):**
- `InputTarget.WebViewBridge(bridge, view, eligible)` with **`isLive()`** =
  `view.isAttachedToWindow && width>0 && eligible?.invoke() != false`.
- `resolveWebViewTarget` requires `isLive()` + inside bounds.
- All five paths gated: pointer (incl. drag-latch fallback), hover, scroll, button
  actions, keyboard, onText. Gesture latch still follows drags out of bounds; cleared on
  UP/CANCEL and in `resetState()`.
- BrowserApp passes `isTopWindow = { shell.windows.value.lastOrNull{!it.isMinimized}?.id == windowState.id }`
  (top of z-order must be this browser window).
- `gradlew assembleDebug` → **BUILD SUCCESSFUL**. APK at
  `android-host/app/build/outputs/apk/debug/app-debug.apk` (72.8 MB, Oct 8 17:20).
  **NOT installed — device absent.**

---

## 4. Current file-level state of every file we touched

| File | State |
|---|---|
| `rust-receiver/zc-core/src/main.rs` | wait-for-keyframe + decoder rebuild + batch catch-up; nav_action dispatch; (temp frame-dump debug code REMOVED) |
| `rust-receiver/zc-video/src/decoder.rs` | unchanged this session (per-NAL + whole-AU decode; works — proven by test) |
| `rust-receiver/zc-protocol/proto/input.proto` | + `NavEvent`, `NavAction`, oneof field 7 |
| `rust-receiver/zc-input/src/lib.rs` | + `create_nav_event` |
| `rust-receiver/zc-core/src/ui/taskbar.rs` | responsive left cluster; nav buttons; imports `NavAction` |
| `rust-receiver/zc-core/src/ui/types.rs` | `UiActions.nav_action: Option<NavAction>`; ALL_APPS = 5 apps (browser/files/terminal/settings; security+diagnostics+vscode removed) |
| `rust-receiver/zc-core/src/ui/start_menu.rs` | clamped size; diagnostics special-case removed |
| `android-host/.../vm/ShellViewModel.kt` | registry-validated openApp (cap 16, raise/restore on dup), moveWindow (clamped), raiseWindow, focusedId; (temp moveWindow log REMOVED) |
| `android-host/.../ui/components/WindowChrome.kt` | bounds-driven, density-corrected drag, raise-on-press, focus tint, maximize full height; (temp DRAW logs REMOVED) |
| `android-host/.../DesktopShell.kt` | wallpaper + windows + frameTick keepalive ONLY (no taskbar/launcher/polls; wallpaper byte-identical to original art) |
| `android-host/.../service/DisplayService.kt` | CAPTURE_DPI=160; frameTick + 1Hz handler; forceRedraw deleted; single OPEN_APP receiver |
| `android-host/.../service/DesktopPresentation.kt` | heartbeat deleted; only FLAG_DIM_BEHIND cleared; PAUSE→STOP→DESTROY lifecycle; registers DesktopShell() |
| `android-host/.../service/InputManager.kt` | NAV case → accessibility service; forceRedraw bump removed |
| `android-host/.../input/LocalInputDispatcher.kt` | WebView direct pointer routing WITH isLive() guards everywhere (§3.7) |
| `android-host/.../ui/apps/BrowserApp.kt` | bridge registered at creation with isTopWindow eligibility |
| `android-host/.../ui/apps/FilesApp.kt` | ShellDialog modals (in-window), inside WindowChrome content |
| `android-host/.../ui/components/ShellDialog.kt` | NEW — safe in-composition dialog |
| `android-host/.../ui/components/AppRegistry.kt` | 4 apps (terminal/files/settings/browser) with vector icons |
| `android-host/.../MainActivity.kt` | receiver removed; ControlPanel redesigned (+StatTile); imports size/width/HorizontalDivider/mutableLongStateOf/scroll |
| DELETED | `ui/components/Taskbar.kt`, `ui/components/AppLauncher.kt` |
| `android-host/.../ui/linux/CodeServerWindow.kt` | still present, unreachable (VS Code "for now") |
| `rust-receiver/zc-video/tests/decode_dump.rs` | REMOVED (temp) |

---

## 5. Hard-won knowledge (do not re-learn these)

1. **`c2.qti.avc.encoder` ignores `KEY_REPEAT_PREVIOUS_FRAME_AFTER`** — measured. Static
   screens need real content changes; the 1-px keepalive handles it.
2. **OpenH264 never recovers from `dsRefLost` by itself** — rebuild the decoder on the
   next IDR. Error display `Native:N` is the DECODING_STATE bitmask (2/16/18 meanings above).
3. **Compose Dialog/AlertDialog inside a Presentation = FATAL on Android 15**
   (window type mismatch). Always use in-composition overlays (ShellDialog).
4. **Drag deltas from Compose are physical px; window bounds are dp-units** (density 1.0
   on the virtual display, 3.0 on the physical) — divide by density when writing bounds.
5. **A dead or covered WebView must never own the pointer slot** — that was the
   "can't type anywhere" regression. Every new input-routing idea must keep the `isLive()`
   gates on ALL paths.
6. Windows shell quirk: `HH:mm` clock strings only change once a minute → Compose skips
   recomposition → encoder starves. Any "idle indicator" must actually change value per tick.
7. Virtual-display screencap is broken on this ROM; use receiver-window `PrintWindow`
   captures (flag 2) on the PC instead.
8. Kill `zc-core.exe` before `cargo build`; use `MSYS_NO_PATHCONV=1` in Git Bash for adb.
9. Receiver reconnect loop is 3 s; after phone app force-stop the old receiver may need a
   restart too. Receiver exe exit code 0x40010004 = window closed/cancelled (normal).
10. The receiver's egui hit-testing reserves a 42px taskbar zone and flyout zones at the
    screen edges (see `overlay.rs handle_event`) — clicks there are overlay-owned by design.

---

## 6. User's working style (the new agent MUST match it)

- Verify every reported bug against source before fixing; push back with evidence when a
  report is wrong (they explicitly asked for that and rewarded it).
- No fake data, no dummy stubs, no untested "should work". Test on the device whenever it
  is connected; when it isn't, say so and mark things code-verified only.
- Report failures honestly (e.g., the A8 gate failing, the §3.7 regression) — the user
  values this highly.
- Prefer minimal, root-cause fixes in the existing architecture and idiom; keep temporary
  debug instrumentation OUT of the final tree (several debug logs were added then removed).
- The user types quickly/informally; interpret intent patiently (e.g., "Evo" = the
  receiver's Windows-style egui shell; "conected become idea" = connection went Idle).

---

## 7. IMMEDIATE NEXT STEPS (when work resumes)

1. **Reconnect the phone**, then install the built APK:
   `cd android-host && gradlew.bat installDebug` (or install the existing Oct-8 APK),
   `adb shell am force-stop com.androiddex.host`, relaunch MainActivity, restart receiver.
2. **Run the §3.7 verification checklist (in order):**
   a. Fresh boot → Start menu click → app window opens (clicks reach Compose).
   b. Settings window → click a resolution field → type → characters appear (text path).
   c. Browser → click page links; type in URL bar and page search boxes.
   d. Regression cases: minimize Browser → desktop clicks/typing still work; open Settings
      OVER Browser → clicks on the visible Settings window work.
   If (d) fails, grab `adb logcat` immediately and trace live.
3. **Then continue BUG-REPORT.md sections B, then C, then D** — the user's explicit plan;
   section A is complete. Same rules: verify each claim first.
4. Nice-to-know open items:
   - VS Code restoration is a 2-entry change (AppRegistry + types.rs) when wanted.
   - Debug harness (`DebugDesktopActivity`) has an approximate coordinate map (pre-existing,
     debug-only) — don't chase it as a product bug.
   - Nav buttons (← ⌂ ▤) need the phone's accessibility service enabled; there's a Settings
     shortcut inside the shell's Settings app ("Desktop Accessibility Service").
   - Everything is uncommitted; commit only if the user asks.

---

## 8. Bug-solving methodology (exactly how each bug was solved, one by one)

This is the per-bug loop that produced every fix in §3. Follow it verbatim for the
remaining BUG-REPORT.md sections.

**Step 0 — Batch by subsystem.** Never fix scattered bugs one-off. Group the section's
items by the file/subsystem they touch (e.g. "window system", "input dispatcher",
"encoder"), fix a batch, verify the batch end-to-end, then move to the next batch.

**Step 1 — Verify the claim against source (mandatory).** Open the exact file:line and
confirm the bug exists as described. Outcomes:
- **Valid** → proceed.
- **Partially valid** → fix the real part; prove the wrong part is wrong (e.g. A11's
  "grid clicks dismiss first" was false — child clickables consume taps; A13's
  "let Presentation drive the lifecycle" is unimplementable — Dialog never calls
  onResume).
- **Invalid → skip**, and tell the user why with evidence.
- Also scan the same component for related high-severity bugs the report missed
  (this found E1–E4 and the density-unit drag bug).

**Step 2 — Check the proposed fix is even possible.** The report's suggested fix is a
hint, not gospel: A9's `packageManager.getActivityIcon` is impossible (pseudo-packages),
A8's `KEY_REPEAT_PREVIOUS_FRAME_AFTER` is ignored by this OEM encoder (measured).
If the suggested fix can't work, design the correct one and say so.

**Step 3 — Minimal root-cause fix in the existing architecture and idiom.** No stubs,
no dummy data, no fake fallbacks, no new frameworks. Keep comments in the codebase's
style (explain constraints, not the change).

**Step 4 — Build both sides.** `taskkill //F //IM zc-core.exe` BEFORE `cargo build`;
`gradlew.bat installDebug` for the phone. A fix that doesn't compile is not a fix.

**Step 5 — Verify on hardware (when the device is connected).** Reproduce before/after
with the cheapest decisive instrument:
- logcat greps (`DisplayService: encode`, `ShellViewModel`, crash buffer)
- `adb shell input tap/swipe` on `DebugDesktopActivity` for shell behavior
- screencap pixel-scans (display 0 only) and receiver-window `PrintWindow` captures
  for anything visible in the stream
- measured gates for behavioral claims (encoder honoring a key, crash repro) —
  never assume, always measure
- end-to-end: app restarted, receiver connected, stream decoding, user-level flows work

**Step 6 — Clean up.** Any temporary debug logging/instrumentation added during
diagnosis is removed before the batch is done. Temp test files deleted.

**Step 7 — Report per bug:** ID → verdict (valid/partial/invalid + evidence) → fix →
files touched → verification evidence. Failures and untested items stated plainly
(e.g. "built but not device-tested — phone absent"). The user explicitly values
honest negative results over optimistic claims.

**Step 8 — Update this summary.md** (or a successor handoff) with what changed, so
the next context window never re-derives facts.

**Standing invariants — never break these while fixing anything else:**
1. Receiver decoder recovery: wait-for-keyframe + rebuild-on-IDR + batch catch-up (§3.1).
2. `frameTick` 1 Hz + 1-px keepalive pixel in the wallpaper (encoder liveness on static
   desktops) — removing it re-introduces the "0 keyframes / dead stream" bug.
3. `CAPTURE_DPI = 160`; all shell geometry is dp-at-density-1.0; drag deltas must be
   divided by density.
4. **Never open a Compose `Dialog`/`AlertDialog` inside the Presentation** (Android 15
   window-type crash) — use `ShellDialog`.
5. All WebView input routing must keep the `isLive()` gates (attached + sized +
   top-window eligible) on every path — pointer, hover, scroll, buttons, keys, text.
6. Phone app registry validation in `openApp` (16-window cap, raise/restore on duplicate).
7. The egui taskbar is the only taskbar; navigation buttons depend on the phone's
   optional accessibility service.
8. Don't commit unless the user asks.

---

*End of handoff. — Session of Oct 6–8, 2026.*
