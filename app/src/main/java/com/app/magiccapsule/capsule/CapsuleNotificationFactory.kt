package com.app.magiccapsule.capsule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.RemoteViews
import com.app.magiccapsule.R
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.service.ActiveMirroredPayload
import com.app.magiccapsule.service.CapsuleKeepAliveService
import com.app.magiccapsule.service.NotificationIconStore
import com.app.magiccapsule.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CapsuleNotificationFactory {

    // 原生胶囊主渠道（已验证稳定弹出展开卡片）
    const val CHANNEL_ID = "magic_capsule_live_channel"
    // 静默胶囊渠道（IMPORTANCE_DEFAULT：不弹系统横幅，彻底杜绝自定义顶层横幅下方出现同名重复通知）
    const val QUIET_CHANNEL_ID = "magic_capsule_quiet_channel"
    // 仿媒体播放器强推渠道（可选，用于绕过部分系统对普通服务渠道的校验）
    const val MEDIA_CHANNEL_ID = "magic_media_playback"
    const val NOTIFY_ID = 10086

    @Volatile
    private var dummyMediaSession: MediaSession? = null

    fun ensureChannel(
        context: Context,
        useMediaChannel: Boolean = false,
        useQuietChannel: Boolean = false
    ): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (useQuietChannel) {
            if (nm.getNotificationChannel(QUIET_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    QUIET_CHANNEL_ID,
                    "灵动胶囊静默状态（防重复横幅）",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "开启自定义顶层横幅时使用，仅更新状态栏胶囊与通知栏卡片，不弹出重复系统横幅"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
                AppLogger.i("Capsule", "已创建静默胶囊渠道 id=$QUIET_CHANNEL_ID importance=IMPORTANCE_DEFAULT")
            }
            return QUIET_CHANNEL_ID
        }
        if (useMediaChannel) {
            if (nm.getNotificationChannel(MEDIA_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    MEDIA_CHANNEL_ID,
                    "灵动胶囊媒体通道",
                    NotificationManager.IMPORTANCE_MAX
                ).apply {
                    description = "仿媒体播放通道，用于在严格校验系统上触发灵动胶囊"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
                AppLogger.i("Capsule", "已创建媒体强推渠道 id=$MEDIA_CHANNEL_ID importance=IMPORTANCE_MAX")
            }
            return MEDIA_CHANNEL_ID
        }
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "灵动胶囊实时状态",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "用于在荣耀灵动胶囊展示实时信息"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
            AppLogger.i("Capsule", "已创建通知渠道 id=$CHANNEL_ID importance=IMPORTANCE_HIGH")
        }
        return CHANNEL_ID
    }

    private fun ensureDummyMediaSession(context: Context, title: String, detail: String): MediaSession? {
        return runCatching {
            val session = dummyMediaSession ?: MediaSession(
                context.applicationContext,
                "MagicCapsuleMediaSession"
            ).also { dummyMediaSession = it }
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, detail)
                    .build()
            )
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .build()
            )
            session.isActive = true
            session
        }.onFailure { e ->
            AppLogger.w("Capsule", "创建 MediaSession 失败：${e.message}")
        }.getOrNull()
    }

    fun releaseMediaSession() {
        runCatching {
            dummyMediaSession?.let {
                it.isActive = false
                it.release()
            }
        }
        dummyMediaSession = null
    }

    @Suppress("DEPRECATION")
    fun create(
        context: Context,
        config: CapsuleConfig,
        refreshCount: Int,
        mirroredPayload: ActiveMirroredPayload? = null
    ): Notification {
        val useMediaMode = config.capsuleDeliveryMode == CapsuleConfig.DELIVERY_MEDIA_STYLE
        val useQuietMode = !useMediaMode &&
            mirroredPayload != null &&
            config.enableOverlayBanner &&
            config.capsuleQuietChannelWhenBannerOn
        val activeChannelId = ensureChannel(
            context,
            useMediaChannel = useMediaMode,
            useQuietChannel = useQuietMode
        )
        if (!useMediaMode) {
            releaseMediaSession()
        }

        val nowStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val openAppPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cardClickPi = mirroredPayload?.contentIntent ?: openAppPi

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

        // 透传原通知自带按钮（含直接跳转回复入口）
        val realActions = mirroredPayload?.actions.orEmpty()
        val showOwnBtns = config.renderOwnNotificationButtons || mirroredPayload == null
        val hasPrimaryBtn = realActions.isNotEmpty() || showOwnBtns
        val hasSecondaryBtn = realActions.size >= 2 || showOwnBtns

        val primaryAct = realActions.getOrNull(0)
        val primaryBtnText = primaryAct?.title ?: "刷新"
        val primaryBtnPi = if (primaryAct != null) {
            if (!primaryAct.hasRemoteInput && primaryAct.intent != null) primaryAct.intent else cardClickPi
        } else {
            refreshPi
        }
        val secondaryAct = realActions.getOrNull(1)
        val secondaryBtnText = secondaryAct?.title ?: "停止"
        val secondaryBtnPi = if (secondaryAct != null) {
            if (!secondaryAct.hasRemoteInput && secondaryAct.intent != null) secondaryAct.intent else cardClickPi
        } else {
            stopPi
        }
        val secondaryBtnColor = if (secondaryAct != null) {
            CapsuleConfig.parseColorSafe(config.accentColorHex, Color.parseColor("#FF34D399"))
        } else {
            Color.parseColor("#FFFF6B6B")
        }

        val title = "${config.businessName} · ${config.mainText}"
        val detail = if (config.showTimestamp) {
            "${config.subText} · $nowStr (#$refreshCount)"
        } else {
            config.subText
        }

        // 有新通知且开启「复用横幅样式」时，直接引用横幅通知的底色、标题色、副文色与图标
        val useBannerPalette = mirroredPayload != null && config.capsuleFollowBannerStyle
        val cardBg = if (useBannerPalette) {
            config.resolveOverlayBannerBgColor()
        } else {
            config.resolveCardBgColor()
        }
        val compactBg = if (config.compactBgFollowSystemGlass) Color.TRANSPARENT else cardBg
        val titleColor = if (useBannerPalette) {
            CapsuleConfig.parseColorSafe(config.overlayBannerTitleColorHex, Color.parseColor("#FFFFFFFF"))
        } else {
            CapsuleConfig.parseColorSafe(config.titleColorHex, Color.parseColor("#FFECFDF5"))
        }
        val detailColor = if (useBannerPalette) {
            CapsuleConfig.parseColorSafe(config.overlayBannerDetailColorHex, Color.parseColor("#FFB8D8CC"))
        } else {
            CapsuleConfig.parseColorSafe(config.detailColorHex, Color.parseColor("#FF6EE7B7"))
        }
        val btnBg = CapsuleConfig.parseColorSafe(config.btnBgHex, Color.parseColor("#FF065F46"))
        val accentColor = CapsuleConfig.parseColorSafe(config.accentColorHex, Color.parseColor("#FF34D399"))

        // 有通知时用通知/应用图标替换；手动启动时用用户自选的手动胶囊图标
        val iconBitmap = mirroredPayload?.iconBitmap
            ?: NotificationIconStore.resolveManualIconBitmap(context, config)

        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val longSide = maxOf(bounds.width(), bounds.height())
        val shortSide = minOf(bounds.width(), bounds.height())

        val padH = dpToPx(context, 16f)
        val padBottom = dpToPx(context, 12f)
        val portraitPadTop = getSafeTopPaddingPx(context)
        val landscapePadTop = dpToPx(context, 12f)
        val landscapeWidth = (longSide * 0.56f).toInt().coerceAtMost(shortSide)
        val fixedContentPx = dpToPx(context, 64f)

        val compactRv = RemoteViews(context.packageName, R.layout.layout_capsule_compact).apply {
            setInt(R.id.compact_root, "setBackgroundColor", compactBg)
            if (iconBitmap != null) {
                setViewVisibility(R.id.iv_app_icon, View.VISIBLE)
                setImageViewBitmap(R.id.iv_app_icon, iconBitmap)
            } else {
                setViewVisibility(R.id.iv_app_icon, View.GONE)
            }
            setTextViewText(R.id.tv_title, title)
            setTextColor(R.id.tv_title, titleColor)
            setTextViewTextSize(R.id.tv_title, TypedValue.COMPLEX_UNIT_SP, config.titleSizeSp.coerceAtMost(18f))
            setTextViewText(R.id.tv_detail, detail)
            setTextColor(R.id.tv_detail, detailColor)
            setTextViewTextSize(R.id.tv_detail, TypedValue.COMPLEX_UNIT_SP, config.detailSizeSp.coerceAtMost(15f))
            setViewVisibility(R.id.btn_stop, if (showOwnBtns) View.VISIBLE else View.GONE)
            tintButton(this, R.id.btn_stop, btnBg)
            setOnClickPendingIntent(R.id.compact_root, cardClickPi)
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

                if (iconBitmap != null) {
                    setViewVisibility(R.id.iv_app_icon, View.VISIBLE)
                    setImageViewBitmap(R.id.iv_app_icon, iconBitmap)
                } else {
                    setViewVisibility(R.id.iv_app_icon, View.GONE)
                }

                setTextViewText(R.id.tv_title, title)
                setTextColor(R.id.tv_title, titleColor)
                setTextViewTextSize(R.id.tv_title, TypedValue.COMPLEX_UNIT_SP, config.titleSizeSp)

                setTextViewText(R.id.tv_detail, detail)
                setTextColor(R.id.tv_detail, detailColor)
                setTextViewTextSize(R.id.tv_detail, TypedValue.COMPLEX_UNIT_SP, config.detailSizeSp)

                setOnClickPendingIntent(R.id.card_root, cardClickPi)

                if (config.cardLayoutStyle != CapsuleConfig.LAYOUT_MINIMAL) {
                    val showAnyActionRow = config.showActions && (hasPrimaryBtn || hasSecondaryBtn)
                    setViewVisibility(
                        R.id.action_row,
                        if (showAnyActionRow) View.VISIBLE else View.GONE
                    )
                    setViewVisibility(R.id.btn_refresh, if (hasPrimaryBtn) View.VISIBLE else View.GONE)
                    setViewVisibility(R.id.btn_stop, if (hasSecondaryBtn) View.VISIBLE else View.GONE)
                    val capBtnDp = config.capsuleBtnSizeDp.coerceIn(24, 48).toFloat()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching {
                            if (config.cardLayoutStyle == CapsuleConfig.LAYOUT_COMPACT_ROW) {
                                setViewLayoutHeight(R.id.btn_refresh, capBtnDp, TypedValue.COMPLEX_UNIT_DIP)
                                setViewLayoutHeight(R.id.btn_stop, capBtnDp, TypedValue.COMPLEX_UNIT_DIP)
                                val wDp = (capBtnDp * 1.85f).coerceIn(48f, 92f)
                                setViewLayoutWidth(R.id.btn_refresh, wDp, TypedValue.COMPLEX_UNIT_DIP)
                                setViewLayoutWidth(R.id.btn_stop, wDp, TypedValue.COMPLEX_UNIT_DIP)
                            } else {
                                setViewLayoutHeight(R.id.action_row, capBtnDp, TypedValue.COMPLEX_UNIT_DIP)
                            }
                        }
                    }
                    tintButton(this, R.id.btn_refresh, btnBg)
                    tintButton(this, R.id.btn_stop, btnBg)
                    setTextViewText(R.id.btn_refresh, primaryBtnText)
                    setTextViewText(R.id.btn_stop, secondaryBtnText)
                    setTextColor(R.id.btn_refresh, accentColor)
                    setTextColor(R.id.btn_stop, secondaryBtnColor)
                    setTextViewTextSize(R.id.btn_refresh, TypedValue.COMPLEX_UNIT_SP, config.btnSizeSp)
                    setTextViewTextSize(R.id.btn_stop, TypedValue.COMPLEX_UNIT_SP, config.btnSizeSp)
                    setOnClickPendingIntent(R.id.btn_refresh, primaryBtnPi)
                    setOnClickPendingIntent(R.id.btn_stop, secondaryBtnPi)
                }
            }
        }

        val portraitExpandedRv = buildExpanded(shortSide, portraitPadTop)
        val landscapeExpandedRv = buildExpanded(landscapeWidth, landscapePadTop)
        val dualExpandedRv = RemoteViews(landscapeExpandedRv, portraitExpandedRv)

        val builder = Notification.Builder(context, activeChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(detail)
            .setCustomContentView(compactRv)
            .setCustomBigContentView(dualExpandedRv)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(if (useMediaMode) Notification.CATEGORY_TRANSPORT else Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(cardClickPi)

        if (useMediaMode) {
            val session = ensureDummyMediaSession(context, title, detail)
            if (session != null) {
                builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            }
        }

        // 仅在未开启自定义顶层横幅时才附加系统 HeadsUp 视图，防止在自定义横幅下方弹出同名通知
        if (!config.enableOverlayBanner) {
            builder.setCustomHeadsUpContentView(dualExpandedRv)
        }
        if (iconBitmap != null) {
            builder.setLargeIcon(iconBitmap)
        }

        val notification = builder.build()
        val attachResult = MagicCapsuleBridge.attach(context, notification, config, capsuleIconBitmap = iconBitmap)
        attachResult.onFailure { e ->
            AppLogger.e("Capsule", "MagicCapsuleBridge.attach 失败", e)
        }
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
