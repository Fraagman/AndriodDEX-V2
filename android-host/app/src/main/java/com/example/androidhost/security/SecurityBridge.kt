package com.example.androidhost.security

object SecurityBridge {
    init {
        System.loadLibrary("rust_quic_server")
    }

    @JvmStatic
    external fun verifyPin(pin: String): Boolean
}
