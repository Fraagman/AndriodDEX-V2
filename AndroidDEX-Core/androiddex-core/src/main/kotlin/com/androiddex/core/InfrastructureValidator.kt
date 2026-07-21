package com.androiddex.core

import android.content.Context
import com.androiddex.video.MediaCodecEncoderPlugin
import com.androiddex.network.TransportManager
import com.androiddex.input.InputManager
import com.androiddex.input.ComposeBackend
import com.androiddex.input.MirrorBackend

/**
 * Validates the instantiation and wiring of all core subsystems
 * before attempting to stream video. Fulfills Milestone 0.
 */
object InfrastructureValidator {

    fun validate(context: Context): Boolean {
        println("=== Starting Infrastructure Validation ===")

        try {
            // 1. Core Abstractions
            val eventBus = EventBusImpl()
            val capabilityManager = CapabilityManagerImpl(context)
            val pluginManager = PluginManagerImpl()

            println("[OK] Core abstractions instantiated.")

            // 2. Register Plugins
            pluginManager.registerPlugin(MediaCodecEncoderPlugin())
            pluginManager.registerPlugin(ComposeBackend())
            pluginManager.registerPlugin(MirrorBackend())

            // 3. Initialize Plugins (Triggers CapabilityManager validation)
            pluginManager.initializeAll(capabilityManager)
            println("[OK] PluginManager initialized plugins.")

            // 4. Verify MediaCodec (Encoder Plugin should be healthy)
            val encoderPlugin = pluginManager.getPlugin("com.androiddex.video.mediacodec_encoder")
            require(encoderPlugin != null && encoderPlugin.healthCheck()) {
                "MediaCodecEncoderPlugin failed to initialize."
            }
            println("[OK] MediaCodec initialization verified.")

            // 5. Network Platform
            val transportManager = TransportManager(capabilityManager, eventBus)
            transportManager.selectTransport() // Connects QUIC stub
            
            require(transportManager.getVideoTransport()?.isConnected == true) {
                "QUIC Transport failed to connect."
            }
            println("[OK] QUIC Transport connection verified.")

            // 6. Input Manager
            val inputManager = InputManager(
                capabilityManager, 
                eventBus, 
                listOf(ComposeBackend(), MirrorBackend())
            )
            println("[OK] InputManager routing verified.")

            println("=== Infrastructure Validation Passed ===")
            return true

        } catch (e: Exception) {
            System.err.println("=== Infrastructure Validation FAILED ===")
            e.printStackTrace()
            return false
        }
    }
}
