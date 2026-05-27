package com.example.yolarkadasim.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persists favorite routes and stops with optional nicknames.
 */
class FavoritesStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("yolarkadasim_favorites", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Key: RouteID, Value: Set of StopIDs
    private val FAV_ROUTES_KEY = "fav_routes"
    private val FAV_STOPS_KEY = "fav_stops_v2" // Versioned for new data structure

    data class FavoriteStop(
        val routeId: String,
        val stopId: String,
        val customName: String? = null
    )

    fun toggleRouteFavorite(routeId: String) {
        val favs = getFavoriteRoutes().toMutableSet()
        if (favs.contains(routeId)) favs.remove(routeId) else favs.add(routeId)
        prefs.edit().putStringSet(FAV_ROUTES_KEY, favs).apply()
    }

    fun getFavoriteRoutes(): Set<String> {
        return prefs.getStringSet(FAV_ROUTES_KEY, emptySet()) ?: emptySet()
    }

    fun isRouteFavorite(routeId: String): Boolean = getFavoriteRoutes().contains(routeId)

    fun saveFavoriteStop(routeId: String, stopId: String, customName: String?) {
        val favs = getFavoriteStops().toMutableList()
        // Remove if exists
        favs.removeAll { it.routeId == routeId && it.stopId == stopId }
        favs.add(FavoriteStop(routeId, stopId, customName))
        saveStopsList(favs)
    }

    fun removeFavoriteStop(routeId: String, stopId: String) {
        val favs = getFavoriteStops().toMutableList()
        favs.removeAll { it.routeId == routeId && it.stopId == stopId }
        saveStopsList(favs)
    }

    fun getFavoriteStops(): List<FavoriteStop> {
        val json = prefs.getString(FAV_STOPS_KEY, null) ?: return emptyList()
        val type = object : TypeToken<List<FavoriteStop>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getFavoriteStopsForRoute(routeId: String): List<FavoriteStop> {
        return getFavoriteStops().filter { it.routeId == routeId }
    }

    fun isStopFavorite(routeId: String, stopId: String): Boolean {
        return getFavoriteStops().any { it.routeId == routeId && it.stopId == stopId }
    }

    private fun saveStopsList(list: List<FavoriteStop>) {
        val json = gson.toJson(list)
        prefs.edit().putString(FAV_STOPS_KEY, json).apply()
    }
}
