package com.androiddex.diagnostics

import org.json.JSONObject

/**
 * Compares two benchmark run reports and calculates the delta improvement/degradation.
 * Critical for benchmark-driven optimization.
 */
class BenchmarkComparison {

    fun compare(previousRun: JSONObject, currentRun: JSONObject): JSONObject {
        val comparison = JSONObject()
        
        val metricsToCompare = listOf("EncodeLatency", "DecodeLatency", "GlassToGlassLatency")

        metricsToCompare.forEach { metricName ->
            val prevMetric = previousRun.optJSONObject(metricName)
            val currMetric = currentRun.optJSONObject(metricName)

            if (prevMetric != null && currMetric != null) {
                val metricComparison = JSONObject()
                
                listOf("mean", "p95", "p99").forEach { stat ->
                    val prevVal = prevMetric.optLong(stat, -1L)
                    val currVal = currMetric.optLong(stat, -1L)
                    
                    if (prevVal > 0 && currVal > 0) {
                        val delta = currVal - prevVal
                        val percentage = (delta.toDouble() / prevVal.toDouble()) * 100.0
                        
                        val statComparison = JSONObject().apply {
                            put("previous", prevVal)
                            put("current", currVal)
                            put("delta", delta)
                            put("improvementPercentage", String.format("%.1f%%", percentage))
                        }
                        metricComparison.put(stat, statComparison)
                    }
                }
                comparison.put(metricName, metricComparison)
            }
        }
        
        return comparison
    }
    
    fun printComparisonReport(comparison: JSONObject) {
        println("=== Benchmark Delta Report ===")
        comparison.keys().forEach { metric ->
            println("\n[$metric]")
            val data = comparison.getJSONObject(metric)
            data.keys().forEach { stat ->
                val details = data.getJSONObject(stat)
                val prev = details.getLong("previous")
                val curr = details.getLong("current")
                val improvement = details.getString("improvementPercentage")
                println("  $stat: Prev: ${prev}ms | Curr: ${curr}ms | Delta: $improvement")
            }
        }
    }
}
