package com.androiddex.network

import com.androiddex.core.EncodedVideoFrame
import com.androiddex.core.VideoTransport

/**
 * The QUIC protocol implementation of the VideoTransport interface.
 * Exposes a clean `sendVideoFrame()` method to the application while 
 * internally handling QUIC streams, datagrams, and Quinn FFI bindings.
 */
class QuicVideoTransport : VideoTransport {

    private var connected = false

    override val isConnected: Boolean
        get() = connected

    override fun connect() {
        println("QuicVideoTransport: Establishing UDP QUIC connection...")
        // Actual Quinn / native library init goes here
        connected = true
    }

    override fun disconnect() {
        println("QuicVideoTransport: Closing QUIC connection.")
        connected = false
    }

    override fun sendVideoFrame(frame: EncodedVideoFrame) {
        if (!connected) return

        // 1. Serialize the EncodedVideoFrame into the unified DexPacket structure
        // 2. Map DeliveryGuarantee.UNRELIABLE to QUIC Datagrams
        // 3. Dispatch byte array to native QUIC library
        
        // Log for architectural validation
        println("QuicVideoTransport [DATAGRAM]: Sent Frame Seq ${frame.sequenceNumber} " +
                "(${frame.payload.size} bytes, ${frame.frameType})")
    }
}
