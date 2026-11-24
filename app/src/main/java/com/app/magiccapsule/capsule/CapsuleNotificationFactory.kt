package com.app.magiccapsule.capsule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.View
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

    @Suppress("DEPRECATION")
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
        val detail = if (config.showTimestamp) {
            "${config.subText} · $nowStr (#$refreshCount)"
        } else {
            config.subText
        }

        val cardBg = CapsuleConfig.parseColorSafe(config.cardBgHex, Color.parseColor("#FF0D1F18"))
        val titleColor = CapsuleConfig.parseColorSafe(config.titleColorHex, Color.parseColor("#FFECFDF5"))
        val detailColor = CapsuleConfig.parseColorSafe(config.detailColorHex, Color.parseColor("#FF6EE7B7"))
        val btnBg = CapsuleConfig.parseColorSafe(config.btnBgHex, Color.parseColor("#FF065F46"))
        val accentColor = CapsuleConfig.parseColorSafe(config.accentColorHex, Color.parseColor("#FF34D399"))

        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val longSide = maxOf(bounds.width(), bounds.height())
        val shortSide = minOf(bounds.width(), bounds.height())

        val padH = dpToPx(context, 16f)
        val padBottom = dpToPx(context, 12f)
        val portraitPadTop = getSafeTopPaddingPx(context)
        val landscapePadTop = dpToPx(context, 12f)
        val landscapeWidth = (longSide * 0.72f).toInt()
        val fixedContentPx = dpToPx(context, 64f)

        val compactRv = RemoteViews(context.packageName, R.layout.layout_capsule_compact).apply {
            setInt(R.id.compact_root, "setMinimumWidth", shortSide)
            setInt(R.id.compact_root, "setBackgroundColor", cardBg)
            setTextViewText(R.id.tv_title, title)
            setTextColor(R.id.tv_title, titleColor)
            setTextViewTextSize(R.id.tv_title, TypedValue.COMPLEX_UNIT_SP, config.titleSizeSp.coerceAtMost(18f))
            setTextViewText(R.id.tv_detail, detail)
            setTextColor(R.id.tv_detail, detailColor)
            setTextViewTextSize(R.id.tv_detail, TypedValue.COMPLEX_UNIT_SP, config.detailSizeSp.coerceAtMost(15f))
            tintButton(this, R.id.btn_stop, btnBg)
            setOnClickPendingIntent(R.id.compact_root, openAppPi)
            setOnClickPendingIntent(R.id.btn_stop, stopPi)
        }

        val layoutRes = when (config.cardLayoutStyle) {
            CapsuleConfig.LAYOUT_STANDARD -> R.layout.layout_card_standard
            CapsuleConfig.LAYOUT_HERO -> R.layout.layout_card_hero
            CapsuleConfig.LAYOUT_MINIMAL -> R.layout.layout_card_minimal
            else -> R.layout.layout_card_compact_row
        }

        fun buildExpanded(widthPx: Int, topPadPx: Int): RemoteViews {
            val fixedTotalHeight = topPadPx + fixedContentPx + padBottom
            return RemoteViews(context.packageName, layoutRes).apply {
                setViewPadding(R.id.card_root, padH, topPadPx, padH, padBottom)
                setInt(R.id.card_root, "setMinimumWidth", widthPx)
                setInt(R.id.card_root, "setMinimumHeight", fixedTotalHeight)
                setInt(R.id.card_root, "setBackgroundColor", cardBg)

                setViewVisibility(
                    R.id.accent_bar,
                    if (config.showAccentBar) View.VISIBLE else View.GONE
                )
                setInt(R.id.accent_bar, "setBackgroundColor", accentColor)

                setTextViewText(R.id.tv_title, title)
                setTextColor(R.id.tv_title, titleColor)
                setTextViewTextSize(R.id.tv_title, TypedValue.COMPLEX_UNIT_SP, config.titleSizeSp)

                setTextViewText(R.id.tv_detail, detail)
                setTextColor(R.id.tv_detail, detailColor)
                setTextViewTextSize(R.id.tv_detail, TypedValue.COMPLEX_UNIT_SP, config.detailSizeSp)

                setOnClickPendingIntent(R.id.card_root, openAppPi)

                if (config.cardLayoutStyle != CapsuleConfig.LAYOUT_MINIMAL) {
                    setViewVisibility(
                        R.id.action_row,
                        if (config.showActions) View.VISIBLE else View.GONE
                    )
                    tintButton(this, R.id.btn_refresh, btnBg)
                    tintButton(this, R.id.btn_stop, btnBg)
                    setTextColor(R.id.btn_refresh, accentColor)
                    setTextViewTextSize(R.id.btn_refresh, TypedValue.COMPLEX_UNIT_SP, config.btnSizeSp)
                    setTextViewTextSize(R.id.btn_stop, TypedValue.COMPLEX_UNIT_SP, config.btnSizeSp)
                    setOnClickPendingIntent(R.id.btn_refresh, refreshPi)
                    setOnClickPendingIntent(R.id.btn_stop, stopPi)
                }
            }
        }

        val portraitExpandedRv = buildExpanded(shortSide, portraitPadTop)
        val landscapeExpandedRv = buildExpanded(landscapeWidth, landscapePadTop)
        val dualExpandedRv = RemoteViews(landscapeExpandedRv, portraitExpandedRv)

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(detail)
            .setCustomContentView(compactRv)
            .setCustomBigContentView(dualExpandedRv)
            .setCustomHeadsUpContentView(dualExpandedRv)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openAppPi)
            .build()

        MagicCapsuleBridge.attach(context, notification, config)
        return notification
    }

    private fun tintButton(rv: RemoteViews, viewId: Int, color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                rv.setColorStateList(viewId, "setBackgroundTintList", ColorStateList.valueOf(color))
            }.onFailure {
                rv.setInt(viewId, "setBackgroundColor", color)
            }
        } else {
            rv.setInt(viewId, "setBackgroundColor", color)
        }
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
            wm.maximumWindowMetrics.windowInsets.getInsets(
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
