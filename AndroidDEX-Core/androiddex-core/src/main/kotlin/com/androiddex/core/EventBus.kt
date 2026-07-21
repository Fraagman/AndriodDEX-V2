package com.androiddex.core

/**
 * Type-safe decoupled asynchronous Event Bus for pipeline stages.
 */
interface EventBus {
    /** Publishes an event to all subscribers. */
    fun <T : DexEvent> publish(event: T)

    /** Subscribes to a specific event type. */
    fun <T : DexEvent> subscribe(eventType: Class<T>, listener: (T) -> Unit)
    
    /** Unsubscribes a listener. */
    fun <T : DexEvent> unsubscribe(eventType: Class<T>, listener: (T) -> Unit)
}

/** Marker interface for all pipeline events. */
interface DexEvent

// Example Pipeline Events:
data class FrameCapturedEvent(val surfaceBufferId: Long, val timestampNs: Long) : DexEvent
data class FrameEncodedEvent(val nalUnits: ByteArray, val isKeyFrame: Boolean, val timestampUs: Long) : DexEvent
data class NetworkMetricsUpdateEvent(val rttMs: Int, val packetLossPct: Float, val bitrateKbps: Int) : DexEvent
