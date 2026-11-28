package com.app.magiccapsule.capsule

import com.app.magiccapsule.capsule.android16.AndroidLiveUpdateBridge
import com.app.magiccapsule.capsule.honor.MagicCapsuleBridge
import com.app.magiccapsule.capsule.huawei.HuaweiLiveCapsuleBridge
import com.app.magiccapsule.capsule.vivo.VivoSuperXBridge
import com.app.magiccapsule.capsule.xiaomi.XiaomiFocusBridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
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
import com.app.magiccapsule.service.MediaContentType
import com.app.magiccapsule.service.MediaControlReceiver
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
    @Volatile private var cachedRvIconSrcHash: Int = 0
    @Volatile private var cachedRvIconBmp: Bitmap? = null

    /**
     * 将 RemoteViews 图标轻量化至 64x64 并按位图指纹缓存（单图字节数下降 56%，每句歌词刷新零重新分配）。
     */
    private fun getCompactRemoteViewsBitmap(src: Bitmap?): Bitmap? {
        if (src == null || src.isRecycled) return null
        val h = NotificationIconStore.bitmapFingerprint(src)
        val cached = cachedRvIconBmp
        if (h == cachedRvIconSrcHash && cached != null && !cached.isRecycled) {
            return cached
        }
        val out = if (src.width <= 64 && src.height <= 64) {
            src
        } else {
            runCatching {
                Bitmap.createScaledBitmap(src, 64, 64, true).also { it.prepareToDraw() }
            }.getOrDefault(src)
        }
        cachedRvIconSrcHash = h
        cachedRvIconBmp = out
        return out
    }

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

        val refreshPi = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, MediaControlReceiver::class.java)
                .setAction(MediaControlReceiver.ACTION_CAPSULE_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPi = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, MediaControlReceiver::class.java)
                .setAction(MediaControlReceiver.ACTION_CAPSULE_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mediaPrevBroadcastPi = PendingIntent.getBroadcast(
            context, 11,
            Intent(context, MediaControlReceiver::class.java)
                .setAction(MediaControlReceiver.ACTION_MEDIA_PREV),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mediaPlayPauseBroadcastPi = PendingIntent.getBroadcast(
            context, 12,
            Intent(context, MediaControlReceiver::class.java)
                .setAction(MediaControlReceiver.ACTION_MEDIA_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mediaNextBroadcastPi = PendingIntent.getBroadcast(
            context, 13,
            Intent(context, MediaControlReceiver::class.java)
                .setAction(MediaControlReceiver.ACTION_MEDIA_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mediaRewindBroadcastPi = PendingIntent.getBroadcast(
            context, 14,
            Intent(context, MediaControlReceiver::class.java)
                .setAction(MediaControlReceiver.ACTION_MEDIA_REWIND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mediaFastForwardBroadcastPi = PendingIntent.getBroadcast(
            context, 15,
            Intent(context, MediaControlReceiver::class.java)
                .setAction(MediaControlReceiver.ACTION_MEDIA_FAST_FORWARD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val accentColorForBtns = CapsuleConfig.parseColorSafe(config.accentColorHex, Color.parseColor("#FF34D399"))
        val stopRedColor = Color.parseColor("#FFFF6B6B")
        val realActions = mirroredPayload?.actions.orEmpty()
        val showOwnBtns = config.renderOwnNotificationButtons || mirroredPayload == null

        // 动态自适应按钮列表（0–4 个，按内容自适应宽度，不再固定死 2 个按钮）
        data class RenderedBtn(val text: String, val pi: PendingIntent, val color: Int)
        val renderedBtns = mutableListOf<RenderedBtn>()
        fun makeMappedActionBroadcastPi(reqCode: Int, notifKey: String, title: String, idx: Int): PendingIntent {
            val i = Intent(context, MediaControlReceiver::class.java)
                .setAction(MediaControlReceiver.ACTION_MAPPED_ACTION)
                .putExtra(MediaControlReceiver.EXTRA_NOTIF_KEY, notifKey)
                .putExtra(MediaControlReceiver.EXTRA_ACTION_TITLE, title)
                .putExtra(MediaControlReceiver.EXTRA_ACTION_INDEX, idx)
            return PendingIntent.getBroadcast(
                context,
                reqCode,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        if (mirroredPayload?.isMedia == true) {
            // 按媒体类型智能分配播控按钮（全部走同 UID 广播接收器）：
            // - 视频类 / 录音回放类：快退 | 暂停/播放 | 快进
            // - 音乐类：上一曲 | 快退 | 暂停/播放 | 快进 | 下一曲 (+ 可选歌词)
            val playLabel = if (mirroredPayload.mediaPlaying) "暂停" else "播放"
            val canSeek = mirroredPayload.mediaSupportsSeek
            when (mirroredPayload.mediaContentType) {
                MediaContentType.VIDEO, MediaContentType.RECORDER -> {
                    if (canSeek) renderedBtns.add(RenderedBtn("快退", mediaRewindBroadcastPi, accentColorForBtns))
                    renderedBtns.add(RenderedBtn(playLabel, mediaPlayPauseBroadcastPi, accentColorForBtns))
                    if (canSeek) renderedBtns.add(RenderedBtn("快进", mediaFastForwardBroadcastPi, accentColorForBtns))
                }
                MediaContentType.MUSIC -> {
                    renderedBtns.add(RenderedBtn("上一曲", mediaPrevBroadcastPi, accentColorForBtns))
                    if (canSeek) renderedBtns.add(RenderedBtn("快退", mediaRewindBroadcastPi, accentColorForBtns))
                    renderedBtns.add(RenderedBtn(playLabel, mediaPlayPauseBroadcastPi, accentColorForBtns))
                    if (canSeek) renderedBtns.add(RenderedBtn("快进", mediaFastForwardBroadcastPi, accentColorForBtns))
                    renderedBtns.add(RenderedBtn("下一曲", mediaNextBroadcastPi, accentColorForBtns))
                    if (com.app.magiccapsule.service.CapsuleNotificationListener.shouldShowLyricButton(mirroredPayload.packageName, realActions)) {
                        val lyricLabel = com.app.magiccapsule.service.CapsuleNotificationListener.getLyricButtonLabel(mirroredPayload.packageName)
                        val lyricIdx = realActions.indexOfFirst { it.title.contains("词") || it.title.contains("解锁") }.coerceAtLeast(0)
                        val lyricPi = makeMappedActionBroadcastPi(104, mirroredPayload.key, lyricLabel, lyricIdx)
                        renderedBtns.add(RenderedBtn(lyricLabel, lyricPi, accentColorForBtns))
                    }
                    if (renderedBtns.size < 6 && com.app.magiccapsule.service.CapsuleNotificationListener.shouldShowFavoriteButton(context, mirroredPayload.packageName, realActions)) {
                        val favLabel = com.app.magiccapsule.service.CapsuleNotificationListener.getFavoriteButtonLabel(mirroredPayload.packageName)
                        val favPi = PendingIntent.getBroadcast(
                            context,
                            108,
                            Intent(context, MediaControlReceiver::class.java)
                                .setAction(MediaControlReceiver.ACTION_MEDIA_FAVORITE)
                                .putExtra(MediaControlReceiver.EXTRA_NOTIF_KEY, mirroredPayload.key),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        renderedBtns.add(RenderedBtn(favLabel, favPi, accentColorForBtns))
                    }
                }
            }
        } else if (realActions.isNotEmpty() && mirroredPayload != null) {
            realActions.take(4).forEachIndexed { idx, act ->
                val pi = makeMappedActionBroadcastPi(101 + idx, mirroredPayload.key, act.title, idx)
                val color = when {
                    act.isDestructive -> stopRedColor
                    else -> accentColorForBtns
                }
                renderedBtns.add(RenderedBtn(act.title, pi, color))
            }
        } else if (showOwnBtns) {
            renderedBtns.add(RenderedBtn("刷新", refreshPi, accentColorForBtns))
            renderedBtns.add(RenderedBtn("停止", stopPi, stopRedColor))
        }
        val hasPrimaryBtn = renderedBtns.isNotEmpty()
        val hasSecondaryBtn = renderedBtns.size >= 2

        val title = "${config.businessName} · ${config.mainText}"
        val detail = if (config.showTimestamp) {
            "${config.subText} · $nowStr (#$refreshCount)"
        } else {
            config.subText
        }

        // 三套配色严格分离：设备监控胶囊独立配色 / 通知镜像复用横幅配色 / 灵动胶囊基础配色
        val isSysMonitor = mirroredPayload?.key == "sys_status_snapshot"
        val useBannerPalette = !isSysMonitor && mirroredPayload != null && config.capsuleFollowBannerStyle
        val cardBg = when {
            isSysMonitor -> config.resolveSysCapsuleBgColor()
            useBannerPalette -> config.resolveOverlayBannerBgColor()
            else -> config.resolveCardBgColor()
        }
        val compactBg = if (config.compactBgFollowSystemGlass) Color.TRANSPARENT else cardBg
        val titleColor = when {
            isSysMonitor -> CapsuleConfig.parseColorSafe(config.sysCapsuleTitleColorHex, Color.parseColor("#FFF8FAFC"))
            useBannerPalette -> CapsuleConfig.parseColorSafe(config.overlayBannerTitleColorHex, Color.parseColor("#FFFFFFFF"))
            else -> CapsuleConfig.parseColorSafe(config.titleColorHex, Color.parseColor("#FFECFDF5"))
        }
        val detailColor = when {
            isSysMonitor -> CapsuleConfig.parseColorSafe(config.sysCapsuleDetailColorHex, Color.parseColor("#FF38BDF8"))
            useBannerPalette -> CapsuleConfig.parseColorSafe(config.overlayBannerDetailColorHex, Color.parseColor("#FFB8D8CC"))
            else -> CapsuleConfig.parseColorSafe(config.detailColorHex, Color.parseColor("#FF6EE7B7"))
        }
        val btnBg = CapsuleConfig.parseColorSafe(config.btnBgHex, Color.parseColor("#FF065F46"))
        val accentColor = CapsuleConfig.parseColorSafe(config.accentColorHex, Color.parseColor("#FF34D399"))

        // 有通知时用通知/媒体会话封面图标替换；手动启动时用用户自选的手动胶囊图标
        val iconBitmap = mirroredPayload?.iconBitmap
            ?: NotificationIconStore.resolveManualIconBitmap(context, config)
        val rvIconBitmap: Bitmap? = getCompactRemoteViewsBitmap(iconBitmap)

        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val longSide = maxOf(bounds.width(), bounds.height())
        val shortSide = minOf(bounds.width(), bounds.height())

        val padH = dpToPx(context, 16f)
        val padBottom = dpToPx(context, 12f)
        val portraitPadTop = (getSafeTopPaddingPx(context) + config.portraitCapsuleOffsetYPx).coerceAtLeast(dpToPx(context, 8f))
        val landscapePadTop = (dpToPx(context, 14f) + config.landscapeCapsuleOffsetYPx).coerceAtLeast(dpToPx(context, 4f))
        val fixedContentPx = dpToPx(context, 64f)

        val compactRv = RemoteViews(context.packageName, R.layout.layout_capsule_compact).apply {
            setInt(R.id.compact_root, "setBackgroundColor", compactBg)
            val compactBmp: Bitmap? = rvIconBitmap
            if (compactBmp != null) {
                setViewVisibility(R.id.iv_app_icon, View.VISIBLE)
                setImageViewBitmap(R.id.iv_app_icon, compactBmp)
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

        val isMediaCard = mirroredPayload?.isMedia == true
        val effectiveLayoutStyle = if (isMediaCard && config.cardLayoutStyle == CapsuleConfig.LAYOUT_MINIMAL) {
            CapsuleConfig.LAYOUT_COMPACT_ROW
        } else {
            config.cardLayoutStyle
        }
        val layoutRes = when (effectiveLayoutStyle) {
            CapsuleConfig.LAYOUT_STANDARD -> R.layout.layout_card_standard
            CapsuleConfig.LAYOUT_HERO -> R.layout.layout_card_hero
            CapsuleConfig.LAYOUT_MINIMAL -> R.layout.layout_card_minimal
            else -> R.layout.layout_card_compact_row
        }

        fun buildExpanded(minWidthPx: Int?, topPadPx: Int, offsetXPx: Int): RemoteViews {
            val fixedTotalHeight = topPadPx + fixedContentPx + padBottom
            val padStart = (padH + offsetXPx).coerceAtLeast(dpToPx(context, 4f))
            val padEnd = (padH - offsetXPx).coerceAtLeast(dpToPx(context, 4f))
            return RemoteViews(context.packageName, layoutRes).apply {
                setViewPadding(R.id.card_root, padStart, topPadPx, padEnd, padBottom)
                // 横屏不写死窄 minWidth，让 match_parent 铺满荣耀系统展开容器全宽，彻底修复右侧圆角与像素裁切缺失
                if (minWidthPx != null && minWidthPx > 0) {
                    setInt(R.id.card_root, "setMinimumWidth", minWidthPx)
                }
                setInt(R.id.card_root, "setMinimumHeight", fixedTotalHeight)
                setInt(R.id.card_root, "setBackgroundColor", cardBg)

                setViewVisibility(
                    R.id.accent_bar,
                    if (config.showAccentBar) View.VISIBLE else View.GONE
                )
                setInt(R.id.accent_bar, "setBackgroundColor", accentColor)

                val expandedBmp: Bitmap? = rvIconBitmap
                if (expandedBmp != null) {
                    setViewVisibility(R.id.iv_app_icon, View.VISIBLE)
                    setImageViewBitmap(R.id.iv_app_icon, expandedBmp)
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

                // 低功耗高精度媒体进度条：原生 Chronometer 由 SystemUI 逐秒走时（零唤醒），ProgressBar 按 1% 阶跃同步
                val showMediaBar = mirroredPayload?.isMedia == true && mirroredPayload.mediaDurationMs > 0L
                setViewVisibility(R.id.media_progress_row, if (showMediaBar) View.VISIBLE else View.GONE)
                if (showMediaBar && mirroredPayload != null) {
                    val dur = mirroredPayload.mediaDurationMs.coerceAtLeast(1L)
                    val pos = mirroredPayload.mediaPositionMs.coerceIn(0L, dur)
                    val stepPermille = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                    setProgressBar(R.id.media_progress, 1000, stepPermille, false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching {
                            setColorStateList(R.id.media_progress, "setProgressTintList", ColorStateList.valueOf(accentColor))
                        }
                    }
                    val chronometerBase = SystemClock.elapsedRealtime() - pos
                    setChronometer(R.id.media_chronometer, chronometerBase, "%s", mirroredPayload.mediaPlaying)
                    setTextColor(R.id.media_chronometer, detailColor)
                    setTextViewText(R.id.tv_media_duration, " / ${formatMediaMs(dur)}")
                    setTextColor(R.id.tv_media_duration, detailColor)
                }

                if (effectiveLayoutStyle != CapsuleConfig.LAYOUT_MINIMAL) {
                    val showAnyActionRow = (config.showActions || isMediaCard) && renderedBtns.isNotEmpty()
                    setViewVisibility(
                        R.id.action_row,
                        if (showAnyActionRow) View.VISIBLE else View.GONE
                    )
                    // 防误触吸收盾：按钮行间隙与进度条区域拦截点击，快速连点快退/快进稍有偏移绝不误跳原应用
                    val noopShieldPi = PendingIntent.getBroadcast(
                        context,
                        999,
                        Intent(context, MediaControlReceiver::class.java).setAction(MediaControlReceiver.ACTION_NOOP_CONSUME),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    setOnClickPendingIntent(R.id.action_row, noopShieldPi)
                    if (showMediaBar) {
                        setOnClickPendingIntent(R.id.media_progress_row, noopShieldPi)
                    }
                    val btnIds = listOf(R.id.btn_refresh, R.id.btn_stop, R.id.btn_extra_3, R.id.btn_extra_4, R.id.btn_extra_5, R.id.btn_extra_6)
                    val capBtnDp = config.capsuleBtnSizeDp.coerceIn(36, 52).toFloat()
                    val btnPadH = dpToPx(context, if (renderedBtns.size <= 3) 16f else if (renderedBtns.size == 4) 12f else 6f)
                    val btnMinW = dpToPx(context, if (renderedBtns.size <= 3) 64f else if (renderedBtns.size == 4) 52f else 38f)
                    btnIds.forEachIndexed { idx, viewId ->
                        val spec = renderedBtns.getOrNull(idx)
                        if (spec != null) {
                            setViewVisibility(viewId, View.VISIBLE)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                runCatching {
                                    setViewLayoutHeight(viewId, capBtnDp, TypedValue.COMPLEX_UNIT_DIP)
                                }
                            }
                            setViewPadding(viewId, btnPadH, 0, btnPadH, 0)
                            setInt(viewId, "setMinWidth", btnMinW)
                            tintButton(this, viewId, btnBg)
                            setTextViewText(viewId, spec.text)
                            setTextColor(viewId, spec.color)
                            setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_SP, if (renderedBtns.size >= 5) 11f else config.btnSizeSp.coerceAtLeast(12f))
                            setOnClickPendingIntent(viewId, spec.pi)
                        } else {
                            setViewVisibility(viewId, View.GONE)
                        }
                    }
                }
            }
        }

        // 按当前屏幕方向单向构图（不再同时打包横竖屏双份 RemoteViews，Binder IPC 体积与大图复制直接减半）
        val isLandNow = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val activeMinWidth = if (isLandNow) longSide else shortSide
        val activeTopPad = if (isLandNow) landscapePadTop else portraitPadTop
        val activeOffsetX = if (isLandNow) config.landscapeCapsuleOffsetXPx else config.portraitCapsuleOffsetXPx

        // 1. 通知中心 / 锁屏展开视图（setCustomBigContentView）
        val shadePadTop = dpToPx(context, 12f)
        val shadeBigRv = buildExpanded(
            minWidthPx = activeMinWidth,
            topPadPx = shadePadTop,
            offsetXPx = 0
        )

        // 2. 荣耀灵动胶囊展开专用视图（仅在需要顶部挖孔留白/偏移时单独构建；否则直接复用 shadeBigRv 避免重复塞图）
        val isHonorEngine = DeviceBrandRouter.currentEngine == DeviceBrandRouter.BrandEngine.HONOR_MAGIC_CAPSULE
        val capsuleExpandedRv = if (isHonorEngine && (activeTopPad != shadePadTop || activeOffsetX != 0)) {
            buildExpanded(
                minWidthPx = activeMinWidth,
                topPadPx = activeTopPad,
                offsetXPx = activeOffsetX
            )
        } else {
            shadeBigRv
        }

        val realMediaToken = if (mirroredPayload?.isMedia == true) {
            mirroredPayload.mediaSessionToken
                ?: com.app.magiccapsule.service.CapsuleNotificationListener.getPinnedMediaToken()
        } else null
        // 荣耀 MagicOS 上对挂载 smart_island_capsule_data 的通知混挂 MediaStyle 会导致 SystemUI 冻结 headsUpContentView
        // 出现“收起胶囊已切歌，展开卡片仍卡在上一首歌”的严重错位，因此荣耀机型始终走同步三视图 RemoteViews 刷新
        val useNativeMediaCard = mirroredPayload?.isMedia == true &&
            config.useNativeSystemMediaCard &&
            realMediaToken != null &&
            DeviceBrandRouter.currentEngine != DeviceBrandRouter.BrandEngine.HONOR_MAGIC_CAPSULE &&
            DeviceBrandRouter.currentEngine != DeviceBrandRouter.BrandEngine.HUAWEI_LIVE_CAPSULE

        val builder = Notification.Builder(context, activeChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(if (useMediaMode || useNativeMediaCard) Notification.CATEGORY_TRANSPORT else Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(cardClickPi)

        val isAndroid16LiveUpdate = DeviceBrandRouter.currentEngine == DeviceBrandRouter.BrandEngine.ANDROID_LIVE_UPDATES
        if (isAndroid16LiveUpdate) {
            AndroidLiveUpdateBridge.configureBuilderForPromotedLiveUpdate(
                context = context,
                builder = builder,
                config = config,
                mirroredPayload = mirroredPayload,
                title = title,
                detail = detail,
                iconBitmap = iconBitmap
            )
            // Android 16 Live Updates 禁用 RemoteViews，改由标准 Notification.Action 承载播控/导航按钮
            renderedBtns.take(3).forEach { b ->
                builder.addAction(Notification.Action.Builder(null, b.text, b.pi).build())
            }
        } else if (useNativeMediaCard) {
            // 原生系统媒体卡片模式：直接绑定播放器真实 MediaSession.Token，由 SystemUI 原生 HnMediaControlPanel + HnSeekBarObserver
            // 以 60fps 连续渲染可拖动进度条与播控按钮，不覆盖 CustomViews，彻底消除 2.5s 周期性 notify 耗电
            val playIcon = if (mirroredPayload!!.mediaPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val playText = if (mirroredPayload.mediaPlaying) "暂停" else "播放"
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "上一曲", mediaPrevBroadcastPi).build())
            builder.addAction(Notification.Action.Builder(playIcon, playText, mediaPlayPauseBroadcastPi).build())
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "下一曲", mediaNextBroadcastPi).build())
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(realMediaToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            // 关键：荣耀 MagicOS 点击状态栏胶囊展开卡片强依赖 headsUpContentView，必须同步刷新为当前媒体卡片，
            // 否则切换通知时会残留上一条普通通知（如 Play 商店）的旧展开视图
            builder.setCustomHeadsUpContentView(capsuleExpandedRv)
            AppLogger.i("Capsule", "已启用原生系统 MediaStyle 卡片+同步展开视图（零2.5s轮询）pkg=${mirroredPayload.packageName}")
        } else {
            builder.setCustomContentView(compactRv)
            builder.setCustomBigContentView(shadeBigRv)
            if (useMediaMode) {
                val session = ensureDummyMediaSession(context, title, detail)
                if (session != null) {
                    builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
                }
            }
            // 仅荣耀 MagicOS 胶囊点击展开强依赖 headsUpContentView；非荣耀或纯静默渠道不再重复挂载 headsUpContentView
            if (isHonorEngine) {
                builder.setCustomHeadsUpContentView(capsuleExpandedRv)
            }
        }
        // 仅原生 MediaStyle 卡片需要 setLargeIcon；自定义 RemoteViews 已内置 64px 轻量化图标，避免额外序列化一整份大图
        if (useNativeMediaCard && iconBitmap != null) {
            builder.setLargeIcon(iconBitmap)
        }

        val notification = builder.build()
        val isRecorderWithTimer = mirroredPayload?.isRecorder == true && mirroredPayload.chronometerBaseMs > 0L
        val bridgeConfig = when {
            isSysMonitor -> config.copy(brandColor = config.sysCapsuleBrandColor)
            isRecorderWithTimer -> config.copy(template = CapsuleConfig.TEMPLATE_IMAGE_TEXT_TIMER, enableTimer = false)
            else -> config
        }
        val attachResult = when (DeviceBrandRouter.currentEngine) {
            DeviceBrandRouter.BrandEngine.HONOR_MAGIC_CAPSULE -> {
                MagicCapsuleBridge.attach(
                    context = context,
                    notification = notification,
                    config = bridgeConfig,
                    capsuleIconBitmap = iconBitmap,
                    overrideChronometerBaseMs = if (isRecorderWithTimer) mirroredPayload!!.chronometerBaseMs else 0L,
                    overrideChronometerPaused = if (isRecorderWithTimer) mirroredPayload!!.chronometerPaused else false,
                    isMediaPlayback = mirroredPayload?.isMedia == true,
                    mediaPlaying = mirroredPayload?.mediaPlaying == true
                ).onFailure { e ->
                    AppLogger.e("Capsule", "MagicCapsuleBridge.attach 失败", e)
                }
            }
            DeviceBrandRouter.BrandEngine.HUAWEI_LIVE_CAPSULE -> {
                HuaweiLiveCapsuleBridge.attach(
                    context = context,
                    notification = notification,
                    config = bridgeConfig,
                    mirroredPayload = mirroredPayload,
                    capsuleIconBitmap = iconBitmap,
                    overrideChronometerBaseMs = if (isRecorderWithTimer) mirroredPayload!!.chronometerBaseMs else 0L,
                    overrideChronometerPaused = if (isRecorderWithTimer) mirroredPayload!!.chronometerPaused else false
                ).onFailure { e ->
                    AppLogger.e("Capsule", "HuaweiLiveCapsuleBridge.attach 失败", e)
                }
            }
            DeviceBrandRouter.BrandEngine.VIVO_SUPERX -> {
                VivoSuperXBridge.attach(
                    context = context,
                    notification = notification,
                    config = bridgeConfig,
                    mirroredPayload = mirroredPayload,
                    capsuleIconBitmap = iconBitmap,
                    clickIntent = cardClickPi
                ).onFailure { e ->
                    AppLogger.w("Capsule", "VivoSuperXBridge.attach 异常：${e.message}")
                }
            }
            DeviceBrandRouter.BrandEngine.XIAOMI_HYPEROS_FOCUS -> {
                XiaomiFocusBridge.attach(
                    context = context,
                    notification = notification,
                    config = bridgeConfig,
                    capsuleIconBitmap = iconBitmap
                ).onFailure { e ->
                    AppLogger.w("Capsule", "XiaomiFocusBridge.attach 异常：${e.message}")
                }
            }
            DeviceBrandRouter.BrandEngine.ANDROID_LIVE_UPDATES -> {
                AndroidLiveUpdateBridge.verifyAndLog(context, notification, title)
                Result.success(Unit)
            }
            DeviceBrandRouter.BrandEngine.UNIVERSAL_OVERLAY -> {
                AppLogger.d("Capsule", "通用机型跳过私有胶囊桥接，改由通用悬浮横幅/迷你胶囊渲染")
                Result.success(Unit)
            }
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        val liveImp = runCatching { nm.getNotificationChannel(activeChannelId)?.importance ?: -1 }.getOrDefault(-1)
        val notifEnabled = runCatching { nm.areNotificationsEnabled() }.getOrDefault(false)
        AppLogger.i(
            "Capsule",
            "构建完成 ch=$activeChannelId(liveImp=$liveImp) notifOn=$notifEnabled media=$useMediaMode quiet=$useQuietMode " +
                "headsUpView=${notification.headsUpContentView != null} bigView=${notification.bigContentView != null} " +
                "layout=${config.cardLayoutStyle} tpl=${bridgeConfig.template} icon=${iconBitmap != null} " +
                "btns=${hasPrimaryBtn}/${hasSecondaryBtn} rec=${mirroredPayload?.isRecorder == true}(chrono=${mirroredPayload?.chronometerBaseMs ?: 0L}) " +
                "isMedia=${mirroredPayload?.isMedia == true}(type=${mirroredPayload?.mediaContentType},play=${mirroredPayload?.mediaPlaying == true}) bridgeOk=${attachResult.isSuccess}"
        )
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

    private fun formatMediaMs(ms: Long): String =
        com.app.magiccapsule.service.NativeMediaSeekBarObserver.formatElapsedTime(ms)

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
