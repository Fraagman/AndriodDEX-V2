package com.androiddex.diagnostics

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToInt

/**
 * Phase 2C - Metrics Collection
 * Captures raw latency data points and computes distribution statistics 
 * (Mean, Median, P95, P99) to provide objective benchmark reports.
 */
class MetricsCollector {
    private val encodeLatencies = ConcurrentLinkedQueue<Long>()
    private val decodeLatencies = ConcurrentLinkedQueue<Long>()
    private val glassToGlassLatencies = ConcurrentLinkedQueue<Long>()

    fun recordEncodeLatency(latencyMs: Long) = encodeLatencies.add(latencyMs)
    fun recordDecodeLatency(latencyMs: Long) = decodeLatencies.add(latencyMs)
    fun recordGlassToGlassLatency(latencyMs: Long) = glassToGlassLatencies.add(latencyMs)

    fun computeStats(data: Collection<Long>): Map<String, Long> {
        if (data.isEmpty()) return mapOf("mean" to 0L, "median" to 0L, "p95" to 0L, "p99" to 0L, "min" to 0L, "max" to 0L)

        val sorted = data.sorted()
        val size = sorted.size

        val mean = sorted.average().roundToInt().toLong()
        val median = sorted[size / 2]
        val p95 = sorted[(size * 0.95).toInt().coerceAtMost(size - 1)]
        val p99 = sorted[(size * 0.99).toInt().coerceAtMost(size - 1)]
        val min = sorted.first()
        val max = sorted.last()

        return mapOf(
            "mean" to mean,
            "median" to median,
            "p95" to p95,
            "p99" to p99,
            "min" to min,
            "max" to max
        )
    }

    fun generateFullReport(): Map<String, Map<String, Long>> {
        return mapOf(
            "EncodeLatency" to computeStats(encodeLatencies),
            "DecodeLatency" to computeStats(decodeLatencies),
            "GlassToGlassLatency" to computeStats(glassToGlassLatencies)
        )
    }

    fun clear() {
        encodeLatencies.clear()
        decodeLatencies.clear()
        glassToGlassLatencies.clear()
    }
}
