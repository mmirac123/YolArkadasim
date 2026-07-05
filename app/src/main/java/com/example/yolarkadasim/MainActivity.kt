package com.example.yolarkadasim

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.yolarkadasim.util.StopMatcher
import com.google.android.material.slider.Slider
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var routeRepository: RouteRepository
    private lateinit var recentDestStore: RecentDestinationStore
    private lateinit var statsStore: StatsStore
    private lateinit var settingsStore: SettingsStore
    
    private var trackingService: TrackingService? = null
    private var isBound = false
    private var isTracking = false
    private var pendingTrackingStart = false

    private var selectedRoute: BusRoute? = null
    private var selectedDestinationStop: BusStop? = null
    private var destinationStopIndex: Int = -1

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private var speechRecognizer: SpeechRecognizer? = null
    private var speechIntent: Intent? = null

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastShakeTime: Long = 0
    private val SHAKE_THRESHOLD = 15f

    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastCurIdx = -1
    private var lastDistance = 0.0
    private var lastStartIdx = -1

    private var userMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private val stopMarkers = mutableListOf<Marker>()

    // "Beni izle" modu: takipte harita kullanıcıyı ortalar; elle kaydırınca
    // kapanır ve konumuma-dön butonu belirir
    private var followUser = true

    // SAF ile CSV dışa aktarma: izin gerektirmez, kullanıcı konumu kendi seçer
    private val csvExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(statsStore.buildCsvExport().toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, getString(R.string.csv_export_done), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "CSV export fail", e)
            Toast.makeText(this, getString(R.string.csv_export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val binder = service as TrackingService.TrackingBinder
                trackingService = binder.getService()
                isBound = true
                trackingService?.onUpdate = { lat, lon, curIdx, deviation, direction, distance, startIdx ->
                    handleUpdate(lat, lon, curIdx, deviation, direction, distance, startIdx)
                }
                syncStateFromService()
                if (pendingTrackingStart) {
                    pendingTrackingStart = false
                    doStartTracking()
                }
            } catch (e: Exception) { Log.e("MainActivity", "Service bind fail", e) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            trackingService = null
        }
    }

    // Activity yeniden bağlandığında (ör. uygulamaya geri dönüldüğünde) servisin
    // gerçek durumunu UI'a yansıtır; servis arkada takipteyken buton "Başlat" göstermez.
    private fun syncStateFromService() {
        val service = trackingService ?: return
        if (service.isTrackingActive) {
            val route = service.currentRoute ?: return
            selectedRoute = route
            destinationStopIndex = service.currentDestinationIndex
            selectedDestinationStop = route.stops.getOrNull(destinationStopIndex)
            isTracking = true
            binding.layoutRouteInfo.visibility = View.VISIBLE
            selectedDestinationStop?.let { stop ->
                binding.textDestinationInfo.text = getString(R.string.destination_format, stop.name)
                binding.textModernRouteName.text = getString(R.string.route_arrow_format, route.routeId, stop.name)
                binding.textMapRouteName.text = getString(R.string.route_arrow_format, route.routeId, stop.name)
            }
            updateButtonState()
        } else if (isTracking) {
            // Servis takip etmiyor (ör. sistem tarafından öldürülmüş) ama UI takipte sanıyor
            isTracking = false
            updateButtonState()
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
            Configuration.getInstance().userAgentValue = packageName
            Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            routeRepository = RouteRepository(this)
            // 119 hatlık JSON'u (~700 KB) ilk dokunuşta UI thread'inde parse etmemek
            // için önbelleği arka planda ısıt
            Thread { try { routeRepository.getAllRoutes() } catch (e: Exception) { Log.e("MainActivity", "Route preload fail", e) } }.start()
            recentDestStore = RecentDestinationStore(this)
            statsStore = StatsStore(this)
            settingsStore = SettingsStore(this)
            tts = TextToSpeech(this, this)
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
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
        } catch (e: Exception) { Log.e("MainActivity", "CRITICAL ONCREATE FAIL", e) }
    }

    private fun setupMap() {
        try {
            binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
            binding.mapView.setMultiTouchControls(true)
            binding.mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.ALWAYS)
            binding.mapView.controller.setZoom(15.0)
            binding.mapView.controller.setCenter(GeoPoint(39.9334, 32.8597))

            // Kullanıcı haritayı elle oynatınca izleme modundan çık.
            // (MapListener programatik hareketlerde de tetiklendiği için
            // dokunma olayı üzerinden ayırt ediyoruz.)
            binding.mapView.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN && followUser) {
                    followUser = false
                    binding.fabMapFollowMe.show()
                }
                false // haritanın kendi dokunma işleyişi devam etsin
            }
            binding.fabMapFollowMe.setOnClickListener {
                followUser = true
                binding.fabMapFollowMe.hide()
                if (lastLat != 0.0) {
                    binding.mapView.controller.animateTo(GeoPoint(lastLat, lastLon))
                }
            }
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
                // Takip başlamamış olsa da seçili güzergâhı göster
                if (selectedRoute != null) updateMapRoute()
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
        binding.sliderVoiceLevel.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) { settingsStore.setVoiceLevel(slider.value.toInt()) }
        })
        binding.sliderSpeechRate.value = settingsStore.getSpeechRate().toFloat().coerceIn(50f, 150f)
        binding.sliderSpeechRate.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                settingsStore.setSpeechRate(slider.value.toInt())
                try { tts?.setSpeechRate(slider.value / 100f) } catch (e: Exception) {}
                trackingService?.applyTtsSettings()
                speakMessage("Konuşma hızı böyle duyulacak.") // anında örnek ver
            }
        })
        binding.btnExportCsv.setOnClickListener {
            try { csvExportLauncher.launch("yol_arkadasim_metrikler.csv") }
            catch (e: Exception) { Log.e("MainActivity", "CSV launcher fail", e) }
        }
        binding.editEmergencyContact.setText(settingsStore.getEmergencyContact())
        binding.btnSaveEmergencyContact.setOnClickListener {
            val phone = binding.editEmergencyContact.text?.toString()?.trim().orEmpty()
            settingsStore.setEmergencyContact(phone)
            Toast.makeText(this, getString(R.string.settings_emergency_saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStatsUi() {
        try {
            binding.valTotalTrips.text = statsStore.getTotalTrips().toString()
            binding.valTotalDist.text = String.format(Locale.US, "%.1f km", statsStore.getTotalDistanceKm())
            binding.valTotalStops.text = statsStore.getTotalStops().toString()
            binding.valFavRoute.text = statsStore.getMostUsedRoute()
            binding.valWrongDirection.text = statsStore.getWrongDirectionCount().toString()
            binding.valGpsDev.text = String.format(Locale.US, "%.1fm", statsStore.getAverageGpsDeviation())
            binding.valBatteryDrop.text = String.format(Locale.US, "%.0f%%", statsStore.getTotalBatteryConsumedPct())
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
        try { 
            binding.mapView.onResume()
            accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        } catch (e: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try { 
            binding.mapView.onPause()
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {}
    }

    override fun onStop() {
        // Servis arkada çalışmaya devam ederken Activity'ye referans tutmasın (bellek sızıntısı);
        // yeniden bağlanınca onServiceConnected callback'i tekrar kurar.
        trackingService?.onUpdate = null
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
            try { tts?.setSpeechRate(settingsStore.getSpeechRate() / 100f) } catch (e: Exception) { Log.e("MainActivity", "TTS rate fail", e) }
            isTtsReady = true
            if (!settingsStore.isModernModePreferred() && settingsStore.isVoiceGuidanceEnabled()) {
                binding.root.postDelayed({
                    if (!isFinishing && !isDestroyed) speakGuidedWalkthrough()
                }, 1000)
            }
        }
    }

    private fun speakGuidedWalkthrough() {
        val msg = "Yol Arkadaşım uygulamasına hoş geldiniz. Kolay mod aktif. " +
                  "Şu an ana ekrandasınız. Ekranın en üstünde yer alan dev butona basarak hangi durağa gitmek istediğinizi seçebilirsiniz. " +
                  "Seçim yaptıktan sonra ekranın ortasında kocaman bir yeşil buton belirecek, ona basarak takibi başlatabilirsiniz. " +
                  "Ayrıca telefonu sallayarak veya sağ üstteki mikrofon butonuna basarak hedefinizi sesle söyleyebilirsiniz. " +
                  "Zor durumda kalırsanız ekranın en altındaki turuncu yardım butonu kayıtlı yakınınızı arar."
        speakMessage(msg)
    }

    private fun setupUiModeSwitcher() {
        binding.switchUiMode.setOnCheckedChangeListener { _, isChecked -> updateUiMode(isChecked) }
    }

    private fun updateUiMode(isModern: Boolean) {
        if (isModern) {
            // Stop any ongoing speech when switching to Modern Mode
            try { tts?.stop() } catch (e: Exception) {}

            binding.layoutAccessibility.visibility = View.GONE
            binding.layoutModern.visibility = View.VISIBLE
            binding.switchUiMode.text = getString(R.string.mode_modern)
            binding.bottomNavModern.selectedItemId = R.id.nav_tracking
        } else {
            binding.layoutAccessibility.visibility = View.VISIBLE
            binding.layoutModern.visibility = View.GONE
            binding.switchUiMode.text = getString(R.string.mode_easy)
            if (isTtsReady && settingsStore.isVoiceGuidanceEnabled()) speakGuidedWalkthrough()
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
        binding.btnHelp.setOnClickListener { requestHelp() }
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
                        else speakMessage(getString(R.string.tts_command_not_understood))
                    }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        // Görme engelli/yaşlı kullanıcı mikrofonun dinlemediğini göremez;
                        // sessiz kalmak yerine sesli geri bildirim ver.
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> speakMessage(getString(R.string.tts_not_heard))
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                            SpeechRecognizer.ERROR_CLIENT -> { /* geçici durum, anons spam'lemeyelim */ }
                            else -> speakMessage(getString(R.string.tts_mic_error))
                        }
                    }
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
        val cmd = command.lowercase(Locale("tr", "TR"))

        // Acil yardım her durumda önceliklidir
        if (cmd.contains("yardım") || cmd.contains("imdat")) { requestHelp(); return }

        val route = selectedRoute ?: routeRepository.getAllRoutes().firstOrNull() ?: return
        val wantsDestination = cmd.contains("hedef") || cmd.contains("gitmek") || cmd.contains("git") ||
                cmd.contains("ayarla") || cmd.contains("yap") || cmd.contains("istiyorum")
        if (wantsDestination) {
            val matchedIdx = StopMatcher.findBestStopIndex(cmd, route.stops.map { it.name })
            if (matchedIdx >= 0) {
                val matchedStop = route.stops[matchedIdx]
                destinationStopIndex = matchedIdx
                selectedDestinationStop = matchedStop
                selectedRoute = route
                binding.layoutRouteInfo.visibility = View.VISIBLE
                binding.textDestinationInfo.text = getString(R.string.destination_format, matchedStop.name)
                binding.textModernRouteName.text = getString(R.string.route_arrow_format, route.routeId, matchedStop.name)
                binding.textMapRouteName.text = getString(R.string.route_arrow_format, route.routeId, matchedStop.name)
                recentDestStore.save(route, matchedStop, destinationStopIndex)
                if (isTracking) {
                    trackingService?.updateDestination(destinationStopIndex)
                    speakMessage(getString(R.string.tts_new_destination_set, matchedStop.name))
                } else {
                    speakMessage(getString(R.string.tts_destination_selected, matchedStop.name))
                    updateButtonState()
                }
                return
            }
            // Eşleşme yoksa hemen pes etme: komut "hedefe kaç durak kaldı" gibi bir
            // durum sorgusu olabilir; aşağıdaki dallara devam et.
        }
        if (!isTracking) {
            speakMessage(if (wantsDestination) getString(R.string.tts_stop_not_found) else getString(R.string.tts_info_not_tracking))
            return
        }
        when {
            cmd.contains("nerede") || cmd.contains("durak") -> {
                if (lastCurIdx in route.stops.indices) {
                    val currentStop = route.stops[lastCurIdx].name
                    val nextIdx = if (destinationStopIndex >= lastCurIdx) lastCurIdx + 1 else lastCurIdx - 1
                    if (nextIdx in route.stops.indices) speakMessage("Şu an $currentStop durağı civarındasınız. Sıradaki durak ${route.stops[nextIdx].name}.")
                    else speakMessage("Şu an $currentStop durağındasınız.")
                }
            }
            cmd.contains("kaç") || cmd.contains("kaldı") -> speakMessage("Hedefinize ${Math.abs(destinationStopIndex - lastCurIdx)} durak kaldı.")
            cmd.contains("mesafe") || cmd.contains("metre") -> speakMessage("Sıradaki durağa yaklaşık ${lastDistance.toInt()} metre var.")
            cmd.contains("durdur") || cmd.contains("bitir") -> { stopTracking(); speakMessage(getString(R.string.tts_tracking_stopped_voice)) }
            else -> speakMessage(if (wantsDestination) getString(R.string.tts_stop_not_found) else getString(R.string.tts_command_not_understood))
        }
    }

    /**
     * Yardım butonu / "yardım" sesli komutu: kayıtlı yakının numarasını arama
     * ekranında açar (ACTION_DIAL izin gerektirmez; yanlışlıkla arama da başlatmaz —
     * kullanıcının yeşil tuşa basması yeterlidir).
     */
    private fun requestHelp() {
        val phone = settingsStore.getEmergencyContact()
        if (phone.isBlank()) {
            speakMessage(getString(R.string.tts_no_contact))
            return
        }
        speakMessage(getString(R.string.tts_calling_contact))
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        } catch (e: Exception) { Log.e("MainActivity", "Dial fail", e) }
    }

    private fun speakMessage(message: String) {
        // Takip sırasında tek konuşma otoritesi servistir; iki TTS birbirini kesmesin.
        val svc = trackingService
        if (isTracking && svc != null) {
            svc.speak(message)
            return
        }
        if (isTtsReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "voice_cmd")
    }

    private fun toggleTracking() { if (isTracking) stopTracking() else startTrackingIfReady() }

    private fun startTrackingIfReady() {
        if (selectedRoute == null || destinationStopIndex < 0) { speakMessage(getString(R.string.tts_select_stop_first)); showRouteSelectionDialog(); return }
        startTracking()
    }

    private fun startTracking() {
        try {
            // Extras, servis sistem tarafından öldürülüp yeniden başlatılırsa (REDELIVER)
            // yolculuğun kaldığı yerden kurulmasını sağlar.
            val intent = Intent(this, TrackingService::class.java).apply {
                putExtra(TrackingService.EXTRA_ROUTE_ID, selectedRoute?.routeId)
                putExtra(TrackingService.EXTRA_DEST_IDX, destinationStopIndex)
            }
            ContextCompat.startForegroundService(this, intent)
            if (trackingService == null) {
                // Servis henüz bağlanmadı; bağlantı kurulunca onServiceConnected başlatacak
                pendingTrackingStart = true
                if (!isBound) bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                return
            }
            doStartTracking()
        } catch (e: Exception) { Log.e("MainActivity", "startTracking fail", e) }
    }

    private fun doStartTracking() {
        try {
            val route = selectedRoute ?: return
            val service = trackingService ?: return
            service.startTracking(route, destinationStopIndex, routeRepository.getStopLatitudes(route), routeRepository.getStopLongitudes(route))
            statsStore.incrementTrips()
            statsStore.recordRouteUsage(route.routeId)
            isTracking = true
            followUser = true
            binding.fabMapFollowMe.hide()
            updateButtonState()
        } catch (e: Exception) { Log.e("MainActivity", "doStartTracking fail", e) }
    }

    private fun stopTracking() {
        pendingTrackingStart = false
        isTracking = false
        trackingService?.stopTracking()
        binding.cardMapTripInfo.visibility = View.GONE
        binding.textDirectionStatus.text = ""
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
    }

    private fun updateUi(lat: Double, lon: Double, curIdx: Int, deviation: Double, distance: Double, startIdx: Int, direction: Int) {
        if (isFinishing || isDestroyed) return
        val route = selectedRoute ?: return
        binding.textCoordinates.text = String.format(Locale.US, "%.6f, %.6f", lat, lon)
        binding.textModernCoords.text = String.format(Locale.US, "GPS: %.5f, %.5f", lat, lon)
        binding.textDeviation.text = "Sapma: ${if (deviation.isFinite()) deviation.toInt() else 0} m"
        binding.textDistanceToNext.text = getString(R.string.distance_short_format, distance.toInt())

        // Rota durumu — hem kolay hem modern mod aynı gerçeği göstersin
        val offRoute = deviation > 200.0 || direction == 1
        val statusText = getString(if (offRoute) R.string.status_off_route else R.string.status_on_route)
        val statusColor = ContextCompat.getColor(this, if (offRoute) R.color.accent_red else R.color.accent_green)
        binding.textDirectionStatus.text = statusText
        binding.textDirectionStatus.setTextColor(statusColor)
        binding.textModernDeviationStatus.text = statusText
        binding.textModernDeviationStatus.setTextColor(statusColor)

        val remainingStops = Math.abs(destinationStopIndex - curIdx)
        val nextIdx = if (destinationStopIndex >= curIdx) curIdx + 1 else curIdx - 1
        if (nextIdx in route.stops.indices) {
            val nextStop = route.stops[nextIdx]
            binding.textNextStop.text = getString(R.string.next_stop_short_format, nextStop.name)
            binding.textModernNextStopName.text = nextStop.name
            binding.textRemainingStops.text = getString(R.string.remaining_short_format, remainingStops)
            binding.textModernRemainingValue.text = "$remainingStops"
            binding.textModernDistValue.text = "${distance.toInt()} m"
            val tripStops = Math.abs(destinationStopIndex - startIdx).toFloat()
            val coveredStops = Math.abs(curIdx - startIdx).toFloat()
            val progress = if (tripStops > 0) ((coveredStops / tripStops) * 100).toInt().coerceIn(0, 100) else 100
            binding.progressModernTrip.progress = progress

            // Harita sekmesindeki yolculuk kartı (önceden hiç beslenmiyordu)
            binding.textMapNextStop.text = getString(R.string.next_stop_short_format, nextStop.name)
            binding.textMapDistance.text = getString(R.string.distance_short_format, distance.toInt())
            binding.textMapRemainingStops.text = getString(R.string.remaining_short_format, remainingStops)
        }
        if (binding.modernMapView.visibility == View.VISIBLE) {
            binding.cardMapTripInfo.visibility = View.VISIBLE
            updateMapUserPosition()
        }
    }

    private fun updateMapUserPosition() {
        try {
            if (isFinishing || isDestroyed || lastLat == 0.0) return
            val userPos = GeoPoint(lastLat, lastLon)
            if (userMarker == null) {
                userMarker = Marker(binding.mapView)
                userMarker?.title = "Siz"
                userMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                binding.mapView.overlays.add(userMarker)
                binding.mapView.controller.setZoom(16.0)
                binding.mapView.controller.animateTo(userPos)
            } else {
                userMarker?.position = userPos
                if (followUser) binding.mapView.controller.animateTo(userPos)
            }
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
            // Gerçek yol geometrisi varsa onu çiz; yoksa durak-durak düz çizgi
            val points = if (route.shape.isNotEmpty()) route.shape.map { GeoPoint(it.lat, it.lon) }
                         else route.stops.map { GeoPoint(it.lat, it.lon) }
            if (points.isEmpty()) return
            routePolyline = Polyline()
            routePolyline?.setPoints(points)
            routePolyline?.outlinePaint?.color = Color.parseColor("#2196F3")
            routePolyline?.outlinePaint?.strokeWidth = 10f
            binding.mapView.overlays.add(routePolyline)
            route.stops.forEachIndexed { index, stop ->
                val p = GeoPoint(stop.lat, stop.lon)
                val marker = Marker(binding.mapView)
                marker.position = p
                marker.title = stop.name
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                if (index == destinationStopIndex) marker.subDescription = "HEDEF"
                stopMarkers.add(marker)
            }
            binding.mapView.overlays.addAll(stopMarkers)
            // Takipte ve izleme modundayken tüm güzergâha uzaklaşma; kamera kullanıcıda kalsın
            if (points.size > 1 && !(isTracking && followUser)) {
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
            binding.textDestinationInfo.text = getString(R.string.destination_format, stop.name)
            binding.textModernRouteName.text = getString(R.string.route_arrow_format, route.routeId, stop.name)
            binding.textMapRouteName.text = getString(R.string.route_arrow_format, route.routeId, stop.name)
            recentDestStore.save(route, stop, stopIndex)
            if (!isModern && settingsStore.isVoiceGuidanceEnabled()) speakMessage("Hedef seçildi: ${stop.name}. Şimdi takibi başlatabilirsiniz.")
            updateButtonState()
            if (binding.modernMapView.visibility == View.VISIBLE) updateMapRoute()
        }
        dialog.show(supportFragmentManager, "route_selection")
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startListening()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            val gForce = sqrt(x*x + y*y + z*z)
            val now = System.currentTimeMillis()
            if (gForce > SHAKE_THRESHOLD) {
                if (now - lastShakeTime > 2000) {
                    lastShakeTime = now
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
