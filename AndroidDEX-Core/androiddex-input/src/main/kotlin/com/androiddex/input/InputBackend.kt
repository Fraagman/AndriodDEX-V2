package com.androiddex.input

import com.androiddex.core.CapabilityManager
import com.androiddex.core.Plugin

/**
 * Base interface for an Input Backend.
 * Each backend maps universal InputEvents to platform-specific injection logic.
 */
interface InputBackend : Plugin {
    val supportedMode: InputMode

    /** 
     * Indicates if the backend is actively handling events.
     */
    val isActive: Boolean

    /**
     * Activates this backend.
     */
    fun activate()

    /**
     * Deactivates this backend.
     */
    fun deactivate()

    /**
     * Dispatches a unified input event into this specific backend.
     */
    fun dispatchEvent(event: InputEvent)
}
