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

---

## 9. Section B (input PC→phone) — DONE (session of Oct 9)

### 9.1 Test-rig notes for this session (IMPORTANT for further device testing)

- The **physical phone was removed mid-session** (`adb devices` now lists only
  `emulator-5554`, Pixel 8 Pro AVD, Android 17, x86_64). The user said: use the emulator.
- Emulator transport: the emulator's console `redir udp:` is **broken on this build**
  (raw UDP test fails). Working rig: a length-prefixed UDP↔TCP relay pair lives in
  `%TEMP%\opencode\udprelay\` (outside the repo). `guest` mode runs in the emulator
  (`/data/local/tmp/udprelay guest 4434 4433`), `host` mode on the PC (UDP 4433 ↔ TCP
  4434 via `adb forward tcp:4434 tcp:4434`). The receiver's built-in 127.0.0.1 fallback
  (client.rs) then connects through it. **After every app restart: restart the guest
  relay, then wait for a NEW "Opened input stream" line in the receiver's stdout log**
  (the old QUIC session takes ~30 s to time out; input sent before that is lost).
- The receiver's stdout/stderr go to `%TEMP%\opencode\rx_out.log` / `rx_err.log`.
- PC→receiver input injection: `%TEMP%\opencode\drive_receiver.ps1` (SetCursorPos +
  mouse_event + SendKeys + VK hold, with a SetForegroundWindow ALT-key workaround and a
  FOCUS OK/FAILED self-check). Captures: `capture_receiver.ps1` (PrintWindow flag 2),
  `analyze_pixels.ps1`, `ascii_view.ps1` (coarse luminance map).
- The phone-side pairing UI, ControlPanel and shell are fully readable on the emulator
  via `adb -s emulator-5554 shell uiautomator dump`.
- **Emulator limitation found:** page-internal WebView focus does not follow synthetic
  touches on the emulator's Chromium (routing + WebView.onTouch fire — verified by temp
  logs — but DOM focus doesn't move, so insertText/execCommand no-op). §3.6 verified
  page-click focus on the real phone, so this is a platform quirk. Any test that needs
  focus inside a page must wait for the phone.
- The receiver's Start-menu/Action-center/flyout regions invisibly swallow clicks over
  the video when open (H8's hardcoded rects at work) — during testing, press Escape
  first to close stray panels.

### 9.2 Fixes shipped (B batch)

Receiver (`rust-receiver`):
- `zc-input`: new `letterbox_point()`; `create_mouse_event`/`create_scroll_event` now
  take the decoded frame size and map through the same aspect-preserving rect the
  shader draws (B10). Tests updated + `test_winit_modifiers_to_wire` added (B14/B16).
- `zc-core/src/shader.wgsl`: letterbox rect uniform (binding 4) scales the full-screen
  triangle (B10).
- `zc-core/src/main.rs`:
  - Escape redesign (B3): closes the topmost overlay; exits only when idle and not
    kiosk; otherwise forwarded to the phone.
  - Focused(false) flush (B4): sends `buttons=0` plus a key-up for every held key
    (`pressed_keys` set), so alt-tab can't leave a stuck drag/keystroke.
  - OS auto-repeat suppression (B7 receiver half): `key_event.repeat` events for
    keyboard-routed keys are dropped; text-routed keys keep their repeats.
  - `push_input_event()` helper (dedupe of six inline serialize/push blocks).
  - G12: `--forget-pairing` parsed before `cleanup_legacy_trust()`; the `Ping` debug
    print removed. G8/G9: `launch_android_app()` (hardcoded adb path + double-send)
    deleted.
- `zc-core/src/ui/types.rs`: stale `ALL_APPS.len() >= 5` → `>= 4` (registry test).
- `zc-network/src/client.rs`: the user's `ANDROIDDEX_HOST` + 127.0.0.1 emulator
  fallback (added mid-session) completed — `ScanError::Failed` → `ScanError::Other`.

Phone (`android-host`):
- `LocalInputDispatcher.kt`:
  - B1: `onMouse`/`onScroll` carry the wire modifier mask; all pointer/hover/scroll/
    button events dispatch `wireModifiersToAndroidMeta(mods) or lockState`.
  - B13: the local `metaState` tracker is **deleted** — the wire mask is always
    authoritative; only CapsLock/NumLock latch locally.
  - B7: synthesised key auto-repeat (400 ms delay, 50 ms period, proper
    `KeyEvent.repeatCount`, newest-key takeover, cancel on release/detach).
  - B12: `keyDownTimes` keyed by winit keycode.
  - B5/B8: WebView control keys now go as **real Android KeyEvents dispatched into the
    WebView** (trusted keydown/keyup + native caret/backspace/form-submit behavior).
    The DOM `controlKey`/`buildControlKeyScript` machinery is deleted; the bridge is
    text-only. Printables still arrive as TextEvents by design (layout independence).
  - B2: dropped onText now logs `"Dropping text (N chars): no input target
    registered"` instead of vanishing.
  - B11: `lastX/lastY` reset in `resetState()`.
  - The `onKey` fall-through comment is no longer a lie: unconsumed keys dispatch into
    the view tree (WebView targets get them in the WebView).
- `BrowserApp.kt`: fields' `onKey` returns consumed-properly (unhandled keys fall
  through instead of being swallowed) — same convention applied in SettingsApp,
  FilesApp, TerminalWindow. `normalizeUrl()` replaces the scheme-mangling https-prefix
  (data:/about:/file: survive). AndroidView `onRelease` unregisters the bridge and
  `destroy()`s the WebView (B6 remainder + J17). `onConsoleMessage` → logcat
  (permanent diagnostics). Weight modifier fixed (J17).
