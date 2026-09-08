package com.example.androidhost.network

import com.androiddex.protocol.HybridFrame
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class FrameSenderTest {

    @Test
    fun testFrameSenderSerializationRoundTrip() {
        val testNalBytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E, 0xAB.toByte(), 0xCD.toByte())
        val payload = ByteString.copyFrom(testNalBytes)
        val isKeyframe = true
        val ptsUs = 123456789L
        val width = 1920
        val height = 1080

        // Serialize frame using FrameSender's framing
        val wireBytes = FrameSender.serializeFrame(payload, isKeyframe, ptsUs, width, height)

        // Verify message type prefix byte is MSG_TYPE_VIDEO (0x01)
        assertEquals(0x01.toByte(), wireBytes[0])
        assertTrue("Wire packet must contain header byte and protobuf payload", wireBytes.size > 1)

        // Parse protobuf payload skipping the 1-byte header
        val payloadStream = ByteArrayInputStream(wireBytes, 1, wireBytes.size - 1)
        val parsed = HybridFrame.parseFrom(payloadStream)

        // Verify every field in the protobuf survives exactly
        assertTrue(parsed.hasVideo())
        val video = parsed.video
        assertArrayEquals(testNalBytes, video.nalData.toByteArray())
        assertEquals(isKeyframe, video.isKeyframe)
        assertEquals(ptsUs, video.ptsUs)
        assertEquals(width, video.width)
        assertEquals(height, video.height)
    }
}
