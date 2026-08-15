package com.androiddex.diagnostics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

enum class MetricCategory {
    VIDEO, NETWORK, RENDERER, SYSTEM, SESSION
}

data class TimeSeriesDataPoint(val timestampMs: Long, val value: Double)

/**
 * Persists and aggregates the raw data produced by the MetricsCollector.
 * Allows historical graphing and separation from the active pipeline.
 */
class MetricsRepository {
    private val timeSeriesData = ConcurrentHashMap<String, CopyOnWriteArrayList<TimeSeriesDataPoint>>()
    private val instantaneousData = ConcurrentHashMap<String, String>()

    fun recordTimeSeries(category: MetricCategory, key: String, value: Double) {
        val seriesKey = "${category.name}_$key"
        val series = timeSeriesData.computeIfAbsent(seriesKey) { CopyOnWriteArrayList() }
        series.add(TimeSeriesDataPoint(System.currentTimeMillis(), value))
    }

    fun recordInstantaneous(category: MetricCategory, key: String, value: String) {
        instantaneousData["${category.name}_$key"] = value
    }

    fun getTimeSeries(category: MetricCategory, key: String): List<TimeSeriesDataPoint> {
        return timeSeriesData["${category.name}_$key"]?.toList() ?: emptyList()
    }

    fun getAllInstantaneous(): Map<String, String> {
        return instantaneousData.toMap()
    }

    fun clear() {
        timeSeriesData.clear()
        instantaneousData.clear()
    }
}