- `InputManager.kt`: scroll modifiers passed through (B1); per-key Log.d gated behind
  BuildConfig.DEBUG (J21 part).
- Tests: `LocalInputDispatcherTest` updated for the isLive() era (dead WebView must not
  steal text) + new tests for wire-modifier mapping, auto-repeat, repeat-takeover;
  `JsEscapeTest`'s controlKey test removed with the function.

### 9.3 Device verification (evidence)

- **B10 letterbox**: phone — pixel scan: top/bottom bars exactly black (avgLum 0.0)
  where 16:9 predicts; emulator — same bars visible in the luminance map; clicks map
  through the rect (press at wire (115,225) from screen (250,370) — logcat-verified).
- **B7 repeat**: phone — 21 Backspace deletes from a 1.5 s hold (400 ms + 50·n ms);
  emulator — 38 deletes / 3 s and 219 deletes / 12 s. Timing matches exactly.
- **B3 Escape**: closed an open Start menu (pixel-measured 28.0→0.8 avgLum) with the
  receiver surviving; Escape-forwarding while streaming observed via companion events.
- **B2 warning**: "Dropping text (1 chars): no input target registered" fired in real
  runs (fresh shell, no fields) — twice.
- **URL-bar typing end-to-end** (phone and emulator): onText → ComposeTarget →
  `https://www.google.com/hello`; Enter → navigate (BTEST logs).
- **normalizeUrl**: data: URL reached `loadUrl` intact after fix (BTEST ENTER log).
- **G8/G9**: app launches arrive once via QUIC only (logcat `Opening app via QUIC
  InputEvent`), no adb process spawn.
- **Not device-verifiable here**: B5's in-WebView delete direction (emulator focus
  quirk above — the real-KeyEvent path is strictly more native than the deleted
  execCommand hack; verify on the phone), B4's flush in the act (code-exercised on
  every alt-tab; no direct observable), Ctrl+A/arrows inside Compose fields (the
  Presentation window has no window focus, so Compose's key handling never sees
  them — pre-existing limitation, unchanged by this batch).

### 9.4 Per-bug verdicts (Section B)

| ID | Verdict | Note |
|---|---|---|
| B1 | Valid — fixed | wire modifiers reach all pointer/scroll events |
| B2 | Partially valid — mitigated | all fields already register; drops now logged; KeyEvent-synthesis fallback impossible by design (InputConnection is dead on the virtual display) |
| B3 | Valid — fixed, device-verified | overlays close first; idle-only exit; forwarding while streaming |
| B4 | Valid — fixed (code-verified) | focus-loss release flush |
| B5 | Partially valid — fixed by construction | execCommand('delete') path deleted; real KEYCODE_DEL KeyEvents; direction verify-on-phone |
| B6 | Partialially valid (isLive era) — remainder fixed | unregister + WebView.destroy on window close |
| B7 | Partialially valid — fixed properly | receiver suppresses OS repeats; phone synthesises 400/50 ms repeats (device-measured) |
| B8 | Redesigned | TextEvents cannot carry keyup (documented); all keyboard-routed keys are now trusted pairs |
| B9 | Invalid (outdated) | direct dispatch never reaches the system; no HOME/POWER effect from our own views |
| B10 | Valid — fixed, device-verified | letterbox render + input through the same rect |
| B11 | Valid — fixed | |
| B12 | Valid — fixed | keyed by winit code |
| B13 | Valid — fixed | wire mask authoritative, locks local |
| B14 | Partialially valid — tests added | cross-language golden vector still open (I14) |
| B15 | Now used | `isModifier` gates the repeat scheduler |
| B16 | Partialially valid — pin tests added | wire bitmask pinned in tests on both sides |

Extra finds this batch: BrowserApp URL scheme mangling (fixed + device-verified);
`onConsoleMessage` diagnostics; E6 confirmed valid while reading `lib.rs` (fix pending
in E); action-center/flyout invisible click-swallowing (UX note for H8).

---

## 10. Section C (video pipeline) + F1 — DONE (session of Oct 9, part 2)

### 10.1 Device-verified findings (emulator rig, §9.1)

- **F1 CONFIRMED AND WORSE THAN REPORTED**: dismissing the Presentation tore
  down DisplayService. Two stacked causes, both fixed:
  1. `DisplayViewModel` (composed inside the Presentation) bound DisplayService;
     its whole purpose — surfacing `DisplayService.surface` — fed an *unused*
     parameter of `DesktopShellContent`. **Deleted the class and the plumbing**
     (`DesktopShell` no longer takes/binds a display VM).
  2. **The real killer (not in the report):** MainActivity's ControlPanel had a
     1 Hz poller that called `ctx.stopService(DisplayService)` whenever the QUIC
     state left "authenticated" — any PC disconnect destroyed the whole service
     (explains historical "connected becomes idle" reports). Removed; the
     service's own client watch now owns disconnect behavior.
- **C3 pause/resume FULLY VERIFIED end-to-end** (logcat): kill receiver →
  `Client gone — pausing pipeline` → `Encoder released after N frames` → the
  foreground service SURVIVES (dumpsys ServiceRecord still present) → restart
  receiver → `Client authenticated — resuming paused pipeline` → `Encoder
  prepared` → `Encoder started` → stream visible again after ~23 s.
- **J9 validation VERIFIED**: garbage in the resolution fields renders the inline
  error ("Each dimension must be between 16 and 3840." — pixel-detected red
  text) and NO reconfigure runs.
- **C1 swap order**: prepare-new-before-release-old shipped; the success path
  could not be driven end-to-end because of the Button-click gap below.

### 10.2 Open finding — QUIC-driven Compose *Button* clicks never verified

