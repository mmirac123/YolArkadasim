package com.example.yolarkadasim.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight SharedPreferences wrapper for persisting the user's
 * most recently selected destination (route + stop).
 *
 * Storing both route and stop info allows the "Son Hedef" shortcut
 * to bypass both selection steps in a single tap.
 */
class RecentDestinationStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "yolarkadasim_recent_dest"
        private const val KEY_ROUTE_ID = "route_id"
        private const val KEY_ROUTE_NAME = "route_name"
        private const val KEY_STOP_ID = "stop_id"
        private const val KEY_STOP_NAME = "stop_name"
        private const val KEY_STOP_INDEX = "stop_index"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Save the user's selected destination. Called when tracking starts.
     */
    fun save(route: BusRoute, stop: BusStop, stopIndex: Int) {
        prefs.edit()
            .putString(KEY_ROUTE_ID, route.routeId)
            .putString(KEY_ROUTE_NAME, route.routeName)
            .putString(KEY_STOP_ID, stop.id)
            .putString(KEY_STOP_NAME, stop.name)
            .putInt(KEY_STOP_INDEX, stopIndex)
            .apply()
    }

    /**
     * Load the last destination, or null if none has been saved yet.
     */
    fun load(): RecentDestination? {
        val routeId = prefs.getString(KEY_ROUTE_ID, null) ?: return null
        val stopName = prefs.getString(KEY_STOP_NAME, null) ?: return null
        return RecentDestination(
            routeId = routeId,
            routeName = prefs.getString(KEY_ROUTE_NAME, "") ?: "",
            stopId = prefs.getString(KEY_STOP_ID, "") ?: "",
            stopName = stopName,
            stopIndex = prefs.getInt(KEY_STOP_INDEX, -1)
        )
    }

    /**
     * Clear saved destination (e.g., if routes.json changes).
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
}

/**
 * Immutable snapshot of a previously selected destination.
 */
data class RecentDestination(
    val routeId: String,
    val routeName: String,
    val stopId: String,
    val stopName: String,
    val stopIndex: Int
)
