package com.example.androidhost.security

/**
 * Pairing surface of the native QUIC server (Protocol v2).
 *
 * The phone no longer receives a PIN. The native server computes a 6-digit
 * Short Authentication String (SAS) derived from the ECDH key exchange and TLS channel
 * binding.
 *
 * [confirmPairing] submits the user's decision ("Codes match" -> true, "They're different" -> false)
 * and blocks until the verdict settles. **Call it off the main thread.**
 *
 * The native methods are declared inside a `companion object` so Kotlin emits them as
 * static natives on this class.
 */
class SecurityBridge {
    companion object {
        init {
            System.loadLibrary("rust_quic_server")
        }

        /**
         * Confirms (matched = true) or rejects (matched = false) the pairing code.
         * Blocks until the native handshake decides or times out.
         */
        fun confirmPairing(matched: Boolean): Boolean = nativeConfirmPairing(matched)

        /**
         * Reads the pending 6-digit SAS code, or returns null if no pairing is awaiting confirmation.
         */
        fun getPendingSas(): String? = nativeGetPendingSas()

        /**
         * True while a PC is mid-pairing and the phone is waiting for the user to confirm the SAS.
         */
        fun isAwaitingConfirmation(): Boolean = nativeIsAwaitingConfirmation()

        /** True when a pairing key is on record, so a known PC connects without confirmation. */
        fun isPaired(): Boolean = nativeIsPaired()

        /** Forgets the paired PC; the next connection has to pair again. */
        fun forgetPairing() = nativeClearPairing()

        @JvmStatic
        private external fun nativeConfirmPairing(matched: Boolean): Boolean

        @JvmStatic
        private external fun nativeGetPendingSas(): String?

        @JvmStatic
        private external fun nativeIsAwaitingConfirmation(): Boolean

        @JvmStatic
        private external fun nativeIsPaired(): Boolean

        @JvmStatic
        private external fun nativeClearPairing()
    }
}
