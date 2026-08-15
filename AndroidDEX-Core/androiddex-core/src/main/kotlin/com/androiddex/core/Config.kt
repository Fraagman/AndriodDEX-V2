package com.androiddex.core

/**
 * Unified configuration system avoiding hardcoded magic numbers.
 */
data class DexConfig(
    val video: VideoConfig,
    val audio: AudioConfig,
    val network: NetworkConfig,
    val security: SecurityConfig
) {
    companion object {
        val PERFORMANCE_MODE = DexConfig(
            video = VideoConfig(1280, 720, 60, 4_000_000, CodecType.H264),
            audio = AudioConfig(44100, AudioCodec.OPUS),
            network = NetworkConfig(TransportType.QUIC_DATAGRAM, 5000),
            security = SecurityConfig(AuthMode.TOFU)
        )

        val QUALITY_MODE = DexConfig(
            video = VideoConfig(1920, 1080, 30, 12_000_000, PerformancePreset.HIGH_QUALITY, CodecType.HEVC),
            audio = AudioConfig(48000, AudioCodec.OPUS),
            network = NetworkConfig(TransportType.QUIC_DATAGRAM, 5000),
            security = SecurityConfig(AuthMode.TOFU)
        )
    }
}

enum class PerformancePreset {
    LOW_LATENCY, BALANCED, HIGH_QUALITY
}

data class VideoConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val preset: PerformancePreset = PerformancePreset.BALANCED,
    val codec: CodecType
)

data class AudioConfig(
    val sampleRate: Int,
    val codec: AudioCodec
)

data class NetworkConfig(
    val preferredTransport: TransportType,
    val timeoutMs: Long
)

data class SecurityConfig(
    val authMode: AuthMode
)

enum class CodecType { H264, HEVC, AV1 }
enum class AudioCodec { OPUS, AAC }
enum class TransportType { QUIC_DATAGRAM, WEBRTC }
enum class AuthMode { TOFU, MUTUAL_TLS }
