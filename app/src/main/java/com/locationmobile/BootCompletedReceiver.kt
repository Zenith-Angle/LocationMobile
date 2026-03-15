package com.locationmobile.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        if (!LocationForegroundService.isTracking(context)) {
            return
        }

        val serviceIntent = Intent(context, LocationForegroundService::class.java).apply {
            this.action = LocationForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(context, serviceIntent)
        Log.d(TAG, "系统重启后恢复持续定位服务")
    }
}
