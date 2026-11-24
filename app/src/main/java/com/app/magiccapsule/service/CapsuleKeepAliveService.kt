package com.app.magiccapsule.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.app.magiccapsule.capsule.CapsuleNotificationFactory
import com.app.magiccapsule.data.CapsulePrefs
import com.app.magiccapsule.model.CapsuleConfig

class CapsuleKeepAliveService : Service() {

    companion object {
        const val ACTION_START = "com.app.magiccapsule.ACTION_START"
        const val ACTION_REFRESH = "com.app.magiccapsule.ACTION_REFRESH"
        const val ACTION_MIRROR_NOTIFICATION = "com.app.magiccapsule.ACTION_MIRROR_NOTIFICATION"
        const val ACTION_STOP = "com.app.magiccapsule.ACTION_STOP"

        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_IS_WHITELISTED = "extra_is_whitelisted"
    }

    private lateinit var prefs: CapsulePrefs
    private val handler = Handler(Looper.getMainLooper())
    private var config = CapsuleConfig()
    private var refreshCount = 0
    private var isForegroundStarted = false

    // 记录当前正在镜像的通知来源，刷新样式时不丢失当前通知内容并防止自身包名冲突
    private var isMirroringActive = false
    private var mirroredPackageName: String? = null
    private var mirroredAppName: String = ""
    private var mirroredTitle: String = ""
    private var mirroredText: String = ""

    private val keepAliveTask = object : Runnable {
        override fun run() {
            refreshCount++
            CapsuleNotificationListener.refreshSnapshot()
            publish(startFg = !isForegroundStarted)
            handler.postDelayed(this, config.refreshIntervalSec * 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = CapsulePrefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                handler.removeCallbacks(keepAliveTask)
                isForegroundStarted = false
                isMirroringActive = false
                mirroredPackageName = null
                CapsuleNotificationListener.clearActiveSource()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                val base = prefs.load()
                config = if (isMirroringActive && mirroredAppName.isNotBlank()) {
                    base.copy(
                        businessName = mirroredAppName,
                        mainText = mirroredTitle,
                        subText = mirroredText
                    )
                } else {
                    base
                }
                refreshCount++
                CapsuleNotificationListener.refreshSnapshot()
                publish(startFg = !isForegroundStarted)
                return START_STICKY
            }
            ACTION_MIRROR_NOTIFICATION -> {
                val srcPkg = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
                // 防冲突：拒绝本应用自身包名镜像
                if (srcPkg == packageName) return START_STICKY

                val base = prefs.load()
                val appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty().ifBlank { base.businessName }
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { base.mainText }
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { title }

                isMirroringActive = true
                mirroredPackageName = srcPkg.ifBlank { null }
                mirroredAppName = appName
                mirroredTitle = title
                mirroredText = text

                config = base.copy(
                    businessName = appName,
                    mainText = title,
                    subText = text
                )
                refreshCount++
                publish(startFg = !isForegroundStarted)
                handler.removeCallbacks(keepAliveTask)
                handler.postDelayed(keepAliveTask, config.refreshIntervalSec * 1000L)
                return START_STICKY
            }
            else -> {
                isMirroringActive = false
                mirroredPackageName = null
                CapsuleNotificationListener.clearActiveSource()
                config = prefs.load()
                refreshCount = 1
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
            isForegroundStarted = true
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
