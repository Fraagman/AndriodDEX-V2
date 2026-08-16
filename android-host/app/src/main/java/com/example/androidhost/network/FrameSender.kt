package com.example.androidhost.network

import android.util.Log
import com.androiddex.protocol.HybridFrame
import com.androiddex.protocol.VideoFrame
import com.example.androidhost.quic.QuicServer
import com.google.protobuf.ByteString
import com.google.protobuf.CodedOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Puts encoded H.264 access units on the wire.
 *
 * Messages are built with the protobuf classes generated from
 * `rust-receiver/zc-protocol/proto/video.proto`, the same file the Rust receiver
 * compiles. Nothing here hand-writes varints or field tags.
 *
 * Framing matches what the receiver expects: a single leading message-type byte
 * followed by a serialized `HybridFrame`. The length prefix in front of that is added
 * by the native QUIC layer.
 */
object FrameSender {
    private const val TAG = "FrameSender"

    /** Message type byte for video, as read by `zc-core/src/main.rs`. */
    private const val MSG_TYPE_VIDEO: Byte = 0x01

    private var isRunning = false
    val framesSent = AtomicInteger(0)

    val isConnected: Boolean
        get() = QuicServer.handle != 0L

    fun start() {
        if (isRunning) return
        isRunning = true
        framesSent.set(0)
        Log.d(TAG, "Starting FrameSender (H.264 over QUIC)")
    }

    fun stop() {
        isRunning = false
    }

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
            // ByteString.concat builds a rope rather than copying both halves again.
            val payload = if (csd != null) {
                ByteString.copyFrom(csd).concat(ByteString.copyFrom(nal))
            } else {
                ByteString.copyFrom(nal)
            }

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

            // Serialize straight into a buffer that already has room for the type byte,
            // so the frame is copied once rather than twice.
            val size = hybrid.serializedSize
            val out = ByteArray(size + 1)
            out[0] = MSG_TYPE_VIDEO
            val stream = CodedOutputStream.newInstance(out, 1, size)
            hybrid.writeTo(stream)
            stream.flush()

            QuicServer.sendFrame(out)
            framesSent.incrementAndGet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send encoded frame", e)
        }
    }
}
