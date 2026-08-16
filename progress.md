> continue with phase 2 till Phase 4 — Latency tuning across the wire

This is a large body of work spanning both sides of the wire. Let me map the Rust side properly first — I've only seen fragments of it.

Read 5 files, listed 1 directory, ran 10 shell commands

Good — local protoc.exe exists as a fallback, and CursorUpdate has no producer. Starting Phase 2 with the proto, per rule 6.

Ran 1 shell command

● Write(~\Documents\GitHub\AndriodDEX-V2\rust-receiver\zc-protocol\proto\video.proto)
Added 13 lines, removed 27 lines
syntax = "proto3";
package zc_protocol;

enum SourceType {
ANDROID_NATIVE = 0;
VM_WAYLAND = 1;
}
option java_package = "com.androiddex.protocol";
option java_outer_classname = "VideoProto";
option java_multiple_files = true;

// One H.264 access unit, produced by the phone's hardware encoder.
//
// nal_data is Annex-B byte-stream format. On keyframes the cached SPS/PPS is
// prepended, so a client that joins mid-session can start decoding at the first
// keyframe it receives without any out-of-band configuration.
message VideoFrame {
uint32 width = 1;
uint32 height = 2;
uint64 timestamp = 3;
bytes rgba_data = 4;
SourceType source = 5;
bytes nal_data = 1;
bool is_keyframe = 2;
int64 pts_us = 3;
int32 width = 4;
int32 height = 5;
}

message TileUpdate {
uint32 x = 1;
uint32 y = 2;
uint32 width = 3;
uint32 height = 4;
bytes zstd_data = 5;
}

message CursorUpdate {
uint32 x = 1;
uint32 y = 2;
bytes bitmap = 3;
uint32 width = 4;
uint32 height = 5;
}

message HybridFrame {
oneof payload {
VideoFrame video = 1;
TileUpdate tile = 2;
CursorUpdate cursor = 3;
}
}

Now the input proto, which Phase 4 extends with scroll and real modifiers:

● Write(~\Documents\GitHub\AndriodDEX-V2\rust-receiver\zc-protocol\proto\input.proto)
Added 42 lines, removed 12 lines
syntax = "proto3";
package protocol;

option java_package = "com.androiddex.protocol";
option java_outer_classname = "InputProto";
option java_multiple_files = true;

// Modifier bitmask shared by MouseEvent and KeyboardEvent.
//
// The receiver reports the real modifier state from winit rather than making the
// phone reconstruct it from keypress history, which desynchronises whenever a
// modifier release is missed (classic symptom: Shift stuck on).
enum Modifier {
MODIFIER_NONE = 0;
MODIFIER_SHIFT = 1;
MODIFIER_CTRL = 2;
MODIFIER_ALT = 4;
MODIFIER_SUPER = 8;
}

message MouseEvent {
uint32 x = 1;
uint32 y = 2;
uint32 buttons = 3;
uint64 timestamp = 4;
uint32 x = 1;
uint32 y = 2;
uint32 buttons = 3;
uint64 timestamp = 4;
uint32 modifiers = 5;
}

message KeyboardEvent {
uint32 keycode = 1;
bool pressed = 2;
uint32 modifiers = 3;
uint64 timestamp = 4;
uint32 keycode = 1;
bool pressed = 2;
uint32 modifiers = 3;
uint64 timestamp = 4;
}

// Mouse wheel. Values are in detents (notches), matching winit's LineDelta;
// positive v_scroll scrolls content away from the user, positive h_scroll right.
message ScrollEvent {
uint32 x = 1;
uint32 y = 2;
float v_scroll = 3;
float h_scroll = 4;
uint64 timestamp = 5;
uint32 modifiers = 6;
}

message InputEvent {
oneof event {
MouseEvent mouse = 1;
KeyboardEvent keyboard = 2;
}
oneof event {
MouseEvent mouse = 1;
KeyboardEvent keyboard = 2;
ScrollEvent scroll = 3;
}
}

Now wire the protobuf Gradle plugin into the Android build. AGP 9 compatibility is the main risk, so let me verify it early:

Ran 3 shell commands

