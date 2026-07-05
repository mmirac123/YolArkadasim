package com.example.yolarkadasim.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.yolarkadasim.MainActivity
import com.example.yolarkadasim.R
import com.example.yolarkadasim.data.BusRoute
import com.example.yolarkadasim.data.BusStop
import com.example.yolarkadasim.data.RouteRepository
import com.example.yolarkadasim.data.SettingsStore
import com.example.yolarkadasim.data.StatsStore
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class TrackingService : Service(), TextToSpeech.OnInitListener, SensorEventListener {

    private val binder = TrackingBinder()
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private lateinit var sensorManager: SensorManager
    private var linearAccelSensor: Sensor? = null

    private var accelXSum = 0f
    private var accelYSum = 0f
    private var accelZSum = 0f
    private var accelCount = 0

    private var lastAccelX = 0f
    private var lastAccelY = 0f
    private var lastAccelZ = 0f

    private var selectedRoute: BusRoute? = null
    private var startStopIndex: Int = -1
    private var destinationIndex: Int = -1
    private var stopLats: DoubleArray? = null
    private var stopLons: DoubleArray? = null

    // State
    private var prevIdx = -1
    private var lastAnnouncedNextIdx = -1
    private var lastAnnouncedLeavingPreDest = false
    private var hasAnnouncedTargetReminder = false
    private var hasAnnouncedArrival = false
    private var isStartStopConfirmed = false
    private var startStopDetectionCount = 0

    private var startBatteryLevel: Int = -1
    private var sessionGpsDeviationSum = 0.0
    private var sessionGpsDeviationCount = 0
    private var sessionWrongDirectionCount = 0
    private var lastDirectionState = 0
    private var lastWrongDirectionAnnounceMs = 0L

    private lateinit var settingsStore: SettingsStore
    private var vibrator: Vibrator? = null

    var onUpdate: ((Double, Double, Int, Double, Int, Double, Int) -> Unit)? = null

    var isTrackingActive = false
        private set
    val currentRoute: BusRoute? get() = selectedRoute
    val currentDestinationIndex: Int get() = destinationIndex

    companion object {
        private const val CHANNEL_ID = "tracking_channel"
        private const val NOTIF_ID = 101
        private const val TAG = "TrackingService"
        private const val WRONG_DIRECTION_COOLDOWN_MS = 30_000L

        const val EXTRA_ROUTE_ID = "extra_route_id"
        const val EXTRA_DEST_IDX = "extra_dest_idx"

        init {
            System.loadLibrary("yolarkadasim")
        }
    }

    external fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double
    external fun getEngineDeviation(): Double
    external fun checkRouteDirection(curIdx: Int, prevIdx: Int, destIdx: Int): Int
    external fun initNavigationEngine(storagePath: String, enableLogging: Boolean)
    external fun resetEngine()
    external fun setEngineRoute(lats: DoubleArray, lons: DoubleArray)
    external fun processEngineLocation(lat: Double, lon: Double, speed: Float, bearing: Float, accuracy: Float, accelX: Float, accelY: Float, accelZ: Float, timestamp: Long)
    external fun getEngineActiveStopIndex(): Int
    external fun getEngineStopState(index: Int): Int
    external fun getEngineSmoothedLat(): Double
    external fun getEngineSmoothedLon(): Double

    inner class TrackingBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        tts = TextToSpeech(this, this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification(getString(R.string.notif_content_init))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        linearAccelSensor?.let {
            // UI frekansı (~60ms) Kalman girdisi için yeterli; GAME (~20ms) 3 kat
            // fazla uyandırma yapıp batarya tüketiyordu — veriler zaten ortalanıyor.
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        // Sistem servisi öldürüp intent'i yeniden teslim ettiyse yolculuğu kaldığı yerden kur.
        // Normal akışta Activity binder üzerinden startTracking çağırır; extras sadece
        // kurtarma senaryosunda kullanılır.
        if (!isTrackingActive) {
            val routeId = intent?.getStringExtra(EXTRA_ROUTE_ID)
            val destIdx = intent?.getIntExtra(EXTRA_DEST_IDX, -1) ?: -1
            if (routeId != null && destIdx >= 0) {
                try {
                    val repo = RouteRepository(applicationContext)
                    val route = repo.getRouteById(routeId)
                    if (route != null && destIdx < route.stops.size) {
                        startTracking(route, destIdx, repo.getStopLatitudes(route), repo.getStopLongitudes(route))
                        speak(getString(R.string.tts_tracking_restored))
                    }
                } catch (e: Exception) { Log.e(TAG, "Trip restore failed", e) }
            }
        }

        return START_REDELIVER_INTENT
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("tr", "TR"))
            applyTtsSettings()
            isTtsReady = true
        }
    }

    /** Ayarlardaki konuşma hızını uygular (yaşlı kullanıcılar için yavaşlatılabilir). */
    fun applyTtsSettings() {
        try { tts?.setSpeechRate(settingsStore.getSpeechRate() / 100f) } catch (e: Exception) { Log.e(TAG, "TTS rate fail", e) }
    }

    fun updateDestination(newDestIndex: Int) {
        destinationIndex = newDestIndex
        hasAnnouncedTargetReminder = false
        hasAnnouncedArrival = false
        lastAnnouncedLeavingPreDest = false
        lastAnnouncedNextIdx = -1
        selectedRoute?.stops?.getOrNull(newDestIndex)?.let {
            val notification = createNotification("Yeni Hedef: ${it.name}")
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(NOTIF_ID, notification)
        }
    }

    fun startTracking(route: BusRoute, destIdx: Int, lats: DoubleArray, lons: DoubleArray) {
        isTrackingActive = true
        selectedRoute = route
        destinationIndex = destIdx
        stopLats = lats
        stopLons = lons
        
        prevIdx = -1
        lastAnnouncedNextIdx = -1
        lastAnnouncedLeavingPreDest = false
        hasAnnouncedTargetReminder = false
        hasAnnouncedArrival = false
        isStartStopConfirmed = false
        startStopDetectionCount = 0
        startStopIndex = -1

        sessionGpsDeviationSum = 0.0
        sessionGpsDeviationCount = 0
        sessionWrongDirectionCount = 0
        lastDirectionState = 0

        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            startBatteryLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) { startBatteryLevel = -1 }

        val logPath = applicationContext.getExternalFilesDir(null)?.absolutePath ?: applicationContext.filesDir.absolutePath
        initNavigationEngine(logPath, false)
        resetEngine() // Önceki seyahatin Kalman/FSM durumu yeni seyahate taşınmasın
        setEngineRoute(lats, lons)

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && startStopIndex == -1) {
                    processLocation(loc)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error", e)
        }

        beginLocationUpdates()
    }

    fun stopTracking() {
        isTrackingActive = false
        try {
            val stats = StatsStore(this)
            stats.addGpsDeviationBatch(sessionGpsDeviationSum, sessionGpsDeviationCount)
            stats.addWrongDirectionBatch(sessionWrongDirectionCount)
            if (startBatteryLevel != -1) {
                val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                val endBatteryLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val drop = startBatteryLevel - endBatteryLevel
                if (drop > 0) stats.addBatteryDrop(drop.toFloat())
            }
        } catch (e: Exception) { Log.e(TAG, "Stats save error", e) }

        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        sensorManager.unregisterListener(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private var currentInterval = 1000L
    private var lastIntervalSwitchMs = 0L

    private fun beginLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentInterval)
            .setMinUpdateDistanceMeters(0f)
            .build()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                processLocation(location)
                adjustSamplingRate(location.speed)
            }
        }
        
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        }
    }

    private fun adjustSamplingRate(speed: Float) {
        // Histerezis (1.5/2.5 m/s) + 10 sn bekleme: otobüs trafikte eşik etrafında
        // salındığında her saniye remove/request döngüsü GPS kilitlenmesini bozup
        // batarya yakıyordu.
        val newInterval = when {
            speed < 1.5f -> 2000L
            speed > 2.5f -> 1000L
            else -> currentInterval
        }
        val now = System.currentTimeMillis()
        if (newInterval != currentInterval && now - lastIntervalSwitchMs > 10_000L) {
            lastIntervalSwitchMs = now
            currentInterval = newInterval
            locationCallback?.let {
                fusedLocationClient.removeLocationUpdates(it)
                beginLocationUpdates()
            }
        }
    }

    private fun processLocation(location: android.location.Location) {
        try {
            val lat = location.latitude
            val lon = location.longitude
            val speed = location.speed
            val bearing = location.bearing
            val accuracy = location.accuracy

            if (accelCount > 0) {
                lastAccelX = accelXSum / accelCount
                lastAccelY = accelYSum / accelCount
                lastAccelZ = accelZSum / accelCount
                accelXSum = 0f; accelYSum = 0f; accelZSum = 0f; accelCount = 0
            }

            processEngineLocation(lat, lon, speed, bearing, accuracy, lastAccelX, lastAccelY, lastAccelZ, System.currentTimeMillis())

            val lats = stopLats ?: return
            val lons = stopLons ?: return
            val route = selectedRoute ?: return

            val curIdx = getEngineActiveStopIndex()
            if (curIdx < 0) return

            if (startStopIndex == -1) {
                startStopIndex = curIdx
                val distToStop = calculateDistance(lat, lon, route.stops[curIdx].lat, route.stops[curIdx].lon)
                val stopName = route.stops[curIdx].name
                val totalStops = Math.abs(destinationIndex - startStopIndex)

                if (distToStop <= 35.0) {
                    speak(getString(R.string.tts_boarding_detected, stopName, totalStops))
                    vibrateDouble()
                } else {
                    speak(getString(R.string.tts_walk_to_stop, stopName, totalStops))
                }
            }

            val deviation = getEngineDeviation()
            val direction = checkRouteDirection(curIdx, prevIdx, destinationIndex)

            sessionGpsDeviationSum += deviation
            sessionGpsDeviationCount++
            if (direction == 1 && lastDirectionState != 1) {
                sessionWrongDirectionCount++
                val now = System.currentTimeMillis()
                if (now - lastWrongDirectionAnnounceMs > WRONG_DIRECTION_COOLDOWN_MS) {
                    lastWrongDirectionAnnounceMs = now
                    speak(getString(R.string.tts_wrong_bus_direction))
                    vibrateWarning()
                    updateNotification(getString(R.string.notif_wrong_direction))
                }
            }
            lastDirectionState = direction

            var distToNext = 0.0
            val nextIdxForUi = if (destinationIndex >= curIdx) curIdx + 1 else curIdx - 1
            if (nextIdxForUi in route.stops.indices) {
                val ns = route.stops[nextIdxForUi]
                distToNext = calculateDistance(lat, lon, ns.lat, ns.lon)
            }

            onUpdate?.invoke(lat, lon, curIdx, deviation, direction, distToNext, startStopIndex)
            processNavigationLogic(lat, lon, curIdx, route, distToNext)
            prevIdx = curIdx
        } catch (e: Exception) { Log.e(TAG, "processLocation error", e) }
    }

    private fun processNavigationLogic(lat: Double, lon: Double, curIdx: Int, route: BusRoute, distToNext: Double) {
        val lats = stopLats ?: return
        val lons = stopLons ?: return
        
        val nextIdx = if (destinationIndex >= curIdx) curIdx + 1 else curIdx - 1
        if (nextIdx in route.stops.indices) {
            val nextStop = route.stops[nextIdx]
            // Mesafeyi 50 m'lik kovalara yuvarla: bildirim ancak anlamlı değişimde yenilensin
            val bucketedDist = (distToNext.toInt() / 50) * 50
            updateNotification(getString(R.string.notif_next_stop, nextStop.name, bucketedDist))
            if (distToNext <= 150.0 && lastAnnouncedNextIdx != nextIdx) {
                if (nextIdx == destinationIndex) {
                    speak(getString(R.string.tts_arriving, nextStop.name))
                    vibrateDouble()
                } else {
                    speak(getString(R.string.tts_next_stop, nextStop.name), urgent = false)
                    vibrateShort()
                }
                lastAnnouncedNextIdx = nextIdx
            }
            if (nextIdx == destinationIndex && distToNext <= 80.0 && !hasAnnouncedTargetReminder) {
                speak(getString(R.string.tts_prepare_to_exit))
                hasAnnouncedTargetReminder = true
            }
        }

        val preDest = if (destinationIndex > 0) destinationIndex - 1 else -1
        if (curIdx == preDest) {
            val d = calculateDistance(lat, lon, lats[preDest], lons[preDest])
            if (d > 50.0 && !lastAnnouncedLeavingPreDest) {
                speak(getString(R.string.tts_next_is_destination))
                vibrateDouble()
                lastAnnouncedLeavingPreDest = true
            }
        }

        val distToDest = calculateDistance(lat, lon, lats[destinationIndex], lons[destinationIndex])
        if (curIdx == destinationIndex && distToDest < 35.0 && !hasAnnouncedArrival) {
            speak(getString(R.string.tts_arrived_get_off))
            vibrateArrival()
            hasAnnouncedArrival = true
            updateNotification(getString(R.string.notif_arrived))
        }
    }

    /**
     * Tüm sesli anonsların tek çıkış noktası. Activity de takip sırasında buraya
     * yönlendirir; böylece iki TTS birbirinin sözünü kesmez.
     * @param urgent true: mevcut anonsu keser (varış, yanlış yön gibi kritik uyarılar);
     *               false: kuyruğa eklenir, süren anons yarıda kalmaz.
     */
    fun speak(text: String, urgent: Boolean = true) {
        if (!isTtsReady) return
        val queueMode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, (settingsStore.getVoiceLevel() / 100f).coerceIn(0f, 1f))
        }
        tts?.speak(text, queueMode, params, "track_svc")
    }

    // Titreşim kalıpları: görme/işitme zorluğu yaşayan kullanıcı için sesin yanında
    // ikinci bir kanal. Kalıplar birbirinden ayırt edilebilir olacak şekilde seçildi.
    private fun vibrate(pattern: LongArray) {
        try {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (e: Exception) { Log.e(TAG, "Vibrate fail", e) }
    }

    private fun vibrateShort() = vibrate(longArrayOf(0, 150))                       // sıradaki durak
    private fun vibrateDouble() = vibrate(longArrayOf(0, 250, 150, 250))            // hedefe yaklaşma / biniş
    private fun vibrateArrival() = vibrate(longArrayOf(0, 600, 200, 600, 200, 600)) // varış
    private fun vibrateWarning() = vibrate(longArrayOf(0, 400, 100, 400, 100, 400, 100, 400)) // yanlış yön

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title_tracking))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private var lastNotificationText: String? = null

    private fun updateNotification(content: String) {
        // Her GPS tikinde (1 sn) aynı bildirimi yeniden yayınlamak sistemi meşgul
        // ediyor ve Android'in bildirim hız sınırına takılabiliyordu.
        if (content == lastNotificationText) return
        lastNotificationText = content
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, createNotification(content))
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            accelXSum += event.values[0]; accelYSum += event.values[1]; accelZSum += event.values[2]; accelCount++
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
