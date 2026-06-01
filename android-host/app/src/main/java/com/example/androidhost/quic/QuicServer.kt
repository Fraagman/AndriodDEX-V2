package com.example.androidhost.quic

class QuicServer {
    companion object {
        var handle: Long = 0
            private set
            
        init {
            System.loadLibrary("rust_quic_server")
        }

        fun startServer(port: Int, dataPath: String) {
            if (handle == 0L) {
                handle = start(port, dataPath)
                android.util.Log.d("QuicServer", "QUIC server started, handle = $handle")
            }
        }

        fun sendFrame(data: ByteArray) {
            if (handle != 0L) {
                send(handle, data)
            }
        }

        fun pollInput(buffer: ByteArray): Int {
            return if (handle != 0L) {
                pollData(handle, buffer)
            } else {
                0
            }
        }

        /**
         * Returns the current connection state from the Rust QUIC server:
         *   0 = STATE_IDLE (server waiting for connection)
         *   1 = STATE_PAIRING (connection received, waiting for PIN)
         *   2 = STATE_AUTHENTICATED (PIN verified, sending frames)
         *   3 = STATE_DISCONNECTED (connection was lost)
         */
        fun getConnectionState(): Int {
            return if (handle != 0L) {
                connectionState(handle)
            } else {
                0
            }
        }

        @JvmStatic
        private external fun start(port: Int, dataPath: String): Long

        @JvmStatic
        private external fun pollData(handle: Long, buffer: ByteArray): Int

        @JvmStatic
        private external fun send(handle: Long, data: ByteArray)

        @JvmStatic
        private external fun connectionState(handle: Long): Int
    }
}
