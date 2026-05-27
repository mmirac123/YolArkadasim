package com.example.yolarkadasim

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.yolarkadasim.data.BusRoute
import com.example.yolarkadasim.data.BusStop
import com.example.yolarkadasim.data.RecentDestinationStore
import com.example.yolarkadasim.data.RouteRepository
import com.example.yolarkadasim.databinding.ActivityMainBinding
import com.example.yolarkadasim.service.TrackingService
import com.example.yolarkadasim.ui.RouteSelectionDialog
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var routeRepository: RouteRepository
    private lateinit var recentDestStore: RecentDestinationStore
    
    private var trackingService: TrackingService? = null
    private var isBound = false
    private var isTracking = false

    private var selectedRoute: BusRoute? = null
    private var selectedDestinationStop: BusStop? = null
    private var destinationStopIndex: Int = -1

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TrackingService.TrackingBinder
            trackingService = binder.getService()
            isBound = true
            
            // Re-sync UI if service is already tracking (e.g. after rotation)
            trackingService?.onUpdate = { lat, lon, curIdx, deviation, direction, distance ->
                runOnUiThread { updateUi(lat, lon, curIdx, deviation, direction, distance) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            trackingService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        routeRepository = RouteRepository(this)
        recentDestStore = RecentDestinationStore(this)
        tts = TextToSpeech(this, this)
        
        setupUiModeSwitcher()
        setupDestinationButtons()
        setupTrackingButtons()
        
        requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        Intent(this, TrackingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("tr", "TR"))
            isTtsReady = true
        }
    }

    private fun setupUiModeSwitcher() {
        binding.switchUiMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.layoutAccessibility.visibility = View.GONE
                binding.layoutModern.visibility = View.VISIBLE
            } else {
                binding.layoutAccessibility.visibility = View.VISIBLE
                binding.layoutModern.visibility = View.GONE
            }
        }
    }

    private fun setupDestinationButtons() {
        binding.btnSelectDestination.setOnClickListener { showRouteSelectionDialog() }
        binding.cardModernSelector.setOnClickListener { showRouteSelectionDialog() }
    }

    private fun setupTrackingButtons() {
        binding.btnToggleTracking.setOnClickListener { toggleTracking() }
        binding.btnModernToggle.setOnClickListener { toggleTracking() }
    }

    private fun toggleTracking() {
        if (isTracking) stopTracking() else startTrackingIfReady()
    }

    private fun startTrackingIfReady() {
        if (selectedRoute == null || destinationStopIndex < 0) {
            tts?.speak("Önce durak seçiniz.", TextToSpeech.QUEUE_FLUSH, null, null)
            showRouteSelectionDialog()
            return
        }
        startTracking()
    }

    private fun startTracking() {
        isTracking = true
        val intent = Intent(this, TrackingService::class.java)
        ContextCompat.startForegroundService(this, intent)

        trackingService?.startTracking(
            selectedRoute!!, 
            destinationStopIndex,
            routeRepository.getStopLatitudes(selectedRoute!!),
            routeRepository.getStopLongitudes(selectedRoute!!)
        )
        updateButtonState()
    }

    private fun stopTracking() {
        isTracking = false
        trackingService?.stopTracking()
        updateButtonState()
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
    }

    private fun updateUi(lat: Double, lon: Double, curIdx: Int, deviation: Double, direction: Int, distance: Double) {
        val route = selectedRoute ?: return
        
        // Accessibility
        binding.textCoordinates.text = String.format(Locale.US, "%.6f, %.6f", lat, lon)
        binding.textDeviation.text = "Sapma: ${deviation.toInt()} m"
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
            binding.textRemainingStops.text = "Kalan Durak: ${Math.abs(destinationStopIndex - curIdx)}"
            binding.textModernRemainingValue.text = "${Math.abs(destinationStopIndex - curIdx)}"
            binding.textModernDistValue.text = "${distance.toInt()} m"
            
            val progress = ((curIdx.toFloat() / destinationStopIndex.toFloat()) * 100).toInt().coerceIn(0, 100)
            binding.progressModernTrip.progress = progress
        }
    }

    private fun showRouteSelectionDialog() {
        val routes = routeRepository.getAllRoutes()
        if (routes.isEmpty()) return
        val dialog = RouteSelectionDialog.newInstance(routes)
        dialog.onRouteAndStopSelected = { route, stop, stopIndex ->
            selectedRoute = route
            selectedDestinationStop = stop
            destinationStopIndex = stopIndex
            
            binding.layoutRouteInfo.visibility = View.VISIBLE
            binding.textDestinationInfo.text = "Hedef: ${stop.name}"
            binding.textModernRouteName.text = "Hat ${route.routeId} → ${stop.name}"

            recentDestStore.save(route, stop, stopIndex)
        }
        dialog.show(supportFragmentManager, "route_selection")
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        }
    }
}
