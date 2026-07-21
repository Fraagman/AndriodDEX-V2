package com.androiddex.network

/**
 * The unified packet definition for all network traffic.
 * Instead of raw Protobuf bytes, everything goes through this typed packet layer.
 */
data class DexPacket(
    val header: PacketHeader,
    val payload: ByteArray
)

data class PacketHeader(
    val type: PacketType,
    val flags: Byte = 0,
    val sequenceNumber: Long,
    val timestampUs: Long
)

enum class PacketType {
    VIDEO_FRAME,
    AUDIO_FRAME,
    INPUT_EVENT,
    HEARTBEAT,
    CAPABILITY_EXCHANGE,
    CONFIG_UPDATE,
    DISCOVERY,
    AUTHENTICATION,
    PLUGIN_SYNC,
    DIAGNOSTICS
}

/**
 * Defines the reliability requirement of the packet.
 * Mapped to QUIC Datagrams (Unreliable) vs QUIC Streams (Reliable) under the hood.
 */
enum class DeliveryGuarantee {
    RELIABLE,    // For Authentication, Input, Config
    UNRELIABLE   // For Video, Audio (where latency > reliability)
}
