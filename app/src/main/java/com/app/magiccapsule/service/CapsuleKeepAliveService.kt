package com.app.magiccapsule.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.app.magiccapsule.capsule.CapsuleNotificationFactory
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.crash.GlobalCrashHandler
import com.app.magiccapsule.data.CapsulePrefs
import com.app.magiccapsule.model.CapsuleConfig

/**
 * 省电事件驱动版胶囊保活服务：
 * - 息屏时（!powerManager.isInteractive）彻底暂停定时重绘与跨进程 IPC
 * - 仅在开启「显示刷新时间」或「计时器」时启用定时刷新；默认静态/镜像模式下完全事件驱动，零无意义轮询耗电
 * - 直接复用 NotificationIconStore.currentActivePayload 的真实通知图标与交互按钮
 */
class CapsuleKeepAliveService : Service() {

    companion object {
        const val ACTION_START = "com.app.magiccapsule.ACTION_START"
        const val ACTION_REFRESH = "com.app.magiccapsule.ACTION_REFRESH"
        const val ACTION_MIRROR_NOTIFICATION = "com.app.magiccapsule.ACTION_MIRROR_NOTIFICATION"
        const val ACTION_STOP = "com.app.magiccapsule.ACTION_STOP"
        const val ACTION_DISMISS_CAPSULE = "com.app.magiccapsule.ACTION_DISMISS_CAPSULE"
        const val ACTION_MEDIA_PREV = "com.app.magiccapsule.ACTION_MEDIA_PREV"
        const val ACTION_MEDIA_PLAY_PAUSE = "com.app.magiccapsule.ACTION_MEDIA_PLAY_PAUSE"
        const val ACTION_MEDIA_NEXT = "com.app.magiccapsule.ACTION_MEDIA_NEXT"
        const val ACTION_MEDIA_REWIND = "com.app.magiccapsule.ACTION_MEDIA_REWIND"
        const val ACTION_MEDIA_FAST_FORWARD = "com.app.magiccapsule.ACTION_MEDIA_FAST_FORWARD"

        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_IS_WHITELISTED = "extra_is_whitelisted"
        const val EXTRA_IS_PINNED = "extra_is_pinned"
    }

    private lateinit var prefs: CapsulePrefs
    private lateinit var powerManager: PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var config = CapsuleConfig()
    private var refreshCount = 0
    private var isForegroundStarted = false

    private var isMirroringActive = false
    private var isPinnedMirror = false
    private var mirroredPackageName: String? = null
    private var mirroredAppName: String = ""
    private var mirroredTitle: String = ""
    private var mirroredText: String = ""

