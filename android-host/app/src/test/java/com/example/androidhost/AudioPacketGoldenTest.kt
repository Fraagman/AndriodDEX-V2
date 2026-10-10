package com.example.androidhost

import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Cross-language golden vector: the same AudioPacket bytes the Rust receiver's
 * test (zc-protocol/tests/audio_golden.rs) pins. The Kotlin side serializes with
 * the generated class; if either side drifts from the shared wire format, one of
 * the two tests fails — exactly the class of drift that hand-written varint
 * serialization once introduced (D1).
 */
class AudioPacketGoldenTest {

    @Test
    fun audioPacketMatchesTheRustGoldenVector() {
        val packet = zc_audio.Audio.AudioPacket.newBuilder()
            .setPcmData(ByteString.copyFrom(byteArrayOf(0x11, 0x22)))
            .setTimestamp(5L)
            .build()

        // Field 1 (bytes, tag 0x0A), field 2 (varint, tag 0x10) — the exact bytes
        // the Rust test asserts.
        val expected = byteArrayOf(0x0A, 0x02, 0x11, 0x22, 0x10, 0x05)
        assertArrayEquals(expected, packet.toByteArray())
    }

    @Test
    fun audioPacketEmptyPayloadMatchesTheRustShape() {
        val packet = zc_audio.Audio.AudioPacket.newBuilder()
            .setPcmData(ByteString.EMPTY)
            .setTimestamp(1L)
            .build()

        // proto3 omits empty fields: only the timestamp is on the wire.
        val expected = byteArrayOf(0x10, 0x01)
        assertArrayEquals(expected, packet.toByteArray())
    }
}