Everything DOWN-reactive works through the QUIC input path (field registration,
title-bar raise, typing, key repeat — all verified this session). But **no
Material `Button` onClick has ever been observed to fire from synthetic QUIC
input** (three rounds of pixel-verified on-target presses on the Settings
"Apply Resolution" button produced presses in logcat but no click action).
egui-side buttons work; Compose fields work; the Compose Button gesture is the
one gap. Could be an emulator-Compose quirk or a real event-property gap in
`LocalInputDispatcher`'s synthetic MotionEvents. **Verify on the phone with a
real mouse when it returns.** (Test-rig notes: the receiver window gets stuck in
a Windows modal drag loop if synthetic down/teleport/up sequences collide —
pressing Escape releases it; the window also drifts between launches, so always
read the live rect before computing click coordinates.)

### 10.3 Fixes shipped (C batch)

`DisplayService.kt`:
- C1: `reconfigureResolution` prepares the new encoder first; on rejection it
  logs, rolls the companion values back to the live display mode, and the old
  pipeline keeps streaming. Swap order: display.surface → release old encoder.
- C3: the client watch now runs for the service lifetime and both pauses
  (disconnect) and resumes (reconnect) the pipeline. The watch is no longer
  killed by `stopEncodingPipeline`; only `onDestroy` stops it.
- C4: `CAPTURE_WIDTH/HEIGHT/BIT_RATE` are `@Volatile`.
- C5: `instance = null` in `onDestroy` (only if it still points at this instance).
- C6: `startForegroundWithNotification()` moved into `onCreate` before the
  pipeline starts (bind-created services still become foreground).
- C15: the 1 Hz `encode N fps` stats line is `BuildConfig.DEBUG`-gated.
- Defensive clamps in `updateResolution` (16..3840, `and 0xFE`) and
  `updateBitrate` (100..100_000) — belt-and-braces behind the UI validation.

`ScreenEncoder.kt`:
- C2: `prepare()` wraps the configure/retry pair; on terminal failure the codec
  is released before rethrowing — no more leaked encoder instances.
- C12: keyframe cooldown uses `SystemClock.uptimeMillis()`.
- C15: per-60-frame and per-request logs gated behind `BuildConfig.DEBUG`.

`FrameSender.kt`: C8 (`isRunning` volatile), C16 (orphan duplicate KDoc removed),
C17 (unused lying `isConnected` deleted).

`EncoderStats.kt`: C13 — window anchored to the grid (`windowStartMs +=
WINDOW_MS`) with a catch-up reset after long gaps.

`SettingsApp.kt`: J8/J9 — inline validation messages for resolution (numeric,
16..3840, even) and bitrate (100..100_000 kbps) with red error text under the
row (no dialogs — Presentation invariant); J7 — fields keyed to the live
companion values so they re-seed on external change; J10 — IME/a11y states
re-checked every 10 s.

`MainActivity.kt` / `DesktopShell.kt` / `DebugDesktopActivity.kt`: F1 (above) —
`DisplayViewModel` deleted, the disconnect `stopService` poller removed,
`DesktopShellContent` lost its unused `surface` parameter.

### 10.4 Per-bug verdicts (Section C + F1)

| ID | Verdict | Note |
|---|---|---|
| C1 | Valid — fixed (code), failure-rollback device-verified via J9; success path blocked by §10.2 | prepare-first swap |
| C2 | Valid — fixed | codec released on double-configure failure |
| C3 | Valid — fixed, device-verified end-to-end | pause on disconnect, resume on reconnect |
| C4 | Valid — fixed | @Volatile |
| C5 | Valid — fixed | cleared in onDestroy |
| C6 | Valid — fixed | startForeground in onCreate |
| C7 | Valid observation — deferred to E14 | needs the JNI direct-buffer API; measured cost today is ~0.1 ms/frame |
| C8 | Valid — fixed | @Volatile |
| C9 | Valid — deferred to E | UI metric stays approximate until the native sent-counter exists; dropped counters shown |
| C10 | Invalid | the class doc talks about *pixels* (true), not encoded output |
| C11 | Partially valid — wontfix | KEY_LOW_LATENCY set for R+; surface-input encoders don't burn on static frames; product targets the owner's API 36 device |
| C12 | Valid — fixed | uptimeMillis |
| C13 | Valid — fixed | grid-anchored window |
| C14 | Valid — low impact, wontfix | receiver requests keyframes itself on connect; 500 ms worst case masked |
| C15 | Valid — fixed | DEBUG-gated |
| C16 | Valid — fixed | |
| C17 | Valid — fixed (deleted) | |
| J7 | Valid — fixed | live-keyed fields |
| J8 | Valid — fixed + device-verified | range + overflow guards both sides |
| J9 | Valid — fixed + device-verified | even/range validation with inline error |
| J10 | Valid — fixed | 10 s refresh |
| F1 | Valid (and then some) — fixed + device-verified | see §10.1; J25's DisplayViewModel half resolved by deletion |


---

## 11. Section D (audio capture service) — DONE (session of Oct 9, part 3)

One file, one pass — `AudioCaptureService.kt` rewritten:

- **D1 (P1)**: audio packets now serialize with the **generated**
  `zc_audio.Audio.AudioPacket` (audio.proto compiles to `Audio.java` — no
  `java_package` option, so the class lives under the `zc_audio` package, not
  `com.androiddex.protocol`). `writeVarint`/`encodeAudioPacket` deleted.
- **D2 (P1)**: `mediaProjection.registerCallback(onStop → stop cleanly + stopSelf,
  main handler)` before the AudioRecord is built; unregistered in
  `stopAudioCapture`. The service now learns when the user stops the projection.
