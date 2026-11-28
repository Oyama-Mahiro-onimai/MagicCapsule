package com.app.magiccapsule.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat
import com.app.magiccapsule.R
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.crash.GlobalCrashHandler
import com.app.magiccapsule.data.CapsulePrefs
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.ui.MainActivity

/**
 * 极简低功耗后台防杀常驻守护服务 + 通知使用权三级自愈看门狗：
 * - 独立通知 ID 10087（IMPORTANCE_MIN 静默渠道），销毁胶囊不撤销前台服务保护
 * - 事件驱动零息屏唤醒：仅在服务启动、划卡、亮屏/解锁或监听断开时检查「权限已开但监听假死」
 * - 三级冷却自愈：
 *   1) requestRebind（30s 冷却）
 *   2) 3 秒后仍未连上时，执行一次 setComponentEnabledSetting DISABLED->ENABLED（5 分钟冷却，避免触发系统临时拉黑）
 *   3) 若 Shizuku 已授权，补发 cmd notification allow_listener 强制系统级重绑（2 分钟冷却）
 */
class ResidentKeepAliveService : Service() {

    companion object {
        const val GUARD_NOTIFY_ID = 10087
        private const val GUARD_CHANNEL_ID = "magic_capsule_guard_silent_channel"

        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var lastRequestRebindMs: Long = 0L
        @Volatile private var lastToggleComponentMs: Long = 0L
        @Volatile private var lastShizukuRebindMs: Long = 0L

        fun sync(context: Context, config: CapsuleConfig) {
            val appCtx = context.applicationContext
            if (config.enableBackgroundKeepAlive) {
                start(appCtx)
            } else {
                stop(appCtx)
            }
        }

        fun start(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                ContextCompat.startForegroundService(
                    appCtx,
                    Intent(appCtx, ResidentKeepAliveService::class.java)
                )
            }.onFailure { e ->
                AppLogger.w("App", "启动后台防杀守护服务受限(${e.javaClass.simpleName})：${e.message}")
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                appCtx.stopService(Intent(appCtx, ResidentKeepAliveService::class.java))
            }
        }

        /**
         * 通知使用权三级自愈入口（仅在「权限已授但实际断连」时触发，带严格冷却保护）：
         */
        fun healNotificationListenerIfStuck(context: Context, reason: String) {
            val appCtx = context.applicationContext
            if (CapsuleNotificationListener.connected.value) return
            if (!CapsuleNotificationListener.isPermissionGranted(appCtx)) return

            val now = SystemClock.elapsedRealtime()
            val cn = ComponentName(appCtx, CapsuleNotificationListener::class.java)

            // 1. 一级自愈：官方 requestRebind（30s 冷却）
            if (now - lastRequestRebindMs >= 30_000L) {
                lastRequestRebindMs = now
                val ok = runCatching {
                    NotificationListenerService.requestRebind(cn)
                    true
                }.getOrDefault(false)
                AppLogger.i("Notif", "通知使用权一级自愈(requestRebind) reason=$reason ok=$ok")
            }

            // 2. 延迟 3 秒复检：若依然假死，按冷却策略升级至二级组件切换 + 三级 Shizuku 强绑
            mainHandler.postDelayed({
                if (CapsuleNotificationListener.connected.value) return@postDelayed
                if (!CapsuleNotificationListener.isPermissionGranted(appCtx)) return@postDelayed
                val checkNow = SystemClock.elapsedRealtime()

                // 二级自愈：低频组件禁用再启用（5 分钟冷却，严防触发系统拉黑）
                if (checkNow - lastToggleComponentMs >= 5 * 60_000L) {
                    lastToggleComponentMs = checkNow
                    runCatching {
                        val pm = appCtx.packageManager
                        pm.setComponentEnabledSetting(
                            cn,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        pm.setComponentEnabledSetting(
                            cn,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        NotificationListenerService.requestRebind(cn)
                        AppLogger.i("Notif", "通知使用权二级自愈(ComponentToggle+Rebind) 已触发 reason=$reason")
                    }.onFailure { e ->
                        AppLogger.w("Notif", "通知使用权二级自愈异常：${e.message}")
                    }
                }

                // 三级自愈：Shizuku 系统级 allow_listener 强绑（2 分钟冷却）
                if (checkNow - lastShizukuRebindMs >= 2 * 60_000L) {
                    SoCThreadPools.monitorIoExecutor.execute {
                        runCatching { ShizukuBridge.refreshState(autoRequest = false, triggerAutoGrantIfNeeded = false) }
                        if (ShizukuBridge.binderAlive.value && ShizukuBridge.permissionGranted.value) {
                            lastShizukuRebindMs = SystemClock.elapsedRealtime()
                            val compStr = "${appCtx.packageName}/${CapsuleNotificationListener::class.java.name}"
                            val (ok, out) = ShizukuBridge.runShellCommand("cmd", "notification", "allow_listener", compStr)
                            AppLogger.i("Notif", "通知使用权三级自愈(Shizuku allow_listener) ok=$ok out=${out.take(80)}")
                        }
                    }
                }
            }, 3000L)
        }
    }

    private var screenReceiverRegistered: Boolean = false

    private val screenWakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val act = intent?.action ?: return
            if (act == Intent.ACTION_USER_PRESENT || act == Intent.ACTION_SCREEN_ON) {
                healNotificationListenerIfStuck(context, act)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        GlobalCrashHandler.install(this)
        if (!screenReceiverRegistered) {
            runCatching {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
                ContextCompat.registerReceiver(this, screenWakeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                screenReceiverRegistered = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cfg = CapsulePrefs(this).load()
        if (!cfg.enableBackgroundKeepAlive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notif = buildSilentGuardNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(GUARD_NOTIFY_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(GUARD_NOTIFY_ID, notif)
            }
        }.onFailure { e ->
            AppLogger.w("App", "ResidentKeepAliveService startForeground 失败：${e.message}")
        }
        healNotificationListenerIfStuck(this, "keepAliveStart")
        SystemStatusMonitor.syncMonitorRegistration(this, cfg)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val cfg = CapsulePrefs(this).load()
        if (cfg.enableBackgroundKeepAlive) {
            AppLogger.i("App", "检测到后台卡片划除，触发通知监听与守护服务自愈")
            healNotificationListenerIfStuck(this, "onTaskRemoved")
        }
    }

    override fun onDestroy() {
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenWakeReceiver) }
            screenReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun buildSilentGuardNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(GUARD_CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                GUARD_CHANNEL_ID,
                "后台防杀守护（静默低优先级）",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "保持灵动胶囊与媒体监听在后台稳定运行，零弹窗零声音零耗电"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(ch)
        }
        val openPi = PendingIntent.getActivity(
            this,
            9001,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, GUARD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_px_shield)
            .setContentTitle("灵动胶囊后台守护中")
            .setContentText("保持通知同步与媒体播控稳定运行（可在设置中关闭）")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openPi)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
