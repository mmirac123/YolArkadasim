package com.example.yolarkadasim.data

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Repository for loading and querying bus route data.
 *
 * Routes are loaded from a JSON file in the app's assets/ folder.
 * This approach keeps the data layer simple (no Room/SQLite) while
 * remaining easy to extend later with real GTFS data or API calls.
 *
 * NO AI/ML libraries are used — all matching is done with standard algorithms.
 */
class RouteRepository(private val context: Context) {

    companion object {
        private const val TAG = "RouteRepository"
        private const val ROUTES_FILE = "routes.json"
    }

    private var cachedRoutes: List<BusRoute>? = null

    /**
     * Load and return all bus routes from assets/routes.json.
     * Results are cached after first load.
     */
    fun getAllRoutes(): List<BusRoute> {
        cachedRoutes?.let { return it }

        val routes = mutableListOf<BusRoute>()
        try {
            val jsonString = context.assets.open(ROUTES_FILE)
                .bufferedReader()
                .use { it.readText() }

            val root = JSONObject(jsonString)
            val routesArray = root.getJSONArray("routes")

            for (i in 0 until routesArray.length()) {
                val routeObj = routesArray.getJSONObject(i)
                val stopsArray = routeObj.getJSONArray("stops")
                val stops = mutableListOf<BusStop>()

                for (j in 0 until stopsArray.length()) {
                    val stopObj = stopsArray.getJSONObject(j)
                    stops.add(
                        BusStop(
                            id = stopObj.getString("id"),
                            name = stopObj.getString("name"),
                            lat = stopObj.getDouble("lat"),
                            lon = stopObj.getDouble("lon")
                        )
                    )
                }

                // Opsiyonel yol geometrisi: [[lat, lon], ...]
                val shape = mutableListOf<LatLon>()
                val shapeArray = routeObj.optJSONArray("shape")
                if (shapeArray != null) {
                    for (k in 0 until shapeArray.length()) {
                        val pt = shapeArray.getJSONArray(k)
                        shape.add(LatLon(pt.getDouble(0), pt.getDouble(1)))
                    }
                }

                routes.add(
                    BusRoute(
                        routeId = routeObj.getString("routeId"),
                        routeName = routeObj.getString("routeName"),
                        stops = stops,
                        shape = shape
                    )
                )
            }

            Log.i(TAG, "Loaded ${routes.size} route(s) with " +
                    "${routes.sumOf { it.stops.size }} total stop(s).")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load routes from $ROUTES_FILE", e)
        }

        cachedRoutes = routes
        return routes
    }

    /**
     * Get a specific route by its ID (e.g., "521").
     */
    fun getRouteById(routeId: String): BusRoute? {
        return getAllRoutes().find { it.routeId == routeId }
    }

    /**
     * Extract the latitude array from a route's stops (for JNI transfer).
     */
    fun getStopLatitudes(route: BusRoute): DoubleArray {
        return route.stops.map { it.lat }.toDoubleArray()
    }

    /**
     * Extract the longitude array from a route's stops (for JNI transfer).
     */
    fun getStopLongitudes(route: BusRoute): DoubleArray {
        return route.stops.map { it.lon }.toDoubleArray()
    }
}
