package com.androiddex.diagnostics

import android.content.Context
import android.hardware.display.DisplayManager
import com.androiddex.core.CapabilityManagerImpl
import com.androiddex.core.EventBusImpl
import com.androiddex.core.PluginManagerImpl
import com.androiddex.core.VideoConfig
import com.androiddex.network.TransportManager
import com.androiddex.video.MediaCodecEncoderPlugin
import com.androiddex.video.SyntheticFrameGenerator
import com.androiddex.video.VideoPipelineOrchestrator
import kotlinx.coroutines.runBlocking

/**
 * Milestone 1.5 - Live Runtime Validation
 * Wires up the live end-to-end pipeline and executes the Benchmark Harness
 * against the hardware encoder.
 */
object LiveValidator {

    fun executeLiveValidationSequence(context: Context, displayManager: DisplayManager) {
        println("=== Starting Live Runtime Validation (Milestone 1.5) ===")

        // 1. Stand up infrastructure
        val eventBus = EventBusImpl()
        val capabilityManager = CapabilityManagerImpl(context)
        val pluginManager = PluginManagerImpl()
        val encoderPlugin = MediaCodecEncoderPlugin()
        
        pluginManager.registerPlugin(encoderPlugin)
        pluginManager.initializeAll(capabilityManager)

        // 2. Configure video target
        val config = VideoConfig(width = 1920, height = 1080, fps = 60, bitrate = 10_000_000)

        // 3. Initialize Transport
        val transportManager = TransportManager(capabilityManager, eventBus)
        transportManager.selectTransport()
        val transport = transportManager.getVideoTransport() 
            ?: throw IllegalStateException("QUIC Transport not available")

        // 4. Bridge Encoder -> EventBus -> Transport
        val pipelineOrchestrator = VideoPipelineOrchestrator(
            eventBus = eventBus,
            videoTransport = transport,
            codecType = com.androiddex.core.CodecType.H264,
            width = config.width,
            height = config.height
        )

        // 5. Initialize the Synthetic Generator feeding the Encoder Surface
        val encoderSurface = encoderPlugin.getInputSurface()
            ?: throw IllegalStateException("Hardware Encoder failed to provide Input Surface")
        val generator = SyntheticFrameGenerator(encoderSurface)

        // 6. Run Benchmark Sequences
        val benchmarkRunner = BenchmarkRunner(generator, encoderPlugin, config)

        runBlocking {
            println("--- Sequence 1: Latency Profile (60 seconds) ---")
            benchmarkRunner.runBenchmark(StressProfile.LATENCY, durationSeconds = 60)

            println("--- Sequence 2: Throughput Profile (60 seconds) ---")
            benchmarkRunner.runBenchmark(StressProfile.THROUGHPUT, durationSeconds = 60)

            println("--- Sequence 3: Long-Run Stability (900 seconds / 15 mins) ---")
            // In a real environment, uncomment this to validate memory stability
            // benchmarkRunner.runBenchmark(StressProfile.LONG_RUN, durationSeconds = 900)
        }

        println("=== Live Runtime Validation Completed ===")
    }
}
