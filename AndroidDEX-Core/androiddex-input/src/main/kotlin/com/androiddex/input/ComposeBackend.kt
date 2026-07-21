package com.androiddex.input

import com.androiddex.core.CapabilityManager

class ComposeBackend : InputBackend {
    override val id = "com.androiddex.input.compose"
    override val version = "1.0.0"
    override val supportedMode = InputMode.DESKTOP_MODE

    private var active = false
    override val isActive: Boolean get() = active

    override fun validate(capabilityManager: CapabilityManager): Boolean {
        // Compose Desktop mode is always available (no special privileges needed)
        return true
    }

    override fun initialize() {}

    override fun healthCheck(): Boolean = true

    override fun shutdown() {
        deactivate()
    }

    override fun activate() {
        active = true
        println("ComposeBackend: Activated.")
    }

    override fun deactivate() {
        active = false
        println("ComposeBackend: Deactivated.")
    }

    override fun dispatchEvent(event: InputEvent) {
        if (!active) return
        // Inject into our own Compose View hierarchy
        println("ComposeBackend: Dispatching $event to Compose UI.")
    }
}
