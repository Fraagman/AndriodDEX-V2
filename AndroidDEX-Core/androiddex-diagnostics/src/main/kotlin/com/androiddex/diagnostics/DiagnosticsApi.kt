package com.androiddex.diagnostics

import org.json.JSONArray
import org.json.JSONObject

/**
 * Decouples the UI/Dashboard from the MetricsRepository.
 * Supports exporting JSON for external telemetry (Prometheus, CSV, etc).
 */
class DiagnosticsApi(private val repository: MetricsRepository) {

    fun getDashboardSnapshot(): JSONObject {
        val snapshot = JSONObject()
        val instantaneous = repository.getAllInstantaneous()
        
        instantaneous.forEach { (key, value) ->
            val parts = key.split("_", limit = 2)
            if (parts.size == 2) {
                val category = parts[0]
                val metric = parts[1]
                
                val categoryObj = snapshot.optJSONObject(category) ?: JSONObject().also { snapshot.put(category, it) }
                
                // Enforce Performance Budgets (Milestone 5)
                val status = evaluateBudgetStatus(metric, value.toLongOrNull() ?: 0L)
                val metricData = JSONObject().apply {
                    put("value", value)
                    put("status", status)
                }
                categoryObj.put(metric, metricData)
            }
        }
        return snapshot
    }

    private fun evaluateBudgetStatus(metricName: String, latency: Long): String {
        return when {
            metricName.contains("CaptureLatency", ignoreCase = true) -> if (latency <= 3) "✅" else "❌"
            metricName.contains("EncodeLatency", ignoreCase = true) -> if (latency <= 10) "✅" else "❌"
            metricName.contains("TransportLatency", ignoreCase = true) -> if (latency <= 10) "✅" else "❌"
            metricName.contains("JitterLatency", ignoreCase = true) -> if (latency <= 5) "✅" else "❌"
            metricName.contains("DecodeLatency", ignoreCase = true) -> if (latency <= 10) "✅" else "❌"
            metricName.contains("RenderLatency", ignoreCase = true) -> if (latency <= 8) "✅" else "❌"
            else -> "N/A"
        }
    }

    fun exportHistoricalData(category: MetricCategory, key: String): JSONArray {
        val series = repository.getTimeSeries(category, key)
        val array = JSONArray()
        
        series.forEach { point ->
            val obj = JSONObject()
            obj.put("t", point.timestampMs)
            obj.put("v", point.value)
            array.put(obj)
        }
        return array
    }
}
