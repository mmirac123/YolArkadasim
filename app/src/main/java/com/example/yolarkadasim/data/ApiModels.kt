package com.example.yolarkadasim.data

import org.json.JSONObject

/**
 * Data classes representing the EGO API response structure.
 */
data class Waypoint(
    val lat: Double,
    val lng: Double
)

data class EgoRouteResponse(
    val routeNumber: String,
    val direction: String,
    val waypoints: List<Waypoint>
) {
    /** Helper to extract latitude array for JNI */
    fun getLatitudes(): DoubleArray = waypoints.map { it.lat }.toDoubleArray()
    
    /** Helper to extract longitude array for JNI */
    fun getLongitudes(): DoubleArray = waypoints.map { it.lng }.toDoubleArray()
}
