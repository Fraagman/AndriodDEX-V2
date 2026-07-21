package com.androiddex.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import com.androiddex.core.CodecType
import com.androiddex.core.EventBus
import com.androiddex.core.FrameEncodedEvent
import com.androiddex.core.VideoConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Hardware-accelerated VideoEncoder implementation utilizing Android's MediaCodec API.
 */
class MediaCodecVideoEncoder : VideoEncoder {

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var eventBus: EventBus? = null
    
    private val isRunning = AtomicBoolean(false)
    private var encoderThread: Thread? = null

    override fun prepare(config: VideoConfig, eventBus: EventBus): Surface? {
        this.eventBus = eventBus
        
        val mimeType = when (config.codec) {
            CodecType.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            CodecType.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
            CodecType.AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
        }

        val format = MediaFormat.createVideoFormat(mimeType, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second between automatic I-Frames
            
            // Ultra-low latency settings (where supported)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

        try {
            mediaCodec = MediaCodec.createEncoderByType(mimeType).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        return inputSurface
    }

    override fun start() {
        if (isRunning.getAndSet(true)) return
        
        mediaCodec?.start()
        
        encoderThread = thread(start = true, name = "MediaCodecEncoderLoop") {
            val bufferInfo = MediaCodec.BufferInfo()
            val codec = mediaCodec ?: return@thread
            
            while (isRunning.get()) {
                try {
                    val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outputBufferId >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferId)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // Extract NAL unit
                            val outData = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(outData)
                            
                            val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            
                            // Publish encoded frame to the EventBus (decoupled pipeline)
                            eventBus?.publish(
                                FrameEncodedEvent(
                                    nalUnits = outData,
                                    isKeyFrame = isKeyFrame,
                                    timestampUs = bufferInfo.presentationTimeUs
                                )
                            )
                        }
                        codec.releaseOutputBuffer(outputBufferId, false)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    override fun stop() {
        if (!isRunning.getAndSet(false)) return
        
        try {
            encoderThread?.join(500)
            mediaCodec?.stop()
            mediaCodec?.release()
            inputSurface?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaCodec = null
            inputSurface = null
        }
    }

    override fun requestKeyFrame() {
        if (!isRunning.get()) return
        try {
            val bundle = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(bundle)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
