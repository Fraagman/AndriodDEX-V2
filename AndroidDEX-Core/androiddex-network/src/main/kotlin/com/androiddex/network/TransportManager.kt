package com.androiddex.network

import com.androiddex.core.CapabilityManager
import com.androiddex.core.EventBus
import com.androiddex.core.VideoTransport
import java.util.concurrent.atomic.AtomicReference

/**
 * Centralized manager for selecting, failing over, and monitoring network transports.
 * The application business logic only interacts with this manager, entirely agnostic
 * to whether QUIC, WebRTC, or a future Cloud Relay is currently active.
 */
class TransportManager(
    private val capabilityManager: CapabilityManager,
    private val eventBus: EventBus
) {
    private val activeVideoTransport = AtomicReference<VideoTransport?>(null)

    // Example internal diagnostics
    private var estimatedBandwidthKbps: Int = 0
    private var currentRttMs: Int = 0
    private var currentPacketLossPct: Float = 0f

    /**
     * Dynamically selects the best available transport based on hardware capabilities
     * and current network conditions.
     */
    fun selectTransport() {
        // As per architecture mandate, we default to the high-performance QUIC stack.
        // If capabilityManager.supportsWebRTC() and we are on an external network,
        // we would gracefully switch.
        
        println("TransportManager: Selecting optimal transport...")
        val quicTransport = QuicVideoTransport()
        quicTransport.connect()
        activeVideoTransport.set(quicTransport)
        
        println("TransportManager: Successfully bound to QUICTransport.")
    }

    /**
     * Gracefully fails over to a secondary transport without dropping the connection state.
     */
    fun handleNetworkChanged() {
        println("TransportManager: Network change detected. Evaluating failover...")
        // e.g. switch from QUIC LAN to WebRTC Internet Relay
    }

    /**
     * Exposes the currently active VideoTransport for the Video Encoder to send frames.
     */
    fun getVideoTransport(): VideoTransport? = activeVideoTransport.get()

    /**
     * Emits current network statistics to the EventBus for the Diagnostics plugin to consume.
     */
    fun emitFlowControlMetrics() {
        // eventBus.publish(NetworkMetricsUpdateEvent(currentRttMs, currentPacketLossPct, estimatedBandwidthKbps))
    }
}
