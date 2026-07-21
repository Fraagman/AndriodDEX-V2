package com.androiddex.input

import com.androiddex.core.CapabilityManager
import com.androiddex.core.EventBus

/**
 * Orchestrates the active InputMode and routes events to the appropriate backend.
 * Subscribes to the EventBus to receive incoming inputs from the Network Transport.
 */
class InputManager(
    private val capabilityManager: CapabilityManager,
    private val eventBus: EventBus,
    private val backends: List<InputBackend>
) {
    private var currentMode: InputMode = InputMode.DISCONNECTED
    private var activeBackend: InputBackend? = null

    init {
        // Subscribe to all mapped InputEvents coming from the receiver
        eventBus.subscribe(PointerEvent::class.java, ::handleEvent)
        eventBus.subscribe(KeyboardEvent::class.java, ::handleEvent)
        eventBus.subscribe(ScrollEvent::class.java, ::handleEvent)
    }

    /**
     * Safely transitions to a new InputMode, gracefully degrading if capabilities are missing.
     */
    fun setMode(requestedMode: InputMode) {
        val targetBackend = backends.find { it.supportedMode == requestedMode }

        if (targetBackend == null || !targetBackend.validate(capabilityManager)) {
            eventBus.publish(BackendUnavailableEvent(requestedMode.name, "Capabilities not met or Backend missing."))
            // Degrade to Mirror (View-Only) if Advanced Mode fails
            if (requestedMode == InputMode.ADVANCED_MODE) {
                setMode(InputMode.MIRROR_MODE)
            }
            return
        }

        activeBackend?.deactivate()
        activeBackend = targetBackend
        currentMode = requestedMode
        activeBackend?.activate()

        eventBus.publish(ModeChangedEvent(currentMode))
    }

    private fun handleEvent(event: InputEvent) {
        activeBackend?.dispatchEvent(event)
    }
}
