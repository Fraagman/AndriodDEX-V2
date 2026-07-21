package com.androiddex.video

import com.androiddex.core.CapabilityManager
import com.androiddex.core.Plugin

/**
 * Registers the Hardware Video Encoder into the Plugin Registry.
 */
class MediaCodecEncoderPlugin : Plugin {
    override val id = "com.androiddex.video.mediacodec_encoder"
    override val version = "1.0.0"

    private var encoderInstance: MediaCodecVideoEncoder? = null

    override fun validate(capabilityManager: CapabilityManager): Boolean {
        // Fallback gracefully if none of the hardware encoders are supported
        return capabilityManager.supportsH264HardwareEncoder() ||
               capabilityManager.supportsHEVCHardwareEncoder() ||
               capabilityManager.supportsAV1HardwareEncoder()
    }

    override fun initialize() {
        encoderInstance = MediaCodecVideoEncoder()
    }

    override fun healthCheck(): Boolean {
        return encoderInstance != null
    }

    override fun shutdown() {
        encoderInstance?.stop()
        encoderInstance = null
    }

    /** Exposes the encoder to the rest of the application via DI/PluginManager. */
    fun getEncoder(): VideoEncoder? = encoderInstance
}
