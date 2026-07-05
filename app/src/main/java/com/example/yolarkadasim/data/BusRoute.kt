package com.example.yolarkadasim.data

/**
 * Represents a single bus stop with its geographic coordinates.
 *
 * @property id     Unique stop identifier (e.g., "s1", "s2")
 * @property name   Human-readable stop name (e.g., "Fatih Metro")
 * @property lat    Latitude in decimal degrees (WGS-84)
 * @property lon    Longitude in decimal degrees (WGS-84)
 */
data class BusStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double
)

/**
 * Basit koordinat çifti — güzergâhın yol geometrisi için (osmdroid bağımlılığı
 * veri katmanına sızmasın diye ayrı tip).
 */
data class LatLon(
    val lat: Double,
    val lon: Double
)

/**
 * Represents a bus route with an ordered list of stops.
 *
 * The stop order defines the route direction:
 * - Index 0 = first stop (origin)
 * - Last index = final stop (terminus)
 *
 * To determine whether a user is traveling in the correct direction,
 * the C++ layer compares the nearest-stop index progression against
 * the destination stop index within this ordered list.
 *
 * @property routeId    Route number/code (e.g., "521")
 * @property routeName  Human-readable route name (e.g., "Fatih – Ümitköy")
 * @property stops      Ordered list of stops from origin to terminus
 * @property shape      Gerçek yol geometrisi (OSM'den); boşsa harita durak-durak
 *                      düz çizgiye düşer
 */
data class BusRoute(
    val routeId: String,
    val routeName: String,
    val stops: List<BusStop>,
    val shape: List<LatLon> = emptyList()
) {
    /** First stop name (origin) */
    val originName: String get() = stops.firstOrNull()?.name ?: ""

    /** Last stop name (terminus) */
    val terminusName: String get() = stops.lastOrNull()?.name ?: ""
}
