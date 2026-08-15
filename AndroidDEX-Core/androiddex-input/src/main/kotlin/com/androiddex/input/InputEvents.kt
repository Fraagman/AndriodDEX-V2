package com.androiddex.input

import com.androiddex.core.DexEvent

sealed interface InputEvent : DexEvent

data class PointerEvent(
    val x: Float,
    val y: Float,
    val action: Action
) : InputEvent {
    enum class Action { DOWN, MOVE, UP }
}

data class KeyboardEvent(
    val keyCode: Int,
    val isDown: Boolean
) : InputEvent

data class ScrollEvent(
    val scrollX: Float,
    val scrollY: Float
) : InputEvent

data class ModeChangedEvent(val newMode: InputMode) : DexEvent
data class BackendUnavailableEvent(val backendId: String, val reason: String) : DexEvent

enum class InputMode {
    DISCONNECTED,
    DESKTOP_MODE,
    MIRROR_MODE,
    PAUSED
}
