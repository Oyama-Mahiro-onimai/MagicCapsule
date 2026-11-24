package com.app.magiccapsule.capsule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.RemoteViews
import com.app.magiccapsule.R
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.service.CapsuleKeepAliveService
import com.app.magiccapsule.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CapsuleNotificationFactory {

    const val CHANNEL_ID = "magic_capsule_live_channel"
    const val NOTIFY_ID = 10086

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "灵动胶囊实时状态",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "用于在荣耀灵动胶囊展示实时信息"
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun create(context: Context, config: CapsuleConfig, refreshCount: Int): Notification {
        ensureChannel(context)

        val nowStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val openAppPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val refreshPi = PendingIntent.getService(
            context, 1,
            Intent(context, CapsuleKeepAliveService::class.java)
                .setAction(CapsuleKeepAliveService.ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPi = PendingIntent.getService(
            context, 2,
            Intent(context, CapsuleKeepAliveService::class.java)
                .setAction(CapsuleKeepAliveService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "${config.businessName} · ${config.mainText}"
        val detail = "${config.subText} · $nowStr (#$refreshCount)"

        val bounds = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val isLandscape = bounds.width() > bounds.height()

        val expandedWidth = if (isLandscape) {
            (bounds.width() * 0.70f).toInt()
        } else {
            bounds.width()
        }
        val compactWidth = minOf(bounds.width(), bounds.height())
        val padH = dpToPx(context, 16f)
        val padTop = if (isLandscape) dpToPx(context, 14f) else getSafeTopPaddingPx(context)

        val compactRv = RemoteViews(context.packageName, R.layout.layout_capsule_compact).apply {
            setInt(R.id.compact_root, "setMinimumWidth", compactWidth)
            setTextViewText(R.id.tv_title, title)
            setTextViewText(R.id.tv_detail, detail)
            setOnClickPendingIntent(R.id.compact_root, openAppPi)
            setOnClickPendingIntent(R.id.btn_stop, stopPi)
        }

        val expandedRv = RemoteViews(context.packageName, R.layout.layout_capsule_expanded).apply {
            setViewPadding(R.id.card_root, padH, padTop, padH, dpToPx(context, 16f))
            setInt(R.id.card_root, "setMinimumWidth", expandedWidth)
            setTextViewText(R.id.tv_title, title)
            setTextViewText(R.id.tv_detail, detail)
            setOnClickPendingIntent(R.id.card_root, openAppPi)
            setOnClickPendingIntent(R.id.btn_refresh, refreshPi)
            setOnClickPendingIntent(R.id.btn_stop, stopPi)
        }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(detail)
            .setCustomContentView(compactRv)
            .setCustomBigContentView(expandedRv)
            .setCustomHeadsUpContentView(expandedRv)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openAppPi)
            .build()

        MagicCapsuleBridge.attach(context, notification, config)
        return notification
    }

    private fun dpToPx(context: Context, dp: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()

    private fun getSafeTopPaddingPx(context: Context): Int {
        val wm = context.getSystemService(WindowManager::class.java)
        val insetsTop = runCatching {
            wm.currentWindowMetrics.windowInsets.getInsets(
                WindowInsets.Type.displayCutout() or WindowInsets.Type.statusBars()
            ).top
        }.getOrDefault(0)

        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarPx = if (resId > 0) {
            context.resources.getDimensionPixelSize(resId)
        } else {
            dpToPx(context, 38f)
        }
        return maxOf(insetsTop, statusBarPx) + dpToPx(context, 4f)
    }
}
