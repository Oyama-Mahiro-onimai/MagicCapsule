package com.app.magiccapsule.capsule.android16

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.service.ActiveMirroredPayload

/**
 * Android 16+ (API 36+) 官方原生实时更新通知（Live Updates / Status Chip）桥接：
 * 严格遵循 Android Developers 官方规范（developer.android.com/develop/ui/compose/notifications/live-update）：
 * - 清单声明非运行时权限 android.permission.POST_PROMOTED_NOTIFICATIONS
 * - 写入 EXTRA_REQUEST_PROMOTED_ONGOING ("android.requestPromotedOngoing") = true 并反射调用 setRequestPromotedOngoing(true)
 * - 反射调用 setShortCriticalText(...) 向状态栏 Chip（<=96dp，建议 <=7 字符）推送精简实时状态
 * - 严格禁止挂接 customContentView / customBigContentView / setColorized(true)，确保通过 hasPromotableCharacteristics() 校验
 * - 全反射兼容 compileSdk = 35，在 Android 12–15 上零开销静默跳过
 */
object AndroidLiveUpdateBridge {

    const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    const val ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS = "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS"

    private val setRequestPromotedOngoingMethod by lazy {
        runCatching {
            Notification.Builder::class.java.getMethod(
                "setRequestPromotedOngoing",
                Boolean::class.javaPrimitiveType
            )
        }.getOrNull()
    }

    private val setShortCriticalTextMethod by lazy {
        runCatching {
            Notification.Builder::class.java.getMethod(
                "setShortCriticalText",
                String::class.java
            )
        }.getOrNull()
    }

    private val canPostPromotedMethod by lazy {
        runCatching {
            NotificationManager::class.java.getMethod("canPostPromotedNotifications")
        }.getOrNull()
    }

    private val hasPromotableCharacteristicsMethod by lazy {
        runCatching {
            Notification::class.java.getMethod("hasPromotableCharacteristics")
        }.getOrNull()
    }

    fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= 36 || setRequestPromotedOngoingMethod != null
    }

    fun canPostPromotedNotifications(context: Context): Boolean {
        if (!isSupported()) return false
        val m = canPostPromotedMethod ?: return false
        return runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            (m.invoke(nm) as? Boolean) ?: false
        }.getOrDefault(false)
    }

    fun buildManagePromotedSettingsIntent(context: Context): Intent {
        return Intent(ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 按 Android 16 官方 Live Updates 规范配置 Notification.Builder（不含任何 RemoteViews）
     */
    fun configureBuilderForPromotedLiveUpdate(
        context: Context,
        builder: Notification.Builder,
        config: CapsuleConfig,
        mirroredPayload: ActiveMirroredPayload?,
        title: String,
        detail: String,
        iconBitmap: Bitmap?
    ) {
        builder
            .setOngoing(true)
            .setColorized(false)
            .setGroupSummary(false)
            .setContentTitle(title.ifBlank { config.businessName.ifBlank { "灵动胶囊" } })
            .setContentText(detail.ifBlank { title })
            .setStyle(Notification.BigTextStyle().bigText(detail.ifBlank { title }))

        if (iconBitmap != null && !iconBitmap.isRecycled) {
            builder.setLargeIcon(iconBitmap)
        }

        // 若为媒体或导航进度，写入标准进度条
        if (mirroredPayload?.isMedia == true && mirroredPayload.mediaDurationMs > 0L) {
            val max = 100
            val prog = ((mirroredPayload.mediaPositionMs.coerceAtLeast(0L) * 100L) / mirroredPayload.mediaDurationMs)
                .toInt()
                .coerceIn(0, 100)
            builder.setProgress(max, prog, false)
        }

        // 写入官方推广请求标记 + 反射调用 setRequestPromotedOngoing(true)
        val extras = Bundle().apply {
            putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        }
        builder.addExtras(extras)
        runCatching { setRequestPromotedOngoingMethod?.invoke(builder, true) }

        // 状态栏 Chip 文本（官方建议 <= 7 字符以保证 96dp Chip 完整显示）
        val rawChip = mirroredPayload?.liveLyricLine?.takeIf { it.isNotBlank() }
            ?: title.ifBlank { config.mainText }
        val cleanChip = rawChip.replace('\n', ' ').replace('\r', ' ').trim()
        val shortChip = if (cleanChip.length > 7) cleanChip.take(7) else cleanChip
        if (shortChip.isNotBlank()) {
            runCatching { setShortCriticalTextMethod?.invoke(builder, shortChip) }
        }
    }

    fun verifyAndLog(context: Context, notification: Notification, title: String) {
        val canPost = canPostPromotedNotifications(context)
        val promotable = runCatching {
            (hasPromotableCharacteristicsMethod?.invoke(notification) as? Boolean) ?: false
        }.getOrDefault(false)
        AppLogger.i(
            "Capsule",
            "AndroidLiveUpdateBridge 构建完成 canPost=$canPost promotable=$promotable title=$title"
        )
    }
}
