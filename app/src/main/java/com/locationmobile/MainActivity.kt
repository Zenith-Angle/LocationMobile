package com.locationmobile.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LocationMobile"
        private const val REQUEST_FOREGROUND_LOCATION = 1001
        private const val REQUEST_BACKGROUND_LOCATION = 1002
        private const val REQUEST_NOTIFICATIONS = 1003
    }

    private lateinit var webView: WebView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var assetLoader: WebViewAssetLoader

    private var cesiumIonToken: String = ""
    private var tiandituToken: String = ""
    private var isContinuousTracking = false
    private var pendingStartTracking = false
    private var locationReceiverRegistered = false

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
            val altitude = intent.getDoubleExtra(LocationForegroundService.EXTRA_ALTITUDE, 0.0)
            val accuracy = intent.getFloatExtra(LocationForegroundService.EXTRA_ACCURACY, 0f)
            val speed = intent.getFloatExtra(LocationForegroundService.EXTRA_SPEED, 0f)
            pushLocationToWeb(longitude, latitude, altitude, accuracy, speed, "continuous")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadLocalTokens()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
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
        syncTrackingState()
        if (pendingStartTracking) {
            continuePendingTrackingStartIfPossible()
        }
        LocationForegroundService.getLastLocation(this)?.let { snapshot ->
            pushLocationToWeb(
                longitude = snapshot.longitude,
                latitude = snapshot.latitude,
                altitude = snapshot.altitude,
                accuracy = snapshot.accuracy,
                speed = snapshot.speed,
                updateType = "continuous"
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::webView.isInitialized) {
            webView.destroy()
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
                injectTokens()
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
        pushLocationToWeb(
            longitude = location.longitude,
            latitude = location.latitude,
            altitude = location.altitude,
            accuracy = location.accuracy,
            speed = location.speed,
            updateType = if (isSingleUpdate) "single" else "continuous"
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
        longitude: Double,
        latitude: Double,
        altitude: Double,
        accuracy: Float,
        speed: Float,
        updateType: String
    ) {
        val script = "flyToLocation($longitude, $latitude, $altitude, $accuracy, $speed, '$updateType')"
        webView.evaluateJavascript(script) { result ->
            Log.v(TAG, "JavaScript 执行结果: $result")
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
