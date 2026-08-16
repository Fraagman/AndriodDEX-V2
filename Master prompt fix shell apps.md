# MASTER TASK: Make the desktop shell's apps actually work

## Rules (non-negotiable)

1. No placeholders, no stubs, no empty windows. `// Empty for unsupported mock windows`
   in `DesktopShell.kt` is exactly the thing this task removes — do not create more of it.
2. Every window that appears in the launcher must render real, working content. If you
   cannot fully implement one, remove it from the launcher rather than shipping a black box.
3. Do not guess at root causes. Where this document says "instrument first", add logging,
   observe the actual behaviour, and report what you found before changing code.
4. Pin exact dependency versions. Never "latest".
5. State anything you did not finish at the **top** of your report, not the bottom.

---

## Context

The streaming pipeline is working: H.264 video reaches the PC, windows render, mouse and
keyboard input arrives. The remaining defects are all inside the desktop shell's own
application windows.

Confirmed on real hardware. Screenshots show `QUIC: Connected`, live video, and a working
window manager, so transport and input plumbing are **out of scope** for this task except
where Phase 6 explicitly says otherwise.

---

## Phase 1 — Files and Settings render nothing

### The defect

`DesktopShell.kt` around line 229 dispatches window content by package name.
`com.androiddex.codeserver` and `com.androiddex.terminal` get real composables. Everything
else falls into:

```kotlin
} else {
    WindowChrome(...) {
        // Empty for unsupported mock windows
    }
}
```

`com.androiddex.files` and `com.androiddex.settings` are both listed in `AppLauncher.kt`
(lines 122-123) and both land here. They are not broken — **they were never written**. The
black rectangles in the screenshots are empty windows behaving exactly as coded.

### Required

Replace the package-name `if/else` chain with a single registry that maps a package name to
its content composable. Adding a window in future must mean adding one registry entry, not
another `else if`. A package with no registered content must not appear in the launcher at
all.

**Files** (`com.androiddex.files`) — a real file manager over the app's own storage:

- Browse `context.filesDir` and `context.getExternalFilesDir(null)`. Do **not** attempt to
  browse `/` or `/sdcard` — the app is sandboxed and those reads fail, which is the same
  mistake the terminal is currently making.
- Directory listing with name, size, and modified date. Click to enter, up-navigation,
  breadcrumb path.
- Create folder, rename, delete, with a confirmation for delete.
- Open a text file in a viewer; show a clear "cannot preview this type" state for binaries.
- Handle empty directories and permission errors with visible messages, never a blank pane.

**Settings** (`com.androiddex.settings`) — real, wired to real state:

- Display: current resolution and encoder bitrate, both editable, applied live.
- Input: whether the AndroidDEX IME is the active keyboard, whether the accessibility
  service is enabled, with buttons that open the correct system screens.
- Connection: pairing state, connected peer, and an **Unpair** action that clears the stored
  PSK in `rust_quic_server`'s store and forces a fresh PIN flow.
- Stats: live encode fps and kbps — `encoderStats.latest` is already exposed and used in
  `DesktopShell.kt`. Add the dropped-frame counters from `QuicServer.getDroppedVideoFrames()`
  and `getDroppedAudioFrames()`.
- Every control must read and write real state. A toggle that does nothing is a stub.

---

## Phase 2 — code-server is blocked by cleartext policy, not by the server

`ERR_CLEARTEXT_NOT_PERMITTED` on `http://127.0.0.1:18080/`. Android 9 (API 28) disables
cleartext HTTP by default, and `android-host/app/src/main/res/xml/` contains no
`network_security_config.xml`. The page cannot load regardless of whether anything is
listening on 18080.

- Add `res/xml/network_security_config.xml` permitting cleartext **only** for `127.0.0.1`
  and `localhost`. Do not set `android:usesCleartextTraffic="true"` — that opens cleartext
  to every host on the internet.
- Reference it from `<application>` in the manifest.
- Then determine whether anything actually listens on 18080. If code-server is not running,
  the window must show a clear state explaining that, with whatever start action is real —
  not a browser error page.
