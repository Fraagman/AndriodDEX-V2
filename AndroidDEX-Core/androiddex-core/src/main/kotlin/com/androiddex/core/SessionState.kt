package com.androiddex.core

/**
 * Represents the discrete stages of the connection lifecycle.
 * Managed by the SessionManager to ensure predictable behavior.
 */
enum class SessionState {
    IDLE,
    DISCOVERING,
    PAIRING,
    CONNECTING,
    AUTHENTICATING,
    STREAMING,
    RECONNECTING,
    DISCONNECTED
}

/**
 * Listens for changes in the overall session state.
 */
interface SessionStateListener {
    fun onStateChanged(previousState: SessionState, newState: SessionState)
}
