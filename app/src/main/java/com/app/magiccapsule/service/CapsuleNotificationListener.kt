package com.app.magiccapsule.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.app.magiccapsule.data.CapsulePrefs
import com.app.magiccapsule.model.CapturedNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通知监听服务：
 * - 读取每条通知的真实发起应用包名（sbn.packageName / sbn.opPkg），过滤自身包名防止自触发冲突
 * - 锁定模式（pinnedKey + pinnedPackage）：仅实时同步所选通知，不受频率限制
 * - 自动模式白名单（autoWhitelistPackages）：白名单应用不受自动模式刷新频率限制与常驻通知过滤限制，
 *   且白名单通知存活期间不会被非白名单通知顶掉
 * - 自动模式非白名单：受 autoThrottleIntervalSec 频率限制和 ignoreOngoingInAutoMode 过滤
 */
class CapsuleNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        private var instance: CapsuleNotificationListener? = null

        private val _notifications = MutableStateFlow<List<CapturedNotification>>(emptyList())
        val notifications: StateFlow<List<CapturedNotification>> = _notifications.asStateFlow()

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _pinnedKey = MutableStateFlow<String?>(null)
        val pinnedKey: StateFlow<String?> = _pinnedKey.asStateFlow()

        private val _pinnedPackage = MutableStateFlow<String?>(null)

        private val _activeSourcePackage = MutableStateFlow<String?>(null)
        val activeSourcePackage: StateFlow<String?> = _activeSourcePackage.asStateFlow()

        private val _activeSourceKey = MutableStateFlow<String?>(null)

        @Volatile
        private var lastNonWhitelistAutoPushMs: Long = 0L

        @Volatile
        private var lastPushedSignature: String? = null

        fun pinNotification(key: String?, packageName: String? = null) {
            _pinnedKey.value = key
            _pinnedPackage.value = if (key != null) packageName else null
            lastPushedSignature = null
            if (key != null && packageName != null) {
                _activeSourceKey.value = key
                _activeSourcePackage.value = packageName
            }
        }

        fun clearActiveSource() {
            _activeSourceKey.value = null
            _activeSourcePackage.value = null
            lastPushedSignature = null
        }

        fun isPermissionGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val target = ComponentName(context, CapsuleNotificationListener::class.java)
            return enabled.contains(target.flattenToString()) ||
                enabled.contains(target.flattenToShortString()) ||
                enabled.contains(context.packageName)
        }

        fun refreshSnapshot() {
            instance?.reloadActiveNotifications()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        _connected.value = true
        reloadActiveNotifications()
    }

    override fun onListenerDisconnected() {
        _connected.value = false
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        // 严格隔离自身包名，避免本应用胶囊通知引发循环刷新冲突
        val opPkg = runCatching { sbn.opPkg }.getOrNull().orEmpty()
        if (sbn.packageName == packageName || opPkg == packageName) return

        reloadActiveNotifications()

        val item = toCaptured(sbn) ?: return
        val currentPinned = _pinnedKey.value
        val currentPinnedPkg = _pinnedPackage.value
        val sig = "${item.packageName}|${item.key}|${item.title}|${item.text}"

        if (currentPinned != null) {
            // 锁定模式：校验 key + 发起包名，防止不同应用或重建通知冲突
            val pkgMatches = currentPinnedPkg == null || item.packageName == currentPinnedPkg
            if (item.key == currentPinned && pkgMatches) {
                if (sig != lastPushedSignature) {
                    lastPushedSignature = sig
                    pushToCapsule(item, isWhitelisted = true)
                }
            }
            return
        }

        val prefs = CapsulePrefs(this).load()
        if (!prefs.autoMirrorNotifications) return

        val isWhitelisted = item.packageName in prefs.autoWhitelistPackages
        if (isWhitelisted) {
            // 白名单机制：完全不受自动模式刷新频率限制，也不受忽略常驻通知限制
            if (sig == lastPushedSignature) return
            lastPushedSignature = sig
            pushToCapsule(item, isWhitelisted = true)
            return
        }

        // 非白名单应用规则
        if (prefs.whitelistOnlyInAutoMode) return
        if (prefs.ignoreOngoingInAutoMode && item.isOngoing) return

        // 防冲突保护：当前胶囊正展示白名单应用的活跃通知时，不被非白名单通知顶掉
        val activePkg = _activeSourcePackage.value
        val activeKey = _activeSourceKey.value
        if (activePkg != null && activePkg in prefs.autoWhitelistPackages && activeKey != null) {
            val whitelistedStillAlive = _notifications.value.any {
                it.key == activeKey && it.packageName == activePkg
            }
            if (whitelistedStillAlive) return
        }

        // 自动模式非白名单刷新频率限制
        val now = SystemClock.elapsedRealtime()
        val minIntervalMs = prefs.autoThrottleIntervalSec.coerceIn(1, 60) * 1000L
        if (now - lastNonWhitelistAutoPushMs < minIntervalMs) return
        if (sig == lastPushedSignature) return

        lastNonWhitelistAutoPushMs = now
        lastPushedSignature = sig
        pushToCapsule(item, isWhitelisted = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null) {
            if (sbn.key == _pinnedKey.value &&
                (_pinnedPackage.value == null || sbn.packageName == _pinnedPackage.value)
            ) {
                _pinnedKey.value = null
                _pinnedPackage.value = null
            }
            if (sbn.key == _activeSourceKey.value && sbn.packageName == _activeSourcePackage.value) {
                clearActiveSource()
            }
        }
        reloadActiveNotifications()
    }

    private fun pushToCapsule(item: CapturedNotification, isWhitelisted: Boolean) {
        _activeSourceKey.value = item.key
        _activeSourcePackage.value = item.packageName
        val intent = Intent(this, CapsuleKeepAliveService::class.java).apply {
            action = CapsuleKeepAliveService.ACTION_MIRROR_NOTIFICATION
            putExtra(CapsuleKeepAliveService.EXTRA_PACKAGE_NAME, item.packageName)
            putExtra(CapsuleKeepAliveService.EXTRA_APP_NAME, item.appName)
            putExtra(CapsuleKeepAliveService.EXTRA_TITLE, item.title)
            putExtra(CapsuleKeepAliveService.EXTRA_TEXT, item.text)
            putExtra(CapsuleKeepAliveService.EXTRA_IS_WHITELISTED, isWhitelisted)
        }
        runCatching { ContextCompat.startForegroundService(this, intent) }
    }

    private fun reloadActiveNotifications() {
        val list = runCatching { activeNotifications }.getOrNull() ?: return
        val parsed = list.mapNotNull { toCaptured(it) }
            .sortedByDescending { it.postTime }
            .take(25)
        _notifications.value = parsed
    }

    private fun toCaptured(sbn: StatusBarNotification): CapturedNotification? {
        val opPkg = runCatching { sbn.opPkg }.getOrNull().orEmpty().ifBlank { sbn.packageName }
        if (sbn.packageName == packageName || opPkg == packageName) return null
        val n = sbn.notification ?: return null
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null

        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return null

        val appName = runCatching {
            val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrElse { sbn.packageName.substringAfterLast('.') }

        val isOngoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0

        return CapturedNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            opPkg = opPkg,
            notificationId = sbn.id,
            appName = appName,
            title = title.ifBlank { appName },
            text = text.ifBlank { title },
            postTime = sbn.postTime,
            isOngoing = isOngoing
        )
    }
}
