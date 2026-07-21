package com.androiddex.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class EventBusImpl : EventBus {
    private val subscribers = ConcurrentHashMap<Class<out DexEvent>, CopyOnWriteArraySet<(DexEvent) -> Unit>>()

    override fun <T : DexEvent> publish(event: T) {
        val eventClass = event::class.java
        subscribers[eventClass]?.forEach { listener ->
            listener(event)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : DexEvent> subscribe(eventType: Class<T>, listener: (T) -> Unit) {
        val set = subscribers.computeIfAbsent(eventType) { CopyOnWriteArraySet() }
        set.add(listener as (DexEvent) -> Unit)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : DexEvent> unsubscribe(eventType: Class<T>, listener: (T) -> Unit) {
        subscribers[eventType]?.remove(listener as (DexEvent) -> Unit)
    }
}
