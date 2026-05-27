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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import com.example.yolarkadasim.data.SettingsStore
import com.example.yolarkadasim.data.StatsStore
import com.example.yolarkadasim.databinding.ActivityMainBinding
import com.example.yolarkadasim.service.TrackingService
import com.example.yolarkadasim.ui.RouteSelectionDialog
import com.google.android.material.slider.Slider
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

    // Speech Recognition
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechIntent: Intent? = null

    // Latest trip data for voice commands / stats
    private var lastCurIdx = -1
    private var lastDistance = 0.0
    private var lastStartIdx = -1

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TrackingService.TrackingBinder
            trackingService = binder.getService()
            isBound = true
            
            trackingService?.onUpdate = { lat, lon, curIdx, deviation, direction, distance, startIdx ->
                if (curIdx > lastCurIdx && lastCurIdx != -1) {
                    statsStore.incrementStops()
                    statsStore.addDistance(distance) 
                }
                lastCurIdx = curIdx
                lastDistance = distance
                lastStartIdx = startIdx
                runOnUiThread { updateUi(lat, lon, curIdx, deviation, direction, distance, startIdx) }
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
        statsStore = StatsStore(this)
        settingsStore = SettingsStore(this)
        tts = TextToSpeech(this, this)
        
        // Apply startup mode
        val startInModern = settingsStore.isModernModePreferred()
        binding.switchUiMode.isChecked = startInModern
        updateUiMode(startInModern, isInitial = true)

        setupUiModeSwitcher()
        setupBottomNavigation()
        setupDestinationButtons()
        setupTrackingButtons()
        setupVoiceCommands()
        setupSettingsPage()

        requestPermissions()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavModern.setOnItemSelectedListener { item ->
            binding.modernTrackingView.visibility = if (item.itemId == R.id.nav_tracking) View.VISIBLE else View.GONE
            binding.modernStatsView.visibility = if (item.itemId == R.id.nav_stats) View.VISIBLE else View.GONE
            binding.modernSettingsView.visibility = if (item.itemId == R.id.nav_settings) View.VISIBLE else View.GONE
            
            if (item.itemId == R.id.nav_stats) refreshStatsUi()
            true
        }
    }

    private fun setupSettingsPage() {
        binding.switchStartupMode.isChecked = settingsStore.isModernModePreferred()
        binding.switchStartupMode.setOnCheckedChangeListener { _, isChecked ->
            settingsStore.setModernModePreferred(isChecked)
        }

        binding.switchVoiceGuidance.isChecked = settingsStore.isVoiceGuidanceEnabled()
        binding.switchVoiceGuidance.setOnCheckedChangeListener { _, isChecked ->
            settingsStore.setVoiceGuidanceEnabled(isChecked)
        }

        binding.sliderVoiceLevel.value = settingsStore.getVoiceLevel().toFloat()
        binding.sliderVoiceLevel.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                settingsStore.setVoiceLevel(slider.value.toInt())
            }
        })
    }

    private fun refreshStatsUi() {
        binding.valTotalTrips.text = statsStore.getTotalTrips().toString()
        binding.valTotalDist.text = String.format(Locale.US, "%.1f km", statsStore.getTotalDistanceKm())
        binding.valTotalStops.text = statsStore.getTotalStops().toString()
        binding.valFavRoute.text = statsStore.getMostUsedRoute()
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
        speechRecognizer?.destroy()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("tr", "TR"))
            isTtsReady = true
            
            // Trigger initial guidance if starting in Easy Mode
            val startInModern = settingsStore.isModernModePreferred()
            if (!startInModern && settingsStore.isVoiceGuidanceEnabled()) {
                // Short delay to ensure system is ready for speech
                binding.root.postDelayed({
                    speakMessage("Yol Arkadaşım uygulamasına hoş geldiniz. Kolay mod aktif. Ekranın en üstündeki dev düğmeye basarak nereye gideceğinizi seçebilirsiniz.")
                }, 1000)
            }
        }
    }

    private fun setupUiModeSwitcher() {
        binding.switchUiMode.setOnCheckedChangeListener { _, isChecked ->
            updateUiMode(isChecked, isInitial = false)
        }
    }

    private fun updateUiMode(isModern: Boolean, isInitial: Boolean) {
        if (isModern) {
            binding.layoutAccessibility.visibility = View.GONE
            binding.layoutModern.visibility = View.VISIBLE
            binding.switchUiMode.text = getString(R.string.mode_modern)
            binding.bottomNavModern.selectedItemId = R.id.nav_tracking
        } else {
            binding.layoutAccessibility.visibility = View.VISIBLE
            binding.layoutModern.visibility = View.GONE
            binding.switchUiMode.text = getString(R.string.mode_easy)
            
            // Automatic Assistant Guidance for Kolay Mod
            if (!isInitial && settingsStore.isVoiceGuidanceEnabled()) {
                speakMessage("Kolay mod açıldı. Ekranın en üstündeki dev düğmeye basarak nereye gideceğinizi seçebilirsiniz. Seçim yaptıktan sonra ortadaki dev yeşil düğmeye basarak takibi başlatabilirsiniz.")
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

    private fun setupVoiceCommands() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        processVoiceCommand(matches[0].lowercase(Locale("tr", "TR")))
                    } else speakMessage(getString(R.string.tts_command_not_understood))
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { Log.e("Voice", "Error: $error") }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

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
            speechRecognizer?.startListening(speechIntent)
        }, 500)
    }

    private fun processVoiceCommand(command: String) {
        if (!isTracking) {
            speakMessage(getString(R.string.tts_info_not_tracking))
            return
        }
        val route = selectedRoute ?: return

        when {
            command.contains("nerede") || command.contains("neredeyim") || command.contains("durak") -> {
                if (lastCurIdx >= 0 && lastCurIdx < route.stops.size) {
                    val currentStop = route.stops[lastCurIdx].name
                    val nextIdx = if (destinationStopIndex >= lastCurIdx) lastCurIdx + 1 else lastCurIdx - 1
                    if (nextIdx in route.stops.indices) {
                        speakMessage("Şu an $currentStop durağı civarındasınız. Sıradaki durak ${route.stops[nextIdx].name}.")
                    } else speakMessage("Şu an $currentStop durağındasınız.")
                }
            }
            command.contains("kaç") || command.contains("kaldı") -> {
                val remaining = Math.abs(destinationStopIndex - lastCurIdx)
                speakMessage("Hedefinize $remaining durak kaldı.")
            }
            command.contains("mesafe") || command.contains("metre") -> {
                speakMessage("Sıradaki durağa yaklaşık ${lastDistance.toInt()} metre var.")
            }
            command.contains("durdur") || command.contains("bitir") -> {
                stopTracking()
                speakMessage("Takip sesli komutla durduruldu.")
            }
            else -> speakMessage(getString(R.string.tts_command_not_understood))
        }
    }

    private fun speakMessage(message: String) {
        if (isTtsReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "voice_cmd")
    }

    private fun toggleTracking() {
        if (isTracking) stopTracking() else startTrackingIfReady()
    }

    private fun startTrackingIfReady() {
        if (selectedRoute == null || destinationStopIndex < 0) {
            speakMessage("Önce durak seçiniz.")
            showRouteSelectionDialog()
            return
        }
        startTracking()
    }

    private fun startTracking() {
        isTracking = true
        val intent = Intent(this, TrackingService::class.java)
        ContextCompat.startForegroundService(this, intent)

        selectedRoute?.let { 
            trackingService?.startTracking(it, destinationStopIndex, 
                routeRepository.getStopLatitudes(it), routeRepository.getStopLongitudes(it))
            statsStore.incrementTrips()
            statsStore.recordRouteUsage(it.routeId)
        }
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

    private fun updateUi(lat: Double, lon: Double, curIdx: Int, deviation: Double, direction: Int, distance: Double, startIdx: Int) {
        val route = selectedRoute ?: return
        
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
            
            val tripStops = Math.abs(destinationStopIndex - startIdx).toFloat()
            val coveredStops = Math.abs(curIdx - startIdx).toFloat()
            val progress = if (tripStops > 0) ((coveredStops / tripStops) * 100).toInt().coerceIn(0, 100) else 100
            binding.progressModernTrip.progress = progress
        }
    }

    private fun showRouteSelectionDialog() {
        val routes = routeRepository.getAllRoutes()
        if (routes.isEmpty()) return
        val isModern = settingsStore.isModernModePreferred()
        val dialog = RouteSelectionDialog.newInstance(routes, isEasyMode = !isModern)
        dialog.onRouteAndStopSelected = { route, stop, stopIndex ->
            selectedRoute = route
            selectedDestinationStop = stop
            destinationStopIndex = stopIndex
            
            binding.layoutRouteInfo.visibility = View.VISIBLE
            binding.textDestinationInfo.text = "Hedef: ${stop.name}"
            binding.textModernRouteName.text = "Hat ${route.routeId} → ${stop.name}"
            recentDestStore.save(route, stop, stopIndex)
            
            // Guidance after selection in Easy Mode
            if (!isModern && settingsStore.isVoiceGuidanceEnabled()) {
                speakMessage("Hedef seçildi: ${stop.name}. Şimdi ekranın ortasındaki dev yeşil düğmeye basarak takibi başlatabilirsiniz.")
            }
        }
        dialog.show(supportFragmentManager, "route_selection")
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startListening()
    }
}
