package com.app.magiccapsule

import android.app.Application
import android.content.ComponentCallbacks2
import com.app.magiccapsule.capsule.vivo.VivoSuperXBridge
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.crash.GlobalCrashHandler
import com.app.magiccapsule.data.CapsulePrefs
import com.app.magiccapsule.service.CapsuleNotificationListener
import com.app.magiccapsule.service.ConversationMessageParser
import com.app.magiccapsule.service.NotificationIconStore
import com.app.magiccapsule.service.ResidentKeepAliveService
import com.app.magiccapsule.service.ShizukuBridge
import com.app.magiccapsule.service.SystemStatusMonitor

class MagicCapsuleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GlobalCrashHandler.install(this)
        com.app.magiccapsule.service.SystemPropertyProfile.logStartupSummary(this)
        AppLogger.init(this)
        // 进程冷启动或被系统回收重建时，自动恢复全部后台引擎与监听，杜绝「杀后台后功能静默失效」
        val cfg = runCatching { CapsulePrefs(this).load() }.getOrNull()
        runCatching { ShizukuBridge.init {} }
        runCatching { VivoSuperXBridge.ensureStateReceiverRegistered(this) }
        if (cfg != null) {
            runCatching { SystemStatusMonitor.syncMonitorRegistration(this, cfg) }
            runCatching { ResidentKeepAliveService.sync(this, cfg) }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            NotificationIconStore.trimMemory()
            ConversationMessageParser.trimMemory()
            CapsuleNotificationListener.trimMemoryWhenHidden()
        }
    }
}