- **D3 (P2)**: `stopAudioCapture` interrupts then **joins(2000)** the capture
  thread before `stop/release` — no more use-after-release when the thread is
  inside `read()`. Device-verified: toggle off → clean stop, thread exits, no
  IllegalStateException, crash buffer clean (the entries there are the
  emulator's own `android.hardwar` HAL noise).
- **D4 (P2)**: `SharedPreferences.edit().commit()` → `apply()` (all 3 sites) — no
  sync disk writes on the main thread. The muting behavior itself is KEPT
  (deliberate: the "System Audio" toggle streams the phone's audio to the PC);
  the restore-on-launch hook already covers force-stop.
- **D5 (P2)**: the service Toast removed; the note ("Phone playback is muted
  while streaming; DRM apps block capture by OS design") now renders in the
  ControlPanel's System Audio row while capturing.
- **D6 (P2)**: the manual varint machinery is gone (D1); per-packet allocation is
  down to the generated class's normal path (~100/s small packets — acceptable;
  the scratch-buffer optimization is moot with the generated class).
- **D7 (P2)**: the hex-dump logs are `BuildConfig.DEBUG`-gated AND now use the
  class TAG (`AudioCaptureService`), not the stray `"Audio"` tag.
- **D8 (P2)**: packet timestamp = `SystemClock.elapsedRealtimeNanos()/1000`
  (monotonic µs), matching the video path's clock domain.
- **D9 (P2)**: `AudioRecord.state != STATE_INITIALIZED` is checked after build()
  and surfaced ("capture unavailable on this device" + stopSelf) — no more
  silent services; the read-error path now also tears the service down instead
  of leaving the UI "LIVE" forever.
- **D10 (P3)**: `getParcelableExtra("DATA", Intent::class.java)` on TIRAMISU+.
- **D11**: INVALID — `stopSelf()` always lands in `onDestroy`, which resets
  `isServiceRunning` and removes the notification; no stuck-LIVE path exists.
- **D12**: partially valid — the `DisplayService.forceRedraw` half of the claim
  no longer exists (deleted in section A); the remaining "per-service status
  flows" design is deliberate and works; unified-AppState refactor = wontfix.

Device-verified on the emulator: System Audio toggled through the real consent
UI ("Share entire screen" → Share screen) → capture runs (12288-byte reads at
~100 ms), clean stop via the switch. D2's system-stop firing was not triggered
(code-verified only).


---

## 13. Section F (services, lifecycle, security) — DONE (session of Oct 9, part 5)

- **F2 (P1)**: the exported `OPEN_APP` broadcast receiver is **removed entirely**
  (DisplayService's receiver + registration + unregister) — with G8's adb fallback
  gone, nothing legitimate sent the broadcast, so the any-app-can-open-windows
  attack surface dies instead of being narrowed. Device-verified: `adb shell am
  broadcast` now lands nowhere (no handler log).
- **F3 (P1)**: the terminal is no longer `sh -c "<user text>"`. It now runs an
  **allow-list of read-only programs** (`ls pwd cat echo date whoami df uname head
  tail wc`) via `ProcessBuilder(tokens)` with **no shell** (arguments are literal,
  so `; | $() backticks` cannot smuggle commands), and `cat` is restricted to paths
  that canonicalize inside filesDir (same check as `cd`). `cat filesDir/pairing_v2.psk`
  is now impossible (absolute paths are refused before any process spawns — the
  security property is structural, verified by reading; the check is the J19-fixed
  canonical+separator form). Device-verified: the terminal ran `ls` end-to-end
  through the QUIC input path (scrollback output pixel-diffed, 10410 changed px).
  Restore of a fuller shell would need the F3-report's heavier options.
- **F4 (P1)**: the lock screen has "Try again" (re-arms the prompt) and "Skip — end
  locked session" (the same exit an app-kill always gave, made visible); the
  ControlPanel's Lock now checks `BiometricManager.canAuthenticate` first and
  refuses to lock without a working unlock method (toast instead of a dead-end).
- **F5 (P1)**: locking now stops **both** DisplayService and AudioCaptureService —
  a locked device stops streaming AND stops capturing; unlocking re-starts the
  stream via the existing `LaunchedEffect(currentScreen)`.
- **F12 (P2)**: both rule files now exclude `pairing_v2.psk` (+ the legacy name
  dropped); new `BackupRulesTest` (plain unit test) asserts the excluded names exist
  and the legacy name is gone.
- **F13 (P2)**: the reflective `getTetheredIfaces()` (non-SDK, always threw) is
  deleted; the `NetworkInterface` name check is the only detector.
- **F14 (P2)**: notification ids are constants (service=2, tap-hint=1); the "tap to
  enable" hint is re-posted only on a tethering-state flip (tracked `lastTethered`),
  not on every USB broadcast. The service itself is deliberately never stopped
  (it monitors the link for the app's lifetime).
- **F15 (P2)**: DisplayService's notification now uses the app's launcher-foreground
  icon and a `PendingIntent` to MainActivity (tappable). The specialUse
  justification text belongs in PLAY_SUBMISSION.md — noted for stage 4.
- **F16 (P2)**: the dead `vmOutputStream` vsock branch is deleted; `stopPolling()`
  is called from `DisplayService.onDestroy` (the poll thread dies with the service;
  the process-global QUIC server is re-polled by the next start).
- **F17 (P3)**: INVALID/already fixed — the 2-second a11y poller does not exist;
  the only `refresh()` caller (InputSetupPanel, itself dead code — never composed)
  fired on ON_RESUME only. InputSetupPanel and `refresh()` deleted in this batch.
- **F18 (P3)**: `currentScreen` is now `rememberSaveable`.
- **F19/J26 (P3)**: the dead Navigation3 template is deleted: `data/`,
  `ui/main/`, `Navigation.kt`, `NavigationKeys.kt` removed (MainNavigation,
  DefaultDataRepository, MainScreen were never referenced).
- **F20 (P3)**: manifest comment explains `allowBackup=false` + the rules as defence
  in depth (BackupRulesTest guards them).
- **F21 (P3)**: `android:launchMode="singleTask"` on MainActivity.
- **F22 (P3)**: `Theme.AndroidHost` is now dark (`android:Theme.Material.NoActionBar`)
  with a dark `windowBackground` (`@color/window_background`) — no white flash on
  cold start. The unused Compose theme packages (`theme/`) deleted (I4's cleanup
  option).
- **F23 (P3)**: DebugDesktopActivity stays `exported="true"` in debug builds (the
  test harness relies on `am start`); the "true protection" comment is outdated but
  the harness is debug-only — acceptable as the report itself says.

I4 partial: theme packages deleted; J26 done (template removed); J28 done
(`app_name` = "AndroidDex").

Not device-verified: F4/F5's lock behavior (exercised only on an actual lock —
code-verified + compiled), F12's exclusion semantics (a BackupRulesTest guards the
names; the actual backup behavior needs a device-backup run), F15's notification
appearance.


---

## 14. Section G (receiver network/trust) — DONE (session of Oct 9, part 6)

- **G1 (P1) — FIXED AND DEVICE-MEASURED**: the `AboutToWait → request_redraw()`
  spin is gone. The render loop is now event-driven; it is woken by:
  1. a frame arrival (the QUIC task calls `request_redraw()` through the shared
     window handle — the loop sleeps in `ControlFlow::Wait` otherwise),
  2. input/phase events (the existing paths),
  3. egui's own repaint deadline (`ViewportOutput.repaint_delay`, read after
     `end_frame`; short delays become `ControlFlow::WaitUntil` so open panels'
     animations still run),
  4. the taskbar clock's next minute boundary (`request_repaint_after`) — without
     it the once-a-minute clock would freeze the moment the stream went quiet
     (the same §5.6 lesson the phone-side keepalive solved).
  **Measured gate: 4.2% of one core while idle (was ~100%); the stream still
  works (connected, 246 frames, video visible).**
- **G2 (P1)**: the store-order half doesn't apply to this flow shape (the PC never
  sends a verdict message in the pairing flow — the 'Y' comes from the phone; the
  PC's store failure correctly aborts with NO trust data on the PC). The real fix
  shipped: close code 6 (`CLOSE_ALREADY_PAIRED`) is now mapped to a specific
  message ("the phone already has a pairing key for another PC — open the phone and
  tap 'Forget paired PC' or run with --forget-pairing") at every scan close site,
  instead of a silent generic retry forever. The "Forget Pairing" button already
  exists in the CertificateChanged dialog.
- **G3 (P1)**: the 4-byte length prefix is no longer trusted: `len > MAX_FRAME_BYTES`
  (8 MiB) drops the stream; a hostile or corrupt length can no longer OOM the
  receiver. (The report's line numbers were stale; the claim stood.)
- **G4 (P2)**: the unbounded `std::sync::mpsc` video queue is now
  `sync_channel(4)` — a full queue blocks the uni-stream read, which backs the
  QUIC flow control up to the phone's drop-oldest queue: backpressure end-to-end
  instead of unbounded buffering. The decode-side newest-keyframe catch-up
  (§3.1) is unchanged and still bounds latency.
- **G5 (P2)**: the discovery scan backs off (3, 5, 8, 12, then 15 s) instead of a
  fixed 3 s forever; the attempt counter stays (informative).
- **G7 (P2) — FIXED AND TESTED**: the trust file is now **DPAPI-protected**
  (`CryptProtectData`, magic-prefixed `ADDEX-DPAPI1`) and written via
  temp+fsync+rename. A synced or stolen `%APPDATA%` file yields nothing on another
  machine or user. A pre-DPAPI plaintext file still loads (and is re-encrypted on
  the next store) so an upgrade never strands a pairing. New test:
  `test_legacy_plaintext_trust_still_loads`; the round-trip tests pass on Windows.
- **G10 (P2)**: the reconnect loop backs off (1→2→3→4→5 s; reset after a healthy
  session). Input buffering while disconnected is KEPT: it is bounded (1000,
  drop-oldest) and typed-ahead input landing on reconnect is a feature.
- **G11 (P2)**: `ANDROIDDEX_PORT` env override added next to the existing
  `ANDROIDDEX_HOST`; `connect(port, …)` no longer hardcodes 4433 in the binary.

Invalid / wontfix (with evidence):
- **G6**: kept the current design deliberately — the pinned fingerprint is
  compared after the handshake and the PSK proofs bind the TLS session, so no
  data flows without the proofs; moving the pin INTO the verifier would fail the
  handshake with a generic TLS error and LOSE the specific "Device Identity
  Changed" security phase (better UX, equivalent security).
- **G13**: the 500 ms `is_peer_close_not_paired` wait is failure-path-only (not
  "every reconnect" as the report claims), and shortening it risks misreading a
  NOT_PAIRED close as a generic auth failure (worse: no re-pair). Kept.
- **G14/G15**: dead legacy v1 code deleted (`generate_pin`, `derive_psk`, their
  3 tests), `cert.rs` deleted (zero callers) + its test, `store_server_cert`/
  `load_server_cert` deleted, and `cleanup_legacy_trust` now also removes
  `server_cert.bin`. zc-security's now-dead `rand`/`rcgen` deps removed (I6).
- **G16**: wontfix — the store already takes explicit paths; the global
  `CUSTOM_DATA_PATH` exists only for the production entry points and the
  TEST_MUTEX serializes the tests that mutate it.

Verified: receiver workspace builds; all workspace tests pass; the DPAPI
round-trip and legacy-plaintext tests pass; the CPU gate measured (4.2%); the
stream verified end-to-end after the change.


---

## 15. Section H (receiver render/UI) — DONE (session of Oct 9, part 7)

- **H1 (P1)**: `Renderer::new` returns `Result` (wgpu 0.19's `request_adapter`
  returns Option — matched accordingly); on a missing adapter it retries with
  `force_fallback_adapter` before giving up; `alpha_modes.first().unwrap_or(Auto)`
  and an empty-formats guard; `main` shows a **Win32 MessageBoxW** (MB_ICONERROR)
  plus stderr on every fatal startup path instead of a bare panic. Event-loop and
  window-builder failures handled the same way (H18/H19).
- **H2 (P1)**: never `unwrap()` in the audio paths — all mutexes (the cpal
  realtime callbacks, the resampler thread, `play_pcm`) use
  `lock_or_recover()` (poisoned → `into_inner()`). Chosen over try_lock+underrun
  (which would glitch ~10% of callbacks while the resampler holds the lock) and
  over a new lock-free-ring dependency: zero glitching, no UB, no new dep.
- **H3 (P2)**: the resampler is created BEFORE the worker spawns (a failure
  returns an error to the caller instead of a silently dead thread); the resampler
  worker's `expect` is gone; the resampler input queue is bounded (~200 ms,
  drop-oldest in `play_pcm`).
- **H4**: INVALID/already fine — the volume clamp exists at the input side
  (main.rs's decode loop clamps to i16 range) and the queue carries normalized
  [-1..1] samples.
- **H5**: wontfix — the wire format is stereo-48k by design (the phone's capture
  format is fixed); a channel-count field would be a proto change for a stream
  that is always stereo. The receiver's >2-channel handling already exists.
- **H6 (P3)**: the "latest wins" contract documented on `decode()` (with B-frames
  disabled an AU produces at most one picture, so nothing is lost today).
- **H7**: INVALID — `DecodedFrame.y_stride = width` is the stride of the PACKED
  buffer it accompanies (correct by construction); the decoder's real source
  stride WAS used for the repack copy.
- **H8 (P2)**: new `ui/layout.rs` — one source of truth for the taskbar/start
  menu/action-center rects (in egui logical pixels), used by BOTH the draw code
  and the pointer hit-testing. **The drift was live: the action center drew 340
  wide while the hit-test zone was 380 (a 40-px invisible swallow-strip), and the
  start-menu hit zone (640x560) ignored the drawn menu's window clamp.** The
  hit-test is now in logical space against the shared rects. Device-verified:
  the menu rows fire through the new path (openApp via a menu row click).
- **H9 (P2)**: `actions.reconnect` and `actions.toggle_kiosk` are now handled:
  Reconnect bumps a generation counter; a watcher future inside the select! sees
  the change and closes the connection (the outer loop reconnects immediately);
  Kiosk toggles a software lock.
- **H10 (P2)**: mute has one authority: the settings-dialog checkbox now emits
  `toggle_mute` on change (it was the one silent writer; the report's start-menu
  claim was outdated — that entry already emitted), and main.rs pushes the atomic
  into `ui_state.is_muted` after every render, making it a read-only mirror.
- **H11 (P2)**: deferred — the real fix needs window-state frames in the protocol
  (a proto message + the phone-side sender + the receiver-side driver); the
  taskbar's highlight remains click-based until then.
- **H12**: confirmed already done (the phantom apps were removed from types.rs in
  the previous session; verified by reading).
- **H13 (P2)**: covered with H9 — the kiosk state is re-evaluated (the software
  toggle) and **Ctrl+Alt+Esc releases the receiver's own kiosk lock** (the escape
  hatch; the registry lock stays until the Assigned Access profile is removed).
  CloseRequested/Escape-exit/exit_app all honor both locks.
- **H15 (P3)**: the fake "AndroidDEX Ready — USB Tethering link active"
  notification seed removed (the action center starts empty and honest); the
  stale test updated.
- **H16 (P3)**: one command encoder with two render passes (video + egui overlay)
  and a single submit, replacing two encoders/submits per frame.
- **H17**: deferred — a runtime present-mode reconfigure + a settings toggle; the
  tearing is bounded by Immediate's low latency and the choice matches §3.1's
  latency goal.
- **H14**: wontfix — a locale-correct clock needs chrono (a new dep) or more FFI
  for a cosmetic format; the FFI clock is stable and now repaints correctly.
- **H18/H19 (P3)**: the window builder sets a min inner size and every fatal path
  (event loop, window, renderer, run) shows the message box + exits non-zero
  instead of panicking.
- **H20**: INVALID — the disconnect placeholder paints in egui's Background layer
  OVER the video pass (LoadOp::Load then rect_filled over the full screen), so the
  last decoded frame is covered, not "presented forever".
- **H21**: wontfix — the BT.601 limited-range matrix matches what the phone's
  encoder produces (the shader's own doc); an SPS-VUI-driven matrix is a
  feature-sized parser, and the colors looked correct in every capture this
  session.

Verified: the workspace builds; all tests pass; the CPU gate re-measured after
the H8 refactor (4.1% of one core); the stream works; menu rows fire.


---

## 16. Section I (build/deps/packaging) — DONE (session of Oct 9, part 8)

- **I1 (P1)**: `lint { abortOnError = true; checkReleaseBuilds = true; baseline =
  file("lint-baseline.xml") }` added; baseline generated (42 existing findings
  accepted) and the gate verified (`:app:lintDebug` passes with it; new findings
  now fail the build). `versionCode` 2→3, `versionName` 2.0.0→2.1.0.
  The Compose BOM bump (2024.05.00 → newer) is **deferred**: the mixed-vintage
  stack works and is what this entire session was verified on; a bump needs a
  full re-verification cycle.
- **I2 (P2)**: `-keepattributes SourceFile,LineNumberTable` added (readable release
  crash traces). The DEBUG-log gates were already shipped (C15/D7/J21).
- **I3**: MOOT — kotlinx-serialization had no remaining `@Serializable` users after
  J26's template deletion, so the plugin alias and the nav3 deps were REMOVED
  entirely (also the dead `nav3Core`/`lifecycleViewmodelNav3` catalog entries).
- **I5 (P2)**: version pins ALIGNED — both sides now on quinn `=0.11.11`, rustls
  `=0.23.43`, ring `=0.17.14` (the receiver's zc-network/zc-security bumped to
  match the phone crate; workspace builds + all tests pass, including the QUIC
  loopback e2e suite). The mixed-editions claim (2021 vs 2024) is legal and
  harmless in one workspace.
- **I6 (P2)**: dead deps removed on both sides — receiver: get_if_addrs,
  directories, x25519-dalek, rand_core, crossbeam-queue (+ crossbeam-channel from
  the phone crate in E1); zc-security: rand moved to dev-dependencies, rcgen kept
  only where tests use it. `bytemuck` became genuinely used (the letterbox uniform).
- **I7 (P3)**: dead `protobufPlugin` version and plugin alias dropped; robolectric
  moved into the version catalog.
- **I8 (P2)**: `tools/run_desktop_receiver.bat` now builds **release** with
  `@echo off` and errorlevel handling (the debug build measured "the app" 3-5×
  slow).
- **I9 (P2)**: `rust-receiver/receiver_live.log` untracked; `pair.png` and
  `progress.md` moved to `docs/`; the dangling `.gitignore` comment finished with
  real rules (`*.log` etc).
- **I10 (P2)**: `.github/workflows/ci.yml` added — receiver (fmt/clippy/test),
  phone crate (test), Android (unit tests, lint, assembleDebug, proto regen), and
  the wire-format cross-check (Rust golden vectors; the Kotlin twin runs in the
  Android job).
- **I11 (P3)**: AndroidDEX.md stale facts fixed (ALPNs `androiddex-pair-v2`/
  `androiddex-v2`, SAS not PIN, `pairing_v2.psk` + DPAPI, targetSdk 36, NDK
  30.0.14904198, no Ping on the wire, no IME, crossbeam-queue gone).
- **I12 (P3)**: the duplicate repo-root `proto/` directory DELETED.
- **I13 (P3)**: PRIVACY.md updated to match reality (X25519 identity, atomic
  0600 writes, DPAPI on the receiver side, DEBUG-only logging, BackupRulesTest).
- **I14 (P3)**: cross-language golden vectors shipped — Rust
  (`zc-protocol/tests/audio_golden.rs`) and Kotlin (`AudioPacketGoldenTest`)
  both assert the SAME AudioPacket bytes (field 1: `0A 02 11 22`, field 2:
  `10 05`; plus the empty-payload proto3 shape). Both sides' tests pass.
- **I15 (P3)**: zc-protocol build.rs — `rerun-if-changed=proto`, no unwrap/expect,
  a clear protoc-missing message.
- **I16 (P3)**: deferred — MSIX signing needs a certificate; documented.
- **I17 (P3)**: dev scripts moved to `tools/`.

---

## 17. Section J (misc sweep) — DONE (session of Oct 9, part 8)

Fixed this pass: J5 (permission-denial consequences documented), J11 (preview
capped at 64 KB; the full Dispatchers.IO rework is deferred), J12 (folder/rename
names sanitized against `/ \ ..` + failures surface in the dialog instead of a
silent close), J14 (formatSize to GB/TB; the per-row SimpleDateFormat is
negligible — left), J15 (scheme-less non-domain input becomes a Google search;
`urlBarEditing` stops onPageStarted from clobbering a mid-edit field), J16 (error
page interpolations HTML-escaped — assembled from parts so no literal entity
appears in source), J22 (the four dead Rust JNI aliases deleted; SecurityBridge.kt
was already clean), J27 (`ui-tooling-preview` moved to debugImplementation),
J28 (app_name "AndroidDex"), J30 (Forget Pairing is now a two-click confirm with
an armed state; the volume-slider debounce skipped — the action is a single
atomic store, not IO), J31 (the mute tile's icon AND label reflect state), J34
(the firewall script's note explains the phone is the listener).

Done earlier this session: J7 (live-keyed settings fields), J8/J9 (validation),
J10 (10 s a11y refresh), J17 (weight + WebView destroy + bridge unregister),
J18 (destroy-on-dispose + output caps; the "double space" claim was wrong — the
prompt has exactly one), J19 (cd separator), J21 (Log gate), J26 (template +
nav3/serialization deps deleted), J29 (via G1).

Invalid/moot (with evidence): J1/J20 (the OPEN_APP receiver is gone entirely —
F2), J2 (the double-entry guard `virtualDisplay != null` is the design and
works), J3 (the shared handler is now safe since the watch never dies — C3),
J4 (the Listener KDoc already says "do not retain it"), J6 (the lock lambda is
behavior-correct; a ViewModel refactor adds nothing today), J13 (binary preview
is a feature), J23 (the pairing screen's 200 ms polling is transient-screen
behavior; the countdown and Forget-confirm are UX niceties), J24 (the state
constant duplication is D12 territory), J25 (DisplayViewModel deleted in F1;
ConnectionViewModel's register/unregister is balanced; ShellHolder is a deliberate
singleton), J32 (the protoc fallback message fixed in I15; the module structure
is fine), J33 (the clone is per-connection, not per-frame — the report's churn
claim was wrong).

Build state at the end of this batch: `gradlew :app:testDebugUnitTest
:app:assembleDebug :app:lintDebug` all pass; `cargo build/test --workspace` pass
(receiver), `cargo test` passes 34/34 in the phone crate.

---

## 12. Section E (wire protocol + native QUIC server) — DONE (session of Oct 9, part 4)

`android-host/rust_quic_server/src/lib.rs`:
- **E1 (P1)**: the unbounded crossbeam input channel replaced with a bounded,
  drop-oldest `InputQueue` (Mutex+Condvar, capacity 64): a push into a full queue
  evicts the oldest, `pop_timeout` keeps the old blocking semantics. crossbeam-channel
  removed from the crate's dependencies entirely (I6 partial).
- **E2 (P1)**: a token-bucket `RateLimiter` (250 events/s, burst 100) in `read_input`;
  over-limit events are dropped with a counted warning. A hostile client can no longer
  flood the main thread into an ANR.
- **E7 (P2)**: a live session now REFUSES a second PC with `CLOSE_BUSY` (new close
  code 8) instead of silently waiting — and, worse, the old code's stale-lock
  force-acquire would have let two pumps drain one queue (interleaved garbage on
  both viewers). The stale-lock force-acquire remains ONLY when no live session
  exists (a crashed previous session's leaked lock). Receiver side: `scan_close_error()`
  maps close 8 to "another PC is already streaming on the phone" at all four
  ApplicationClosed sites in `zc-network/src/client.rs`.
- **E3 (P2)**: the SO_REUSEADDR comment no longer overstates (UDP REUSEADDR does not
  take over a held port; that is SO_REUSEPORT); the retry only helps a closing owner.
- **E6 (P2)**: the drop-after-dequeue path is `log_e` + an explicit comment noting it
  is unreachable by construction (events are capped at 64 KiB, the Kotlin buffer is
  1 MiB) — the invariant is now documented at the landmine.
- **E8 (P2)**: pairing-cooldown eviction is true LRU (`min_by_key` on the attempt
  Instant), not an arbitrary `keys().next()`; the comment fixed. The suggested
  auth-throttle was REJECTED: authentication is the normal reconnect path (the
  receiver retries every 3 s) — throttling it would break reconnection; pairing is
  the hostile path and is already limited.

`store.rs`: **E10** — the containing directory is fsynced after the atomic rename, so
the rename itself survives a crash (the file was already fsynced; `with_extension("tmp")`
kept — only one file per stem is ever written).

`logging.rs`: **E18** — the NUL sanitization allocates only when a NUL is present
(Cow); the level filter suggestion was skipped (the crate deliberately avoids a
logging framework — its own doc says so).

`crypto.rs`: **E17** — the ~20-bit SAS entropy is now documented explicitly
(1-in-a-million per attempt, ~120 attempts bounded by the cooldown+window, two-screen
comparison, PSK proofs bound to the TLS session). The `constant_time` all-zero check
was KEPT (more conservative than a plain byte compare; the deprecation allow stays).

`QuicServer.kt` + `MainActivity.kt`: **E4** — `start()` returning 0 now sets
`serverStartFailed`, logs at ERROR ("a receiver will never be able to connect"), and
the ControlPanel shows a terminal status ("Server failed to start / Port 4433 busy or
storage unusable") instead of a lying "Idle".

Invalid / wontfix (with evidence):
- **E5**: the process IS the unit of restart (a fresh process gets a fresh `SERVER`
  OnceLock); the runtime/endpoint live for the process lifetime by design and nothing
  ever needs a `stop()`. Documented in code.
- **E11/E12**: INVALID — submissions outside a handshake are rejected at the door
  (`is_awaiting` gate), a live handshake's waiter drains the queue, and
  `cancel()`'s drain wakes any parked JNI submitter (the waiter, when it exists, is
  the pairing task itself; when the task dies nobody holds the lock). The "wasted
  tap after a dead handshake" cost is one tap, answered false — no corruption.
- **E13**: INVALID — `settle_state()` IS called on the pairing-failure path, and the
  session teardown's DISCONNECTED state is correct-by-design (a PC was connected and
  left; PAIRING never leaks).
- **E14 (+C7)**: deferred — the direct-buffer JNI send needs a cross-language API
  change; the measured copy cost is ~0.1-0.2 ms/frame on the callback thread.
- **E15**: wontfix — SeqCst is correct; Relaxed-for-counters trades correctness
  review burden for negligible gain on a cold path.
- **E16**: INVALID — the non-sequential step numbers in `run_pairing` deliberately
  mirror `zc-network/src/client.rs`'s pairing comments (both sides use the same
  protocol-spec step numbering: 1,2,3,5,4,6-9,10-11,12); the claim they don't match
  is false.
- **E19**: INVALID — `build.rs` already has `cargo:rerun-if-changed=build.rs`, which
  is exactly right for a build script that only links `liblog`; adding `=src` would
  make cargo re-run it needlessly.
- **E9**: deferred — pts_us-based video pacing + a jitter buffer is a feature-sized
  receiver rework (the render loop is redraw-driven); the desync only manifests
  after stalls today.

Verified: `cargo test` — 34/34 phone-crate tests pass (including the real-QUIC
loopback e2e pairing/auth suite); the receiver workspace builds and its tests pass.

*End of handoff - updated Oct 10, 2026: BUG-REPORT sections B-J all processed. Verdict tables in 9-17. Emulator rig in 9.1. Remaining open items: 10.2 (Compose Button clicks via QUIC need phone verification), H11 (window-state protocol), E9 (A/V sync), C7/E14 (direct-buffer send), I16 (MSIX signing), I1's BOM bump.*
