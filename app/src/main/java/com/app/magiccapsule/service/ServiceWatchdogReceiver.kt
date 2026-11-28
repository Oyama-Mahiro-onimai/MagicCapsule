package com.app.magiccapsule.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.data.CapsulePrefs

/**
 * 开机自启与覆盖更新自愈接收器：
 * - 手机重启或安装新版本 APK 后，自动重新绑定 NotificationListenerService、
 *   恢复设备状态监控与后台防杀守护服务，避免必须手动点开一次 App 功能才生效
 */
class ServiceWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val appCtx = context.applicationContext
        AppLogger.i("App", "收到系统自愈广播：$action，正在恢复后台服务")
        val cfg = CapsulePrefs(appCtx).load()

        if (CapsuleNotificationListener.isPermissionGranted(appCtx)) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(appCtx, CapsuleNotificationListener::class.java)
                )
            }.onFailure { e ->
                AppLogger.w("App", "requestRebind 失败：${e.message}")
            }
        }

        runCatching { ShizukuBridge.init {} }
        runCatching { SystemStatusMonitor.syncMonitorRegistration(appCtx, cfg) }
        ResidentKeepAliveService.sync(appCtx, cfg)
    }
}
