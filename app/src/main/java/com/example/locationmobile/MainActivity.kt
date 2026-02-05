package com.example.locationmobile

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
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
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.example.locationmobile.R

/**
 * 主活动 - 集成Cesium地图和GPS定位功能
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LocationMobile"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val LOCATION_UPDATE_INTERVAL = 5000L // 5秒
        private const val LOCATION_FASTEST_INTERVAL = 2000L // 2秒
    }

    // WebView组件,用于加载Cesium页面
    private lateinit var webView: WebView
    
    // 定位服务客户端
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    
    // 定位请求配置
    private lateinit var locationRequest: LocationRequest
    
    // 定位回调
    private var locationCallback: LocationCallback? = null
    
    // 是否正在持续定位
    private var isContinuousTracking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "应用启动")

        // 初始化定位客户端
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 初始化定位请求配置
        initLocationRequest()

        // 初始化WebView
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
    }

    /**
     * 配置WebView设置
     */
    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        
        // WebView基础设置
        with(webView.settings) {
            // 启用JavaScript
            javaScriptEnabled = true
            
            // 启用DOM存储
            domStorageEnabled = true
            
            // 允许访问文件
            allowFileAccess = true
            
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
            
            // 混合内容模式
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        
        // 设置WebViewClient
        webView.webViewClient = object : WebViewClient() {
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
                Log.e(TAG, "页面加载错误: $description")
                showError("页面加载失败: $description")
            }
        }
        
        // 设置WebChromeClient
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d(TAG, "WebView控制台: ${it.message()}")
                }
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                Log.v(TAG, "页面加载进度: $newProgress%")
            }
        }
        
        // 添加JavaScript接口
        webView.addJavascriptInterface(WebAppInterface(), "Android")
        
        // 加载本地HTML文件
        webView.loadUrl("file:///android_asset/cesium/index.html")
        
        Log.d(TAG, "WebView初始化完成")
    }

    /**
     * JavaScript接口类
     */
    inner class WebAppInterface {
        
        @JavascriptInterface
        fun getLocation() {
            Log.d(TAG, "网页请求获取位置")
            runOnUiThread {
                requestCurrentLocation()
            }
        }

        @JavascriptInterface
        fun startTracking() {
            Log.d(TAG, "网页请求开始持续定位")
            runOnUiThread {
                startContinuousLocationUpdates()
            }
        }

        @JavascriptInterface
        fun stopTracking() {
            Log.d(TAG, "网页请求停止持续定位")
            runOnUiThread {
                stopContinuousLocationUpdates()
            }
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
            .setMessage("此应用需要访问您的位置信息以在地图上显示您的当前位置。")
            .setPositiveButton("授予权限") { _, _ ->
                requestLocationPermission()
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "没有定位权限,部分功能将无法使用", Toast.LENGTH_LONG).show()
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && 
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "定位权限已授予")
                    Toast.makeText(this, "定位权限已授予", Toast.LENGTH_SHORT).show()
                } else {
                    Log.w(TAG, "定位权限被拒绝")
                    Toast.makeText(this, "需要定位权限才能使用此功能", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 请求当前位置
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
                getLastKnownLocation()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "安全异常", e)
            showError("定位权限异常")
        }
    }

    /**
     * 获取最后已知位置
     */
    private fun getLastKnownLocation() {
        if (!hasLocationPermission()) return

        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        Log.d(TAG, "使用最后已知位置")
                        handleLocationUpdate(location, false)
                        Toast.makeText(this, "使用最后已知位置", Toast.LENGTH_SHORT).show()
                    } else {
                        showError("无法获取位置信息,请确保GPS已开启")
                    }
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "安全异常", e)
        }
    }

    /**
     * 开始持续定位
     */
    private fun startContinuousLocationUpdates() {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "请先授予定位权限", Toast.LENGTH_SHORT).show()
            return
        }

        if (isContinuousTracking) return

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
        }
    }

    /**
     * 停止持续定位
     */
    private fun stopContinuousLocationUpdates() {
        if (!isContinuousTracking) return

        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            isContinuousTracking = false
            Log.d(TAG, "停止持续定位")
            Toast.makeText(this, "停止持续定位", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 处理位置更新
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

        Log.d(TAG, "位置: 经度=$longitude, 纬度=$latitude, 精度=$accuracy")

        val updateType = if (isSingleUpdate) "single" else "continuous"
        val script = "flyToLocation($longitude, $latitude, $altitude, $accuracy, '$updateType')"
        
        webView.evaluateJavascript(script) { result ->
            Log.v(TAG, "JavaScript执行结果: $result")
        }

        if (isSingleUpdate) {
            Toast.makeText(this, "定位成功 (精度: ${accuracy.toInt()}米)", Toast.LENGTH_SHORT).show()
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

    override fun onBackPressed() {
        when {
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopContinuousLocationUpdates()
        webView.destroy()
    }
}
