package com.androiddex.core

/**
 * Detects and manages hardware and OS-level capabilities at runtime.
 * Instead of relying on hardcoded Android version checks, the system queries
 * the CapabilityManager to determine if a specific transport, codec, or input mode is supported.
 */
interface CapabilityManager {
    /** Checks if the device supports hardware H264 encoding. */
    fun supportsH264HardwareEncoder(): Boolean

    /** Checks if the device supports hardware HEVC (H265) encoding. */
    fun supportsHEVCHardwareEncoder(): Boolean

    /** Checks if the device supports AV1 encoding. */
    fun supportsAV1HardwareEncoder(): Boolean

    /** Checks if USB Tethering / RNDIS is currently active and available. */
    fun supportsUsbTransport(): Boolean

    /** Checks if Shizuku is currently running and permission is granted. */
    fun supportsShizukuInput(): Boolean

    /** Checks if the Accessibility Service is enabled for the app. */
    fun supportsAccessibilityInput(): Boolean

    /** Checks if MediaProjection API (Screen/Audio Capture) is available. */
    fun supportsMediaProjection(): Boolean

    /** Checks if the device screen supports 120Hz refresh rate. */
    fun supports120Hz(): Boolean
}
