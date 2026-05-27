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

    fun getTotalTrips(): Int = prefs.getInt(KEY_TOTAL_TRIPS, 0)
    fun getTotalDistanceKm(): Double = prefs.getFloat(KEY_TOTAL_DISTANCE, 0f).toDouble() / 1000.0
    fun getTotalStops(): Int = prefs.getInt(KEY_TOTAL_STOPS, 0)
    fun getMostUsedRoute(): String = prefs.getString(KEY_MOST_USED_ROUTE, "Yok") ?: "Yok"

    private fun getRouteUsageCounts(): Map<String, Int> {
        val data = prefs.getString("route_counts", "") ?: ""
        if (data.isEmpty()) return emptyMap()
        return data.split(",").associate {
            val parts = it.split(":")
            parts[0] to parts[1].toInt()
        }
    }
}
