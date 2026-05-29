package com.example.yolarkadasim

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.yolarkadasim.data.BusRoute
import com.example.yolarkadasim.data.BusStop
import com.example.yolarkadasim.data.RecentDestinationStore
import com.example.yolarkadasim.data.RouteRepository
import com.example.yolarkadasim.data.SettingsStore
import com.example.yolarkadasim.data.StatsStore
import com.example.yolarkadasim.databinding.ActivityMainBinding
import com.example.yolarkadasim.service.TrackingService
import com.example.yolarkadasim.ui.RouteSelectionDialog
import com.google.android.material.slider.Slider
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var routeRepository: RouteRepository
    private lateinit var recentDestStore: RecentDestinationStore
    private lateinit var statsStore: StatsStore
    private lateinit var settingsStore: SettingsStore
    
    private var trackingService: TrackingService? = null
    private var isBound = false
    private var isTracking = false

    private var selectedRoute: BusRoute? = null
    private var selectedDestinationStop: BusStop? = null
    private var destinationStopIndex: Int = -1

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private var speechRecognizer: SpeechRecognizer? = null
    private var speechIntent: Intent? = null

    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastCurIdx = -1
    private var lastDistance = 0.0
    private var lastStartIdx = -1

    private var userMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private val stopMarkers = mutableListOf<Marker>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val binder = service as TrackingService.TrackingBinder
                trackingService = binder.getService()
                isBound = true
                
                trackingService?.onUpdate = { lat, lon, curIdx, deviation, direction, distance, startIdx ->
                    handleUpdate(lat, lon, curIdx, deviation, direction, distance, startIdx)
                }
            } catch (e: Exception) { Log.e("MainActivity", "Service bind fail", e) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            trackingService = null
        }
    }

    private fun handleUpdate(lat: Double, lon: Double, curIdx: Int, deviation: Double, direction: Int, distance: Double, startIdx: Int) {
        try {
            if (curIdx > lastCurIdx && lastCurIdx != -1) {
                statsStore.incrementStops()
                if (distance.isFinite()) statsStore.addDistance(distance) 
            }
            lastLat = lat
            lastLon = lon
            lastCurIdx = curIdx
            lastDistance = if (distance.isFinite()) distance else 0.0
            lastStartIdx = startIdx
            
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    updateUi(lat, lon, curIdx, deviation, lastDistance, startIdx, direction)
                }
            }
        } catch (e: Exception) { Log.e("MainActivity", "Update loop fail", e) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // OSM Configuration
            Configuration.getInstance().userAgentValue = packageName
            Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            
            routeRepository = RouteRepository(this)
            recentDestStore = RecentDestinationStore(this)
            statsStore = StatsStore(this)
            settingsStore = SettingsStore(this)
            tts = TextToSpeech(this, this)
            
            setupMap()
            
            val startInModern = settingsStore.isModernModePreferred()
            binding.switchUiMode.isChecked = startInModern
            updateUiMode(startInModern)

            setupUiModeSwitcher()
            setupBottomNavigation()
            setupDestinationButtons()
            setupTrackingButtons()
            setupVoiceCommands()
            setupSettingsPage()

            requestPermissions()
        } catch (e: Exception) {
            Log.e("MainActivity", "CRITICAL ONCREATE FAIL", e)
            Toast.makeText(this, "Uygulama başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupMap() {
        try {
            binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
            binding.mapView.setMultiTouchControls(true)
            binding.mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.ALWAYS)
            binding.mapView.controller.setZoom(15.0)
            binding.mapView.controller.setCenter(GeoPoint(39.9334, 32.8597))
        } catch (e: Exception) { Log.e("MainActivity", "Map setup fail", e) }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavModern.setOnItemSelectedListener { item ->
            if (isFinishing || isDestroyed) return@setOnItemSelectedListener false
            binding.modernTrackingView.visibility = if (item.itemId == R.id.nav_tracking) View.VISIBLE else View.GONE
            binding.modernMapView.visibility = if (item.itemId == R.id.nav_map) View.VISIBLE else View.GONE
            binding.modernStatsView.visibility = if (item.itemId == R.id.nav_stats) View.VISIBLE else View.GONE
            binding.modernSettingsView.visibility = if (item.itemId == R.id.nav_settings) View.VISIBLE else View.GONE
            
            if (item.itemId == R.id.nav_stats) refreshStatsUi()
            if (item.itemId == R.id.nav_map) {
                updateMapUserPosition()
                if (isTracking) updateMapRoute()
            }
            true
        }
    }

    private fun setupSettingsPage() {
        binding.switchStartupMode.isChecked = settingsStore.isModernModePreferred()
        binding.switchStartupMode.setOnCheckedChangeListener { _, isChecked -> settingsStore.setModernModePreferred(isChecked) }
        binding.switchVoiceGuidance.isChecked = settingsStore.isVoiceGuidanceEnabled()
        binding.switchVoiceGuidance.setOnCheckedChangeListener { _, isChecked -> settingsStore.setVoiceGuidanceEnabled(isChecked) }
        binding.sliderVoiceLevel.value = settingsStore.getVoiceLevel().toFloat().coerceIn(0f, 100f)
        binding.sliderVoiceLevel.setLabelFormatter { value -> value.toInt().toString() }
        binding.sliderVoiceLevel.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) { settingsStore.setVoiceLevel(slider.value.toInt()) }
        })
    }

    private fun refreshStatsUi() {
        try {
            binding.valTotalTrips.text = statsStore.getTotalTrips().toString()
            binding.valTotalDist.text = String.format(Locale.US, "%.1f km", statsStore.getTotalDistanceKm())
            binding.valTotalStops.text = statsStore.getTotalStops().toString()
            binding.valFavRoute.text = statsStore.getMostUsedRoute()
        } catch (e: Exception) { Log.e("MainActivity", "Stats refresh error", e) }
    }

    override fun onStart() {
        super.onStart()
        try {
            Intent(this, TrackingService::class.java).also { intent ->
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        } catch (e: Exception) { Log.e("MainActivity", "Start bind fail", e) }
    }

    override fun onResume() {
        super.onResume()
        try { binding.mapView.onResume() } catch (e: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try { binding.mapView.onPause() } catch (e: Exception) {}
    }

    override fun onStop() {
        if (isBound) {
            try { unbindService(serviceConnection) } catch (e: Exception) {}
            isBound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        try { speechRecognizer?.destroy() } catch (e: Exception) {}
        speechRecognizer = null
        try { tts?.shutdown() } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("tr", "TR"))
            isTtsReady = true
            if (!settingsStore.isModernModePreferred() && settingsStore.isVoiceGuidanceEnabled()) {
                binding.root.postDelayed({
                    if (!isFinishing && !isDestroyed) speakMessage("Yol Arkadaşım uygulamasına hoş geldiniz. Kolay mod aktif.")
                }, 1000)
            }
        }
    }

    private fun setupUiModeSwitcher() {
        binding.switchUiMode.setOnCheckedChangeListener { _, isChecked -> updateUiMode(isChecked) }
    }

    private fun updateUiMode(isModern: Boolean) {
        if (isModern) {
            binding.layoutAccessibility.visibility = View.GONE
            binding.layoutModern.visibility = View.VISIBLE
            binding.switchUiMode.text = getString(R.string.mode_modern)
            binding.bottomNavModern.selectedItemId = R.id.nav_tracking
        } else {
            binding.layoutAccessibility.visibility = View.VISIBLE
            binding.layoutModern.visibility = View.GONE
            binding.switchUiMode.text = getString(R.string.mode_easy)
            if (isTtsReady && settingsStore.isVoiceGuidanceEnabled()) {
                speakMessage("Kolay mod açıldı.")
            }
        }
    }

    private fun setupDestinationButtons() {
        binding.btnSelectDestination.setOnClickListener { showRouteSelectionDialog() }
        binding.cardModernSelector.setOnClickListener { showRouteSelectionDialog() }
        binding.cardMapRouteSelector.setOnClickListener { showRouteSelectionDialog() }
    }

    private fun setupTrackingButtons() {
        binding.btnToggleTracking.setOnClickListener { toggleTracking() }
        binding.btnModernToggle.setOnClickListener { toggleTracking() }
        binding.btnMapStartTracking.setOnClickListener { toggleTracking() }
    }

    private fun setupVoiceCommands() {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                }
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) processVoiceCommand(matches[0].lowercase(Locale("tr", "TR")))
                    }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) { Log.e("MainActivity", "STT setup fail", e) }

        binding.fabVoiceCommand.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
                return@setOnClickListener
            }
            startListening()
        }
    }

    private fun startListening() {
        speakMessage(getString(R.string.tts_listening))
        binding.fabVoiceCommand.postDelayed({
            if (!isFinishing && !isDestroyed) {
                try { speechRecognizer?.startListening(speechIntent) } catch (e: Exception) {}
            }
        }, 500)
    }

    private fun processVoiceCommand(command: String) {
        if (!isTracking) { speakMessage(getString(R.string.tts_info_not_tracking)); return }
        val route = selectedRoute ?: return
        when {
            command.contains("nerede") || command.contains("durak") -> {
                if (lastCurIdx in route.stops.indices) {
                    val currentStop = route.stops[lastCurIdx].name
                    val nextIdx = if (destinationStopIndex >= lastCurIdx) lastCurIdx + 1 else lastCurIdx - 1
                    if (nextIdx in route.stops.indices) speakMessage("Şu an $currentStop durağı civarındasınız. Sıradaki durak ${route.stops[nextIdx].name}.")
                    else speakMessage("Şu an $currentStop durağındasınız.")
                }
            }
            command.contains("kaç") || command.contains("kaldı") -> speakMessage("Hedefinize ${Math.abs(destinationStopIndex - lastCurIdx)} durak kaldı.")
            command.contains("mesafe") || command.contains("metre") -> speakMessage("Sıradaki durağa yaklaşık ${lastDistance.toInt()} metre var.")
            command.contains("durdur") || command.contains("bitir") -> { stopTracking(); speakMessage("Takip durduruldu.") }
            else -> speakMessage(getString(R.string.tts_command_not_understood))
        }
    }

    private fun speakMessage(message: String) {
        if (isTtsReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "voice_cmd")
    }

    private fun toggleTracking() { if (isTracking) stopTracking() else startTrackingIfReady() }

    private fun startTrackingIfReady() {
        if (selectedRoute == null || destinationStopIndex < 0) { speakMessage("Önce durak seçiniz."); showRouteSelectionDialog(); return }
        startTracking()
    }

    private fun startTracking() {
        try {
            isTracking = true
            val intent = Intent(this, TrackingService::class.java)
            ContextCompat.startForegroundService(this, intent)
            selectedRoute?.let { 
                trackingService?.startTracking(it, destinationStopIndex, routeRepository.getStopLatitudes(it), routeRepository.getStopLongitudes(it))
                statsStore.incrementTrips()
                statsStore.recordRouteUsage(it.routeId)
            }
            updateButtonState()
        } catch (e: Exception) { Log.e("MainActivity", "startTracking fail", e) }
    }

    private fun stopTracking() {
        isTracking = false
        trackingService?.stopTracking()
        updateButtonState()
        userMarker?.let { try { binding.mapView.overlays.remove(it) } catch (e: Exception) {} }
        userMarker = null
        binding.mapView.invalidate()
    }

    private fun updateButtonState() {
        val btnText = if (isTracking) getString(R.string.btn_stop_tracking) else getString(R.string.btn_start_tracking)
        val btnColor = if (isTracking) R.color.accent_red else R.color.accent_green
        binding.btnToggleTracking.text = btnText
        binding.btnToggleTracking.setBackgroundColor(ContextCompat.getColor(this, btnColor))
        binding.layoutTripInfo.visibility = if (isTracking) View.VISIBLE else View.GONE
        binding.btnModernToggle.text = btnText
        binding.btnModernToggle.setBackgroundColor(ContextCompat.getColor(this, btnColor))
        binding.cardModernTripInfo.visibility = if (isTracking) View.VISIBLE else View.GONE
        binding.btnMapStartTracking.text = btnText
        binding.btnMapStartTracking.setBackgroundColor(ContextCompat.getColor(this, btnColor))
        binding.btnMapStartTracking.visibility = if (selectedRoute != null) View.VISIBLE else View.GONE
        binding.cardMapTripInfo.visibility = if (isTracking) View.VISIBLE else View.GONE
    }

    private fun updateUi(lat: Double, lon: Double, curIdx: Int, deviation: Double, distance: Double, startIdx: Int, direction: Int) {
        if (isFinishing || isDestroyed) return
        val route = selectedRoute ?: return
        
        binding.textCoordinates.text = String.format(Locale.US, "%.6f, %.6f", lat, lon)
        binding.textDeviation.text = "Sapma: ${if (deviation.isFinite()) deviation.toInt() else 0} m"
        binding.textDistanceToNext.text = "Mesafe: ${distance.toInt()} m"
        
        if (deviation > 200.0 || direction == 1) {
            binding.textDirectionStatus.text = "UYARI: ROTA DIŞI!"
            binding.textDirectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
        } else {
            binding.textDirectionStatus.text = "Doğru yoldasınız."
            binding.textDirectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        }

        val nextIdx = if (destinationStopIndex >= curIdx) curIdx + 1 else curIdx - 1
        if (nextIdx in route.stops.indices) {
            val nextStop = route.stops[nextIdx]
            binding.textNextStop.text = "Sıradaki: ${nextStop.name}"
            binding.textModernNextStopName.text = nextStop.name
            binding.textMapNextStop.text = "Sıradaki Durak: ${nextStop.name}"
            
            val remain = Math.abs(destinationStopIndex - curIdx)
            binding.textRemainingStops.text = "Kalan Durak: $remain"
            binding.textModernRemainingValue.text = "$remain"
            binding.textMapRemainingStops.text = "Kalan Durak: $remain"
            
            binding.textModernDistValue.text = "${distance.toInt()} m"
            binding.textMapDistance.text = "Mesafe: ${distance.toInt()} m"
            val tripStops = Math.abs(destinationStopIndex - startIdx).toFloat()
            val coveredStops = Math.abs(curIdx - startIdx).toFloat()
            val progress = if (tripStops > 0) ((coveredStops / tripStops) * 100).toInt().coerceIn(0, 100) else 100
            binding.progressModernTrip.progress = progress
        }
        
        if (binding.modernMapView.visibility == View.VISIBLE) updateMapUserPosition()
    }

    private fun updateMapUserPosition() {
        try {
            if (isFinishing || isDestroyed || lastLat == 0.0) return
            val userPos = GeoPoint(lastLat, lastLon)
            if (userMarker == null) {
                userMarker = Marker(binding.mapView)
                userMarker?.title = "Siz"
                
                val blueCircle = android.graphics.drawable.GradientDrawable()
                blueCircle.shape = android.graphics.drawable.GradientDrawable.OVAL
                blueCircle.setColor(android.graphics.Color.BLUE)
                blueCircle.setStroke(4, android.graphics.Color.WHITE)
                blueCircle.setSize(45, 45)
                
                userMarker?.icon = blueCircle
                userMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                
                binding.mapView.overlays.add(userMarker)
                binding.mapView.controller.setZoom(16.0)
                binding.mapView.controller.animateTo(userPos)
            } else userMarker?.position = userPos
            binding.mapView.invalidate()
        } catch (e: Exception) { Log.e("MainActivity", "Map user fail", e) }
    }

    private fun updateMapRoute() {
        try {
            if (isFinishing || isDestroyed) return
            val route = selectedRoute ?: return
            routePolyline?.let { binding.mapView.overlays.remove(it) }
            binding.mapView.overlays.removeAll(stopMarkers)
            stopMarkers.clear()
            val points = route.stops.map { GeoPoint(it.lat, it.lon) }
            if (points.isEmpty()) return
            routePolyline = Polyline() 
            routePolyline?.setPoints(points)
            routePolyline?.outlinePaint?.color = Color.parseColor("#2196F3")
            routePolyline?.outlinePaint?.strokeWidth = 10f
            binding.mapView.overlays.add(routePolyline)
            val redCircle = android.graphics.drawable.GradientDrawable()
            redCircle.shape = android.graphics.drawable.GradientDrawable.OVAL
            redCircle.setColor(android.graphics.Color.RED)
            redCircle.setStroke(3, android.graphics.Color.WHITE)
            redCircle.setSize(40, 40)

            route.stops.forEachIndexed { index, stop ->
                val p = GeoPoint(stop.lat, stop.lon)
                val marker = Marker(binding.mapView)
                marker.position = p
                marker.title = stop.name
                marker.icon = redCircle
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                if (index == destinationStopIndex) marker.subDescription = "HEDEF"
                stopMarkers.add(marker)
            }
            binding.mapView.overlays.addAll(stopMarkers)
            if (points.size > 1) {
                val bounds = org.osmdroid.util.BoundingBox.fromGeoPoints(points)
                binding.mapView.post {
                    try {
                        if (!isFinishing && !isDestroyed && binding.mapView.width > 0) binding.mapView.zoomToBoundingBox(bounds, true, 100)
                    } catch (e: Exception) {}
                }
            }
            binding.mapView.invalidate()
        } catch (e: Exception) { Log.e("MainActivity", "Map route fail", e) }
    }

    private fun showRouteSelectionDialog() {
        val routes = routeRepository.getAllRoutes()
        if (routes.isEmpty()) return
        val isModern = (binding.layoutModern.visibility == View.VISIBLE)
        val dialog = RouteSelectionDialog.newInstance(routes, isEasyMode = !isModern)
        dialog.onRouteAndStopSelected = { route, stop, stopIndex ->
            selectedRoute = route
            selectedDestinationStop = stop
            destinationStopIndex = stopIndex
            binding.layoutRouteInfo.visibility = View.VISIBLE
            binding.textDestinationInfo.text = "Hedef: ${stop.name}"
            binding.textModernRouteName.text = "Hat ${route.routeId} → ${stop.name}"
            binding.textMapRouteName.text = "Hat ${route.routeId} → ${stop.name}"
            recentDestStore.save(route, stop, stopIndex)
            if (!isModern && settingsStore.isVoiceGuidanceEnabled()) speakMessage("Hedef seçildi: ${stop.name}. Şimdi takibi başlatabilirsiniz.")
            updateButtonState()
            if (binding.modernMapView.visibility == View.VISIBLE) updateMapRoute()
        }
        dialog.show(supportFragmentManager, "route_selection")
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.RECORD_AUDIO)
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startListening()
    }
}