    private val autoDismissCapsuleTask: Runnable = Runnable {
        handler.removeCallbacks(keepAliveTask)
        handler.removeCallbacks(autoDismissCapsuleTask)
        isForegroundStarted = false
        isMirroringActive = false
        isPinnedMirror = false
        mirroredPackageName = null
        NotificationIconStore.currentActivePayload = null
        CapsuleNotificationListener.clearActiveSource()
        CapsuleNotificationFactory.releaseMediaSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val keepAliveTask: Runnable = object : Runnable {
        override fun run() {
            // 省电：仅在亮屏且确实需要刷新时间戳或计时器时才执行定时重绘
            val externalCapsuleActive = !isMirroringActive &&
                CapsuleNotificationListener.activeSourcePackage.value != null
            if (powerManager.isInteractive && needsPeriodicTick(config) && !externalCapsuleActive) {
                refreshCount++
                publish(startFg = !isForegroundStarted)
            }
            if (needsPeriodicTick(config)) {
                handler.postDelayed(this, config.refreshIntervalSec.coerceAtLeast(15) * 1000L)
            }
        }
    }

    private fun needsPeriodicTick(cfg: CapsuleConfig): Boolean =
        cfg.showTimestamp || cfg.enableTimer || cfg.template.contains("timer")

    private fun rescheduleIfNeeded() {
        handler.removeCallbacks(keepAliveTask)
        handler.removeCallbacks(autoDismissCapsuleTask)
        if (needsPeriodicTick(config)) {
            handler.postDelayed(keepAliveTask, config.refreshIntervalSec.coerceAtLeast(15) * 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        GlobalCrashHandler.install(this)
        prefs = CapsulePrefs(this)
        powerManager = getSystemService(PowerManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MEDIA_PREV, ACTION_MEDIA_PLAY_PAUSE, ACTION_MEDIA_NEXT -> {
                CapsuleNotificationListener.dispatchPinnedMediaCommand(this, intent.action.orEmpty())
                return START_NOT_STICKY
            }
            ACTION_DISMISS_CAPSULE -> {
                com.app.magiccapsule.capsule.CapsulePublisher.setManualCapsulePinned(false)
                handler.removeCallbacks(keepAliveTask)
                handler.removeCallbacks(autoDismissCapsuleTask)
                isForegroundStarted = false
                isMirroringActive = false
                isPinnedMirror = false
                mirroredPackageName = null
                NotificationIconStore.currentActivePayload = null
                CapsuleNotificationListener.clearActiveSource()
                CapsuleNotificationFactory.releaseMediaSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                com.app.magiccapsule.capsule.CapsulePublisher.setManualCapsulePinned(false)
                handler.removeCallbacks(keepAliveTask)
                handler.removeCallbacks(autoDismissCapsuleTask)
                isForegroundStarted = false
                isMirroringActive = false
                isPinnedMirror = false
                mirroredPackageName = null
                NotificationIconStore.currentActivePayload = null
                CapsuleNotificationListener.clearActiveSource()
                CapsuleOverlayAccessibilityService.dismissBanner()
                SystemHeadsUpController.restoreDefault(this)
                CapsuleNotificationFactory.releaseMediaSession()
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
                publish(startFg = !isForegroundStarted)
                rescheduleIfNeeded()
                return START_STICKY
            }
            ACTION_MIRROR_NOTIFICATION -> {
                val srcPkg = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
                if (srcPkg == packageName) return START_STICKY

                val base = prefs.load()
                val appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty().ifBlank { base.businessName }
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { base.mainText }
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { title }

                isMirroringActive = true
                isPinnedMirror = intent.getBooleanExtra(EXTRA_IS_PINNED, false)
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
                rescheduleIfNeeded()
                // 即弹出即销毁：自动模式且开启自动销毁时，到达保持时间后自动撤下胶囊并结束服务
                handler.removeCallbacks(autoDismissCapsuleTask)
                if (!isPinnedMirror && config.capsuleAutoDismiss) {
                    val holdMs = config.capsuleHoldDurationSec.coerceIn(2, 60) * 1000L
                    handler.postDelayed(autoDismissCapsuleTask, holdMs)
                }
                return START_STICKY
            }
            else -> {
                handler.removeCallbacks(autoDismissCapsuleTask)
                isMirroringActive = false
                isPinnedMirror = false
                mirroredPackageName = null
                NotificationIconStore.currentActivePayload = null
                CapsuleNotificationListener.clearActiveSource()
                config = prefs.load()
                refreshCount = 1
                publish(startFg = true)
                rescheduleIfNeeded()
                return START_STICKY
            }
        }
    }

    private fun publish(startFg: Boolean) {
        val payload = if (isMirroringActive) NotificationIconStore.currentActivePayload else null
        val notification = CapsuleNotificationFactory.create(this, config, refreshCount, payload)
        if (startFg) {
            runCatching {
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
                AppLogger.i("Capsule", "KeepAlive.startForeground 成功 id=${CapsuleNotificationFactory.NOTIFY_ID} mirror=$isMirroringActive count=$refreshCount")
            }.onFailure { e ->
                isForegroundStarted = false
                AppLogger.e("Capsule", "KeepAlive.startForeground 失败，回退 notify", e)
                runCatching {
                    getSystemService(NotificationManager::class.java)
                        .notify(CapsuleNotificationFactory.NOTIFY_ID, notification)
                }
            }
        } else {
            getSystemService(NotificationManager::class.java)
                .notify(CapsuleNotificationFactory.NOTIFY_ID, notification)
            AppLogger.i("Capsule", "KeepAlive.notify 更新成功 id=${CapsuleNotificationFactory.NOTIFY_ID} mirror=$isMirroringActive count=$refreshCount")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(keepAliveTask)
        handler.removeCallbacks(autoDismissCapsuleTask)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
