# TASK: Fix the idle-frame regression and finish Phase 6

## Rules

1. No placeholders, no stubs.
2. Phase 1 is a regression you introduced in the last change. Read the analysis before
   writing code — the previous fix was directionally right and overshot.
3. Phase 3 asks for measurements that were requested last time and not delivered. Numbers,
   not descriptions.
4. State anything unfinished at the **top** of your report.

---

## Phase 1 — A client connecting to an idle desktop sees black forever (CRITICAL)

### What happened

The last change removed `KeepAliveRedraw` from `DesktopPresentation.kt` entirely, so the
shell now renders at 0 fps when idle. The reasoning — that constant 30-90 fps redrawing was
wasting bandwidth and feeding a keyframe congestion loop — was correct. The fix went too far.

### Why 0 fps breaks

`MediaCodec` in surface input mode only produces an encoded frame when something is rendered
to its input surface. `PARAMETER_KEY_REQUEST_SYNC_FRAME` takes effect on the **next input
frame**, so with no input frames there is no output at all — not even a keyframe.

The failure sequence:

1. The desktop sits idle. The shell renders nothing. The encoder emits nothing.
2. A PC connects and authenticates.
3. `requestKeyframe()` fires, but the encoder has no input frame to mark as an IDR.
4. The PC shows black indefinitely. Moving the mouse from the PC does not obviously help,
   because the user cannot see anything to interact with.

The 1000 ms throttle added to `requestKeyframe()` makes reconnect recovery slower on top of
this.

### Required

- Reinstate a redraw heartbeat in `DesktopPresentation.kt`, but at an **idle floor of 1-2
  fps**, not the display refresh rate. That keeps the encoder alive and preserves
  essentially all of the bandwidth saving, since an idle desktop encodes to almost nothing
  per frame.
- The floor applies only when idle. Active interaction must still drive full frame rate —
  do not cap the shell at 2 fps.
- When a client authenticates, force an immediate redraw so the encoder has an input frame
  to attach the IDR to. A keyframe request with nothing to encode is a no-op.
- The first keyframe request after a client connects must **bypass** the 1000 ms cooldown.
  Keep the cooldown for repeated requests during decode errors — that part solved a real
  congestion problem and should stay.

### Verification (run all three on hardware)

1. Start the app, leave the phone untouched for 30 seconds, then start the PC receiver.
   Video must appear within about a second. This is the exact case that is broken now.
2. With the desktop idle, confirm the encoder is producing roughly 1-2 fps at a very low
   bitrate — not 0, and not 30+.
3. Interact with a window and confirm the frame rate rises to full immediately.

---

## Phase 2 — Cleanup

- `service/AndroidDexIME.kt`: remove the `Log.d(TAG, "AndroidDexIME dispatchFromHost: ...")`
  line. It runs on every keystroke and is leftover debug output.
- `DesktopShell.kt` line 23: `import androidx.compose.runtime.withFrameNanos` is now unused.
  Remove it.
- Sweep both `android-host` and `rust-receiver` for other debug logging left in per-frame or
  per-keystroke paths and remove it. Logging that reports state changes or errors stays.

---

## Phase 3 — The Phase 6 measurements, properly this time

The previous report described the lag investigation qualitatively — "massive amounts of
keyframes", "congestion collapse loop" — with no figures. The diagnosis may well be right,
but without numbers there is no way to tell whether the lag is actually fixed.

Produce a table with these rows, measured over a 60-second run, **before and after** the
Phase 1 change:

| Metric                                  | Source                               |
| --------------------------------------- | ------------------------------------ |
| Sustained encode fps, idle              | `encoderStats.latest`                |
| Sustained encode fps, dragging a window | `encoderStats.latest`                |
| Encode kbps, idle                       | `encoderStats.latest`                |
| Encode kbps, dragging                   | `encoderStats.latest`                |
| Dropped video frames                    | `QuicServer.getDroppedVideoFrames()` |
| Decode fps                              | receiver overlay                     |
| Decode time, ms                         | receiver overlay                     |
| Keyframes emitted                       | count in `ScreenEncoder`             |

Then answer directly: **is the residual lag gone, and what number shows it?**

If dropped frames are non-zero and growing, the network is the limit — say so rather than
raising the bitrate.

---

## Out of scope

Pairing, TLS, the H.264 pipeline, the app registry, and the Files, Settings, and Browser
windows. All verified working. Do not touch them.

---

## Final report

1. Anything unfinished, at the top.
2. The three Phase 1 hardware verification results.
3. The Phase 3 table, filled in with real numbers.
4. Files modified.
