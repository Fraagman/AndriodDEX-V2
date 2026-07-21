package com.androiddex.core

/**
 * Universal format for transporting an encoded video frame across the network.
 * This ensures the transport layer receives consistent, decoupled data regardless 
 * of the encoder (MediaCodec vs Software) or protocol (QUIC vs WebRTC).
 */
data class EncodedVideoFrame(
    val codec: CodecType,
    val width: Int,
    val height: Int,
    val timestampUs: Long,
    val frameType: FrameType,
    val sequenceNumber: Long,
    val payload: ByteArray // Raw NAL units
)

enum class FrameType {
    KEY_FRAME, // I-Frame (IDR)
    DELTA_FRAME // P/B-Frame
}
