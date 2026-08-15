package com.example.androidhost.service

/**
 * Lifecycle of the on-device compute engine backing [NativeComputeService].
 *
 * [UNSUPPORTED] is reported on devices where the engine cannot start at all; the shell
 * treats it the same as [OFF] and simply does not offer the toggle.
 */
enum class ComputeState {
    OFF,
    STARTING,
    RUNNING,
    STOPPED,
    UNSUPPORTED
}
