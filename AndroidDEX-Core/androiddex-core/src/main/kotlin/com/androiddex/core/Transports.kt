package com.androiddex.core

import java.nio.ByteBuffer

/**
 * Base interface for all network transports (QUIC, WebRTC, etc.)
 */
interface Transport {
    val isConnected: Boolean
    fun connect()
    fun disconnect()
}

/**
 * Abstraction for sending encoded video frames to the receiver.
 */
interface VideoTransport : Transport {
    /** 
     * Sends an encoded H264/HEVC/AV1 video frame to the receiver.
     * Serialization into Packets happens internally within the Transport layer.
     */
    fun sendVideoFrame(frame: EncodedVideoFrame)
}

/**
 * Abstraction for sending encoded audio frames to the receiver.
 */
interface AudioTransport : Transport {
    /**
     * Sends an encoded audio chunk (Opus/AAC).
     */
    fun sendAudioFrame(buffer: ByteBuffer, timestampUs: Long)
}

/**
 * Abstraction for receiving input events from the receiver.
 */
interface InputTransport : Transport {
    /**
     * Sets a callback to be invoked when an input event (Mouse, Keyboard, Touch) is received.
     */
    fun setOnInputEventListener(listener: (InputEvent) -> Unit)
}

/**
 * Represents a generic input event from the receiver.
 */
data class InputEvent(
    val type: EventType,
    val x: Float = 0f,
    val y: Float = 0f,
    val button: Int = 0,
    val keyCode: Int = 0
) {
    enum class EventType {
        MOUSE_MOVE, MOUSE_DOWN, MOUSE_UP, KEY_DOWN, KEY_UP, SCROLL
    }
}
