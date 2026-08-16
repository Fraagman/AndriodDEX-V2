package com.example.androidhost.security

/**
 * Pairing surface of the native QUIC server.
 *
 * The PIN is checked in Rust, not here. The phone cannot tell whether the digits the user
 * typed are correct until the PC proves it derived the same pre-shared key, so
 * [verifyPin] hands the PIN to the native pairing task and blocks until that exchange
 * settles. **Call it off the main thread.**
 *
 * The native methods are declared inside a `companion object` so Kotlin emits them as
 * static natives on this class — the same shape as
 * `com.example.androidhost.quic.QuicServer`, whose bindings are known to resolve.
 */
class SecurityBridge {
    companion object {
        init {
            System.loadLibrary("rust_quic_server")
        }

        /**
         * Submits the PIN the user typed and waits for the verdict.
         *
         * Blocks for as long as the handshake takes, up to roughly 20 seconds. Returns
         * false if the PIN was wrong, if no pairing is in progress, or if the PC gave up.
         */
        fun verifyPin(pin: String): Boolean = nativeVerifyPin(pin)

        /**
         * True while a PC is mid-pairing and the server is waiting for a PIN. The PIN
         * screen polls this to know whether to accept input.
         */
        fun isAwaitingPin(): Boolean = nativeIsAwaitingPin()

        /** True when a pairing key is on record, so a known PC connects without a PIN. */
        fun isPaired(): Boolean = nativeIsPaired()

        /** Forgets the paired PC; the next connection has to pair again. */
        fun forgetPairing() = nativeClearPairing()

        @JvmStatic
        private external fun nativeVerifyPin(pin: String): Boolean

        @JvmStatic
        private external fun nativeIsAwaitingPin(): Boolean

        @JvmStatic
        private external fun nativeIsPaired(): Boolean

        @JvmStatic
        private external fun nativeClearPairing()
    }
}
