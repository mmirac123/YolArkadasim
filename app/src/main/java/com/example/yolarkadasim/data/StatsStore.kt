package com.example.yolarkadasim.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores and manages user travel statistics.
 */
class StatsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("yolarkadasim_stats", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TOTAL_TRIPS = "total_trips"
        private const val KEY_TOTAL_DISTANCE = "total_distance_m"
        private const val KEY_TOTAL_STOPS = "total_stops"
        private const val KEY_MOST_USED_ROUTE = "most_used_route"
    }

    fun incrementTrips() {
        val current = prefs.getInt(KEY_TOTAL_TRIPS, 0)
        prefs.edit().putInt(KEY_TOTAL_TRIPS, current + 1).apply()
    }

    fun addDistance(meters: Double) {
        val current = prefs.getFloat(KEY_TOTAL_DISTANCE, 0f)
        prefs.edit().putFloat(KEY_TOTAL_DISTANCE, current + meters.toFloat()).apply()
    }

    fun incrementStops() {
        val current = prefs.getInt(KEY_TOTAL_STOPS, 0)
        prefs.edit().putInt(KEY_TOTAL_STOPS, current + 1).apply()
    }

    fun recordRouteUsage(routeId: String) {
        val counts = getRouteUsageCounts().toMutableMap()
        counts[routeId] = (counts[routeId] ?: 0) + 1
        
        // Save back as a simple comma-separated string or multiple keys
        // For simplicity, we'll just track the single top route for now
        val topRoute = counts.maxByOrNull { it.value }?.key
        prefs.edit().putString(KEY_MOST_USED_ROUTE, topRoute).apply()
        
        // Save the map
        val serialized = counts.entries.joinToString(",") { "${it.key}:${it.value}" }
        prefs.edit().putString("route_counts", serialized).apply()
    }

    fun incrementWrongDirection() {
        val current = prefs.getInt("total_wrong_direction", 0)
        prefs.edit().putInt("total_wrong_direction", current + 1).apply()
    }

    fun addGpsDeviationBatch(sum: Double, count: Int) {
        if (count == 0) return
        val currentSum = prefs.getFloat("total_gps_dev_sum", 0f)
        val currentCount = prefs.getInt("total_gps_dev_count", 0)
        prefs.edit()
            .putFloat("total_gps_dev_sum", currentSum + sum.toFloat())
            .putInt("total_gps_dev_count", currentCount + count)
            .apply()
    }

    fun addBatteryDrop(dropPct: Float) {
        if (dropPct > 0) {
            val current = prefs.getFloat("total_battery_drop_pct", 0f)
            prefs.edit().putFloat("total_battery_drop_pct", current + dropPct).apply()
        }
    }

    fun getTotalTrips(): Int = prefs.getInt(KEY_TOTAL_TRIPS, 0)
    fun getTotalDistanceKm(): Double = prefs.getFloat(KEY_TOTAL_DISTANCE, 0f).toDouble() / 1000.0
    fun getTotalStops(): Int = prefs.getInt(KEY_TOTAL_STOPS, 0)
    fun getMostUsedRoute(): String = prefs.getString(KEY_MOST_USED_ROUTE, "Yok") ?: "Yok"

    fun getWrongDirectionCount(): Int = prefs.getInt("total_wrong_direction", 0)
    
    fun getAverageGpsDeviation(): Double {
        val sum = prefs.getFloat("total_gps_dev_sum", 0f)
        val count = prefs.getInt("total_gps_dev_count", 0)
        return if (count > 0) (sum / count).toDouble() else 0.0
    }
    
    fun getTotalBatteryConsumedPct(): Float = prefs.getFloat("total_battery_drop_pct", 0f)

    /**
     * Tüm metrikleri akademik kullanım için CSV olarak üretir.
     * Sayısal format Locale.US ile sabitlenir ki ondalık ayracı her cihazda nokta olsun.
     */
    fun buildCsvExport(): String {
        val sb = StringBuilder()
        sb.appendLine("metric,value")
        sb.appendLine("total_trips,${getTotalTrips()}")
        sb.appendLine("total_distance_km,${String.format(java.util.Locale.US, "%.3f", getTotalDistanceKm())}")
        sb.appendLine("total_stops_passed,${getTotalStops()}")
        sb.appendLine("most_used_route,${getMostUsedRoute()}")
        sb.appendLine("wrong_direction_events,${getWrongDirectionCount()}")
        sb.appendLine("avg_gps_deviation_m,${String.format(java.util.Locale.US, "%.2f", getAverageGpsDeviation())}")
        sb.appendLine("total_battery_consumed_pct,${String.format(java.util.Locale.US, "%.1f", getTotalBatteryConsumedPct())}")
        for ((routeId, count) in getRouteUsageCounts()) {
            sb.appendLine("route_usage_$routeId,$count")
        }
        return sb.toString()
    }

    private fun getRouteUsageCounts(): Map<String, Int> {
        val data = prefs.getString("route_counts", "") ?: ""
        if (data.isBlank()) return emptyMap()
        
        val map = mutableMapOf<String, Int>()
        try {
            data.split(",").forEach {
                val item = it.trim()
                if (item.isNotEmpty() && item.contains(":")) {
                    val parts = item.split(":")
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim().toIntOrNull()
                        if (value != null && key.isNotEmpty()) {
                            map[key] = value
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore corruption
        }
        return map
    }
}
