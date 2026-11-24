package com.app.magiccapsule.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
 * 监听系统通知栏事件：
 * 1. 实时维护当前活跃通知列表（供 UI 自动刷新展示）
 * 2. 开启自动同步时，将新到达的通知自动推送到荣耀灵动胶囊
 */
class CapsuleNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        private var instance: CapsuleNotificationListener? = null

        private val _notifications = MutableStateFlow<List<CapturedNotification>>(emptyList())
        val notifications: StateFlow<List<CapturedNotification>> = _notifications.asStateFlow()

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

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
        reloadActiveNotifications()

        val item = toCaptured(sbn) ?: return
        val prefs = CapsulePrefs(this).load()
        if (prefs.autoMirrorNotifications) {
            val intent = Intent(this, CapsuleKeepAliveService::class.java).apply {
                action = CapsuleKeepAliveService.ACTION_MIRROR_NOTIFICATION
                putExtra(CapsuleKeepAliveService.EXTRA_APP_NAME, item.appName)
                putExtra(CapsuleKeepAliveService.EXTRA_TITLE, item.title)
                putExtra(CapsuleKeepAliveService.EXTRA_TEXT, item.text)
            }
            runCatching { ContextCompat.startForegroundService(this, intent) }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        reloadActiveNotifications()
    }

    private fun reloadActiveNotifications() {
        val list = runCatching { activeNotifications }.getOrNull() ?: return
        val parsed = list.mapNotNull { toCaptured(it) }
            .sortedByDescending { it.postTime }
            .take(15)
        _notifications.value = parsed
    }

    private fun toCaptured(sbn: StatusBarNotification): CapturedNotification? {
        // 跳过自身应用通知，防止死循环
        if (sbn.packageName == packageName) return null
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

        return CapturedNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title.ifBlank { appName },
            text = text.ifBlank { title },
            postTime = sbn.postTime
        )
    }
}