The protobuf Gradle plugin is incompatible with AGP 9 (it casts to the removed BaseExtension). Let me check whether a newer plugin version fixes this before writing my own generation task.

Ran 3 shell commands

Plugin 0.10.0 applies cleanly against AGP 9 — the rest is Kotlin DSL syntax. Let me fix all three:

Read 1 file

● Update(app\build.gradle.kts)
Added 7 lines, removed 4 lines

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/jniLibs")
            // Single canonical copy of the wire format, shared with the Rust receiver.
            // Never duplicate these files: both sides must regenerate from the same source.
            proto.srcDir("../../rust-receiver/zc-protocol/proto")
            // src/main/jniLibs is already AGP's default jniLibs directory.

            // Point at the single canonical copy of the wire format, shared with the
            // Rust receiver. These files are never duplicated into this module: both
            // sides regenerate from the same source, per the protocol change rules.
            val protoSet = extensions.getByName("proto") as org.gradle.api.file.SourceDirectorySet
            protoSet.srcDir("../rust-receiver/zc-protocol/proto")
        }
    }

● Update(app\build.gradle.kts)
Added 2 lines, removed 3 lines
generateProtoTasks {
all().forEach { task ->
task.builtins {
// Drop the full-runtime Java builtin and emit lite messages instead.
remove("java")
create("java") {
// Emit lite messages: the javalite runtime, not the full one.
named("java") {
option("lite")
}
}

Searched for 1 pattern, ran 3 shell commands

Let me inspect the plugin's actual API rather than guess.

Ran 1 shell command

The 0.10.0 plugin no longer exposes a way to point an Android source set at external protos. I'll invoke protoc directly — pinned, deterministic, and independent of AGP internals.

Read 1 file, ran 3 shell commands

Generated. Now the Android transport side — FrameSender rewritten on generated builders:

● Write(app\src\main\java\com\example\androidhost\network\FrameSender.kt)
Added 69 lines, removed 109 lines
package com.example.androidhost.network

import android.util.Log
import java.io.OutputStream
import java.net.Socket
import com.androiddex.protocol.HybridFrame
import com.androiddex.protocol.VideoFrame
import com.example.androidhost.quic.QuicServer
import com.google.protobuf.ByteString
import com.google.protobuf.CodedOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

import com.example.androidhost.quic.QuicServer

/\*\*

- Puts encoded H.264 access units on the wire.
-
- Messages are built with the protobuf classes generated from
- `rust-receiver/zc-protocol/proto/video.proto`, the same file the Rust receiver
- compiles. Nothing here hand-writes varints or field tags.
-
- Framing matches what the receiver expects: a single leading message-type byte
- followed by a serialized `HybridFrame`. The length prefix in front of that is added
- by the native QUIC layer.
  \*/
  object FrameSender {
  private const val TAG = "FrameSender"

      /** Message type byte for video, as read by `zc-core/src/main.rs`. */
      private const val MSG_TYPE_VIDEO: Byte = 0x01

      private var isRunning = false
      val framesSent = AtomicInteger(0)


      val isConnected: Boolean
          get() = com.example.androidhost.quic.QuicServer.handle != 0L
          get() = QuicServer.handle != 0L

      fun start() {
          if (isRunning) return
          isRunning = true
          Log.d(TAG, "Starting FrameSender (via QUIC)")
          framesSent.set(0)
          Log.d(TAG, "Starting FrameSender (H.264 over QUIC)")
      }

      fun stop() {
          isRunning = false
      }

      private fun writeVarint(value: Long, out: java.io.ByteArrayOutputStream) {
          var v = value
          while (true) {
              if ((v and 0xFFFFFFFFFFFFFF80u.toLong()) == 0L) {
                  out.write(v.toInt())
                  return
              }
              out.write((v.toInt() and 0x7F) or 0x80)
              v = v ushr 7
          }
      }

      fun sendVideoFrame(width: Int, height: Int, data: ByteArray) {
      /**
       * Sends one encoded access unit.
       *
       * @param nal   the encoder's output buffer, positioned at the payload. Consumed
       *              in place; the caller still owns it and must release it afterwards.
       * @param csd   cached SPS/PPS, non-null on keyframes. Prepended to [nal] so a
       *              client that joins mid-session can start decoding immediately.
       * @param isKeyframe whether this access unit is an IDR
       * @param ptsUs presentation timestamp in microseconds
       */
      fun sendEncodedFrame(
          nal: ByteBuffer,
          csd: ByteArray?,
          isKeyframe: Boolean,
          ptsUs: Long,
          width: Int,
          height: Int
      ) {
          if (!isRunning) return
          try {
              val protoBaos = java.io.ByteArrayOutputStream(data.size + 100)
              protoBaos.write(8) // Tag 1 (width)
              writeVarint(width.toLong(), protoBaos)
              protoBaos.write(16) // Tag 2 (height)
              writeVarint(height.toLong(), protoBaos)
              protoBaos.write(24) // Tag 3 (timestamp = 0)
              writeVarint(0L, protoBaos)
              protoBaos.write(34) // Tag 4 (rgba_data)
              writeVarint(data.size.toLong(), protoBaos)
              protoBaos.write(data)
              val vfBytes = protoBaos.toByteArray()
              // ByteString.concat builds a rope rather than copying both halves again.
              val payload = if (csd != null) {
                  ByteString.copyFrom(csd).concat(ByteString.copyFrom(nal))
              } else {
                  ByteString.copyFrom(nal)
              }

              val hfBaos = java.io.ByteArrayOutputStream(vfBytes.size + 10)
              hfBaos.write(10) // HybridFrame.video (tag 1, wire type 2)
              writeVarint(vfBytes.size.toLong(), hfBaos)
              hfBaos.write(vfBytes)
              val hybrid = HybridFrame.newBuilder()
                  .setVideo(
                      VideoFrame.newBuilder()
                          .setNalData(payload)
                          .setIsKeyframe(isKeyframe)
                          .setPtsUs(ptsUs)
                          .setWidth(width)
                          .setHeight(height)
                  )
                  .build()

              val hfBytes = hfBaos.toByteArray()
              val finalBuffer = ByteBuffer.allocate(1 + hfBytes.size)
              finalBuffer.put(0x01.toByte())
              finalBuffer.put(hfBytes)
              // Serialize straight into a buffer that already has room for the type byte,
              // so the frame is copied once rather than twice.
              val size = hybrid.serializedSize
              val out = ByteArray(size + 1)
              out[0] = MSG_TYPE_VIDEO
              val stream = CodedOutputStream.newInstance(out, 1, size)
              hybrid.writeTo(stream)
              stream.flush()

              QuicServer.sendFrame(finalBuffer.array())
              val count = framesSent.incrementAndGet()
              Log.d(TAG, "Video frame sent via QUIC: $count")
              QuicServer.sendFrame(out)
              framesSent.incrementAndGet()
          } catch (e: Exception) {
              Log.e(TAG, "Failed to send frame", e)
              Log.e(TAG, "Failed to send encoded frame", e)
          }
      }

      fun sendTileUpdate(x: Int, y: Int, width: Int, height: Int, zstdData: ByteArray) {
          if (!isRunning) return
          try {
              val protoBaos = java.io.ByteArrayOutputStream(zstdData.size + 100)
              protoBaos.write(8) // Tag 1 (x)
              writeVarint(x.toLong(), protoBaos)
              protoBaos.write(16) // Tag 2 (y)
              writeVarint(y.toLong(), protoBaos)
              protoBaos.write(24) // Tag 3 (width)
              writeVarint(width.toLong(), protoBaos)
              protoBaos.write(32) // Tag 4 (height)
              writeVarint(height.toLong(), protoBaos)
              protoBaos.write(42) // Tag 5 (zstd_data)
              writeVarint(zstdData.size.toLong(), protoBaos)
              protoBaos.write(zstdData)
              val tuBytes = protoBaos.toByteArray()

              val hfBaos = java.io.ByteArrayOutputStream(tuBytes.size + 10)
              hfBaos.write(18) // HybridFrame.tile (tag 2, wire type 2)
              writeVarint(tuBytes.size.toLong(), hfBaos)
              hfBaos.write(tuBytes)

              val hfBytes = hfBaos.toByteArray()
              val finalBuffer = ByteBuffer.allocate(1 + hfBytes.size)
              finalBuffer.put(0x01.toByte())
              finalBuffer.put(hfBytes)

              QuicServer.sendFrame(finalBuffer.array())
          } catch (e: Exception) {
              Log.e(TAG, "Failed to send tile", e)
          }
      }

      fun sendCursorUpdate(x: Int, y: Int, width: Int, height: Int, bitmap: ByteArray) {
          if (!isRunning) return
          try {
              val protoBaos = java.io.ByteArrayOutputStream(bitmap.size + 100)
              protoBaos.write(8) // Tag 1 (x)
              writeVarint(x.toLong(), protoBaos)
              protoBaos.write(16) // Tag 2 (y)
              writeVarint(y.toLong(), protoBaos)
              protoBaos.write(26) // Tag 3 (bitmap, wire type 2)
              writeVarint(bitmap.size.toLong(), protoBaos)
              protoBaos.write(bitmap)
              protoBaos.write(32) // Tag 4 (width)
              writeVarint(width.toLong(), protoBaos)
              protoBaos.write(40) // Tag 5 (height)
              writeVarint(height.toLong(), protoBaos)
              val cuBytes = protoBaos.toByteArray()

              val hfBaos = java.io.ByteArrayOutputStream(cuBytes.size + 10)
              hfBaos.write(26) // HybridFrame.cursor (tag 3, wire type 2)
              writeVarint(cuBytes.size.toLong(), hfBaos)
              hfBaos.write(cuBytes)

              val hfBytes = hfBaos.toByteArray()
              val finalBuffer = ByteBuffer.allocate(1 + hfBytes.size)
              finalBuffer.put(0x01.toByte())
              finalBuffer.put(hfBytes)

              QuicServer.sendFrame(finalBuffer.array())
          } catch (e: Exception) {
              Log.e(TAG, "Failed to send cursor", e)
          }
      }

  }

Now delete the tile subsystem on the Android side and wire the encoder to the wire:

Ran 1 shell command

● Update(app\src\main\java\com\example\androidhost\service\DisplayService.kt)
Added 10 lines, removed 5 lines
}

    /**
     * Consumes encoded access units and measures sustained throughput.
     * Puts each encoded access unit on the wire and measures sustained throughput.
     *
     * The NAL is measured but not transmitted: the H.264 frame message and the
     * receiver-side decoder do not exist yet. Nothing is copied and nothing is allocated
     * per frame, so the encoder's zero-allocation property holds end to end.
     * Runs on MediaCodec's callback thread. The NAL is serialized straight out of the
     * codec's own buffer, so the captured pixels are never copied into a frame-sized
     * application buffer at any point in the pipeline.
     */
    private val encoderListener = object : ScreenEncoder.Listener {
        override fun onEncodedFrame(csd: ByteArray?, nal: ByteBuffer, isKeyframe: Boolean, ptsUs: Long) {
            val closed = encoderStats.record(nal.remaining(), isKeyframe) ?: return
            val size = nal.remaining()
            com.example.androidhost.network.FrameSender.sendEncodedFrame(
                nal, csd, isKeyframe, ptsUs, CAPTURE_WIDTH, CAPTURE_HEIGHT
            )

            val closed = encoderStats.record(size, isKeyframe) ?: return
            Log.i(
                TAG,
                "encode ${closed.fps} fps, ${closed.kilobitsPerSecond} kbps, " +

Now remove updateRegionStats and the tile overlay readouts, replacing them with encoder stats:

Searched for 1 pattern, read 1 file, ran 1 shell command

● Update(app\src\main\java\com\example\androidhost\DesktopShell.kt)
Added 7 lines, removed 6 lines
// 3. Launcher overlay (when toggled)
// 4. Taskbar at the bottom

        // Debug overlay — QUIC stats (top-right)
        val regionStats by com.example.androidhost.vm.DisplayViewModel.regionStats.collectAsState()

        // Debug overlay — encoder + QUIC stats (top-right)
        if (quicState > 0 || framesSent > 0) {
            Column(
                modifier = Modifier

                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp)
            ) {
                Text(text = "Sending frames via QUIC", color = Color.White, fontSize = 12.sp)
                Text(text = "Streaming H.264 via QUIC", color = Color.White, fontSize = 12.sp)
                Text(text = "Frames sent: $framesSent", color = Color.White, fontSize = 12.sp)
                if (com.example.androidhost.BuildConfig.DEBUG) {
                    Text(text = "Tiles: ${regionStats.first}", color = Color.White, fontSize = 12.sp)
                    Text(text = "Video: ${if (regionStats.second) "Y" else "N"}", color = Color.White, fontSize = 12.sp)
                    Text(
                        text = "Encode: ${encoderFps} fps, ${encoderKbps} kbps",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }

Ran 1 shell command

● Update(app\src\main\java\com\example\androidhost\DesktopShell.kt)
Added 3 lines, removed 1 line
Text(text = "Streaming H.264 via QUIC", color = Color.White, fontSize = 12.sp)
Text(text = "Frames sent: $framesSent", color = Color.White, fontSize = 12.sp)
if (com.example.androidhost.BuildConfig.DEBUG) {
val encode by com.example.androidhost.service.DisplayService
.encoderStats.latest.collectAsState()
Text(
text = "Encode: ${encoderFps} fps, ${encoderKbps} kbps",
text = "Encode: ${encode.fps} fps, ${encode.kilobitsPerSecond} kbps",
color = Color.White,
fontSize = 12.sp
)

Now InputManager — replace the hand-rolled protobuf parser with generated classes, including Phase 4's scroll and modifiers:

● Write(app\src\main\java\com\example\androidhost\service\InputManager.kt)
Added 38 lines, removed 104 lines
package com.example.androidhost.service

import android.util.Log
import com.androiddex.protocol.InputEvent
import com.example.androidhost.input.LocalInputDispatcher
import com.example.androidhost.quic.QuicServer
import java.io.OutputStream

/\*\*

- Reads input events off the QUIC transport and hands them to [LocalInputDispatcher].
-
- Events are parsed with the protobuf classes generated from
- `rust-receiver/zc-protocol/proto/input.proto` — the same file the Rust receiver
- compiles — rather than by walking varints by hand.
  \*/
  object InputManager {
  private const val TAG = "InputManager"

      var isPolling = false
          private set

      data class ParsedMouseEvent(val x: Int, val y: Int, val buttons: Int, val timestamp: Long)
      data class ParsedKeyboardEvent(val keycode: Int, val pressed: Boolean, val modifiers: Int, val timestamp: Long)
      /** Dedicated vsock stream for the AVF Linux VM, when one is attached. */
      @Volatile
      var vmOutputStream: OutputStream? = null

      // Dedicated vsock stream for AVF Linux VM
      @Volatile var vmOutputStream: OutputStream? = null

      fun startPolling(dataPath: String) {
          if (isPolling) return
          isPolling = true

                  try {
                      val bytesRead = QuicServer.pollInput(buffer)
                      if (bytesRead > 0) {
                          val data = buffer.copyOf(bytesRead)
                          handleInputEvent(data)
                          handleInputEvent(buffer, bytesRead)
                      } else {
                          Thread.sleep(10)
                          Thread.sleep(1)
                      }
                  } catch (e: InterruptedException) {
                      break

          inputServerThread = null
      }

      private fun handleInputEvent(data: ByteArray) {
          // 1. If VM is running, pipe raw bytes directly to vsock bypass!
      private fun handleInputEvent(data: ByteArray, length: Int) {
          // If a VM is running, pipe the raw bytes straight to its vsock and skip Android.
          vmOutputStream?.let { stream ->
              try {
                  // Prepend length header to frame for the VM daemon to parse
                  val lenBytes = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(data.size).array()
                  val lenBytes = java.nio.ByteBuffer.allocate(4)
                      .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                      .putInt(length)
                      .array()
                  stream.write(lenBytes)
                  stream.write(data)
                  stream.write(data, 0, length)
                  stream.flush()
                  return // Short-circuit, bypass Android entirely
                  return
              } catch (e: Exception) {
                  Log.e(TAG, "Failed to write to VM vsock, falling back to Android", e)
                  vmOutputStream = null
              }
          }

          // 2. Parse the protobuf InputEvent and dispatch it into the desktop's own
          //    Compose view tree via LocalInputDispatcher. No OS-level injection and no
          //    special permission is involved — the view hierarchy is ours.
          var pos = 0
          while (pos < data.size) {
              val tagResult = readVarint(data, pos) ?: return
              pos = tagResult.second
              val tag = tagResult.first.toInt()

              val fieldNumber = tag ushr 3
              val wireType = tag and 0x07

              if (wireType != 2) return

              val lengthResult = readVarint(data, pos) ?: return
              pos = lengthResult.second
              val fieldLen = lengthResult.first.toInt()

              if (pos + fieldLen > data.size) return

              val fieldData = data.copyOfRange(pos, pos + fieldLen)
              pos += fieldLen

              when (fieldNumber) {
                  1 -> decodeMouseEvent(fieldData)?.let {
                      LocalInputDispatcher.onMouse(it.x, it.y, it.buttons)
                  }
                  2 -> decodeKeyboardEvent(fieldData)?.let {
                      LocalInputDispatcher.onKey(it.keycode, it.pressed)
                  }
              }
          val event = try {
              InputEvent.parseFrom(data.copyOf(length))
          } catch (e: Exception) {
              Log.w(TAG, "Dropping malformed InputEvent (${length} bytes)", e)
              return
          }
      }

      private fun decodeMouseEvent(data: ByteArray): ParsedMouseEvent? {
          var pos = 0
          var x = 0; var y = 0; var buttons = 0; var timestamp = 0L

          while (pos < data.size) {
              val tagResult = readVarint(data, pos) ?: break
              pos = tagResult.second
              val tag = tagResult.first.toInt()
              val fieldNumber = tag ushr 3
              if ((tag and 0x07) != 0) break

              val valResult = readVarint(data, pos) ?: break
              pos = valResult.second

              when (fieldNumber) {
                  1 -> x = valResult.first.toInt()
                  2 -> y = valResult.first.toInt()
                  3 -> buttons = valResult.first.toInt()
                  4 -> timestamp = valResult.first
          when (event.eventCase) {
              InputEvent.EventCase.MOUSE -> {
                  val m = event.mouse
                  LocalInputDispatcher.onMouse(m.x, m.y, m.buttons, m.modifiers)
              }
          }
          return ParsedMouseEvent(x, y, buttons, timestamp)
      }

      private fun decodeKeyboardEvent(data: ByteArray): ParsedKeyboardEvent? {
          var pos = 0
          var keycode = 0; var pressed = false; var modifiers = 0; var timestamp = 0L

          while (pos < data.size) {
              val tagResult = readVarint(data, pos) ?: break
              pos = tagResult.second
              val tag = tagResult.first.toInt()
              val fieldNumber = tag ushr 3
              if ((tag and 0x07) != 0) break

              val valResult = readVarint(data, pos) ?: break
              pos = valResult.second

              when (fieldNumber) {
                  1 -> keycode = valResult.first.toInt()
                  2 -> pressed = valResult.first != 0L
                  3 -> modifiers = valResult.first.toInt()
                  4 -> timestamp = valResult.first
              InputEvent.EventCase.KEYBOARD -> {
                  val k = event.keyboard
                  LocalInputDispatcher.onKey(k.keycode, k.pressed, k.modifiers)
              }
              InputEvent.EventCase.SCROLL -> {
                  val s = event.scroll
                  LocalInputDispatcher.onScroll(s.x, s.y, s.vScroll, s.hScroll)
              }
              InputEvent.EventCase.EVENT_NOT_SET, null -> Unit
          }
          return ParsedKeyboardEvent(keycode, pressed, modifiers, timestamp)
      }

      private fun readVarint(data: ByteArray, startPos: Int): Pair<Long, Int>? {
          var result = 0L
          var shift = 0
          var pos = startPos
          while (pos < data.size) {
              val b = data[pos].toInt() and 0xFF
              result = result or ((b.toLong() and 0x7F) shl shift)
              pos++
              if (b and 0x80 == 0) return Pair(result, pos)
              shift += 7
              if (shift >= 64) return null
          }
          return null
      }

  }
  You've hit your session limit · resets 2:30am (Asia/Calcutta)
  /upgrade to increase your usage limit.

✻ Crunched for 10m 15s
