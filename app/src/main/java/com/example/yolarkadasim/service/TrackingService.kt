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
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.yolarkadasim.MainActivity
import com.example.yolarkadasim.R
import com.example.yolarkadasim.data.BusRoute
import com.example.yolarkadasim.data.BusStop
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

    var onUpdate: ((Double, Double, Int, Double, Int, Double, Int) -> Unit)? = null

    companion object {
        private const val CHANNEL_ID = "tracking_channel"
        private const val NOTIF_ID = 101
        private const val TAG = "TrackingService"

        init {
            System.loadLibrary("yolarkadasim")
        }
    }

    external fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double
    external fun getEngineDeviation(): Double
    external fun checkRouteDirection(curIdx: Int, prevIdx: Int, destIdx: Int): Int
    external fun initNavigationEngine(storagePath: String, enableLogging: Boolean)
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
        tts = TextToSpeech(this, this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
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
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("tr", "TR"))
            isTtsReady = true
        }
    }

    fun startTracking(route: BusRoute, destIdx: Int, lats: DoubleArray, lons: DoubleArray) {
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
        startStopIndex = -1 // Wait for the first GPS ping to set this accurately

        // Initialize Native Engine
        val isDebug = com.example.yolarkadasim.BuildConfig.DEBUG
        val logPath = applicationContext.getExternalFilesDir(null)?.absolutePath ?: applicationContext.filesDir.absolutePath
        initNavigationEngine(logPath, isDebug)
        setEngineRoute(lats, lons)

        // Try to quickly initialize engine with the best known last location
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && startStopIndex == -1) {
                    processLocation(loc)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error", e)
        }

        // Start location updates directly; the first ping will detect the start stop
        beginLocationUpdates()
    }



    fun stopTracking() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        sensorManager.unregisterListener(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private var currentInterval = 1000L

    private fun beginLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentInterval)
            .setMinUpdateDistanceMeters(0f)
            .build()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                processLocation(location)
                adjustSamplingRate(location.speed) // Speed is in m/s
            }
        }
        
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        }
    }

    /**
     * Dynamically adjusts GPS sampling based on speed (m/s).
     * Stationary/Walking (< 2 m/s): 7 seconds
     * City Driving (2-15 m/s): 4 seconds
     * Fast Driving (> 15 m/s): 2 seconds
     */
    private fun adjustSamplingRate(speed: Float) {
        val newInterval = when {
            speed < 2.0f -> 2000L  // ~7.2 km/h (Stationary or slow walk)
            else -> 1000L          // Moving (1 saniye)
        }

        if (newInterval != currentInterval) {
            currentInterval = newInterval
            Log.d(TAG, "Adjusting GPS interval to: $currentInterval ms (Speed: $speed m/s)")
            
            // Restart updates with new interval
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

            // Do not lock onto a route initially using a wildly inaccurate GPS ping
            if (startStopIndex == -1 && accuracy > 100.0f) {
                Log.w(TAG, "Ignoring inaccurate ping for initial lock-on: $accuracy m")
                return
            }

            // Process Sensor Data Averages
            if (accelCount > 0) {
                lastAccelX = accelXSum / accelCount
                lastAccelY = accelYSum / accelCount
                lastAccelZ = accelZSum / accelCount
                accelXSum = 0f
                accelYSum = 0f
                accelZSum = 0f
                accelCount = 0
            }

            // Send to Engine
            processEngineLocation(lat, lon, speed, bearing, accuracy, lastAccelX, lastAccelY, lastAccelZ, System.currentTimeMillis())

            val lats = stopLats ?: return
            val lons = stopLons ?: return
            val route = selectedRoute ?: return

            val curIdx = getEngineActiveStopIndex()
            if (curIdx < 0) return

            // --- First Time Detection & Announcement ---
            if (startStopIndex == -1) {
                startStopIndex = curIdx
                val distToStop = calculateDistance(lat, lon, route.stops[curIdx].lat, route.stops[curIdx].lon)
                val stopName = route.stops[curIdx].name
                val totalStops = Math.abs(destinationIndex - startStopIndex)

                if (distToStop <= 35.0) {
                    speak("${stopName} durağından biniş algılandı. Hedefinize ${totalStops} durak kaldı.")
                } else {
                    speak("En yakın biniş durağı: ${stopName}. Lütfen durağa gidiniz. Yolculuğunuz ${totalStops} durak sürecek.")
                }
            }

            val deviation = getEngineDeviation()
            val direction = checkRouteDirection(curIdx, prevIdx, destinationIndex)

            // Calculate distance to next stop for UI sync
            var distToNext = 0.0
            val nextIdxForUi = if (destinationIndex >= curIdx) curIdx + 1 else curIdx - 1
            if (nextIdxForUi in route.stops.indices) {
                val ns = route.stops[nextIdxForUi]
                distToNext = calculateDistance(lat, lon, ns.lat, ns.lon)
            }

            // Notify activity
            onUpdate?.invoke(lat, lon, curIdx, deviation, direction, distToNext, startStopIndex)

            // TTS and Logic...
            processNavigationLogic(lat, lon, curIdx, route, distToNext)

            prevIdx = curIdx
        } catch (e: Exception) {
            Log.e(TAG, "processLocation error", e)
        }
    }

    private fun processNavigationLogic(lat: Double, lon: Double, curIdx: Int, route: BusRoute, distToNext: Double) {
        val lats = stopLats ?: return
        val lons = stopLons ?: return
        
        val nextIdx = if (destinationIndex >= curIdx) curIdx + 1 else curIdx - 1
        if (nextIdx in route.stops.indices) {
            val nextStop = route.stops[nextIdx]
            
            updateNotification(getString(R.string.notif_next_stop, nextStop.name, distToNext.toInt()))

            // 150m Approach
            if (distToNext <= 150.0 && lastAnnouncedNextIdx != nextIdx) {
                val msg = if (nextIdx == destinationIndex) "İneceğiniz durağa yaklaştınız: ${nextStop.name}" else "Sıradaki durak: ${nextStop.name}"
                speak(msg)
                lastAnnouncedNextIdx = nextIdx
            }

            // 80m Target Reminder
            if (nextIdx == destinationIndex && distToNext <= 80.0 && !hasAnnouncedTargetReminder) {
                speak("Durağa çok az kaldı. Lütfen kapıya doğru ilerleyin.")
                hasAnnouncedTargetReminder = true
            }
        }

        // Departure from pre-dest
        val preDest = if (destinationIndex > 0) destinationIndex - 1 else -1
        if (curIdx == preDest) {
            val d = calculateDistance(lat, lon, lats[preDest], lons[preDest])
            if (d > 50.0 && !lastAnnouncedLeavingPreDest) {
                speak("Bir önceki duraktan hareket edildi. Bir sonraki durakta ineceksiniz. Lütfen hazırlanın.")
                lastAnnouncedLeavingPreDest = true
            }
        }

        // Arrival
        val distToDest = calculateDistance(lat, lon, lats[destinationIndex], lons[destinationIndex])
        if (curIdx == destinationIndex && distToDest < 35.0 && !hasAnnouncedArrival) {
            speak("İneceğiniz durağa geldiniz. Lütfen ininiz.")
            hasAnnouncedArrival = true
            updateNotification("HEDEFE VARILDI!")
        }
    }

    private fun speak(text: String) {
        if (isTtsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "track_svc")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notification_channel_desc)
            }
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
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use default for now
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, createNotification(content))
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            accelXSum += event.values[0]
            accelYSum += event.values[1]
            accelZSum += event.values[2]
            accelCount++
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
