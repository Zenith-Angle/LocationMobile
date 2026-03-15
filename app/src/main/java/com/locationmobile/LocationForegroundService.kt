package com.locationmobile.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale

data class ServiceLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val speed: Float,
    val timestamp: Long
)

class LocationForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.locationmobile.app.action.START_TRACKING"
        const val ACTION_STOP = "com.locationmobile.app.action.STOP_TRACKING"
        const val ACTION_LOCATION_UPDATE = "com.locationmobile.app.action.LOCATION_UPDATE"

        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_ALTITUDE = "extra_altitude"
        const val EXTRA_ACCURACY = "extra_accuracy"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_TIMESTAMP = "extra_timestamp"

        private const val TAG = "LocationForegroundSvc"
        private const val CHANNEL_ID = "location_tracking_channel"
        private const val CHANNEL_NAME = "持续定位"
        private const val NOTIFICATION_ID = 10086
        private const val UPDATE_INTERVAL = 5000L
        private const val FASTEST_INTERVAL = 2000L

        private const val PREFS_NAME = "location_tracking_prefs"
        private const val KEY_TRACKING_ACTIVE = "tracking_active"
        private const val KEY_LAST_LATITUDE = "last_latitude"
        private const val KEY_LAST_LONGITUDE = "last_longitude"
        private const val KEY_LAST_ALTITUDE = "last_altitude"
        private const val KEY_LAST_ACCURACY = "last_accuracy"
        private const val KEY_LAST_SPEED = "last_speed"
        private const val KEY_LAST_TIMESTAMP = "last_timestamp"

        fun isTracking(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_TRACKING_ACTIVE, false)
        }

        fun getLastLocation(context: Context): ServiceLocationSnapshot? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_LAST_LATITUDE) || !prefs.contains(KEY_LAST_LONGITUDE)) {
                return null
            }
            return ServiceLocationSnapshot(
                latitude = java.lang.Double.longBitsToDouble(
                    prefs.getLong(KEY_LAST_LATITUDE, 0L)
                ),
                longitude = java.lang.Double.longBitsToDouble(
                    prefs.getLong(KEY_LAST_LONGITUDE, 0L)
                ),
                altitude = java.lang.Double.longBitsToDouble(
                    prefs.getLong(KEY_LAST_ALTITUDE, 0L)
                ),
                accuracy = prefs.getFloat(KEY_LAST_ACCURACY, 0f),
                speed = prefs.getFloat(KEY_LAST_SPEED, 0f),
                timestamp = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)
            )
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var locationCallback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL)
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL)
            .setWaitForAccurateLocation(false)
            .build()
        createNotificationChannel()
        Log.d(TAG, "前台定位服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTrackingAndSelf()
            else -> {
                if (isTracking(this)) {
                    startTracking()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeLocationUpdates()
        Log.d(TAG, "前台定位服务已销毁")
    }

    private fun startTracking() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "缺少定位权限，停止服务")
            updateTrackingState(false)
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification("后台定位运行中"))
        startLocationUpdates()
        updateTrackingState(true)
        Log.d(TAG, "前台定位服务开始运行")
    }

    private fun stopTrackingAndSelf() {
        updateTrackingState(false)
        removeLocationUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "前台定位服务已停止")
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    private fun startLocationUpdates() {
        if (locationCallback != null) {
            return
        }
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                val text = String.format(
                    Locale.US,
                    "经度 %.5f, 纬度 %.5f",
                    location.longitude,
                    location.latitude
                )
                notifyLocationChanged(text)
                cacheLastLocation(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = location.altitude,
                    accuracy = location.accuracy,
                    speed = location.speed,
                    timestamp = location.time
                )
                sendLocationBroadcast(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = location.altitude,
                    accuracy = location.accuracy,
                    speed = location.speed,
                    timestamp = location.time
                )
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "请求定位更新失败: 缺少权限", e)
            stopTrackingAndSelf()
        }
    }

    private fun removeLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
    }

    private fun sendLocationBroadcast(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Float,
        speed: Float,
        timestamp: Long
    ) {
        val intent = Intent(ACTION_LOCATION_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_LATITUDE, latitude)
            putExtra(EXTRA_LONGITUDE, longitude)
            putExtra(EXTRA_ALTITUDE, altitude)
            putExtra(EXTRA_ACCURACY, accuracy)
            putExtra(EXTRA_SPEED, speed)
            putExtra(EXTRA_TIMESTAMP, timestamp)
        }
        sendBroadcast(intent)
    }

    private fun updateTrackingState(isTracking: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TRACKING_ACTIVE, isTracking)
            .apply()
    }

    private fun cacheLastLocation(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Float,
        speed: Float,
        timestamp: Long
    ) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_LATITUDE, java.lang.Double.doubleToRawLongBits(latitude))
            .putLong(KEY_LAST_LONGITUDE, java.lang.Double.doubleToRawLongBits(longitude))
            .putLong(KEY_LAST_ALTITUDE, java.lang.Double.doubleToRawLongBits(altitude))
            .putFloat(KEY_LAST_ACCURACY, accuracy)
            .putFloat(KEY_LAST_SPEED, speed)
            .putLong(KEY_LAST_TIMESTAMP, timestamp)
            .apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "用于后台持续定位"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location_icon)
            .setContentTitle("LocationMobile 持续定位中")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun notifyLocationChanged(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}
