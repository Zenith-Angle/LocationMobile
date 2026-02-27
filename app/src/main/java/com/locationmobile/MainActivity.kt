package com.locationmobile.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Looper
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
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource

// ★ 修复：R 文件包名与 namespace 保持一致，不需要显式导入
// 因为 MainActivity 和 R 在同一个包 com.locationmobile.app 下，
// build.gradle 的 namespace 改为 com.locationmobile.app 后自动可用

/**
 * LocationMobile - 主活动
 * 基于 Cesium 的移动端定位应用
 *
 * 核心修复：使用 WebViewAssetLoader 将 assets 文件映射到
 * https://appassets.androidplatform.net/assets/ 路径，
 * 让 WebView 以真正的 HTTPS 身份运行，彻底解决：
 *   1. file:// 协议下 Cesium Worker blob:null URL 被拦截导致黑屏
 *   2. Cesium Ion 默认 Token 401 错误
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LocationMobile"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val LOCATION_UPDATE_INTERVAL = 5000L  // 5秒
        private const val LOCATION_FASTEST_INTERVAL = 2000L // 2秒
    }

    // WebView 组件，用于加载 Cesium 页面
    private lateinit var webView: WebView

    // 定位服务客户端
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // 定位请求配置
    private lateinit var locationRequest: LocationRequest

    // 定位回调
    private var locationCallback: LocationCallback? = null

    // 是否正在持续定位
    private var isContinuousTracking = false

    // ★ 核心修复：WebViewAssetLoader 将 assets 映射为合法的 HTTPS URL
    // 访问路径：https://appassets.androidplatform.net/assets/cesium/index.html
    private lateinit var assetLoader: WebViewAssetLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "LocationMobile 应用启动")

        // 初始化定位客户端
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 初始化定位请求配置
        initLocationRequest()

        // 初始化 WebView
        setupWebView()

        // 检查并请求定位权限
        checkLocationPermission()
    }

    /**
     * 初始化定位请求配置
     */
    private fun initLocationRequest() {
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL
        ).apply {
            setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL)
            setWaitForAccurateLocation(false)
        }.build()

        Log.d(TAG, "定位请求配置已初始化")
    }

    /**
     * 配置 WebView 设置
     */
    private fun setupWebView() {
        webView = findViewById(R.id.webView)

        // ★ 硬件加速，Cesium/WebGL 渲染必须开启
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        // ★ 初始化 WebViewAssetLoader
        // 将 app/src/main/assets 目录映射到 https://appassets.androidplatform.net/assets/
        // 这样 Cesium Worker 生成的是 blob:https:// 而非 blob:null，不会被安全策略拦截
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // WebView 基础设置
        with(webView.settings) {
            // 启用 JavaScript（Cesium 必须）
            javaScriptEnabled = true

            // 启用 DOM 存储（Cesium 内部使用）
            domStorageEnabled = true

            // 使用 AssetLoader 后不再需要 file:// 直接访问
            allowFileAccess = false

            // 数据库存储
            databaseEnabled = true

            // 支持缩放
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            // 自适应屏幕
            useWideViewPort = true
            loadWithOverviewMode = true

            // 缓存设置
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

            // 允许 HTTPS 页面加载 HTTP 资源（OSM 瓦片等混合内容）
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // 设置 WebViewClient
        webView.webViewClient = object : WebViewClient() {

            /**
             * ★ 核心修复：拦截所有资源请求
             * - 本地 assets 请求（https://appassets.androidplatform.net/assets/...）
             *   由 assetLoader 处理，返回本地文件内容
             * - 外部请求（CDN 的 Cesium.js、OSM 瓦片等）返回 null 正常放行
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "页面加载完成: $url")
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.e(TAG, "页面加载错误[$errorCode]: $description, url=$failingUrl")
                showError("页面加载失败: $description")
            }
        }

        // 设置 WebChromeClient，支持 JavaScript 对话框和控制台日志
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

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress % 20 == 0) {
                    Log.v(TAG, "页面加载进度: $newProgress%")
                }
            }
        }

        // 开启 Chrome 远程调试（开发阶段保留，发布前可删除）
        WebView.setWebContentsDebuggingEnabled(true)

        // 添加 JavaScript 接口，用于 Android 与网页双向通信
        webView.addJavascriptInterface(WebAppInterface(), "Android")

        // ★ 使用 HTTPS 形式的 URL 加载本地 index.html
        // 对应文件路径：app/src/main/assets/cesium/index.html
        webView.loadUrl("https://appassets.androidplatform.net/assets/cesium/index.html")

        Log.d(TAG, "WebView 初始化完成")
    }

    /**
     * JavaScript 接口类，提供给网页调用的 Android 原生方法
     */
    inner class WebAppInterface {

        /**
         * 供网页调用：请求单次定位
         */
        @JavascriptInterface
        fun getLocation() {
            Log.d(TAG, "网页请求获取位置")
            runOnUiThread {
                requestCurrentLocation()
            }
        }

        /**
         * 供网页调用：开始持续定位跟踪
         */
        @JavascriptInterface
        fun startTracking() {
            Log.d(TAG, "网页请求开始持续定位")
            runOnUiThread {
                startContinuousLocationUpdates()
            }
        }

        /**
         * 供网页调用：停止持续定位跟踪
         */
        @JavascriptInterface
        fun stopTracking() {
            Log.d(TAG, "网页请求停止持续定位")
            runOnUiThread {
                stopContinuousLocationUpdates()
            }
        }

        /**
         * 供网页调用：显示 Toast 消息
         */
        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * 供网页调用：记录日志到 Android Logcat
         */
        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "网页日志: $message")
        }
    }

    /**
     * 检查定位权限
     */
    private fun checkLocationPermission() {
        when {
            hasLocationPermission() -> {
                Log.d(TAG, "定位权限已授予")
            }

            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                showPermissionRationaleDialog()
            }

            else -> {
                requestLocationPermission()
            }
        }
    }

    /**
     * 检查是否已有定位权限
     */
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 显示权限说明对话框
     */
    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要定位权限")
            .setMessage("LocationMobile 需要访问您的位置信息以在地图上显示您的当前位置。")
            .setPositiveButton("授予权限") { _, _ ->
                requestLocationPermission()
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "没有定位权限，部分功能将无法使用", Toast.LENGTH_LONG)
                    .show()
            }
            .show()
    }

    /**
     * 请求定位权限
     */
    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    /**
     * 权限请求结果回调
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    Log.d(TAG, "定位权限已授予")
                    Toast.makeText(
                        this,
                        "定位权限已授予，可以使用定位功能了",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Log.w(TAG, "定位权限被拒绝")
                    if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        showPermissionDeniedDialog()
                    } else {
                        Toast.makeText(
                            this,
                            "需要定位权限才能使用此功能",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    /**
     * 显示权限被永久拒绝的对话框
     */
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("权限被拒绝")
            .setMessage("定位权限已被永久拒绝，请在系统设置中手动开启。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 请求当前位置（单次定位）
     */
    private fun requestCurrentLocation() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "没有定位权限")
            Toast.makeText(this, "请先授予定位权限", Toast.LENGTH_SHORT).show()
            checkLocationPermission()
            return
        }

        try {
            val cancellationTokenSource = CancellationTokenSource()

            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location: Location? ->
                handleLocationUpdate(location, true)
            }.addOnFailureListener { exception ->
                Log.e(TAG, "获取位置失败", exception)
                showError("获取位置失败: ${exception.message}")
                getLastKnownLocation()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "安全异常", e)
            showError("定位权限异常")
        }
    }

    /**
     * 获取最后已知位置（备选方案）
     */
    private fun getLastKnownLocation() {
        if (!hasLocationPermission()) return

        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        Log.d(TAG, "使用最后已知位置")
                        handleLocationUpdate(location, false)
                        Toast.makeText(this, "使用最后已知位置", Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        showError("无法获取位置信息，请确保 GPS 已开启")
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "获取最后位置失败", exception)
                    showError("定位失败: ${exception.message}")
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "安全异常", e)
        }
    }

    /**
     * 开始持续定位更新
     */
    private fun startContinuousLocationUpdates() {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "请先授予定位权限", Toast.LENGTH_SHORT).show()
            checkLocationPermission()
            return
        }

        if (isContinuousTracking) {
            Log.d(TAG, "已在持续定位中")
            return
        }

        try {
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        handleLocationUpdate(location, false)
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )

            isContinuousTracking = true
            Log.d(TAG, "开始持续定位")
            Toast.makeText(this, "开始持续定位", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.e(TAG, "安全异常", e)
            showError("定位权限异常")
        }
    }

    /**
     * 停止持续定位更新
     */
    private fun stopContinuousLocationUpdates() {
        if (!isContinuousTracking) {
            Log.d(TAG, "未在持续定位中")
            return
        }

        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            isContinuousTracking = false
            Log.d(TAG, "停止持续定位")
            Toast.makeText(this, "停止持续定位", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 处理位置更新，将坐标通过 JS 传给 Cesium 地图
     */
    private fun handleLocationUpdate(location: Location?, isSingleUpdate: Boolean) {
        if (location == null) {
            Log.w(TAG, "位置为空")
            showError("无法获取位置信息")
            return
        }

        val latitude = location.latitude
        val longitude = location.longitude
        val altitude = location.altitude
        val accuracy = location.accuracy

        Log.d(
            TAG,
            "位置更新: 经度=$longitude, 纬度=$latitude, 海拔=$altitude, 精度=$accuracy"
        )

        val updateType = if (isSingleUpdate) "single" else "continuous"
        val script =
            "flyToLocation($longitude, $latitude, $altitude, $accuracy, '$updateType')"

        webView.evaluateJavascript(script) { result ->
            Log.v(TAG, "JavaScript 执行结果: $result")
        }

        if (isSingleUpdate) {
            Toast.makeText(
                this,
                "定位成功 (精度: ${accuracy.toInt()} 米)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 显示错误信息
     */
    private fun showError(message: String) {
        Log.e(TAG, message)
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 处理返回键
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            webView.canGoBack() -> {
                webView.goBack()
            }

            isContinuousTracking -> {
                AlertDialog.Builder(this)
                    .setTitle("退出应用")
                    .setMessage("正在进行位置跟踪，确定要退出吗？")
                    .setPositiveButton("退出") { _, _ ->
                        stopContinuousLocationUpdates()
                        super.onBackPressed()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }

            else -> {
                super.onBackPressed()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "应用暂停")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "应用恢复")
    }

    /**
     * 销毁时清理资源
     */
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "应用销毁")
        stopContinuousLocationUpdates()
        webView.destroy()
    }
}