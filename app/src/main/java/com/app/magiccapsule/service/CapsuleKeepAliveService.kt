package com.app.magiccapsule.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import com.app.magiccapsule.capsule.CapsuleNotificationFactory
import com.app.magiccapsule.data.CapsulePrefs
import com.app.magiccapsule.model.CapsuleConfig

class CapsuleKeepAliveService : Service() {

    companion object {
        const val ACTION_START = "com.app.magiccapsule.ACTION_START"
        const val ACTION_REFRESH = "com.app.magiccapsule.ACTION_REFRESH"
        const val ACTION_STOP = "com.app.magiccapsule.ACTION_STOP"
    }

    private lateinit var prefs: CapsulePrefs
    private lateinit var displayManager: DisplayManager
    private val handler = Handler(Looper.getMainLooper())
    private var config = CapsuleConfig()
    private var refreshCount = 0
    private var lastLandscape: Boolean? = null

    private val keepAliveTask = object : Runnable {
        override fun run() {
            refreshCount++
            publish(startFg = false)
            handler.postDelayed(this, config.refreshIntervalSec * 1000L)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (refreshCount <= 0) return
            val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            val nowLand = b.width() > b.height()
            if (nowLand != lastLandscape) {
                lastLandscape = nowLand
                publish(startFg = false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = CapsulePrefs(this)
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                handler.removeCallbacks(keepAliveTask)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                config = prefs.load()
                refreshCount++
                publish(startFg = false)
                return START_STICKY
            }
            else -> {
                config = prefs.load()
                refreshCount = 1
                val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
                lastLandscape = b.width() > b.height()
                publish(startFg = true)
                handler.removeCallbacks(keepAliveTask)
                handler.postDelayed(keepAliveTask, config.refreshIntervalSec * 1000L)
                return START_STICKY
            }
        }
    }

    private fun publish(startFg: Boolean) {
        val notification = CapsuleNotificationFactory.create(this, config, refreshCount)
        if (startFg) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    CapsuleNotificationFactory.NOTIFY_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(CapsuleNotificationFactory.NOTIFY_ID, notification)
            }
        } else {
            getSystemService(NotificationManager::class.java)
                .notify(CapsuleNotificationFactory.NOTIFY_ID, notification)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(keepAliveTask)
        displayManager.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
