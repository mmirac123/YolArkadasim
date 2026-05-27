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

class TrackingService : Service(), TextToSpeech.OnInitListener {

    private val binder = TrackingBinder()
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private var selectedRoute: BusRoute? = null
    private var destinationIndex: Int = -1
    private var stopLats: DoubleArray? = null
    private var stopLons: DoubleArray? = null

    // State
    private var prevIdx = -1
    private var lastAnnouncedNextIdx = -1
    private var lastAnnouncedLeavingPreDest = false
    private var hasAnnouncedTargetReminder = false
    private var hasAnnouncedArrival = false

    var onUpdate: ((Double, Double, Int, Double, Int, Double) -> Unit)? = null

    companion object {
        private const val CHANNEL_ID = "tracking_channel"
        private const val NOTIF_ID = 101
        private const val TAG = "TrackingService"

        init {
            System.loadLibrary("yolarkadasim")
        }
    }

    external fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double
    external fun findNearestStopIndex(curLat: Double, curLon: Double, lats: DoubleArray, lons: DoubleArray, prevIdx: Int): Int
    external fun calculatePolylineDeviation(curLat: Double, curLon: Double, lats: DoubleArray, lons: DoubleArray, prevIdx: Int): Double
    external fun checkRouteDirection(curIdx: Int, prevIdx: Int, destIdx: Int): Int

    inner class TrackingBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, createNotification(getString(R.string.notif_content_init)))
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

        beginLocationUpdates()
    }

    fun stopTracking() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun beginLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { processLocation(it.latitude, it.longitude) }
            }
        }
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        }
    }

    private fun processLocation(lat: Double, lon: Double) {
        val lats = stopLats ?: return
        val lons = stopLons ?: return
        val route = selectedRoute ?: return

        val curIdx = findNearestStopIndex(lat, lon, lats, lons, prevIdx)
        if (curIdx < 0) return

        val deviation = calculatePolylineDeviation(lat, lon, lats, lons, curIdx)
        val direction = checkRouteDirection(curIdx, prevIdx, destinationIndex)

        // Calculate distance to next stop for UI sync
        var distToNext = 0.0
        val nextIdxForUi = if (destinationIndex >= curIdx) curIdx + 1 else curIdx - 1
        if (nextIdxForUi in route.stops.indices) {
            val ns = route.stops[nextIdxForUi]
            distToNext = calculateDistance(lat, lon, ns.lat, ns.lon)
        }

        // Notify activity (added distToNext)
        onUpdate?.invoke(lat, lon, curIdx, deviation, direction, distToNext)

        // Navigation Logic & TTS
        val nextIdx = if (destinationIndex >= curIdx) curIdx + 1 else curIdx - 1
        if (nextIdx in route.stops.indices) {
            val nextStop = route.stops[nextIdx]
            val dist = calculateDistance(lat, lon, nextStop.lat, nextStop.lon)

            updateNotification(getString(R.string.notif_next_stop, nextStop.name, dist.toInt()))

            // 150m Approach
            if (dist <= 150.0 && lastAnnouncedNextIdx != nextIdx) {
                val msg = if (nextIdx == destinationIndex) "İneceğiniz durağa yaklaştınız: ${nextStop.name}" else "Sıradaki durak: ${nextStop.name}"
                speak(msg)
                lastAnnouncedNextIdx = nextIdx
            }

            // 80m Target Reminder
            if (nextIdx == destinationIndex && dist <= 80.0 && !hasAnnouncedTargetReminder) {
                speak("Durağa çok az kaldı. Lütfen kapıya doğru ilerleyin.")
                hasAnnouncedTargetReminder = true
            }
        }

        // Departure from pre-dest
        val preDest = if (destinationIndex > 0) destinationIndex - 1 else -1
        if (curIdx == preDest) {
            val dist = calculateDistance(lat, lon, lats[preDest], lons[preDest])
            if (dist > 50.0 && !lastAnnouncedLeavingPreDest) {
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

        prevIdx = curIdx
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
}
