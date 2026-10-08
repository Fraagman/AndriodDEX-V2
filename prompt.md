# PROMPT — Solve all remaining AndroidDEX-V2 bugs (sections B–J)

Copy everything below the line and give it to the AI agent together with the two
context files (`summary.md` and `BUG-REPORT.md`) in the repo root. The agent should
also read `summary.md` FIRST — it contains the architecture, the environment quirks,
the fixed already, and the rules for how to work.

---

You are continuing work on the AndroidDEX-V2 project at
`C:\Users\Asus\Documents\GitHub\AndriodDEX-V2` (Git Bash on Windows; Android phone may
or may not be connected via adb — check `adb devices` first; the Rust receiver runs on
this PC).

**Before anything else, read two files in the repo root:**
1. `summary.md` — full handoff: architecture, environment quirks, everything already
   fixed and verified, the working rules, and the standing invariants you must not break.
2. `BUG-REPORT.md` — the full bug list. **Section A (13 items) is already done and
   verified. Your job is sections B through J** (about 175 remaining items), plus the
   "Stage 1–4 production-ready plan" at the bottom of that file.

## Rules — follow exactly, they come from hard-won experience in this repo

1. **Verify every bug against the actual source before fixing.** Open the file:line.
   If a claim is wrong or outdated (the code has changed a lot since the report was
   written — section A's fixes touched many of the same files), say so with evidence
   and skip or re-scope it. Several report rows are already fixed or made impossible:
   - A1–A13: done (see summary.md §3.2).
   - H12's phantom apps (`com.androiddex.security`, `com.androiddex.diagnostics`):
     already removed from `types.rs`.
   - G8/G9's hardcoded `adb.exe` path (`C:\Users\omrai\...`) and double-launch: the
     path is now `C:\Users\Asus\...`-independent (falls back to bare `adb`) and the
     phone de-dupes opens — G8's core complaint (delete the function) is still valid.
   - D12's `DisplayService.forceRedraw`: that global was deleted entirely.
   - C5's `instance = this` never cleared: check `onDestroy` — it may still apply.
   Re-verify each one; don't trust this list blindly.
2. **The report's "Fix (simple)" column is a hint, not an order.** If the suggested fix
   is wrong or impossible on this hardware/ROM, design the correct one and say why.
   Examples from section A: the suggested `getActivityIcon` fix was impossible
   (pseudo-packages); `KEY_REPEAT_PREVIOUS_FRAME_AFTER` is silently ignored by
   `c2.qti.avc.encoder` (measured) — the keepalive solution is different.
3. **No fake data, no dummy stubs, no placeholder code.** If something can't be fixed
   properly now, say so and leave it alone.
4. **Test on hardware whenever the phone is connected** (`adb devices`). Use:
   - `adb logcat` greps (`DisplayService: encode`, `ShellViewModel`, `InputManager`,
     `RustQuicServer`, crash buffer `adb logcat -d -b crash`)
   - `adb shell input tap/swipe` against `DebugDesktopActivity` (debug build only)
     for shell behavior; screencap works on display 0 only
   - receiver-window captures via PowerShell `PrintWindow` (flag 2) for stream visuals
   - measured before/after gates for any behavioral claim
   If the phone is absent, fix by careful code reading, compile both sides, and mark
   every item "built, not device-tested — verify when device returns" with a checklist.
5. **Build both sides after each batch:** `taskkill //F //IM zc-core.exe` before
   `cargo build` in `rust-receiver` (the exe locks the binary); `gradlew.bat
   installDebug` (or `assembleDebug` without a device) in `android-host`.
6. **Do not break the standing invariants** listed in summary.md §8 — especially:
   the receiver decoder recovery logic, the `frameTick` 1 Hz + 1-px keepalive pixel
   (encoder liveness on static desktops), `CAPTURE_DPI = 160`, never open Compose
   Dialogs inside the Presentation (use `ShellDialog`), the WebView `isLive()` input
   gates, and the app registry validation.
7. **Fix in batches by subsystem, verify each batch end-to-end, then move on.** Suggested
   order follows the report's own stage plan: Stage 1 (usability) → Stage 2 (safety) →
   Stage 3 (durability) → Stage 4 (ship). Within each stage, group by file so related
   fixes land together (e.g. all `AudioCaptureService.kt` items D1–D12 in one pass; all
   `AudioCaptureService`-side receiver items H2–H5 in another).
8. **Remove temporary debug code before finishing each batch.** Add debug logs only to
   diagnose, and delete them once verified (or gate them behind `BuildConfig.DEBUG`
   where the fix itself requires a permanent gate, e.g. C15/D7).
9. **Do not git commit unless the user asks.** Everything is uncommitted on `main`.
10. **Report per bug at the end of each batch:** ID → verdict (valid / already fixed /
    invalid + evidence) → what you changed → how you verified (or "not device-tested").
    State failures and unverified items plainly — never claim success without evidence.
11. Update `summary.md` after each major batch so the handoff stays current.

## Known codebase facts you will need (from summary.md, restated for convenience)

- Shell apps are **pseudo-packages** rendered by Compose windows; the app list lives in
  `android-host/.../ui/components/AppRegistry.kt` (4 apps: terminal, files, settings,
  browser — VS Code was deliberately removed "for now") and mirrored in
  `rust-receiver/zc-core/src/ui/types.rs` ALL_APPS.
- The **egui taskbar on the receiver is the only taskbar** (the phone-side streamed
  taskbar was deleted). Back/Home/Recents exist there via `NavEvent` in
  `input.proto` (oneof field 7) and require the phone's optional accessibility service.
- `DisplayService.CAPTURE_DPI = 160` → the shell is dp-at-density-1.0. Drag deltas from
  Compose are physical px — always divide by density when writing into window bounds.
- `DisplayService.frameTick` (1 Hz) + the 1-px wallpaper keepalive keep the encoder
  alive on static desktops. The OEM encoder ignores `KEY_REPEAT_PREVIOUS_FRAME_AFTER`.
- Never open Compose Dialogs/AlertDialogs inside the Presentation (Android 15 window-
  type crash) — use `ui/components/ShellDialog.kt`.
- WebView input: `LocalInputDispatcher` routes pointer/keys/text directly into a
  registered WebView only while `isLive()` (attached + sized + top browser window).
- Transport framing: `[u32 len LE][type byte][protobuf]`; video queue depth 2 drop-oldest;
  input oneof already has `nav = 7`.
- Receiver egui: `zc-core/src/ui/{overlay,taskbar,start_menu,action_center,dialogs,types,
  theme}.rs`; hit-testing zones in `overlay.rs handle_event` use hardcoded rects (H8).
- Known-crash class to watch for: anything opening windows from the Presentation context.

## Work through the sections in this order (from BUG-REPORT.md)

- **B (16 items)** — input PC→phone. Highest-value: B1 (mouse modifiers dropped), B2
  (onText has no fallback), B3 (Escape quits the app), B4 (stuck mouse button on
  alt-tab), B5 (Backspace deletes forward — [verify] on a real page), B6 (WebView bridge
  never unregisters — NOTE: the new `isLive()`/top-window gating already partially
  addresses this; re-verify what remains), B7 (no key auto-repeat), B10 (aspect-ratio
  input mapping). Read `LocalInputDispatcher.kt` fully first — it was heavily reworked
  this session and some rows may be stale.
- **C (17 items)** — video pipeline on the phone. Highest-value: C1 (failed reconfigure
  = permanent black screen), C2 (leaked codec on double configure failure), C3 (encoder
  runs forever after disconnect), J8/J9 (Settings can kill the pipeline — Int overflow
  and odd resolutions; the report's C1 context applies). Several C items reference
  `forceRedraw` which no longer exists — re-verify each.
- **D (12 items)** — audio capture service. One file, one pass: D1 (hand-written
  varints — use the generated `AudioPacket`), D2 (MediaProjection callback missing),
  D4 (mutes user's music + sync disk write), D8 (no A/V sync clock).
- **E (19 items)** — wire protocol + native QUIC server (phone). Highest-value: E1
  (unbounded input queue), E2 (no input rate limit), E6 (event dropped after dequeue),
  E7 (second PC waits forever with no explanation). NOTE: E-items about
  `settle_state()`/STATE_IDLE may interact with the session-state code — read the
  current `lib.rs` around `serve_session` first (it was edited this session).
- **F (23 items)** — services/lifecycle/security. Highest-value: F1 (bind-from-
  Presentation loop), F3 (terminal can read the pairing key — sandbox or remove),
  F4 (biometric lock dead-end), F5 (lock doesn't stop audio/stream), F12 (backup rules
  reference the wrong filename — `pairing_v2.psk`).
- **G (16 items)** — receiver network/trust. Highest-value: G1 (receiver pins a CPU
  core at 100% — `AboutToWait → request_redraw()` defeats `ControlFlow::Wait`; NOTE the
  redraw loop was also the vehicle for per-frame rendering, so design this carefully
  with egui `request_repaint()`), G2 (trust-store failure strands pairing),
  G3 (untrusted 4-byte length prefix → OOM; the receiver's frame reader was reworked
  this session — re-verify line numbers), G7 (PSK in plaintext — DPAPI).
- **H (21 items)** — receiver render/UI. Highest-value: H1 (wgpu panics at startup),
  H2/H3 (mutex unwrap inside the audio callback), H9 (four buttons that do nothing —
  `reconnect`/`toggle_kiosk` never read; note `actions.nav_action` handling was added
  this session as the pattern to follow), H10 (three mute writers, one works), H11
  (fake "running apps" state), H12 (already done — verify & mark).
- **I (17 items)** — build/deps/packaging. I8 (the .bat runs debug), I9 (committed
  log/pair.png), I12 (duplicate `proto/` at repo root), I6 (dead deps).
- **J (34 items)** — small ones, sweep at the end. NOTE: J11/J17/J18/J19 overlap with
  F3 (terminal sandbox) — decide the terminal's fate first (F3), then sweep J.
  J17's WebView-never-destroyed pairs with the input work already done.

## Definition of done for the whole task

- Every item in B–J has a recorded verdict (fixed / already fixed / invalid / skipped
  with reason) in a table appended to `summary.md`.
- Both sides compile; receiver `cargo build` clean; APK `assembleDebug` successful.
- All device-testable fixes verified on hardware with evidence when a device is
  available; anything else explicitly marked "built, not device-tested".
- No temporary debug code left in the tree. No standing invariant broken (summary.md §8).
- The user is told, in plain language, what changed, what was verified, and what
  remains — including anything that could not be verified and why.

Begin by reading `summary.md`, then `BUG-REPORT.md` sections B–J, then start with
section B. Work batch by batch, verify each batch, keep the handoff current.