- If code-server cannot be made to run at all, remove it from the launcher and say so.

---

## Phase 3 — Terminal: every keystroke is entered twice

### The defect

Screenshot shows `lsls: .: Permission denied` — a two-character command arriving as four
characters. Confirmed on hardware.

**Instrument before changing anything.** Add temporary logging at each of these points and
report which ones fire per physical keypress:

1. `zc-core/src/main.rs` `WindowEvent::KeyboardInput` — how many events winit emits per press.
2. `InputManager.handleInputEvent` — how many protobuf events arrive.
3. `LocalInputDispatcher.handleKey` — how many times it runs.
4. `AndroidDexIME.dispatchFromHost` — whether it returns true, and whether
   `InputConnection.sendKeyEvent` then causes a _second_ delivery to the focused view.
5. The terminal composable's own key or text callback.

The three plausible causes are: winit emitting both a key event and a text event for one
press; `dispatchFromHost` returning true after `sendKeyEvent` has already delivered the
event through normal view dispatch; or the terminal handling both a raw `KeyEvent` and a
`TextField` value change. **Find out which. Do not fix all three speculatively** — the wrong
fix here produces dropped keys, which is worse than doubled keys.

Report the instrumentation output, then fix the single real cause.

### Also fix the working directory

`ls: .: Permission denied` is correct behaviour, not a bug: the shell's working directory is
somewhere the sandboxed app cannot read. Default the terminal's cwd to `context.filesDir`,
show it in the prompt, and make `cd` outside the accessible sandbox fail with an
understandable message rather than a bare permission error.

---

## Phase 4 — Add a browser window

There is no browser. Add one as `com.androiddex.browser`, registered through the Phase 1
registry.

- Use Android `WebView` inside an `AndroidView` composable. This needs only the `INTERNET`
  permission — no Developer Options, no Shizuku, consistent with every decision made so far.
- URL bar, back, forward, reload, and a visible loading indicator.
- Enable JavaScript and DOM storage. Set a desktop user-agent string so sites render their
  desktop layout, since this is presented on a monitor.
- Handle `onReceivedError` with a readable message in the page area.
- Confirm the WebView renders correctly on the `VirtualDisplay` — `WebView` is a
  hardware-accelerated surface and is the most likely thing in this task to misbehave on a
  secondary display. If it renders black, report that with what you tried before working
  around it.

---

## Phase 5 — Remove the debug overlay

`DesktopShell.kt` calls `ProofOfLifeIndicator()`, described in its own comment as temporary.
An always-animating element means the screen is never static, so the encoder can never fall
to a low bitrate on an idle desktop. Remove it, and remove the stats text overlay from the
shell — that information belongs in the Settings window from Phase 1.

---

## Phase 6 — Residual lag

The stream is described as "slightly laggy" after the H.264 work. Measure before tuning.

- Report actual sustained encode fps and kbps from `encoderStats`, and the receiver's decode
  fps and decode-time overlay, over 60 seconds of active window dragging.
- Report `getDroppedVideoFrames()` over the same period. A non-zero and growing count means
  the network, not the encoder, is the limit — say so rather than raising the bitrate.
- Check whether the Compose shell itself is rendering at 60 fps. If the shell renders at 30,
  no amount of encoder tuning helps.
- Only after reporting those numbers, tune. State what you changed and the measured
  before/after. Do not change the bitrate or resolution without a number justifying it.

One known issue you may leave alone unless it is implicated: `zc-core/src/main.rs` accepts
unidirectional streams in a loop but processes each inline, so only the first stream is ever
drained. This does not affect video.

---

## Out of scope

- Pairing, PSK, TLS — working, verified, do not touch.
- The H.264 encode/decode pipeline itself.
- Third-party Android app launching — permanently removed by design.
- The MDM console, Microdroid bridge.

---

## Final report

1. Anything unfinished, at the top.
2. Phase 3: the instrumentation output and which cause it actually was.
3. Phase 6: the measured numbers, before and after.
4. Files created, modified, deleted.
5. Whether the WebView renders correctly on the virtual display.
