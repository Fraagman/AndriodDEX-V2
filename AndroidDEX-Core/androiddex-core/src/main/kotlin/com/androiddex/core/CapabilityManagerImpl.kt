package com.androiddex.core

import android.content.Context
import android.media.MediaCodecList

class CapabilityManagerImpl(private val context: Context) : CapabilityManager {

    private val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)

    override fun supportsH264HardwareEncoder(): Boolean {
        return codecList.codecInfos.any { 
            it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.contains("video/avc")
        }
    }

    override fun supportsHEVCHardwareEncoder(): Boolean {
        return codecList.codecInfos.any { 
            it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.contains("video/hevc")
        }
    }

    override fun supportsAV1HardwareEncoder(): Boolean {
        return codecList.codecInfos.any { 
            it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.contains("video/av01")
        }
    }

    override fun supportsUsbTransport(): Boolean {
        // Mock implementation for validation: assumes true for now
        return true
    }

    override fun supportsAccessibilityInput(): Boolean {
        return true // Can always request it
    }

    override fun supportsMediaProjection(): Boolean {
        return true // Supported on Android 5+
    }

    override fun supports120Hz(): Boolean {
        return context.display?.supportedModes?.any { it.refreshRate >= 120f } ?: false
    }
}
