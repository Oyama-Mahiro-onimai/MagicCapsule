package com.app.magiccapsule.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.PendingIntent
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.media.AudioManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateInterpolator
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.content.res.ColorStateList
import android.os.SystemClock
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.app.magiccapsule.R
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.data.CapsulePrefs
import com.app.magiccapsule.model.CapsuleConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class BannerAction(
    val title: String,
    val intent: PendingIntent?,
    val hasRemoteInput: Boolean = false,
    val isPositive: Boolean = false,
    val isDestructive: Boolean = false
)

/**
 * 方案一：极简无障碍置顶层服务（TYPE_ACCESSIBILITY_OVERLAY）
 * - 支持左右滑动顺滑滑掉横幅（使用 onInterceptTouchEvent + scaledTouchSlop，不与内部按钮点击冲突）
 * - 自动读取并渲染通知自带的交互按钮（Notification.actions），点击直接执行原通知 PendingIntent
 * - 支持用户自定义上下偏移 (px)、上下内边距 (px)、独立横幅颜色、不透明度与左侧通知/应用图标
 */
class CapsuleOverlayAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: CapsuleOverlayAccessibilityService? = null

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _lastOverlayStatus = MutableStateFlow("尚未触发顶层横幅")
        val lastOverlayStatus: StateFlow<String> = _lastOverlayStatus.asStateFlow()

        fun isServiceEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val target = ComponentName(context, CapsuleOverlayAccessibilityService::class.java)
            return enabled.contains(target.flattenToString()) ||
                enabled.contains(target.flattenToShortString())
        }

        fun showBanner(
            appName: String,
            packageName: String,
            title: String,
            text: String,
            contentIntent: PendingIntent?,
            iconBitmap: Bitmap?,
            actions: List<BannerAction> = emptyList(),
            config: CapsuleConfig,
            notificationKey: String? = null,
            isPersistent: Boolean = false,
            isMedia: Boolean = false,
            mediaDurationMs: Long = 0L,
            mediaPositionMs: Long = 0L,
            mediaPlaying: Boolean = false,
            mediaContentType: MediaContentType = MediaContentType.MUSIC,
            mediaSupportsSeek: Boolean = false
        ): Boolean {
            val svc = instance
            if (svc == null) {
                _lastOverlayStatus.value = "触发失败：无障碍服务未绑定"
                return false
            }
            svc.postShowBanner(
                appName, packageName, title, text, contentIntent,
                iconBitmap, actions, config, notificationKey, isPersistent,
                isMedia, mediaDurationMs, mediaPositionMs, mediaPlaying, mediaContentType, mediaSupportsSeek
            )
            return true
        }

        fun updateBannerMediaProgress(
            key: String?,
            positionMs: Long,
            durationMs: Long,
            playing: Boolean,
            speed: Float = 1f
        ) {
            if (key.isNullOrBlank()) return
            instance?.postUpdateBannerMediaProgress(key, positionMs, durationMs, playing, speed)
        }

        fun refreshLyricLabel(packageName: String, label: String) {
            instance?.postRefreshLyricLabel(packageName, label)
        }

        fun onTrackSwitchingPlaceholder(
            context: Context,
            key: String?,
            packageName: String,
            appName: String,
            newTitle: String,
            trackToken: String
        ) {
            if (key.isNullOrBlank()) return
            instance?.postTrackSwitchingPlaceholder(context.applicationContext, key, packageName, appName, newTitle, trackToken)
        }

        fun applyTaggedCoverBitmap(key: String?, trackToken: String, bmp: Bitmap?) {
            if (key.isNullOrBlank() || bmp == null || bmp.isRecycled) return
            instance?.postApplyTaggedCoverBitmap(key, trackToken, bmp)
        }

        fun updateLiveLyric(key: String?, lyricLine: String) {
            if (key.isNullOrBlank() || lyricLine.isBlank()) return
            instance?.postUpdateLiveLyric(key, lyricLine)
        }

        fun isBannerVisible(): Boolean = instance?.currentOverlayView != null

        fun dismissBanner() {
            instance?.postDismissBanner()
        }

        fun dismissBannerIfKey(key: String?) {
            if (key.isNullOrBlank()) return
            instance?.postDismissBannerIfKey(key)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentOverlayView: View? = null
    private var currentBannerKey: String? = null
    private var currentDurationMs: Long = 4000L
    private val autoDismissRunnable = Runnable { removeViewImmediate() }
    private var currentBannerProgressBar: ProgressBar? = null
    private var bannerSeekBarResetAnimator: android.animation.Animator? = null
    private var currentBannerTimeText: TextView? = null
    private var currentBannerLyricBtn: TextView? = null
    private var currentBannerPlayPauseBtn: TextView? = null
    private var currentBannerDetailView: TextView? = null
    private var currentBannerTitleView: TextView? = null
    private var currentBannerIconView: ImageView? = null
    private var currentBannerTrackToken: String = ""
    private var currentBannerPackageName: String? = null
    private var currentBannerMediaDurMs: Long = 0L
    private var currentBannerMediaPosMs: Long = 0L
    private var currentBannerMediaAnchorElapsedMs: Long = 0L
    private var currentBannerMediaPlaying: Boolean = false
    private var currentBannerMediaSpeed: Float = 1f

    private val bannerMediaTicker: Runnable = object : Runnable {
        override fun run() {
            if (currentOverlayView == null || !currentBannerMediaPlaying || currentBannerMediaSpeed <= 0f || currentBannerMediaDurMs <= 0L) {
                return
            }
            val elapsed = (SystemClock.elapsedRealtime() - currentBannerMediaAnchorElapsedMs).coerceAtLeast(0L)
            val livePos = (currentBannerMediaPosMs + (elapsed * currentBannerMediaSpeed).toLong()).coerceIn(0L, currentBannerMediaDurMs)
            if (bannerSeekBarResetAnimator?.isRunning != true) {
                val permille = ((livePos * 1000L) / currentBannerMediaDurMs).toInt().coerceIn(0, 1000)
                currentBannerProgressBar?.progress = permille
            }
            currentBannerTimeText?.text = "${formatMediaMs(livePos)} / ${formatMediaMs(currentBannerMediaDurMs)}"
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun formatMediaMs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    private fun postRefreshLyricLabel(packageName: String, label: String) {
        mainHandler.post {
            if (currentBannerPackageName == packageName) {
                currentBannerLyricBtn?.text = label
            }
        }
    }

    private fun postTrackSwitchingPlaceholder(
        ctx: Context,
        key: String,
        packageName: String,
        appName: String,
        newTitle: String,
        trackToken: String
    ) {
        val placeholderBmp = NotificationIconStore.resolveAppIconBitmap(ctx, packageName)
        mainHandler.post {
            if (currentOverlayView == null || currentBannerKey != key) return@post
            currentBannerTrackToken = trackToken
            currentBannerIconView?.tag = trackToken
            if (placeholderBmp != null && !placeholderBmp.isRecycled) {
                currentBannerIconView?.setImageBitmap(placeholderBmp)
            }
            val displayTitle = newTitle.ifBlank { "加载中..." }
            currentBannerTitleView?.text = if (displayTitle == appName) appName else "$appName · $displayTitle"
            currentBannerDetailView?.text = "加载中..."
            currentBannerProgressBar?.progress = 0
            currentBannerTimeText?.text = "00:00 / ${NativeMediaSeekBarObserver.formatElapsedTime(currentBannerMediaDurMs)}"
        }
    }

    private fun postApplyTaggedCoverBitmap(key: String, trackToken: String, bmp: Bitmap) {
        mainHandler.post {
            if (currentOverlayView == null || currentBannerKey != key) return@post
            if (currentBannerTrackToken.isNotBlank() && currentBannerTrackToken != trackToken) return@post
            val iv = currentBannerIconView ?: return@post
            if (iv.tag == null || iv.tag == trackToken) {
                iv.tag = trackToken
                iv.setImageBitmap(bmp)
            }
        }
    }

    private fun postUpdateLiveLyric(key: String, lyricLine: String) {
        val formatted = LrcCapsuleLyricEngine.formatLyricForDisplay(lyricLine, maxCharsPerLine = 30, maxLines = 3)
        if (formatted.isBlank()) return
        mainHandler.post {
            val rootGroup = currentOverlayView as? ViewGroup ?: return@post
            if (currentBannerKey != key) return@post
            val tv = currentBannerDetailView ?: return@post
            if (tv.text?.toString() == formatted) return@post
            val animMs = SystemPropertyProfile.scaledAnimDurationMs(this, 220L)
            if (animMs > 0L) {
                runCatching {
                    TransitionManager.beginDelayedTransition(
                        rootGroup,
                        ChangeBounds().setDuration(animMs).setInterpolator(RenderPowerGovernor.powerSavingLyricInterpolator)
                    )
                }
            }
            tv.maxLines = 3
            tv.text = formatted
            tv.animate().cancel()
            tv.alpha = 0.62f
            tv.animate()
                .alpha(1f)
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun postUpdateBannerMediaProgress(
        key: String,
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
        speed: Float
    ) {
        mainHandler.post {
            if (currentOverlayView == null || currentBannerKey != key) return@post
            currentBannerPlayPauseBtn?.text = if (playing) "暂停" else "播放"
            if (durationMs <= 0L) return@post
            currentBannerMediaDurMs = durationMs
            currentBannerMediaPosMs = positionMs.coerceIn(0L, durationMs)
            currentBannerMediaAnchorElapsedMs = SystemClock.elapsedRealtime()
            currentBannerMediaPlaying = playing
            currentBannerMediaSpeed = if (playing && speed > 0f) speed else 0f
            bannerSeekBarResetAnimator = NativeMediaSeekBarObserver.startSmoothLinearProgress(
                currentBannerProgressBar,
                currentBannerMediaPosMs,
                durationMs,
                playing,
                if (speed > 0f) speed else 1f,
                bannerSeekBarResetAnimator
            )
            currentBannerTimeText?.text = "${formatMediaMs(currentBannerMediaPosMs)} / ${formatMediaMs(durationMs)}"
            mainHandler.removeCallbacks(bannerMediaTicker)
            if (playing) {
                mainHandler.postDelayed(bannerMediaTicker, 1000L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        _lastOverlayStatus.value = "无障碍置顶层已就绪，等待新通知"
        AppLogger.i("Banner", "无障碍顶层横幅服务已连接")
        val cfg = CapsulePrefs(this).load()
        if (cfg.enableOverlayBanner && cfg.suppressSystemHeadsUp) {
            SystemHeadsUpController.setSystemHeadsUpSuppressed(this, true)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不读取、不处理任何屏幕内容事件
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横竖屏切换时及时收起旧方向横幅，避免错位残留
        postDismissBanner()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanupOverlayAndRestore()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanupOverlayAndRestore()
        super.onDestroy()
    }

    private fun cleanupOverlayAndRestore() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        removeViewImmediate()
        SystemHeadsUpController.restoreDefault(this)
        _connected.value = false
        instance = null
    }

    private fun postShowBanner(
        appName: String,
        packageName: String,
        title: String,
        text: String,
        contentIntent: PendingIntent?,
        iconBitmap: Bitmap?,
        actions: List<BannerAction>,
        config: CapsuleConfig,
        notificationKey: String?,
        isPersistent: Boolean,
        isMedia: Boolean,
        mediaDurationMs: Long,
        mediaPositionMs: Long,
        mediaPlaying: Boolean,
        mediaContentType: MediaContentType,
        mediaSupportsSeek: Boolean
    ) {
        mainHandler.post {
            mainHandler.removeCallbacks(autoDismissRunnable)
            removeViewImmediate()

            val wm = getSystemService(WindowManager::class.java)
            if (wm == null) {
                _lastOverlayStatus.value = "触发失败：无法获取 WindowManager"
                return@post
            }

            val bounds = wm.currentWindowMetrics.bounds
            val screenWidth = bounds.width()
            val screenHeight = bounds.height()
            val isLandscape = screenWidth > screenHeight ||
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            val bannerWidth = if (isLandscape) {
                val pct = config.landscapeBannerWidthPercent.coerceIn(32, 75) / 100f
                (screenWidth * pct).toInt().coerceIn(dp(240f), (screenWidth - dp(32f)).coerceAtLeast(dp(240f)))
            } else {
                (screenWidth - dp(20f)).coerceAtLeast(dp(260f))
            }
            val offsetX = if (isLandscape) {
                config.landscapeBannerOffsetXPx.coerceIn(-400, 400)
            } else {
                config.overlayBannerOffsetXPx.coerceIn(-240, 240)
            }
            val topY = if (isLandscape) {
                // 横屏顶部加上安全下移基线，避免圆角横幅顶边被屏幕上边缘裁切
                (dp(8f) + config.landscapeBannerOffsetYPx.coerceIn(0, 240)).coerceAtLeast(dp(6f))
            } else {
                val safeTop = getSafeTopOffsetPx(wm)
                maxOf(safeTop, safeTop + config.overlayBannerOffsetYPx.coerceIn(0, 320))
            }
            currentDurationMs = if (isPersistent) 60_000L else config.overlayBannerDurationSec.coerceIn(2, 15) * 1000L

            val resolvedIcon = iconBitmap ?: NotificationIconStore.resolveAppIconBitmap(this, packageName)
            currentBannerLyricBtn = null
            currentBannerPlayPauseBtn = null

            val view = buildBannerView(
                appName = appName,
                packageName = packageName,
                title = title,
                text = text,
                contentIntent = contentIntent,
                iconBitmap = resolvedIcon,
                actions = actions,
                config = config,
                isLandscape = isLandscape,
                isMedia = isMedia,
                mediaDurationMs = mediaDurationMs,
                mediaPositionMs = mediaPositionMs,
                mediaPlaying = mediaPlaying,
                mediaContentType = mediaContentType,
                mediaSupportsSeek = mediaSupportsSeek
            )
            val lp = WindowManager.LayoutParams(
                bannerWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = offsetX
                y = topY
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                val initLod = if (isMedia) RenderPowerGovernor.RenderLod.BALANCED else RenderPowerGovernor.RenderLod.IDLE_LOW_POWER
                RenderPowerGovernor.applyOverlayParamsLod(this, this@CapsuleOverlayAccessibilityService, initLod)
            }

            runCatching {
                view.alpha = 1f
                view.translationX = 0f
                view.translationY = -dp(20f).toFloat()
                wm.addView(view, lp)
                currentOverlayView = view
                currentBannerKey = notificationKey
                currentBannerPackageName = packageName
                view.animate().withLayer()
                    .translationY(0f)
                    .setDuration(200L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()

                mainHandler.postDelayed(autoDismissRunnable, currentDurationMs)
                if (isMedia && mediaDurationMs > 0L && mediaPlaying) {
                    mainHandler.removeCallbacks(bannerMediaTicker)
                    mainHandler.postDelayed(bannerMediaTicker, 1000L)
                }

                val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val actInfo = if (actions.isNotEmpty()) " · 含 ${actions.size} 个交互按钮" else ""
                _lastOverlayStatus.value = "$now 已弹出：$appName · $title$actInfo"
                AppLogger.i("Banner", "弹出横幅 app=$appName land=$isLandscape w=${bannerWidth}px x=${offsetX}px y=${topY}px actions=${actions.size}")
            }.onFailure { e ->
                _lastOverlayStatus.value = "addView 异常：${e.javaClass.simpleName}: ${e.message}"
                AppLogger.e("Banner", "addView 失败", e)
            }
        }
    }

    private fun postDismissBanner() {
        mainHandler.post {
            mainHandler.removeCallbacks(autoDismissRunnable)
            removeViewImmediate()
        }
    }

    private fun postDismissBannerIfKey(key: String) {
        mainHandler.post {
            if (currentBannerKey == key) {
                mainHandler.removeCallbacks(autoDismissRunnable)
                removeViewImmediate()
            }
        }
    }

    private fun removeViewImmediate() {
        mainHandler.removeCallbacks(bannerMediaTicker)
        currentBannerProgressBar = null
        currentBannerTimeText = null
        currentBannerDetailView = null
        currentBannerLyricBtn = null
        currentBannerPlayPauseBtn = null
        currentBannerTitleView = null
        currentBannerIconView = null
        currentBannerTrackToken = ""
        currentBannerMediaDurMs = 0L
        currentBannerMediaPlaying = false
        val view = currentOverlayView ?: return
        currentOverlayView = null
        currentBannerKey = null
        val wm = getSystemService(WindowManager::class.java)
        runCatching { wm?.removeViewImmediate(view) }
    }

    /**
     * 支持左右滑动滑掉横幅的容器：
     * - 水平滑动超过 scaledTouchSlop 时由容器接管拖动与滑出动画
     * - 普通点按直接透传给子按钮或点击监听，确保通知交互按钮 100% 灵敏
     */
    private inner class SwipeDismissBannerLayout(context: Context) : LinearLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val swipeThresholdPx = maxOf(touchSlop * 2.2f, dp(24f).toFloat())
        private val buttonSwipeThresholdPx = maxOf(touchSlop * 3.5f, dp(42f).toFloat())
        private var downRawX = 0f
        private var downRawY = 0f
        private var isSwiping = false
        private var startedOnActionButton = false

        private fun isPointInsideActionButton(view: View, rawX: Float, rawY: Float): Boolean {
            if (view.tag == "banner_action_btn" && view.visibility == View.VISIBLE) {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                return rawX >= loc[0] && rawX <= loc[0] + view.width &&
                    rawY >= loc[1] && rawY <= loc[1] + view.height
            }
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    if (isPointInsideActionButton(view.getChildAt(i), rawX, rawY)) return true
                }
            }
            return false
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    isSwiping = false
                    startedOnActionButton = isPointInsideActionButton(this, ev.rawX, ev.rawY)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    val threshold = if (startedOnActionButton) buttonSwipeThresholdPx else swipeThresholdPx
                    if (abs(dx) > threshold && abs(dx) > abs(dy) * 1.8f) {
                        isSwiping = true
                        mainHandler.removeCallbacks(autoDismissRunnable)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isSwiping = false
                    startedOnActionButton = false
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun performClick(): Boolean {
            return super.performClick()
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    isSwiping = false
                    alpha = 0.92f
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!isSwiping && abs(dx) > swipeThresholdPx && abs(dx) > abs(dy) * 1.5f) {
                        isSwiping = true
                        mainHandler.removeCallbacks(autoDismissRunnable)
                    }
                    if (isSwiping) {
                        translationX = dx
                        val w = width.coerceAtLeast(1).toFloat()
                        alpha = (1f - abs(dx) / (w * 0.85f)).coerceIn(0.15f, 1f)
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    alpha = 1f
                    if (isSwiping) {
                        val dx = ev.rawX - downRawX
                        val w = width.coerceAtLeast(1).toFloat()
                        if (abs(dx) > w * 0.28f) {
                            val targetX = if (dx > 0f) w * 1.15f else -w * 1.15f
                            animate()
                                .translationX(targetX)
                                .alpha(0f)
                                .setDuration(160L)
                                .setInterpolator(AccelerateInterpolator())
                                .withEndAction { removeViewImmediate() }
                                .start()
                        } else {
                            animate()
                                .translationX(0f)
                                .alpha(1f)
                                .setDuration(180L)
                                .setInterpolator(DecelerateInterpolator())
                                .start()
                            mainHandler.removeCallbacks(autoDismissRunnable)
                            mainHandler.postDelayed(autoDismissRunnable, currentDurationMs)
                        }
                        isSwiping = false
                        return true
                    } else {
                        // 未触发滑动时明确调用 performClick()，确保关闭自带按钮后点击横幅本体 100% 触发跳转
                        performClick()
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    alpha = 1f
                    if (isSwiping) {
                        animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(180L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                        mainHandler.removeCallbacks(autoDismissRunnable)
                        mainHandler.postDelayed(autoDismissRunnable, currentDurationMs)
                        isSwiping = false
                    }
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }
    }

    private fun buildBannerView(
        appName: String,
        packageName: String,
        title: String,
        text: String,
        contentIntent: PendingIntent?,
        iconBitmap: Bitmap?,
        actions: List<BannerAction>,
        config: CapsuleConfig,
        isLandscape: Boolean = false,
        isMedia: Boolean = false,
        mediaDurationMs: Long = 0L,
        mediaPositionMs: Long = 0L,
        mediaPlaying: Boolean = false,
        mediaContentType: MediaContentType = MediaContentType.MUSIC,
        mediaSupportsSeek: Boolean = false
    ): View {
        val bgInt = config.resolveOverlayBannerBgColor()
        val titleColor = CapsuleConfig.parseColorSafe(config.overlayBannerTitleColorHex, Color.parseColor("#FFFFFFFF"))
        val detailColor = CapsuleConfig.parseColorSafe(config.overlayBannerDetailColorHex, Color.parseColor("#FFB8D8CC"))
        val btnBg = CapsuleConfig.parseColorSafe(config.btnBgHex, Color.parseColor("#FF065F46"))
        val accentColor = CapsuleConfig.parseColorSafe(config.accentColorHex, Color.parseColor("#FF34D399"))
        val borderColor = Color.argb(0x40, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))

        val padV = if (isLandscape) {
            config.landscapeBannerPaddingVPx.coerceIn(6, 60)
        } else {
            config.overlayBannerPaddingVPx.coerceIn(8, 120)
        }
        val padH = if (isLandscape) dp(12f) else dp(16f)
        val cornerRadiusPx = if (isLandscape) dp(18f).toFloat() else dp(24f).toFloat()
        val root = SwipeDismissBannerLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerRadiusPx
                setColor(bgInt)
                setStroke(dp(1f), borderColor)
            }
            elevation = dp(10f).toFloat()
            setOnClickListener {
                AppLogger.i("Banner", "点击横幅本体跳转 pkg=$packageName")
                openSourceApp(contentIntent, packageName)
                postDismissBanner()
            }
        }

        if (config.showAccentBar) {
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36f), dp(3f)).apply {
                    bottomMargin = dp(6f)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(2f).toFloat()
                    setColor(accentColor)
                }
            }
            root.addView(bar)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        if (iconBitmap != null && !iconBitmap.isRecycled) {
            val iconSize = if (isLandscape) dp(30f) else dp(40f)
            val cornerR = if (isLandscape) dp(8f).toFloat() else dp(11f).toFloat()
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = dp(12f)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(iconBitmap)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, cornerR)
                    }
                }
            }
            row.addView(iv)
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8f)
            }
        }

        val tvTitle = TextView(this).apply {
            this.text = if (title == appName) appName else "$appName · $title"
            setTextColor(titleColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, config.titleSizeSp)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val tvDetail = TextView(this).apply {
            this.text = if (isMedia) LrcCapsuleLyricEngine.formatLyricForDisplay(text, maxCharsPerLine = 30, maxLines = if (isLandscape) 2 else 3) else text
            setTextColor(detailColor)
            if (isMedia) {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, config.bannerLyricTextSizePx.coerceIn(20f, 72f))
            } else {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isLandscape) (config.detailSizeSp - 0.5f).coerceAtLeast(9f) else config.detailSizeSp)
            }
            maxLines = if (isLandscape) 2 else 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(if (isLandscape) 1f else 3f), 0, 0)
        }
        currentBannerTitleView = tvTitle
        currentBannerDetailView = tvDetail
        textCol.addView(tvTitle)
        textCol.addView(tvDetail)
        row.addView(textCol)

        val btnHeightDp = if (isLandscape) (config.bannerBtnSizeDp - 4).coerceIn(24, 44) else config.bannerBtnSizeDp.coerceIn(26, 56)

        // 仅当开启「渲染应用自带按钮」且原通知没有自带交互按钮时，才显示本应用的「打开 / 关闭」按钮
        // 关闭时点击横幅本体或原通知透传出的真实按钮即可直接跳转
        if (actions.isEmpty() && config.showActions && config.renderOwnNotificationButtons) {
            val btnOpen = makePillButton("打开", accentColor, btnBg, config.btnSizeSp, btnHeightDp) {
                AppLogger.i("Banner", "点击自带按钮[打开] pkg=$packageName")
                openSourceApp(contentIntent, packageName)
                postDismissBanner()
            }
            val btnClose = makePillButton("关闭", Color.parseColor("#FFFF6B6B"), btnBg, config.btnSizeSp, btnHeightDp) {
                AppLogger.i("Banner", "点击自带按钮[关闭] pkg=$packageName")
                postDismissBanner()
            }.apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6f)
            }
            row.addView(btnOpen)
            row.addView(btnClose)
        }

        root.addView(row)

        // 媒体播放通知：套用与胶囊一致的同步进度条与读秒（同帧更新，绝不漂移）
        if (isMedia && mediaDurationMs > 0L) {
            val dur = mediaDurationMs.coerceAtLeast(1L)
            val pos = mediaPositionMs.coerceIn(0L, dur)
            currentBannerMediaDurMs = dur
            currentBannerMediaPosMs = pos
            currentBannerMediaAnchorElapsedMs = SystemClock.elapsedRealtime()
            currentBannerMediaPlaying = mediaPlaying
            currentBannerMediaSpeed = 1f

            val progressRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(if (isLandscape) 6f else 8f)
                }
            }
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(5f), 1f).apply {
                    marginEnd = dp(8f)
                }
                max = 1000
                progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                ContextCompat.getDrawable(this@CapsuleOverlayAccessibilityService, R.drawable.bg_media_progress)?.let {
                    progressDrawable = it
                }
                progressTintList = ColorStateList.valueOf(accentColor)
            }
            val tvTime = TextView(this).apply {
                this.text = "${formatMediaMs(pos)} / ${formatMediaMs(dur)}"
                setTextColor(detailColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                fontFeatureSettings = "tnum"
                maxLines = 1
            }
            currentBannerProgressBar = pb
            currentBannerTimeText = tvTime
            progressRow.addView(pb)
            progressRow.addView(tvTime)
            root.addView(progressRow)
        }

        // 读取并展示交互按钮（媒体按类型分配快退/快进/暂停/上下曲；普通通知透传自带按钮）
        val displayActions: List<BannerAction> = if (isMedia) {
            val playLabel = if (mediaPlaying) "暂停" else "播放"
            val list = mutableListOf<BannerAction>()
            when (mediaContentType) {
                MediaContentType.VIDEO, MediaContentType.RECORDER -> {
                    if (mediaSupportsSeek) list.add(BannerAction("快退", null))
                    list.add(BannerAction(playLabel, null))
                    if (mediaSupportsSeek) list.add(BannerAction("快进", null))
                }
                MediaContentType.MUSIC -> {
                    list.add(BannerAction("上一曲", null))
                    if (mediaSupportsSeek) list.add(BannerAction("快退", null))
                    list.add(BannerAction(playLabel, null))
                    if (mediaSupportsSeek) list.add(BannerAction("快进", null))
                    list.add(BannerAction("下一曲", null))
                    if (CapsuleNotificationListener.shouldShowLyricButton(packageName, actions)) {
                        val lyricLabel = CapsuleNotificationListener.getLyricButtonLabel(packageName)
                        val rawAct = actions.firstOrNull { it.title.contains("词") || it.title.contains("解锁") }
                        list.add(BannerAction(lyricLabel, rawAct?.intent))
                    }
                }
            }
            list
        } else {
            actions
        }
        if (displayActions.isNotEmpty()) {
            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(if (isLandscape) 6f else 10f)
                }
            }
            val effectiveBtnTextSp = if (displayActions.size >= 5) (config.btnSizeSp - 1f).coerceAtLeast(10f) else config.btnSizeSp
            displayActions.take(6).forEachIndexed { idx, act ->
                val pillBg = when {
                    act.isPositive -> Color.parseColor("#FF16A34A")
                    act.isDestructive -> Color.parseColor("#FFDC2626")
                    else -> btnBg
                }
                val pillFg = when {
                    act.isPositive || act.isDestructive -> Color.WHITE
                    else -> accentColor
                }
                val pill = makePillButton(
                    label = act.title,
                    textColor = pillFg,
                    bgColor = pillBg,
                    textSp = effectiveBtnTextSp,
                    heightDp = btnHeightDp
                ) {
                    AppLogger.i("Banner", "点击透传按钮[${act.title}] pkg=$packageName remoteInput=${act.hasRemoteInput}")
                    triggerNotificationAction(act, contentIntent, packageName)
                    if (isMedia && (act.title in setOf("快退", "快进", "暂停", "播放", "播放/暂停", "上一曲", "下一曲", "歌词", "锁词", "关词", "喜欢", "已赞", "取消喜欢") || act.title.contains("词"))) {
                        if (act.title == "暂停" || act.title == "播放" || act.title == "播放/暂停") {
                            currentBannerPlayPauseBtn?.let { ppBtn ->
                                ppBtn.text = if (ppBtn.text?.toString() == "暂停") "播放" else "暂停"
                            }
                        }
                        mainHandler.removeCallbacks(autoDismissRunnable)
                        mainHandler.postDelayed(autoDismissRunnable, currentDurationMs)
                    } else {
                        postDismissBanner()
                    }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx > 0) marginStart = dp(8f)
                    }
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                if (act.title == "暂停" || act.title == "播放" || act.title == "播放/暂停") {
                    currentBannerPlayPauseBtn = pill
                }
                if (act.title == "歌词" || act.title == "锁词" || act.title == "关词" || act.title == "解锁词" || act.title.contains("词")) {
                    currentBannerLyricBtn = pill
                }
                actionRow.addView(pill)
            }
            root.addView(actionRow)
        }

        return root
    }

    private fun sendPendingIntentCompat(pi: PendingIntent?): Boolean {
        if (pi == null) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && pi.isActivity) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                pi.send(this, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
            true
        }.getOrDefault(false)
    }

    private fun dispatchFallbackMediaKey(keyCode: Int): Boolean {
        return runCatching {
            val am = getSystemService(AudioManager::class.java) ?: return false
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        }.getOrDefault(false)
    }

    private fun triggerNotificationAction(
        action: BannerAction,
        fallbackContentIntent: PendingIntent?,
        packageName: String
    ) {
        // 媒体播控按钮优先走活跃 MediaController.TransportControls（与胶囊播控一致），彻底解决横幅播控点击无效
        val mediaCmd = when (action.title) {
            "上一曲" -> CapsuleKeepAliveService.ACTION_MEDIA_PREV to KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "下一曲" -> CapsuleKeepAliveService.ACTION_MEDIA_NEXT to KeyEvent.KEYCODE_MEDIA_NEXT
            "暂停", "播放", "播放/暂停" -> CapsuleKeepAliveService.ACTION_MEDIA_PLAY_PAUSE to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "快退" -> CapsuleKeepAliveService.ACTION_MEDIA_REWIND to KeyEvent.KEYCODE_MEDIA_REWIND
            "快进" -> CapsuleKeepAliveService.ACTION_MEDIA_FAST_FORWARD to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            else -> null
        }
        if (mediaCmd != null) {
            val handled = CapsuleNotificationListener.dispatchPinnedMediaCommand(this, mediaCmd.first)
            if (handled) {
                CapsuleNotificationListener.scheduleImmediateMediaResync()
                return
            }
            if (sendPendingIntentCompat(action.intent)) {
                CapsuleNotificationListener.scheduleImmediateMediaResync()
                return
            }
            dispatchFallbackMediaKey(mediaCmd.second)
            CapsuleNotificationListener.scheduleImmediateMediaResync()
            return
        }
        if (action.title == "歌词" || action.title == "锁词" || action.title == "关词" || action.title == "解锁词" || action.title.contains("词")) {
            CapsuleNotificationListener.triggerLyricCycleAction(this, currentBannerKey, packageName)
            return
        }
        if (action.title == "喜欢" || action.title == "已赞" || action.title == "取消喜欢") {
            if (CapsuleNotificationListener.triggerNativeFavoriteAction(this, currentBannerKey, packageName)) return
        }

        val isNonJumpToggle = action.title.contains("词") ||
            action.title == "标为已读" ||
            action.title == "喜欢" ||
            action.title == "取消喜欢"

        if (isNonJumpToggle) {
            // 歌词/标为已读/喜欢等开关按钮：优先走实时 CustomAction / 最新通知 actionIntent，绝不误跳转打开 App
            val ok = CapsuleNotificationListener.triggerFreshNotificationAction(
                context = this,
                notifKey = currentBannerKey,
                packageName = packageName,
                actionLabel = action.title
            )
            if (!ok && action.intent != null) {
                sendPendingIntentCompat(action.intent)
            }
            return
        }

        if (action.hasRemoteInput || action.intent == null) {
            openSourceApp(fallbackContentIntent, packageName)
            return
        }
        if (!sendPendingIntentCompat(action.intent)) {
            openSourceApp(fallbackContentIntent, packageName)
        }
    }

    private fun makePillButton(
        label: String,
        textColor: Int,
        bgColor: Int,
        textSp: Float,
        heightDp: Int = 34,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        val hDp = heightDp.coerceIn(26, 56)
        val padH = dp((hDp * 0.38f).coerceIn(10f, 20f))
        val padV = dp((hDp * 0.20f).coerceIn(5f, 14f))
        tag = "banner_action_btn"
        text = label
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        setPadding(padH, padV, padH, padV)
        minHeight = dp(hDp.toFloat())
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(hDp * 0.48f).toFloat()
            setColor(bgColor)
        }
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.alpha = 0.72f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
            }
            false
        }
        setOnClickListener { onClick() }
    }

    private fun openSourceApp(contentIntent: PendingIntent?, pkg: String) {
        val sent = sendPendingIntentCompat(contentIntent)
        AppLogger.i("Banner", "执行跳转 pkg=$pkg hasPi=${contentIntent != null} piSent=$sent")
        if (!sent && pkg.isNotBlank()) {
            runCatching {
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    startActivity(launch)
                }
            }
        }
    }

    private fun getSafeTopOffsetPx(wm: WindowManager): Int {
        val insetsTop = runCatching {
            wm.currentWindowMetrics.windowInsets.getInsets(
                WindowInsets.Type.displayCutout() or WindowInsets.Type.statusBars()
            ).top
        }.getOrDefault(0)
        return maxOf(insetsTop, SystemPropertyProfile.notchHeightPxFromProp, dp(38f)) + dp(4f)
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
}
