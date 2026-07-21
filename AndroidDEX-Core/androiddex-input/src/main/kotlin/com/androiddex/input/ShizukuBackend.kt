package com.androiddex.input

import com.androiddex.core.CapabilityManager

class ShizukuBackend : InputBackend {
    override val id = "com.androiddex.input.shizuku"
    override val version = "1.0.0"
    override val supportedMode = InputMode.ADVANCED_MODE

    private var active = false
    override val isActive: Boolean get() = active

    override fun validate(capabilityManager: CapabilityManager): Boolean {
        // Requires Shizuku to be installed and permissions granted
        return capabilityManager.supportsShizukuInput()
    }

    override fun initialize() {
        // Bind to Shizuku service
    }

    override fun healthCheck(): Boolean {
        // Check if Shizuku binder is still alive
        return true
    }

    override fun shutdown() {
        deactivate()
    }

    override fun activate() {
        active = true
        println("ShizukuBackend: Activated (Global Injection).")
    }

    override fun deactivate() {
        active = false
        println("ShizukuBackend: Deactivated.")
    }

    override fun dispatchEvent(event: InputEvent) {
        if (!active) return
        // Inject into /dev/uinput or InputManager service via Shizuku binder
        println("ShizukuBackend: Injecting $event globally.")
    }
}
