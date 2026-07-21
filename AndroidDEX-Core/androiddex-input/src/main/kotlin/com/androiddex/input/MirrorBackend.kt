package com.androiddex.input

import com.androiddex.core.CapabilityManager

class MirrorBackend : InputBackend {
    override val id = "com.androiddex.input.mirror"
    override val version = "1.0.0"
    override val supportedMode = InputMode.MIRROR_MODE

    private var active = false
    override val isActive: Boolean get() = active

    override fun validate(capabilityManager: CapabilityManager): Boolean {
        // Mirroring is always available (View-only)
        return true
    }

    override fun initialize() {}

    override fun healthCheck(): Boolean = true

    override fun shutdown() {
        deactivate()
    }

    override fun activate() {
        active = true
        println("MirrorBackend: Activated (View Only).")
    }

    override fun deactivate() {
        active = false
        println("MirrorBackend: Deactivated.")
    }

    override fun dispatchEvent(event: InputEvent) {
        // Intentionally drops all input events (View-Only mode)
    }
}
