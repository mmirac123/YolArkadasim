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
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.yolarkadasim.data.BusRoute
import com.example.yolarkadasim.data.BusStop
import com.example.yolarkadasim.data.FavoritesStore
import com.example.yolarkadasim.data.RecentDestinationStore
import com.example.yolarkadasim.data.RouteRepository
import com.example.yolarkadasim.data.SettingsStore
import com.example.yolarkadasim.data.StatsStore
import com.example.yolarkadasim.databinding.ActivityMainBinding
import com.example.yolarkadasim.service.TrackingService
import com.example.yolarkadasim.ui.MapController
import com.example.yolarkadasim.ui.RouteSelectionDialog
import com.example.yolarkadasim.ui.SpeechManager
import com.example.yolarkadasim.util.CrashReporter
import com.example.yolarkadasim.util.StopMatcher
import com.google.android.material.slider.Slider
import org.osmdroid.config.Configuration
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var routeRepository: RouteRepository
    private lateinit var recentDestStore: RecentDestinationStore
    private lateinit var statsStore: StatsStore
    private lateinit var settingsStore: SettingsStore
    private lateinit var favoritesStore: FavoritesStore

    // "Tekrar" sesli komutu için Activity tarafında söylenen son anlamlı mesaj
    private var lastLocalAnnouncement: String? = null
    
    private var trackingService: TrackingService? = null
    private var isBound = false
    private var isTracking = false
    private var pendingTrackingStart = false

    private var selectedRoute: BusRoute? = null
    private var selectedDestinationStop: BusStop? = null
    private var destinationStopIndex: Int = -1

    private lateinit var speechManager: SpeechManager

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastShakeTime: Long = 0
    private val SHAKE_THRESHOLD = 15f

    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastCurIdx = -1
    private var lastDistance = 0.0
    private var lastStartIdx = -1

    private lateinit var mapController: MapController

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
            favoritesStore = FavoritesStore(this)
            speechManager = SpeechManager(this, settingsStore, ::processVoiceCommand, ::speakMessage)
            speechManager.onTtsReady = {
                // Kolay modda ve rehber açıksa açılışta yönergeyi seslendir.
                // İlk açılışta onboarding konuştuğu için burada susulur (çift TTS olmasın).
                if (settingsStore.hasOnboarded() && !settingsStore.isModernModePreferred() && settingsStore.isVoiceGuidanceEnabled()) {
                    binding.root.postDelayed({
                        if (!isFinishing && !isDestroyed) speakGuidedWalkthrough()
                    }, 1000)
                }
            }
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            mapController = MapController(binding.mapView, binding.fabMapFollowMe)
            mapController.setup()
            val startInModern = settingsStore.isModernModePreferred()
            binding.switchUiMode.isChecked = startInModern
            updateUiMode(startInModern)
            setupUiModeSwitcher()
            setupBottomNavigation()
            setupDestinationButtons()
            setupTrackingButtons()
            setupVoiceCommands()
            setupSettingsPage()
            // İlk açılışta izin gerekçe ekranını göster; izinleri o ister.
            // Sonraki açılışlarda eksik izin varsa sessizce yeniden iste.
            if (!settingsStore.hasOnboarded()) {
                startActivity(Intent(this, OnboardingActivity::class.java))
            } else {
                val requestedAny = requestPermissions()
                // Pil muafiyeti sorusu sakin bir anda sorulur: izin diyaloğu yokken,
                // ana ekranda — takip başlatma anında (otobüse binerken) DEĞİL.
                if (!requestedAny) maybeAskBatteryOptimization()
            }
        } catch (e: Exception) { Log.e("MainActivity", "CRITICAL ONCREATE FAIL", e) }
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
                mapController.updateUserPosition(lastLat, lastLon)
                // Takip başlamamış olsa da seçili güzergâhı göster
                selectedRoute?.let { mapController.drawRoute(it, destinationStopIndex, isTracking) }
            }
            true
        }
    }

    private fun setupSettingsPage() {
        binding.switchStartupMode.isChecked = settingsStore.isModernModePreferred()
        binding.switchStartupMode.setOnCheckedChangeListener { _, isChecked ->
            settingsStore.setModernModePreferred(isChecked)
            // Bu ayar mevcut ekranı DEĞİL, bir sonraki açılışı etkiler — kullanıcı
            // "bastım ama bir şey olmadı" sanmasın diye anında geri bildirim ver
            Toast.makeText(this, getString(R.string.settings_applies_next_launch), Toast.LENGTH_SHORT).show()
        }
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
                speechManager.applySpeechRate()
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
        binding.btnPrivacyPolicy.setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }
        binding.btnShareCrash.setOnClickListener {
            val log = CrashReporter.lastLog(this)
            if (log.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.crash_none), Toast.LENGTH_SHORT).show()
            } else {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_share_subject))
                    putExtra(Intent.EXTRA_TEXT, log)
                }
                try { startActivity(Intent.createChooser(share, getString(R.string.settings_share_crash))) }
                catch (e: Exception) { Log.e("MainActivity", "share crash fail", e) }
            }
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
            mapController.onResume()
            accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        } catch (e: Exception) { Log.e("MainActivity", "onResume fail", e) }
    }

    override fun onPause() {
        super.onPause()
        try {
            mapController.onPause()
            sensorManager.unregisterListener(this)
        } catch (e: Exception) { Log.e("MainActivity", "onPause fail", e) }
    }

    override fun onStop() {
        // Servis arkada çalışmaya devam ederken Activity'ye referans tutmasın (bellek sızıntısı);
        // yeniden bağlanınca onServiceConnected callback'i tekrar kurar.
        trackingService?.onUpdate = null
        if (isBound) {
            try { unbindService(serviceConnection) } catch (e: Exception) { Log.e("MainActivity", "unbind fail", e) }
            isBound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        speechManager.shutdown()
        mapController.onDetach() // osmdroid kaynaklarını bırak
        super.onDestroy()
    }

    private fun speakGuidedWalkthrough() {
        // Sesli-öncelikli anlatım: önce tam sesli akış, butonlar ikinci seçenek
        val msg = "Yol Arkadaşım uygulamasına hoş geldiniz. Kolay mod aktif. " +
                  "Bu uygulamayı tamamen sesle kullanabilirsiniz. Telefonu hafifçe sallayın ve gitmek istediğiniz yeri söyleyin. " +
                  "Örneğin: Kızılay'a gitmek istiyorum. Hedef seçilince tekrar sallayıp başlat deyin; yolculuğunuz sesli olarak yönlendirilir. " +
                  "Yolculuk sırasında sallayıp neredeyim, kaç durak kaldı, ya da son anonsu duymak için tekrar diyebilirsiniz. " +
                  "Dilerseniz ekranın üstündeki dev butonla durak seçip ortadaki yeşil butonla da başlatabilirsiniz. " +
                  "Zor durumda kalırsanız yardım demeniz yeterli; en alttaki turuncu buton da kayıtlı yakınınızı arar."
        speakMessage(msg)
    }

    private fun setupUiModeSwitcher() {
        binding.switchUiMode.setOnCheckedChangeListener { _, isChecked -> updateUiMode(isChecked) }
    }

    private fun updateUiMode(isModern: Boolean) {
        if (isModern) {
            // Stop any ongoing speech when switching to Modern Mode
            speechManager.stopSpeaking()

            binding.layoutAccessibility.visibility = View.GONE
            binding.layoutModern.visibility = View.VISIBLE
            binding.switchUiMode.text = getString(R.string.mode_modern)
            binding.bottomNavModern.selectedItemId = R.id.nav_tracking
        } else {
            binding.layoutAccessibility.visibility = View.VISIBLE
            binding.layoutModern.visibility = View.GONE
            binding.switchUiMode.text = getString(R.string.mode_easy)
            if (speechManager.isTtsReady && settingsStore.isVoiceGuidanceEnabled()) speakGuidedWalkthrough()
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
        speechManager.setupRecognizer()
        binding.fabVoiceCommand.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
                return@setOnClickListener
            }
            speechManager.startListening()
        }
    }

    private fun processVoiceCommand(command: String) {
        val cmd = command.lowercase(Locale.forLanguageTag("tr-TR"))

        // Acil yardım her durumda önceliklidir
        if (cmd.contains("yardım") || cmd.contains("imdat")) { requestHelp(); return }

        // Son anonsu tekrar et — kör kullanıcı bir anonsu kaçırdığında
        if (cmd.contains("tekrar")) {
            val last = if (isTracking) trackingService?.lastAnnouncement ?: lastLocalAnnouncement
                       else lastLocalAnnouncement
            if (last != null) speakMessage(last)
            else speakMessage(getString(R.string.tts_command_not_understood))
            return
        }

        // Sesle takip başlatma — kör kullanıcının butona ihtiyacı kalmasın
        if (cmd.contains("başlat") || cmd.contains("gidelim")) {
            if (isTracking) speakMessage(getString(R.string.tts_already_tracking))
            else startTrackingByVoice()
            return
        }

        val wantsDestination = cmd.contains("hedef") || cmd.contains("gitmek") || cmd.contains("git") ||
                cmd.contains("ayarla") || cmd.contains("yap") || cmd.contains("istiyorum") || cmd.contains("götür")
        if (wantsDestination && tryVoiceDestination(cmd)) return
        // Eşleşme yoksa hemen pes etme: komut "hedefe kaç durak kaldı" gibi bir
        // durum sorgusu olabilir; aşağıdaki dallara devam et.

        val route = selectedRoute ?: routeRepository.getAllRoutes().firstOrNull() ?: return
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
     * Sesli hedef belirleme. Takip sırasında yalnızca mevcut hatta arar
     * (yolculuk ortasında hat değiştirilemez); takip yokken önce seçili hatta,
     * bulunamazsa 119 hattın TAMAMINDA arar ve en yüksek puanlı hat+durak
     * çiftini seçer. Hat otomatik seçildiyse anonsta hat numarası da söylenir.
     * @return true: hedef ayarlandı ve anons yapıldı
     */
    private fun tryVoiceDestination(cmd: String): Boolean {
        val current = selectedRoute

        // Önce favori takma adları: "evime git" → "Evim" diye kaydedilen durak.
        // Kör kullanıcı için en kısa yol: salla + iki kelime.
        val normalizedCmd = StopMatcher.normalize(cmd)
        for (fav in favoritesStore.getFavoriteStops()) {
            val nick = StopMatcher.normalize(fav.customName ?: continue)
            if (nick.length < 3 || !normalizedCmd.contains(nick)) continue
            val route = routeRepository.getRouteById(fav.routeId) ?: continue
            // Takipteyken hat değiştirilemez; favori ancak mevcut hattaysa geçerli
            if (isTracking && route.routeId != current?.routeId) continue
            val idx = route.stops.indexOfFirst { it.id == fav.stopId }
            if (idx >= 0) {
                applyVoiceDestination(route, idx, announceRoute = !isTracking)
                return true
            }
        }

        if (isTracking) {
            val r = current ?: return false
            val idx = StopMatcher.findBestStopIndex(cmd, r.stops.map { it.name })
            if (idx >= 0) { applyVoiceDestination(r, idx); return true }
            return false
        }
        if (current != null) {
            val idx = StopMatcher.findBestStopIndex(cmd, current.stops.map { it.name })
            if (idx >= 0) { applyVoiceDestination(current, idx); return true }
        }
        var bestRoute: BusRoute? = null
        var bestIdx = -1
        var bestScore = 0.0
        for (r in routeRepository.getAllRoutes()) {
            if (r === current) continue
            val match = StopMatcher.bestMatch(cmd, r.stops.map { it.name }) ?: continue
            if (match.second > bestScore) {
                bestScore = match.second
                bestRoute = r
                bestIdx = match.first
            }
        }
        val chosen = bestRoute
        if (chosen != null && bestScore >= StopMatcher.MATCH_THRESHOLD) {
            applyVoiceDestination(chosen, bestIdx, announceRoute = true)
            return true
        }
        return false
    }

    private fun applyVoiceDestination(route: BusRoute, matchedIdx: Int, announceRoute: Boolean = false) {
        val matchedStop = route.stops[matchedIdx]
        selectedRoute = route
        selectedDestinationStop = matchedStop
        destinationStopIndex = matchedIdx
        binding.layoutRouteInfo.visibility = View.VISIBLE
        binding.textDestinationInfo.text = getString(R.string.destination_format, matchedStop.name)
        binding.textModernRouteName.text = getString(R.string.route_arrow_format, route.routeId, matchedStop.name)
        binding.textMapRouteName.text = getString(R.string.route_arrow_format, route.routeId, matchedStop.name)
        recentDestStore.save(route, matchedStop, matchedIdx)
        if (isTracking) {
            trackingService?.updateDestination(matchedIdx)
            // Redelivery intent'ini tazele: servis sistem tarafından öldürülüp
            // yeniden başlatılırsa yolculuk ESKİ değil GÜNCEL hedefle kurulsun.
            val refresh = Intent(this, TrackingService::class.java).apply {
                putExtra(TrackingService.EXTRA_ROUTE_ID, route.routeId)
                putExtra(TrackingService.EXTRA_DEST_IDX, matchedIdx)
            }
            ContextCompat.startForegroundService(this, refresh)
            speakMessage(getString(R.string.tts_new_destination_set, matchedStop.name))
        } else {
            if (announceRoute) speakMessage(getString(R.string.tts_destination_selected_with_route, route.routeId, matchedStop.name))
            else speakMessage(getString(R.string.tts_destination_selected, matchedStop.name))
            updateButtonState()
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
        // "Dinliyorum" gibi geçici istemler "tekrar" komutunun hafızasına girmesin
        val isListeningPrompt = message == getString(R.string.tts_listening)
        if (!isListeningPrompt) lastLocalAnnouncement = message
        // Takip sırasında tek konuşma otoritesi servistir; iki TTS birbirini kesmesin.
        val svc = trackingService
        if (isTracking && svc != null) {
            svc.speak(message, remember = !isListeningPrompt)
            return
        }
        speechManager.speakLocal(message)
    }

    private fun toggleTracking() { if (isTracking) stopTracking() else startTrackingIfReady() }

    /**
     * "Başlat" sesli komutu: buton akışından farkı, hedef yoksa görsel diyalog
     * açmak yerine sesle yönlendirmesi (kör kullanıcı diyalogla iş yapamaz).
     */
    private fun startTrackingByVoice() {
        if (selectedRoute == null || destinationStopIndex < 0) {
            speakMessage(getString(R.string.tts_say_destination_first))
            return
        }
        if (!locationReadyOrGuide()) return
        startTracking()
    }

    private fun startTrackingIfReady() {
        if (selectedRoute == null || destinationStopIndex < 0) { speakMessage(getString(R.string.tts_select_stop_first)); showRouteSelectionDialog(); return }
        if (!locationReadyOrGuide()) return
        startTracking()
    }

    /**
     * Takip öncesi konum önkoşullarını kontrol eder. Bu kitle için sessiz
     * başarısızlık (buton "Durdur" der ama hiçbir anons gelmez) en kötü sonuç;
     * her engelde sesli yönlendirme verir ve düzeltme yolunu açar.
     * @return true ise takip başlatılabilir
     */
    private fun locationReadyOrGuide(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            speakMessage(getString(R.string.tts_location_permission_needed))
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1001)
            return false
        }
        val locationOn = try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
            else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) { true } // kontrol edilemezse engelleme, servis kendi hatasını loglar
        if (!locationOn) {
            speakMessage(getString(R.string.tts_gps_disabled))
            try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (e: Exception) { Log.e("MainActivity", "Open loc settings fail", e) }
            return false
        }
        return true
    }

    private fun startTracking() {
        try {
            // Rota seçili değilse başlatma: null routeId'li extra, REDELIVER
            // kurtarmasını sessizce bozar (routeId null → restore atlanır).
            val route = selectedRoute
            if (route == null || destinationStopIndex < 0) {
                Log.e("MainActivity", "startTracking aborted: no route/destination")
                return
            }
            // Extras, servis sistem tarafından öldürülüp yeniden başlatılırsa (REDELIVER)
            // yolculuğun kaldığı yerden kurulmasını sağlar.
            val intent = Intent(this, TrackingService::class.java).apply {
                putExtra(TrackingService.EXTRA_ROUTE_ID, route.routeId)
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
            mapController.resetFollow()
            updateButtonState()
            // Anında sesli onay: kör kullanıcı butona/komuta bastığının karşılığını
            // GPS gelene kadar sessizlikte beklemesin (biniş anonsu ayrıca gelecek)
            speakMessage(getString(R.string.tts_tracking_started))
        } catch (e: Exception) { Log.e("MainActivity", "doStartTracking fail", e) }
    }

    /**
     * İlk takipte bir kez: agresif OEM'ler (Xiaomi, Samsung, Oppo…) arka plan
     * servisini öldürüp sesli uyarıları kesebilir. Kullanıcıya pil optimizasyonunu
     * kapatmasını önerir. Rahatsız etmemek için yalnızca bir defa sorulur.
     */
    private fun maybeAskBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (settingsStore.hasBatteryPromptShown()) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) {
            settingsStore.markBatteryPromptShown()
            return
        }
        settingsStore.markBatteryPromptShown()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.battery_opt_title)
            .setMessage(R.string.battery_opt_message)
            .setPositiveButton(R.string.battery_opt_allow) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    // Bazı cihazlar doğrudan isteği desteklemez; genel listeyi aç
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    catch (e2: Exception) { Log.e("MainActivity", "Battery opt intent fail", e2) }
                }
            }
            .setNegativeButton(R.string.battery_opt_later, null)
            .show()
    }

    private fun stopTracking() {
        pendingTrackingStart = false
        isTracking = false
        trackingService?.stopTracking()
        binding.cardMapTripInfo.visibility = View.GONE
        binding.textDirectionStatus.text = ""
        updateButtonState()
        mapController.clearUserMarker()
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
            mapController.updateUserPosition(lastLat, lastLon)
        }
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
            if (binding.modernMapView.visibility == View.VISIBLE) mapController.drawRoute(route, stopIndex, isTracking)
        }
        dialog.show(supportFragmentManager, "route_selection")
    }

    /** @return true: sistem izin diyaloğu gösterildi (eksik izin vardı) */
    private fun requestPermissions(): Boolean {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        return needed.isNotEmpty()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) speechManager.startListening()
        // Takip için konum izni istendikten sonra: verildiyse kullanıcıyı sessiz bırakma
        if (requestCode == 1001) {
            val locationGranted = permissions.withIndex().any { (i, p) ->
                (p == Manifest.permission.ACCESS_FINE_LOCATION || p == Manifest.permission.ACCESS_COARSE_LOCATION) &&
                    grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            }
            if (locationGranted) speakMessage(getString(R.string.tts_permission_granted_can_start))
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            val gForce = sqrt(x*x + y*y + z*z)
            val now = System.currentTimeMillis()
            if (gForce > SHAKE_THRESHOLD) {
                if (now - lastShakeTime > 2000) {
                    lastShakeTime = now
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) speechManager.startListening()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
