package com.example.androidhost.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Hardware H.264 encoder fed directly by the VirtualDisplay.
 *
 * [inputSurface] is a `MediaCodec` input surface. The VirtualDisplay renders straight
 * into it, so frame pixels are handed from SurfaceFlinger to the encoder inside the
 * graphics stack and never enter application memory. There is no `ImageReader`, no
 * pixel copy, and no per-frame allocation anywhere in this class.
 *
 * Output is delivered through [Listener] on MediaCodec's own callback thread, using the
 * asynchronous callback API — no `dequeueOutputBuffer` polling, which would add its own
 * timeout latency.
 */
class ScreenEncoder(
    private val width: Int,
    private val height: Int,
    private var bitrate: Int,
    private val listener: Listener
) {

    companion object {
        private const val TAG = "ScreenEncoder"

        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val FRAME_RATE = 60
        private const val I_FRAME_INTERVAL_SEC = 3

        /** How often to log throughput, in encoded frames. */
        private const val LOG_EVERY_FRAMES = 60L
    }

    /**
     * Receives encoded access units.
     *
     * Called on MediaCodec's callback thread. The implementation must consume [nal]
     * before returning — the buffer is released back to the codec immediately after.
     * Do not block: stalling here stalls the encoder.
     */
    interface Listener {
        /**
         * @param csd  cached SPS/PPS, non-null on keyframes so a late-joining client can
         *             start decoding. The same array instance is reused every time; do
         *             not retain it.
         * @param nal  the encoded access unit, positioned and limited to its payload
         * @param isKeyframe whether this access unit is an IDR
         * @param ptsUs presentation timestamp in microseconds
         */
        fun onEncodedFrame(csd: ByteArray?, nal: ByteBuffer, isKeyframe: Boolean, ptsUs: Long)

        /** Encoding has failed and the encoder is no longer producing output. */
        fun onEncoderError(cause: Exception)
    }

    private var codec: MediaCodec? = null
    private var surface: Surface? = null

    /** SPS/PPS captured from the codec-config buffer, allocated once. */
    @Volatile
    private var codecSpecificData: ByteArray? = null

    @Volatile
    private var running = false

    val framesEncoded = AtomicLong(0)
    val keyframesEncoded = AtomicLong(0)
    val bytesEncoded = AtomicLong(0)

    /**
     * The surface the VirtualDisplay must render into. Valid only between [prepare] and
     * [release].
     */
    val inputSurface: Surface
        get() = surface ?: error("ScreenEncoder.prepare() has not been called")

    /**
     * Creates and configures the codec and its input surface. Must be called before the
     * VirtualDisplay is created, since the display needs [inputSurface].
     *
     * @throws java.io.IOException if no H.264 encoder exists on this device
     */
    fun prepare() {
        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        // The callback must be installed before configure() for asynchronous mode.
        encoder.setCallback(callback)

        try {
            encoder.configure(buildFormat(constrainedBaseline = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders reject an explicit profile/level pair. Losing the profile
            // hint is survivable — KEY_MAX_B_FRAMES still asks for no B-frames — but a
            // failed configure is not, so retry once without it.
            Log.w(TAG, "Encoder rejected ConstrainedBaseline; retrying without profile/level", e)
            encoder.reset()
            encoder.setCallback(callback)
            encoder.configure(buildFormat(constrainedBaseline = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        surface = encoder.createInputSurface()
        codec = encoder
        Log.i(TAG, "Encoder prepared: ${width}x$height @ ${FRAME_RATE}fps, ${bitrate / 1_000_000} Mbps")
    }

    /** Starts encoding. [prepare] must have been called. */
    fun start() {
        val encoder = codec ?: error("ScreenEncoder.prepare() has not been called")
        if (running) return
        encoder.start()
        running = true
        Log.i(TAG, "Encoder started")
    }

    private var lastKeyframeRequestTime = 0L

    /**
     * Asks the encoder to emit an IDR on the next frame. Called when a client finishes
     * pairing, so it does not wait up to [I_FRAME_INTERVAL_SEC] seconds for a picture.
     * Throttled to 1 per second to prevent network congestion collapses if the receiver
     * asks for keyframes in a tight loop.
     */
    fun requestKeyframe() {
        val encoder = codec ?: return
        if (!running) return
        val now = System.currentTimeMillis()
        if (now - lastKeyframeRequestTime < 1000) return
        lastKeyframeRequestTime = now

        try {
            encoder.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
            Log.d(TAG, "Keyframe requested")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request keyframe", e)
        }
    }

    /**
     * Dynamically changes the encoder bitrate without restarting it.
     */
    fun setBitrate(newBitrate: Int) {
        val encoder = codec ?: return
        if (!running) return
        try {
            encoder.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
            })
            bitrate = newBitrate
            Log.d(TAG, "Bitrate updated to $newBitrate")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update bitrate", e)
        }
    }

    /** Stops and releases the codec and its input surface. Safe to call repeatedly. */
    fun release() {
        running = false
        val encoder = codec
        codec = null
        if (encoder != null) {
            try {
                encoder.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Encoder stop failed", e)
            }
            try {
                encoder.release()
            } catch (e: Exception) {
                Log.w(TAG, "Encoder release failed", e)
            }
        }
        surface?.release()
        surface = null
        Log.i(TAG, "Encoder released after ${framesEncoded.get()} frames")
    }

    private fun buildFormat(constrainedBaseline: Boolean): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            // Required for zero-copy: input comes from a Surface, not from byte buffers.
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            // CBR so a burst of desktop activity cannot spike the bitrate and queue up.
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            // Long interval on purpose: keyframes are requested on demand instead.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
            // Realtime priority.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            // No B-frames: they require reordering, which is pure added latency.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)

            if (constrainedBaseline) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline)
                // KEY_PROFILE without KEY_LEVEL is rejected by several encoders.
                // Level 4.2 is the lowest that covers 1920x1080 at 60 fps.
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Tells the encoder to skip lookahead entirely.
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
    }

    private val callback = object : MediaCodec.Callback() {

        /**
         * Never invoked: input arrives through the Surface, not through input buffers.
         */
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer == null) {
                    codec.releaseOutputBuffer(index, false)
                    return
                }

                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // SPS/PPS. Cache it and do not forward it as a frame; it is prepended
                    // to every keyframe instead.
                    cacheCodecSpecificData(buffer, info)
                    codec.releaseOutputBuffer(index, false)
                    return
                }

                if (info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)

                    val isKeyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    listener.onEncodedFrame(
                        if (isKeyframe) codecSpecificData else null,
                        buffer,
                        isKeyframe,
                        info.presentationTimeUs
                    )

                    val count = framesEncoded.incrementAndGet()
                    bytesEncoded.addAndGet(info.size.toLong())
                    if (isKeyframe) keyframesEncoded.incrementAndGet()
                    if (count % LOG_EVERY_FRAMES == 0L) {
                        Log.d(
                            TAG,
                            "Encoded $count frames, ${keyframesEncoded.get()} keyframes, " +
                                "${bytesEncoded.get() / 1024} KiB total"
                        )
                    }
                }

                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                // Codec was released underneath us during teardown.
                Log.w(TAG, "Output buffer handling after release", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle encoded output", e)
                listener.onEncoderError(e)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // The output format carries SPS (csd-0) and PPS (csd-1). This is the more
            // reliable source than the codec-config buffer on some encoders, so prefer it.
            val sps = format.getByteBuffer("csd-0")
            val pps = format.getByteBuffer("csd-1")
            if (sps != null && pps != null) {
                val spsLen = sps.remaining()
                val ppsLen = pps.remaining()
                val merged = ByteArray(spsLen + ppsLen)
                sps.get(merged, 0, spsLen)
                pps.get(merged, spsLen, ppsLen)
                codecSpecificData = merged
                Log.i(TAG, "Cached SPS/PPS from output format: ${merged.size} bytes")
            }
            Log.i(TAG, "Output format changed: $format")
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Encoder error (recoverable=${e.isRecoverable}, transient=${e.isTransient})", e)
            listener.onEncoderError(e)
        }
    }

    /** Copies SPS/PPS out of a codec-config buffer. Runs at most once per session. */
    private fun cacheCodecSpecificData(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val csd = ByteArray(info.size)
        buffer.get(csd)
        codecSpecificData = csd
        Log.i(TAG, "Cached SPS/PPS from codec-config buffer: ${csd.size} bytes")
    }
}
