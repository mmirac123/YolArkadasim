package com.example.yolarkadasim.ui

import android.graphics.Color
import android.util.Log
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import com.example.yolarkadasim.R
import com.example.yolarkadasim.data.BusRoute
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * osmdroid harita etkileşiminin tamamını kapsar: güzergâh/durak çizimi,
 * canlı kullanıcı konumu ve "beni izle" modu. MainActivity'yi harita
 * ayrıntılarından arındırmak için ayrıldı.
 *
 * @param mapView       düzenden gelen MapView
 * @param followMeFab   izleme kapalıyken beliren "konumuma dön" butonu
 */
class MapController(
    private val mapView: MapView,
    private val followMeFab: FloatingActionButton
) {
    private val context get() = mapView.context

    private var userMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private val stopMarkers = mutableListOf<Marker>()

    /** Takipte harita kullanıcıyı ortalar; elle kaydırınca kapanır. */
    var followUser = true
        private set

    // Fab recenter'ın kullanacağı son bilinen konum (Activity'nin lastLat/lon'undan bağımsız kopya)
    private var lastLat = 0.0
    private var lastLon = 0.0

    fun setup() {
        try {
            mapView.setTileSource(TileSourceFactory.MAPNIK)
            mapView.setMultiTouchControls(true)
            mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.ALWAYS)
            mapView.controller.setZoom(15.0)
            mapView.controller.setCenter(GeoPoint(39.9334, 32.8597))

            // ODbL zorunluluğu: "© OpenStreetMap katkıcıları" atıfı harita üzerinde
            // görünür olmalı. CopyrightOverlay bunu köşede gösterir.
            mapView.overlays.add(CopyrightOverlay(context))

            // Kullanıcı haritayı elle oynatınca izleme modundan çık.
            // (MapListener programatik hareketlerde de tetiklendiği için
            // dokunma olayı üzerinden ayırt ediyoruz.)
            mapView.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN && followUser) {
                    followUser = false
                    followMeFab.show()
                }
                false // haritanın kendi dokunma işleyişi devam etsin
            }
            followMeFab.setOnClickListener {
                followUser = true
                followMeFab.hide()
                if (lastLat != 0.0) mapView.controller.animateTo(GeoPoint(lastLat, lastLon))
            }
        } catch (e: Exception) { Log.e(TAG, "Map setup fail", e) }
    }

    fun onResume() { try { mapView.onResume() } catch (e: Exception) {} }
    fun onPause() { try { mapView.onPause() } catch (e: Exception) {} }
    fun onDetach() { try { mapView.onDetach() } catch (e: Exception) {} }

    /** Yeni yolculuk başlarken izlemeyi tekrar aç. */
    fun resetFollow() {
        followUser = true
        followMeFab.hide()
    }

    fun updateUserPosition(lat: Double, lon: Double) {
        try {
            if (lat == 0.0) return
            lastLat = lat
            lastLon = lon
            val userPos = GeoPoint(lat, lon)
            if (userMarker == null) {
                userMarker = Marker(mapView).apply {
                    title = "Siz"
                    // Hedef pininden ayrışsın diye kullanıcı farklı ikonla gösterilir
                    icon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_mylocation)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                mapView.overlays.add(userMarker)
                mapView.controller.setZoom(16.0)
                mapView.controller.animateTo(userPos)
            } else {
                userMarker?.position = userPos
                if (followUser) mapView.controller.animateTo(userPos)
            }
            mapView.invalidate()
        } catch (e: Exception) { Log.e(TAG, "Map user fail", e) }
    }

    fun clearUserMarker() {
        userMarker?.let { try { mapView.overlays.remove(it) } catch (e: Exception) {} }
        userMarker = null
        mapView.invalidate()
    }

    /**
     * Güzergâhı ve durakları çizer. Gerçek yol geometrisi varsa onu, yoksa
     * durak-durak düz çizgiyi kullanır. Ara duraklar küçük nokta, hedef tek pin.
     */
    fun drawRoute(route: BusRoute, destinationIndex: Int, isTracking: Boolean) {
        try {
            routePolyline?.let { mapView.overlays.remove(it) }
            mapView.overlays.removeAll(stopMarkers)
            stopMarkers.clear()

            val points = if (route.shape.isNotEmpty()) route.shape.map { GeoPoint(it.lat, it.lon) }
                         else route.stops.map { GeoPoint(it.lat, it.lon) }
            if (points.isEmpty()) return

            routePolyline = Polyline().apply {
                setPoints(points)
                outlinePaint.color = Color.parseColor("#2196F3")
                outlinePaint.strokeWidth = 10f
            }
            mapView.overlays.add(routePolyline)

            val dotIcon = ContextCompat.getDrawable(context, R.drawable.ic_stop_dot)
            route.stops.forEachIndexed { index, stop ->
                val marker = Marker(mapView)
                marker.position = GeoPoint(stop.lat, stop.lon)
                marker.title = stop.name
                if (index == destinationIndex) {
                    marker.subDescription = "HEDEF"
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                } else {
                    marker.icon = dotIcon
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                stopMarkers.add(marker)
            }
            mapView.overlays.addAll(stopMarkers)

            // Takipte ve izleme modundayken tüm güzergâha uzaklaşma; kamera kullanıcıda kalsın
            if (points.size > 1 && !(isTracking && followUser)) {
                val bounds = BoundingBox.fromGeoPoints(points)
                mapView.post {
                    try {
                        if (mapView.width > 0) mapView.zoomToBoundingBox(bounds, true, 100)
                    } catch (e: Exception) {}
                }
            }
            mapView.invalidate()
        } catch (e: Exception) { Log.e(TAG, "Map route fail", e) }
    }

    companion object { private const val TAG = "MapController" }
}
