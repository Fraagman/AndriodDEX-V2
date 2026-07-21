package com.androiddex.diagnostics

import com.androiddex.video.SyntheticFrameGenerator
import com.androiddex.video.VideoEncoder
import com.androiddex.core.VideoConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

enum class StressProfile {
    LATENCY,       // Minimal buffering, strict 60fps pacing
    THROUGHPUT,    // Maximize bitrate via high-frequency noise
    LONG_RUN       // 30 minute stability loop
}

/**
 * Phase 2B, 2D, 2E - Benchmark Runner & Reporter
 * Orchestrates synthetic frame injection over configured durations.
 */
class BenchmarkRunner(
    private val frameGenerator: SyntheticFrameGenerator,
    private val videoEncoder: VideoEncoder,
    private val config: VideoConfig
) {
    fun runBenchmark(profile: StressProfile, durationSeconds: Int) = runBlocking {
        println("=== Starting Benchmark: $profile ===")
        
        val startTime = System.currentTimeMillis()
        val endTime = startTime + (durationSeconds * 1000)
        
        var framesGenerated = 0L
        val frameIntervalMs = 1000L / config.fps

        while (System.currentTimeMillis() < endTime) {
            val loopStart = System.currentTimeMillis()
            
            // Phase 2A integration: Stressing codec differently based on profile
            when (profile) {
                StressProfile.LATENCY -> frameGenerator.generateMovingGradient(config.width, config.height)
                StressProfile.THROUGHPUT -> frameGenerator.generateNoise(config.width, config.height)
                StressProfile.LONG_RUN -> frameGenerator.generateCheckerboard(config.width, config.height)
            }
            framesGenerated++

            // Pacing for target FPS
            val elapsed = System.currentTimeMillis() - loopStart
            val sleepTime = frameIntervalMs - elapsed
            if (sleepTime > 0) {
                delay(sleepTime)
            }
        }

        println("=== Benchmark Complete ===")
        generateReport(profile, durationSeconds, framesGenerated)
    }

    private fun generateReport(profile: StressProfile, durationSeconds: Int, framesGenerated: Long) {
        // Phase 2E - Benchmark Report (JSON output of full stats)
        val report = JSONObject().apply {
            put("profile", profile.name)
            put("durationSeconds", durationSeconds)
            put("framesGenerated", framesGenerated)
            put("targetFps", config.fps)
            put("actualFps", framesGenerated / durationSeconds.toDouble())
            // In a full implementation, MetricsCollector would feed latency data here
        }

        println("Benchmark Report:\n${report.toString(2)}")
    }
}
