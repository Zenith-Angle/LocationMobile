package com.locationmobile.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class ServiceLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val gpsAltitude: Double?,
    val barometerAltitude: Double?,
    val accuracy: Float,
    val speed: Float,
    val timestamp: Long
)

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val gpsAltitude: Double?,
    val barometerAltitude: Double?,
    val accuracy: Float,
    val speed: Float,
    val timestamp: Long
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("latitude", latitude)
            put("longitude", longitude)
            putNullableDouble("gpsAltitude", gpsAltitude)
            putNullableDouble("barometerAltitude", barometerAltitude)
            put("accuracy", accuracy.toDouble())
            put("speed", speed.toDouble())
            put("timestamp", timestamp)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): TrackPoint? {
            val latitude = json.optDouble("latitude", Double.NaN)
            val longitude = json.optDouble("longitude", Double.NaN)
            if (latitude.isNaN() || longitude.isNaN()) {
                return null
            }
            return TrackPoint(
                latitude = latitude,
                longitude = longitude,
                gpsAltitude = json.optNullableDouble("gpsAltitude"),
                barometerAltitude = json.optNullableDouble("barometerAltitude"),
                accuracy = json.optDouble("accuracy", 0.0).toFloat(),
                speed = json.optDouble("speed", 0.0).toFloat(),
                timestamp = json.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}

private fun JSONObject.putNullableDouble(name: String, value: Double?) {
    if (value == null || value.isNaN()) {
        put(name, JSONObject.NULL)
    } else {
        put(name, value)
    }
}

private fun JSONObject.optNullableDouble(name: String): Double? {
    if (!has(name) || isNull(name)) {
        return null
    }
    val value = optDouble(name, Double.NaN)
    return if (value.isNaN()) null else value
}

class LocationForegroundService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "com.locationmobile.app.action.START_TRACKING"
        const val ACTION_STOP = "com.locationmobile.app.action.STOP_TRACKING"
        const val ACTION_LOCATION_UPDATE = "com.locationmobile.app.action.LOCATION_UPDATE"

        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_GPS_ALTITUDE = "extra_gps_altitude"
        const val EXTRA_BAROMETER_ALTITUDE = "extra_barometer_altitude"
        const val EXTRA_ACCURACY = "extra_accuracy"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_TIMESTAMP = "extra_timestamp"
        const val EXTRA_RESET_TRACK = "extra_reset_track"

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
        private const val KEY_LAST_GPS_ALTITUDE = "last_gps_altitude"
        private const val KEY_LAST_BAROMETER_ALTITUDE = "last_barometer_altitude"
        private const val KEY_LAST_ACCURACY = "last_accuracy"
        private const val KEY_LAST_SPEED = "last_speed"
        private const val KEY_LAST_TIMESTAMP = "last_timestamp"
        private const val KEY_TRACK_POINTS = "track_points"
        private const val MAX_CACHED_TRACK_POINTS = 5000

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
                gpsAltitude = prefs.getNullableDouble(KEY_LAST_GPS_ALTITUDE),
                barometerAltitude = prefs.getNullableDouble(KEY_LAST_BAROMETER_ALTITUDE),
                accuracy = prefs.getFloat(KEY_LAST_ACCURACY, 0f),
                speed = prefs.getFloat(KEY_LAST_SPEED, 0f),
                timestamp = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)
            )
        }

        fun getTrackPoints(context: Context): List<TrackPoint> {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TRACK_POINTS, "[]") ?: "[]"
            return runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val point = TrackPoint.fromJson(array.getJSONObject(index))
                        if (point != null) {
                            add(point)
                        }
                    }
                }
            }.getOrElse {
                Log.w(TAG, "读取缓存轨迹失败，将返回空轨迹", it)
                emptyList()
            }
        }

        private fun android.content.SharedPreferences.getNullableDouble(key: String): Double? {
            if (!contains(key)) {
                return null
            }
            val value = java.lang.Double.longBitsToDouble(getLong(key, 0L))
            return if (value.isNaN()) null else value
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var sensorManager: SensorManager
    private var locationCallback: LocationCallback? = null
    private var pressureSensor: Sensor? = null
    private var latestBarometerAltitude: Double? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL)
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL)
            .setWaitForAccurateLocation(false)
            .build()
        createNotificationChannel()
        Log.d(TAG, "前台定位服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking(intent.getBooleanExtra(EXTRA_RESET_TRACK, false))
            ACTION_STOP -> stopTrackingAndSelf()
            else -> {
                if (isTracking(this)) {
                    startTracking(resetTrack = false)
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
        unregisterPressureSensor()
        Log.d(TAG, "前台定位服务已销毁")
    }

    private fun startTracking(resetTrack: Boolean) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "缺少定位权限，停止服务")
            updateTrackingState(false)
            stopSelf()
            return
        }

        if (resetTrack) {
            clearCachedTrack()
        }
        registerPressureSensor()
        startForeground(NOTIFICATION_ID, buildNotification("后台定位运行中"))
        startLocationUpdates()
        updateTrackingState(true)
        Log.d(TAG, "前台定位服务开始运行")
    }

    private fun stopTrackingAndSelf() {
        updateTrackingState(false)
        removeLocationUpdates()
        unregisterPressureSensor()
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
                val point = TrackPoint(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    gpsAltitude = if (location.hasAltitude()) location.altitude else null,
                    barometerAltitude = latestBarometerAltitude,
                    accuracy = location.accuracy,
                    speed = location.speed,
                    timestamp = location.time
                )
                cacheLastLocation(
                    point = point
                )
                appendCachedTrackPoint(point)
                sendLocationBroadcast(point)
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

    private fun sendLocationBroadcast(point: TrackPoint) {
        val intent = Intent(ACTION_LOCATION_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_LATITUDE, point.latitude)
            putExtra(EXTRA_LONGITUDE, point.longitude)
            putExtra(EXTRA_GPS_ALTITUDE, point.gpsAltitude ?: Double.NaN)
            putExtra(EXTRA_BAROMETER_ALTITUDE, point.barometerAltitude ?: Double.NaN)
            putExtra(EXTRA_ACCURACY, point.accuracy)
            putExtra(EXTRA_SPEED, point.speed)
            putExtra(EXTRA_TIMESTAMP, point.timestamp)
        }
        sendBroadcast(intent)
    }

    private fun updateTrackingState(isTracking: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TRACKING_ACTIVE, isTracking)
            .apply()
    }

    private fun cacheLastLocation(point: TrackPoint) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_LATITUDE, java.lang.Double.doubleToRawLongBits(point.latitude))
            .putLong(KEY_LAST_LONGITUDE, java.lang.Double.doubleToRawLongBits(point.longitude))
            .putNullableDouble(KEY_LAST_GPS_ALTITUDE, point.gpsAltitude)
            .putNullableDouble(KEY_LAST_BAROMETER_ALTITUDE, point.barometerAltitude)
            .putFloat(KEY_LAST_ACCURACY, point.accuracy)
            .putFloat(KEY_LAST_SPEED, point.speed)
            .putLong(KEY_LAST_TIMESTAMP, point.timestamp)
            .apply()
    }

    private fun appendCachedTrackPoint(point: TrackPoint) {
        val points = getTrackPoints(this).toMutableList()
        points.add(point)
        val trimmedPoints = if (points.size > MAX_CACHED_TRACK_POINTS) {
            points.takeLast(MAX_CACHED_TRACK_POINTS)
        } else {
            points
        }
        val array = JSONArray()
        trimmedPoints.forEach { array.put(it.toJson()) }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_TRACK_POINTS, array.toString())
            .apply()
    }

    private fun clearCachedTrack() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_TRACK_POINTS)
            .apply()
    }

    private fun android.content.SharedPreferences.Editor.putNullableDouble(
        key: String,
        value: Double?
    ): android.content.SharedPreferences.Editor {
        return if (value == null || value.isNaN()) {
            remove(key)
        } else {
            putLong(key, java.lang.Double.doubleToRawLongBits(value))
        }
    }

    private fun registerPressureSensor() {
        val sensor = pressureSensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun unregisterPressureSensor() {
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(this)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_PRESSURE || event.values.isEmpty()) {
            return
        }
        val pressure = event.values[0]
        if (pressure <= 0f) {
            return
        }
        latestBarometerAltitude = SensorManager.getAltitude(
            SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
            pressure
        ).toDouble()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

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
