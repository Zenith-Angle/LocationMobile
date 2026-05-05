package com.locationmobile.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "LocationMobile"
        private const val REQUEST_FOREGROUND_LOCATION = 1001
        private const val REQUEST_BACKGROUND_LOCATION = 1002
        private const val REQUEST_NOTIFICATIONS = 1003
        private const val MENU_IMPORT_GPX = 2001
        private const val MENU_IMPORT_GEOJSON = 2002
        private const val MENU_EXPORT_GPX = 2003
        private const val MENU_EXPORT_GEOJSON = 2004
        private const val DEFAULT_EXPORT_FILE_NAME = "LocationMobile-track"
    }

    private lateinit var webView: WebView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var sensorManager: SensorManager

    private var cesiumIonToken: String = ""
    private var tiandituToken: String = ""
    private var isContinuousTracking = false
    private var pendingStartTracking = false
    private var locationReceiverRegistered = false
    private var webPageReady = false
    private var pendingCachedTrackSync = false
    private var pressureSensor: Sensor? = null
    private var latestBarometerAltitude: Double? = null
    private var pendingExportFormat: String? = null

    private val locationUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != LocationForegroundService.ACTION_LOCATION_UPDATE) {
                return
            }
            val latitude = intent.getDoubleExtra(LocationForegroundService.EXTRA_LATITUDE, Double.NaN)
            val longitude = intent.getDoubleExtra(LocationForegroundService.EXTRA_LONGITUDE, Double.NaN)
            if (latitude.isNaN() || longitude.isNaN()) {
                return
            }
            val gpsAltitude = intent.getNullableDoubleExtra(
                LocationForegroundService.EXTRA_GPS_ALTITUDE
            )
            val barometerAltitude = intent.getNullableDoubleExtra(
                LocationForegroundService.EXTRA_BAROMETER_ALTITUDE
            )
            val accuracy = intent.getFloatExtra(LocationForegroundService.EXTRA_ACCURACY, 0f)
            val speed = intent.getFloatExtra(LocationForegroundService.EXTRA_SPEED, 0f)
            val timestamp = intent.getLongExtra(
                LocationForegroundService.EXTRA_TIMESTAMP,
                System.currentTimeMillis()
            )
            val point = TrackPoint(
                latitude = latitude,
                longitude = longitude,
                gpsAltitude = gpsAltitude,
                barometerAltitude = barometerAltitude,
                accuracy = accuracy,
                speed = speed,
                timestamp = timestamp
            )
            pushLocationToWeb(point, updateType = "continuous", shouldFly = false)
        }
    }

    private val importTrackLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@registerForActivityResult
        }
        val uri = result.data?.data ?: return@registerForActivityResult
        importTrackFromUri(uri)
    }

    private val exportTrackLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val format = pendingExportFormat
        pendingExportFormat = null
        if (result.resultCode != Activity.RESULT_OK || format == null) {
            return@registerForActivityResult
        }
        val uri = result.data?.data ?: return@registerForActivityResult
        requestTrackPayloadForExport(format, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadLocalTokens()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        setupWebView()
        checkLocationPermission()
        syncTrackingState()
    }

    override fun onStart() {
        super.onStart()
        registerLocationReceiverIfNeeded()
    }

    override fun onStop() {
        super.onStop()
        unregisterLocationReceiverIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        registerPressureSensor()
        syncTrackingState()
        if (pendingStartTracking) {
            continuePendingTrackingStartIfPossible()
        }
        syncCachedTrackToWeb()
    }

    override fun onPause() {
        super.onPause()
        unregisterPressureSensor()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::webView.isInitialized) {
            webView.destroy()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_IMPORT_GPX, Menu.NONE, "导入 GPX")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_IMPORT_GEOJSON, Menu.NONE, "导入 GeoJSON")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_EXPORT_GPX, Menu.NONE, "导出 GPX")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_EXPORT_GEOJSON, Menu.NONE, "导出 GeoJSON")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_IMPORT_GPX -> {
                openTrackImportPicker("gpx")
                true
            }
            MENU_IMPORT_GEOJSON -> {
                openTrackImportPicker("geojson")
                true
            }
            MENU_EXPORT_GPX -> {
                openTrackExportPicker("gpx")
                true
            }
            MENU_EXPORT_GEOJSON -> {
                openTrackExportPicker("geojson")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun syncTrackingState() {
        isContinuousTracking = LocationForegroundService.isTracking(this)
    }

    private fun registerLocationReceiverIfNeeded() {
        if (locationReceiverRegistered) {
            return
        }
        val filter = IntentFilter(LocationForegroundService.ACTION_LOCATION_UPDATE)
        ContextCompat.registerReceiver(
            this,
            locationUpdateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        locationReceiverRegistered = true
    }

    private fun unregisterLocationReceiverIfNeeded() {
        if (!locationReceiverRegistered) {
            return
        }
        unregisterReceiver(locationUpdateReceiver)
        locationReceiverRegistered = false
    }

    private fun loadLocalTokens() {
        cesiumIonToken = BuildConfig.CESIUM_ION_TOKEN.trim()
        tiandituToken = BuildConfig.TIANDITU_TOKEN.trim()
        Log.d(TAG, "Token 来源: BuildConfig")
    }

    private fun injectTokens() {
        val cesiumToken = JSONObject.quote(cesiumIonToken)
        val tdtToken = JSONObject.quote(tiandituToken)
        val script = """
            (function() {
                if (typeof window.applyNativeTokens === 'function') {
                    window.applyNativeTokens($cesiumToken, $tdtToken);
                    console.log('Token 注入完成');
                } else {
                    console.warn('applyNativeTokens 未就绪');
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Token 注入结果: $result")
        }
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            databaseEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webPageReady = true
                injectTokens()
                if (pendingCachedTrackSync || isContinuousTracking) {
                    pendingCachedTrackSync = false
                    syncCachedTrackToWeb()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                showError("页面加载失败: $description")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d(
                        TAG,
                        "WebView控制台[${it.messageLevel()}]: ${it.message()} " +
                            "-- From line ${it.lineNumber()} of ${it.sourceId()}"
                    )
                }
                return true
            }
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.addJavascriptInterface(WebAppInterface(), "Android")
        webView.loadUrl("https://appassets.androidplatform.net/assets/cesium/index.html")
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun getLocation() {
            runOnUiThread { requestCurrentLocation() }
        }

        @JavascriptInterface
        fun startTracking() {
            runOnUiThread { startContinuousLocationUpdates() }
        }

        @JavascriptInterface
        fun stopTracking() {
            runOnUiThread { stopContinuousLocationUpdates() }
        }

        @JavascriptInterface
        fun importTrack(format: String) {
            runOnUiThread { openTrackImportPicker(format) }
        }

        @JavascriptInterface
        fun exportTrack(format: String) {
            runOnUiThread { openTrackExportPicker(format) }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "网页日志: $message")
        }
    }

    private fun checkLocationPermission() {
        if (hasForegroundLocationPermission()) {
            return
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            AlertDialog.Builder(this)
                .setTitle("需要定位权限")
                .setMessage("请允许定位权限，否则无法显示和跟踪当前位置。")
                .setPositiveButton("授予权限") { _, _ -> requestForegroundLocationPermission() }
                .setNegativeButton("取消", null)
                .show()
        } else {
            requestForegroundLocationPermission()
        }
    }

    private fun hasForegroundLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestForegroundLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQUEST_FOREGROUND_LOCATION
        )
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("需要始终允许定位")
                .setMessage("请在系统权限页把位置权限改为“始终允许”，否则锁屏或后台时会停止定位。")
                .setPositiveButton("去设置") { _, _ -> openAppSettings() }
                .setNegativeButton("取消") { _, _ -> pendingStartTracking = false }
                .show()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQUEST_BACKGROUND_LOCATION
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_FOREGROUND_LOCATION -> {
                if (!hasForegroundLocationPermission()) {
                    if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        showPermissionDeniedDialog()
                    } else {
                        Toast.makeText(this, "需要定位权限才能使用定位功能", Toast.LENGTH_LONG).show()
                    }
                    pendingStartTracking = false
                    return
                }
                continuePendingTrackingStartIfPossible()
            }

            REQUEST_BACKGROUND_LOCATION -> {
                if (!hasBackgroundLocationPermission()) {
                    Toast.makeText(
                        this,
                        "未授予后台定位权限，锁屏后可能会中断",
                        Toast.LENGTH_LONG
                    ).show()
                    pendingStartTracking = false
                    return
                }
                continuePendingTrackingStartIfPossible()
            }

            REQUEST_NOTIFICATIONS -> {
                if (!pendingStartTracking) {
                    return
                }
                if (!hasNotificationPermission()) {
                    Toast.makeText(
                        this,
                        "未授予通知权限，系统可能限制前台服务提示",
                        Toast.LENGTH_LONG
                    ).show()
                }
                startTrackingService()
            }
        }
    }

    private fun continuePendingTrackingStartIfPossible() {
        if (!pendingStartTracking) {
            return
        }
        when {
            !hasForegroundLocationPermission() -> requestForegroundLocationPermission()
            !hasBackgroundLocationPermission() -> requestBackgroundLocationPermission()
            !hasNotificationPermission() -> requestNotificationPermission()
            else -> startTrackingService()
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("权限被拒绝")
            .setMessage("定位权限已被永久拒绝，请在系统设置中手动开启。")
            .setPositiveButton("去设置") { _, _ -> openAppSettings() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }

    private fun requestCurrentLocation() {
        if (!hasForegroundLocationPermission()) {
            Toast.makeText(this, "请先授予定位权限", Toast.LENGTH_SHORT).show()
            checkLocationPermission()
            return
        }

        try {
            val cancellationTokenSource = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location ->
                handleLocationUpdate(location, true)
            }.addOnFailureListener { exception ->
                Log.e(TAG, "获取位置失败", exception)
                getLastKnownLocation()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "定位权限异常", e)
            showError("定位权限异常")
        }
    }

    private fun getLastKnownLocation() {
        if (!hasForegroundLocationPermission()) {
            return
        }
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        handleLocationUpdate(location, false)
                    } else {
                        showError("无法获取位置信息，请确保 GPS 已开启")
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "获取最后位置失败", exception)
                    showError("定位失败: ${exception.message}")
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "定位权限异常", e)
            showError("定位权限异常")
        }
    }

    private fun startContinuousLocationUpdates() {
        if (isContinuousTracking) {
            Toast.makeText(this, "持续定位已在运行", Toast.LENGTH_SHORT).show()
            return
        }

        pendingStartTracking = true
        continuePendingTrackingStartIfPossible()
    }

    private fun startTrackingService() {
        if (isContinuousTracking) {
            pendingStartTracking = false
            return
        }
        val intent = Intent(this, LocationForegroundService::class.java).apply {
            action = LocationForegroundService.ACTION_START
            putExtra(LocationForegroundService.EXTRA_RESET_TRACK, true)
        }
        ContextCompat.startForegroundService(this, intent)

        pendingStartTracking = false
        isContinuousTracking = true
        Toast.makeText(this, "已开启后台持续定位", Toast.LENGTH_SHORT).show()
        maybeShowBatteryOptimizationHint()
    }

    private fun stopContinuousLocationUpdates() {
        pendingStartTracking = false

        val intent = Intent(this, LocationForegroundService::class.java).apply {
            action = LocationForegroundService.ACTION_STOP
        }
        startService(intent)

        isContinuousTracking = false
        Toast.makeText(this, "已停止持续定位", Toast.LENGTH_SHORT).show()
    }

    private fun maybeShowBatteryOptimizationHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            return
        }
        AlertDialog.Builder(this)
            .setTitle("建议关闭电池优化")
            .setMessage("若系统开启电池优化，部分机型在锁屏后仍可能杀后台。建议将本应用加入“不受限制”名单。")
            .setPositiveButton("去设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun handleLocationUpdate(location: Location?, isSingleUpdate: Boolean) {
        if (location == null) {
            showError("无法获取位置信息")
            return
        }
        val point = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            gpsAltitude = if (location.hasAltitude()) location.altitude else null,
            barometerAltitude = latestBarometerAltitude,
            accuracy = location.accuracy,
            speed = location.speed,
            timestamp = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
        pushLocationToWeb(
            point = point,
            updateType = if (isSingleUpdate) "single" else "continuous",
            shouldFly = isSingleUpdate
        )

        if (isSingleUpdate) {
            Toast.makeText(
                this,
                "定位成功 (精度: ${location.accuracy.toInt()} 米)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun pushLocationToWeb(
        point: TrackPoint,
        updateType: String,
        shouldFly: Boolean
    ) {
        if (!webPageReady) {
            pendingCachedTrackSync = true
            return
        }
        val payload = point.toWebJson().apply {
            put("updateType", updateType)
            put("shouldFly", shouldFly)
        }
        val script = "window.applyLocationUpdate(${payload.toString()})"
        webView.evaluateJavascript(script) { result ->
            Log.v(TAG, "JavaScript 执行结果: $result")
        }
    }

    private fun pushTrackBatchToWeb(points: List<TrackPoint>, replace: Boolean) {
        if (points.isEmpty()) {
            return
        }
        if (!webPageReady) {
            pendingCachedTrackSync = true
            return
        }
        val payload = JSONObject().apply {
            put("replace", replace)
            put("points", JSONArray().also { array ->
                points.forEach { array.put(it.toWebJson()) }
            })
        }
        val script = "window.applyTrackBatch(${payload.toString()})"
        webView.evaluateJavascript(script) { result ->
            Log.v(TAG, "轨迹批量同步结果: $result")
        }
    }

    private fun syncCachedTrackToWeb() {
        val points = LocationForegroundService.getTrackPoints(this)
        if (points.isNotEmpty()) {
            pushTrackBatchToWeb(points, replace = true)
            return
        }
        LocationForegroundService.getLastLocation(this)?.let { snapshot ->
            pushLocationToWeb(
                point = TrackPoint(
                    latitude = snapshot.latitude,
                    longitude = snapshot.longitude,
                    gpsAltitude = snapshot.gpsAltitude,
                    barometerAltitude = snapshot.barometerAltitude,
                    accuracy = snapshot.accuracy,
                    speed = snapshot.speed,
                    timestamp = snapshot.timestamp
                ),
                updateType = "continuous",
                shouldFly = false
            )
        }
    }

    private fun TrackPoint.toWebJson(): JSONObject {
        return JSONObject().apply {
            put("longitude", longitude)
            put("latitude", latitude)
            putNullableDouble("gpsAltitude", gpsAltitude)
            putNullableDouble("barometerAltitude", barometerAltitude)
            put("accuracy", accuracy.toDouble())
            put("speed", speed.toDouble())
            put("timestamp", timestamp)
        }
    }

    private fun openTrackImportPicker(format: String) {
        val mimeTypes = when (format.lowercase(Locale.US)) {
            "gpx" -> arrayOf("application/gpx+xml", "text/xml", "application/xml", "*/*")
            "geojson" -> arrayOf("application/geo+json", "application/json", "*/*")
            else -> arrayOf("application/gpx+xml", "application/geo+json", "application/json", "*/*")
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }
        importTrackLauncher.launch(intent)
    }

    private fun openTrackExportPicker(format: String) {
        val normalizedFormat = format.lowercase(Locale.US)
        val mimeType = when (normalizedFormat) {
            "gpx" -> "application/gpx+xml"
            "geojson" -> "application/geo+json"
            else -> {
                Toast.makeText(this, "不支持的导出格式", Toast.LENGTH_SHORT).show()
                return
            }
        }
        pendingExportFormat = normalizedFormat
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, "$DEFAULT_EXPORT_FILE_NAME.$normalizedFormat")
        }
        exportTrackLauncher.launch(intent)
    }

    private fun requestTrackPayloadForExport(format: String, uri: Uri) {
        val script = "window.exportTrack(${JSONObject.quote(format)})"
        webView.evaluateJavascript(script) { result ->
            val payload = parseJavascriptStringResult(result)
            if (payload.isNullOrBlank()) {
                Toast.makeText(this, "没有可导出的轨迹", Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            writeTextToUri(uri, payload)
        }
    }

    private fun writeTextToUri(uri: Uri, payload: String) {
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(payload.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("无法打开输出文件")
            Toast.makeText(this, "轨迹导出完成", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "导出轨迹失败", e)
            showError("导出轨迹失败: ${e.message}")
        }
    }

    private fun importTrackFromUri(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: throw IllegalStateException("无法读取轨迹文件")
            val points = parseTrackContent(content)
            if (points.isEmpty()) {
                showError("轨迹文件中没有有效点")
                return
            }
            pushTrackBatchToWeb(points, replace = true)
            Toast.makeText(this, "已导入 ${points.size} 个轨迹点", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "导入轨迹失败", e)
            showError("导入轨迹失败: ${e.message}")
        }
    }

    private fun parseTrackContent(content: String): List<TrackPoint> {
        val trimmed = content.trim()
        return when {
            trimmed.startsWith("{") -> parseGeoJsonTrack(trimmed)
            trimmed.startsWith("[") -> parseGeoJsonTrack(trimmed)
            trimmed.startsWith("<") -> parseGpxTrack(trimmed)
            else -> emptyList()
        }
    }

    private fun parseGeoJsonTrack(content: String): List<TrackPoint> {
        if (content.trim().startsWith("[")) {
            val coordinates = JSONArray(content)
            return mutableListOf<TrackPoint>().also { points ->
                collectGeoJsonCoordinates(coordinates, JSONObject(), points)
            }
        }
        val root = JSONObject(content)
        val features = when (root.optString("type")) {
            "FeatureCollection" -> root.optJSONArray("features") ?: JSONArray()
            "Feature" -> JSONArray().put(root)
            else -> JSONArray().put(JSONObject().put("geometry", root))
        }
        val points = mutableListOf<TrackPoint>()
        for (featureIndex in 0 until features.length()) {
            val feature = features.optJSONObject(featureIndex) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            when (geometry.optString("type")) {
                "LineString" -> collectGeoJsonCoordinates(
                    geometry.optJSONArray("coordinates"),
                    properties,
                    points
                )
                "MultiLineString" -> {
                    val lines = geometry.optJSONArray("coordinates") ?: continue
                    for (lineIndex in 0 until lines.length()) {
                        collectGeoJsonCoordinates(lines.optJSONArray(lineIndex), properties, points)
                    }
                }
                "Point" -> coordinateToTrackPoint(
                    geometry.optJSONArray("coordinates"),
                    properties,
                    points.size
                )?.let { points.add(it) }
            }
        }
        return points
    }

    private fun collectGeoJsonCoordinates(
        coordinates: JSONArray?,
        properties: JSONObject,
        points: MutableList<TrackPoint>
    ) {
        if (coordinates == null) {
            return
        }
        for (index in 0 until coordinates.length()) {
            coordinateToTrackPoint(
                coordinate = coordinates.optJSONArray(index),
                properties = properties,
                index = points.size
            )?.let { points.add(it) }
        }
    }

    private fun coordinateToTrackPoint(
        coordinate: JSONArray?,
        properties: JSONObject,
        index: Int
    ): TrackPoint? {
        if (coordinate == null || coordinate.length() < 2) {
            return null
        }
        val longitude = coordinate.optDouble(0, Double.NaN)
        val latitude = coordinate.optDouble(1, Double.NaN)
        if (longitude.isNaN() || latitude.isNaN()) {
            return null
        }
        val propertyPoints = properties.optJSONArray("points")
        val pointProps = propertyPoints?.optJSONObject(index)
        return TrackPoint(
            latitude = latitude,
            longitude = longitude,
            gpsAltitude = if (coordinate.length() > 2) coordinate.optDouble(2) else
                pointProps?.optNullableDouble("gpsAltitude")
                    ?: properties.optNullableDouble("gpsAltitude"),
            barometerAltitude = pointProps?.optNullableDouble("barometerAltitude")
                ?: properties.optNullableDouble("barometerAltitude"),
            accuracy = (
                pointProps?.optDouble("accuracy", Double.NaN)
                    ?: properties.optDouble("accuracy", Double.NaN)
                ).takeUnless { it.isNaN() }?.toFloat() ?: 0f,
            speed = (
                pointProps?.optDouble("speed", Double.NaN)
                    ?: properties.optDouble("speed", Double.NaN)
                ).takeUnless { it.isNaN() }?.toFloat() ?: 0f,
            timestamp = pointProps?.optLong("timestamp", 0L)
                ?.takeIf { it > 0L }
                ?: properties.optLong("timestamp", 0L).takeIf { it > 0L }
                ?: System.currentTimeMillis() + index
        )
    }

    private fun parseGpxTrack(content: String): List<TrackPoint> {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(content))

        val points = mutableListOf<TrackPoint>()
        var eventType = parser.eventType
        var currentPoint: MutableTrackPoint? = null
        var currentTag: String? = null
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    if (name == "trkpt" || name == "rtept" || name == "wpt") {
                        currentPoint = MutableTrackPoint(
                            latitude = parser.getAttributeValue(null, "lat")?.toDoubleOrNull(),
                            longitude = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        )
                    } else if (currentPoint != null) {
                        currentTag = name
                    }
                }
                XmlPullParser.TEXT -> {
                    val point = currentPoint
                    val tag = currentTag
                    if (point != null && tag != null) {
                        val text = parser.text.trim()
                        when (tag) {
                            "ele" -> point.gpsAltitude = text.toDoubleOrNull()
                            "time" -> point.timestamp = parseIsoTimeMillis(text)
                            "barometerAltitude" -> point.barometerAltitude = text.toDoubleOrNull()
                            "accuracy" -> point.accuracy = text.toFloatOrNull()
                            "speed" -> point.speed = text.toFloatOrNull()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name
                    if (name == "trkpt" || name == "rtept" || name == "wpt") {
                        currentPoint?.toTrackPoint(points.size)?.let { points.add(it) }
                        currentPoint = null
                    }
                    currentTag = null
                }
            }
            eventType = parser.next()
        }
        return points
    }

    private fun parseIsoTimeMillis(value: String): Long? {
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parseJavascriptStringResult(result: String?): String? {
        if (result == null || result == "null") {
            return null
        }
        return runCatching {
            JSONArray("[$result]").getString(0)
        }.getOrNull()
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

    private fun Intent.getNullableDoubleExtra(key: String): Double? {
        val value = getDoubleExtra(key, Double.NaN)
        return if (value.isNaN()) null else value
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

    private data class MutableTrackPoint(
        val latitude: Double?,
        val longitude: Double?,
        var gpsAltitude: Double? = null,
        var barometerAltitude: Double? = null,
        var accuracy: Float? = null,
        var speed: Float? = null,
        var timestamp: Long? = null
    ) {
        fun toTrackPoint(index: Int): TrackPoint? {
            val lat = latitude ?: return null
            val lon = longitude ?: return null
            return TrackPoint(
                latitude = lat,
                longitude = lon,
                gpsAltitude = gpsAltitude,
                barometerAltitude = barometerAltitude,
                accuracy = max(0f, accuracy ?: 0f),
                speed = max(0f, speed ?: 0f),
                timestamp = timestamp ?: (System.currentTimeMillis() + index)
            )
        }
    }

    private fun showError(message: String) {
        Log.e(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            webView.canGoBack() -> webView.goBack()
            isContinuousTracking -> {
                AlertDialog.Builder(this)
                    .setTitle("持续定位正在运行")
                    .setMessage("退出应用时是否继续在后台定位？")
                    .setPositiveButton("后台继续") { _, _ -> finish() }
                    .setNeutralButton("停止并退出") { _, _ ->
                        stopContinuousLocationUpdates()
                        finish()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }

            else -> super.onBackPressed()
        }
    }
}
