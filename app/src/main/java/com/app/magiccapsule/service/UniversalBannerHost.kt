package com.app.magiccapsule.service

import android.app.ActivityOptions
import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.app.magiccapsule.R
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.model.CapsuleConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 全品牌通用悬浮横幅 / 迷你悬浮胶囊宿主（TYPE_APPLICATION_OVERLAY）：
 * - 只需授予 SYSTEM_ALERT_WINDOW（系统悬浮窗权限或 ADB appops 授权），无需依赖无障碍服务，
 *   彻底解决小米/HyperOS/OPPO/vivo/三星等非荣耀机型在应用外无法弹出横幅的问题
 * - 复刻荣耀灵动胶囊交互逻辑：媒体播放/录音/设备监控常驻时自动展开卡片，超时后自动收起为顶部迷你悬浮胶囊，
 *   点击迷你胶囊随时重新展开，左右滑动即可关闭
 * - 内置与胶囊一致的同步媒体进度条 + 等宽读秒 + 按媒体类型与能力位自适应播控按钮
 */
object UniversalBannerHost {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _lastStatus = MutableStateFlow("通用悬浮横幅待命中")
    val lastStatus: StateFlow<String> = _lastStatus.asStateFlow()

    private val _lastAddViewError = MutableStateFlow("")
    val lastAddViewError: StateFlow<String> = _lastAddViewError.asStateFlow()

    data class OverlayPermissionDiagnostic(
        val settingsCanDraw: Boolean,
        val appOpsMode: Int,
        val appOpsLabel: String,
        val miuiBgPopupAllowed: Boolean?,
        val liveAddViewOk: Boolean,
        val liveAddViewError: String,
        val romFixHint: String
    ) {
        val isActuallyUsable: Boolean
            get() = liveAddViewOk && (settingsCanDraw || appOpsMode == AppOpsManager.MODE_ALLOWED)
    }

    private data class BannerState(
        val appName: String,
        val packageName: String,
        val title: String,
        val text: String,
        val contentIntent: PendingIntent?,
        val iconBitmap: Bitmap?,
        val actions: List<BannerAction>,
        val config: CapsuleConfig,
        val notificationKey: String?,
        val isPersistent: Boolean,
        val collapsibleToPill: Boolean,
        val isMedia: Boolean,
        val mediaDurationMs: Long,
        val mediaPositionMs: Long,
        val mediaPlaying: Boolean,
        val mediaContentType: MediaContentType,
        val mediaSupportsSeek: Boolean,
        val liveLyricLine: String = ""
    )

    private var hostAppContext: Context? = null
    private var currentView: View? = null
    private var currentKey: String? = null
    private var currentTrackToken: String = ""
    private var currentState: BannerState? = null
    private var isCollapsedPill: Boolean = false
    private var currentDurationMs: Long = 4500L

    private var currentIconView: ImageView? = null
    private var currentTitleView: TextView? = null
    private var currentDetailView: TextView? = null
    private var currentPlayPauseBtn: TextView? = null
    private var currentLyricBtn: TextView? = null
    private var currentProgressBar: ProgressBar? = null
    private var seekBarResetAnimator: android.animation.Animator? = null
    private var currentTimeText: TextView? = null
    private var currentPillIconView: ImageView? = null
    private var currentPillText: TextView? = null
    private var mediaDurMs: Long = 0L
    private var mediaPosMs: Long = 0L
    private var mediaAnchorElapsedMs: Long = 0L
    private var mediaPlaying: Boolean = false
    private var mediaSpeed: Float = 1f

    // 媒体播放期间的「设备状态中断/插播断点服务」：保存被中断的媒体横幅快照，状态展示完毕后从精确断点恢复歌词、读秒与进度条
    private data class InterruptedMediaCheckpoint(
        val state: BannerState,
        val wasCollapsedPill: Boolean,
        val savedAtElapsedMs: Long
    )
    @Volatile
    private var interruptedMediaCheckpoint: InterruptedMediaCheckpoint? = null
    @Volatile
    private var isSysInterruptActive: Boolean = false

    fun isMediaBannerActive(): Boolean {
        val st = currentState
        return (currentView != null && st?.isMedia == true) || interruptedMediaCheckpoint != null
    }

    private fun finishCurrentBannerOrResumeInterrupt() {
        val ctx = hostAppContext
        if (isSysInterruptActive && interruptedMediaCheckpoint != null && ctx != null) {
            resumeInterruptedMediaFromCheckpoint(ctx)
            return
        }
        isSysInterruptActive = false
        val st = currentState
        if (st != null && ctx != null && st.collapsibleToPill && !isCollapsedPill) {
            renderCollapsedPill(ctx, st)
        } else {
            removeViewImmediate()
        }
    }

    private val autoCollapseOrDismissRunnable = Runnable {
        finishCurrentBannerOrResumeInterrupt()
    }

    /**
     * 设备监控状态/变化事件复用通用悬浮横幅时的中断服务入口：
     * - 若当前正在展示媒体横幅或迷你胶囊，自动保存断点并暂停横幅读秒/进度条动画
     * - 弹出设备监控状态横幅并保持 holdDurationMs
     * - 展示到期（或用户提前滑走）后，自动从实时断点无缝恢复媒体横幅并同步最新歌词、读秒与进度条
     */
    fun showInterruptBanner(
        context: Context,
        appName: String,
        title: String,
        text: String,
        iconBitmap: Bitmap?,
        config: CapsuleConfig,
        holdDurationMs: Long = 4500L,
        collapsibleToPill: Boolean = false
    ): Boolean {
        val appCtx = context.applicationContext
        if (!canDrawOverlay(appCtx)) return false
        val clampedHoldMs = holdDurationMs.coerceIn(2000L, 12_000L)
        mainHandler.post {
            hostAppContext = appCtx
            // 若当前正在展示媒体横幅/迷你胶囊且尚未处于中断态，保存断点快照
            val cur = currentState
            if (!isSysInterruptActive && currentView != null && cur != null && cur.isMedia) {
                interruptedMediaCheckpoint = InterruptedMediaCheckpoint(
                    state = cur,
                    wasCollapsedPill = isCollapsedPill,
                    savedAtElapsedMs = SystemClock.elapsedRealtime()
                )
                mainHandler.removeCallbacks(mediaTicker)
                seekBarResetAnimator?.cancel()
                seekBarResetAnimator = null
                AppLogger.i("Banner", "媒体横幅已挂起保存断点，插播设备状态(${clampedHoldMs}ms)：$title")
            }
            val hasCheckpoint = interruptedMediaCheckpoint != null
            isSysInterruptActive = hasCheckpoint
            val interruptState = BannerState(
                appName = appName,
                packageName = appCtx.packageName,
                title = title,
                text = text,
                contentIntent = null,
                iconBitmap = iconBitmap ?: NotificationIconStore.resolveManualIconBitmap(appCtx, config),
                actions = emptyList(),
                config = config,
                notificationKey = "sys_status_snapshot",
                isPersistent = false,
                collapsibleToPill = if (hasCheckpoint) false else collapsibleToPill,
                isMedia = false,
                mediaDurationMs = 0L,
                mediaPositionMs = 0L,
                mediaPlaying = false,
                mediaContentType = MediaContentType.MUSIC,
                mediaSupportsSeek = false,
                liveLyricLine = ""
            )
            currentState = interruptState
            currentKey = "sys_status_snapshot"
            renderExpandedBanner(appCtx, interruptState, overrideDurationMs = clampedHoldMs)
        }
        return true
    }

    /**
     * 设备监控状态展示完毕后，从实时断点恢复被中断的媒体横幅：
     * 1. 合并最新 MediaProgressSyncEngine 毫秒级进度快照（零漂移）
     * 2. 合并最新 LrcCapsuleLyricEngine 实时歌词流与 NotificationIconStore 载荷（兼容插播期间切歌）
     * 3. 恢复原展开/收起形态，并立即重建 60fps 进度条动画、秒级读秒与 LRC 时间轴对齐
     */
    private fun resumeInterruptedMediaFromCheckpoint(ctx: Context) {
        val cp = interruptedMediaCheckpoint ?: run {
            isSysInterruptActive = false
            removeViewImmediate()
            return
        }
        interruptedMediaCheckpoint = null
        isSysInterruptActive = false

        val saved = cp.state
        val latestPayload = saved.notificationKey?.let { NotificationIconStore.getPayload(it) }
            ?: NotificationIconStore.currentActivePayload?.takeIf { it.isMedia && it.packageName == saved.packageName }
        val snap = MediaProgressSyncEngine.currentSnapshot()
        val livePosMs = if (snap.durationMs > 0L) snap.positionMs else (latestPayload?.mediaPositionMs ?: saved.mediaPositionMs)
        val liveDurMs = if (snap.durationMs > 0L) snap.durationMs else (latestPayload?.mediaDurationMs ?: saved.mediaDurationMs)
        val livePlaying = if (snap.durationMs > 0L) snap.isActivelyPlaying else (latestPayload?.mediaPlaying ?: saved.mediaPlaying)
        val liveSpeed = if (snap.playbackSpeed > 0f) snap.playbackSpeed else 1f

        val lrcEvent = LrcCapsuleLyricEngine.liveLyric.value
        val freshLyric = when {
            lrcEvent.line.isNotBlank() && (lrcEvent.notifKey == saved.notificationKey || lrcEvent.notifKey == latestPayload?.key) ->
                LrcCapsuleLyricEngine.formatLyricForDisplay(lrcEvent.line, maxCharsPerLine = 30, maxLines = 3)
            !latestPayload?.liveLyricLine.isNullOrBlank() ->
                LrcCapsuleLyricEngine.formatLyricForDisplay(latestPayload!!.liveLyricLine, maxCharsPerLine = 30, maxLines = 3)
            else -> saved.liveLyricLine
        }

        val resumedState = saved.copy(
            title = latestPayload?.title?.ifBlank { saved.title } ?: saved.title,
            text = freshLyric.ifBlank { latestPayload?.text?.ifBlank { saved.text } ?: saved.text },
            iconBitmap = latestPayload?.iconBitmap ?: saved.iconBitmap,
            mediaDurationMs = liveDurMs,
            mediaPositionMs = livePosMs,
            mediaPlaying = livePlaying,
            liveLyricLine = freshLyric
        )
        currentState = resumedState
        currentKey = resumedState.notificationKey

        if (cp.wasCollapsedPill) {
            renderCollapsedPill(ctx, resumedState)
            syncMediaAnchor(livePosMs, liveDurMs, livePlaying, liveSpeed)
        } else {
            renderExpandedBanner(ctx, resumedState)
            syncMediaAnchor(livePosMs, liveDurMs, livePlaying, liveSpeed)
            if (liveDurMs > 0L) {
                seekBarResetAnimator = NativeMediaSeekBarObserver.startSmoothLinearProgress(
                    currentProgressBar,
                    livePosMs,
                    liveDurMs,
                    livePlaying,
                    liveSpeed,
                    seekBarResetAnimator
                )
                currentTimeText?.text = "${NativeMediaSeekBarObserver.formatElapsedTime(livePosMs)} / ${NativeMediaSeekBarObserver.formatElapsedTime(liveDurMs)}"
            }
        }
        // 触发歌词引擎按最新进度立即重新对齐时间轴
        LrcCapsuleLyricEngine.resyncTimeline()
        AppLogger.i("Banner", "设备状态插播结束，已从断点恢复媒体横幅 title=${resumedState.title} pos=${livePosMs}ms/${liveDurMs}ms playing=$livePlaying pill=${cp.wasCollapsedPill}")
    }

    private val mediaTicker: Runnable = object : Runnable {
        override fun run() {
            if (currentView == null || !mediaPlaying || mediaSpeed <= 0f || mediaDurMs <= 0L) return
            val isScreenOn = runCatching {
                hostAppContext?.getSystemService(PowerManager::class.java)?.isInteractive != false
            }.getOrDefault(true)
            if (!isScreenOn) {
                mainHandler.postDelayed(this, 5000L)
                return
            }
            val elapsed = (SystemClock.elapsedRealtime() - mediaAnchorElapsedMs).coerceAtLeast(0L)
            val livePos = (mediaPosMs + (elapsed * mediaSpeed).toLong()).coerceIn(0L, mediaDurMs)
            // 进度条已由 60fps LinearInterpolator 动画连续推进，此处仅刷新秒级时间文案
            if (seekBarResetAnimator?.isRunning != true) {
                val permille = ((livePos * 1000L) / mediaDurMs).toInt().coerceIn(0, 1000)
                currentProgressBar?.progress = permille
            }
            currentTimeText?.text = "${NativeMediaSeekBarObserver.formatElapsedTime(livePos)} / ${NativeMediaSeekBarObserver.formatElapsedTime(mediaDurMs)}"
            mainHandler.postDelayed(this, 1000L)
        }
    }

    /**
     * 增强版悬浮窗权限检查：
     * 兼容部分 Android 12–15 机型刚在系统设置授权后 Settings.canDrawOverlays() 缓存延迟返回 false 的问题，
     * 同时结合 AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW 实测状态（不依赖 Shizuku）。
     */
    fun canDrawOverlay(context: Context): Boolean {
        val settingsOk = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        if (settingsOk) return true
        val appOpsOk = runCatching {
            val aom = context.getSystemService(AppOpsManager::class.java) ?: return@runCatching false
            val mode = aom.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        return appOpsOk
    }

    /**
     * 实际悬浮窗权限深度探针（无 Shizuku 实测）：
     * 1. 读取 Settings.canDrawOverlays
     * 2. 读取 AppOpsManager OPSTR_SYSTEM_ALERT_WINDOW 真实模式
     * 3. 小米/红米机型额外探测 MIUI/HyperOS「后台弹出界面」(op=10021)
     * 4. 在主线程真实挂载并立即销毁 1x1 透明不可触摸 TYPE_APPLICATION_OVERLAY 探针窗口，
     *    捕获 ROM 静默拦截或 BadTokenException / SecurityException。
     */
    fun probeOverlayPermission(context: Context, runLiveWindowProbe: Boolean = true): OverlayPermissionDiagnostic {
        val appCtx = context.applicationContext
        val settingsOk = runCatching { Settings.canDrawOverlays(appCtx) }.getOrDefault(false)
        val appOpsMode = runCatching {
            val aom = appCtx.getSystemService(AppOpsManager::class.java)
            aom?.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                Process.myUid(),
                appCtx.packageName
            ) ?: -1
        }.getOrDefault(-1)
        val appOpsLabel = when (appOpsMode) {
            AppOpsManager.MODE_ALLOWED -> "MODE_ALLOWED (已允许)"
            AppOpsManager.MODE_IGNORED -> "MODE_IGNORED (已忽略/静默拒绝)"
            AppOpsManager.MODE_ERRORED -> "MODE_ERRORED (已拒绝)"
            AppOpsManager.MODE_DEFAULT -> "MODE_DEFAULT (默认未授权)"
            else -> "UNKNOWN($appOpsMode)"
        }

        // 小米/HyperOS 额外检测「后台弹出界面」权限 (OP_BACKGROUND_START_ACTIVITY = 10021)
        val brandLower = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        val miuiBgPopupAllowed: Boolean? = if (
            brandLower.contains("xiaomi") || brandLower.contains("redmi") || brandLower.contains("poco")
        ) {
            runCatching {
                val aom = appCtx.getSystemService(AppOpsManager::class.java) ?: return@runCatching null
                val m = AppOpsManager::class.java.getMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java
                )
                val res = m.invoke(aom, 10021, Process.myUid(), appCtx.packageName) as? Int
                res == AppOpsManager.MODE_ALLOWED
            }.getOrNull()
        } else null

        var liveOk = false
        var liveErr = ""
        if (runLiveWindowProbe) {
            val probeAction = {
                val wm = appCtx.getSystemService(WindowManager::class.java)
                if (wm == null) {
                    liveOk = false
                    liveErr = "WindowManager 服务不可用"
                } else {
                    val probeView = View(appCtx).apply { alpha = 0f }
                    val lp = WindowManager.LayoutParams(
                        1,
                        1,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = 0
                        y = 0
                    }
                    try {
                        wm.addView(probeView, lp)
                        liveOk = true
                        runCatching { wm.removeViewImmediate(probeView) }
                    } catch (t: Throwable) {
                        liveOk = false
                        liveErr = "${t.javaClass.simpleName}: ${t.message ?: "无详细信息"}"
                        AppLogger.e("Banner", "实际悬浮窗探针 addView 失败(无Shizuku场景)", t)
                        runCatching { if (probeView.isAttachedToWindow) wm.removeViewImmediate(probeView) }
                    }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                probeAction()
            } else {
                val latch = CountDownLatch(1)
                mainHandler.post {
                    try {
                        probeAction()
                    } finally {
                        latch.countDown()
                    }
                }
                if (!latch.await(1500L, TimeUnit.MILLISECONDS)) {
                    liveOk = false
                    liveErr = "主线程悬浮窗探针超时 (1500ms)"
                }
            }
        } else {
            liveOk = settingsOk || appOpsMode == AppOpsManager.MODE_ALLOWED
        }

        val romHint = when {
            brandLower.contains("xiaomi") || brandLower.contains("redmi") || brandLower.contains("poco") ->
                "小米/HyperOS 请在「应用信息 -> 权限管理」同时开启「显示悬浮窗」与「后台弹出界面」，并允许自启动。"
            brandLower.contains("vivo") || brandLower.contains("iqoo") ->
                "vivo/OriginOS 请在「应用信息 -> 权限」同时开启「悬浮窗」与「后台弹出界面」，并在电池中设为允许高耗电。"
            brandLower.contains("oppo") || brandLower.contains("oneplus") || brandLower.contains("realme") ->
                "OPPO/一加/真我 请在「应用管理 -> 权限」开启「悬浮窗」，并在「耗电管理」中允许完全后台行为与自启动。"
            brandLower.contains("huawei") || brandLower.contains("honor") ->
                "华为/荣耀 请在「应用信息 -> 权限」开启「悬浮窗」，并在「应用启动管理」关闭自动管理、勾选允许后台活动。"
            else ->
                "请在系统「应用信息 -> 显示在其他应用上层 (SYSTEM_ALERT_WINDOW)」中开启悬浮窗权限，无需 Shizuku。"
        }

        return OverlayPermissionDiagnostic(
            settingsCanDraw = settingsOk,
            appOpsMode = appOpsMode,
            appOpsLabel = appOpsLabel,
            miuiBgPopupAllowed = miuiBgPopupAllowed,
            liveAddViewOk = liveOk,
            liveAddViewError = liveErr,
            romFixHint = romHint
        )
    }

    fun showBanner(
        context: Context,
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
        collapsibleToPill: Boolean = false,
        isMedia: Boolean = false,
        mediaDurationMs: Long = 0L,
        mediaPositionMs: Long = 0L,
        mediaPlaying: Boolean = false,
        mediaContentType: MediaContentType = MediaContentType.MUSIC,
        mediaSupportsSeek: Boolean = false,
        liveLyricLine: String = ""
    ): Boolean {
        val appCtx = context.applicationContext
        if (!canDrawOverlay(appCtx)) {
            _lastStatus.value = "未授予悬浮窗权限 (SYSTEM_ALERT_WINDOW)"
            AppLogger.w("Banner", "通用悬浮横幅跳过：未授予 SYSTEM_ALERT_WINDOW 权限")
            return false
        }
        val state = BannerState(
            appName = appName,
            packageName = packageName,
            title = title,
            text = text,
            contentIntent = contentIntent,
            iconBitmap = iconBitmap ?: NotificationIconStore.resolveAppIconBitmap(appCtx, packageName),
            actions = actions,
            config = config,
            notificationKey = notificationKey,
            isPersistent = isPersistent,
            collapsibleToPill = collapsibleToPill,
            isMedia = isMedia,
            mediaDurationMs = mediaDurationMs,
            mediaPositionMs = mediaPositionMs,
            mediaPlaying = mediaPlaying,
            mediaContentType = mediaContentType,
            mediaSupportsSeek = mediaSupportsSeek,
            liveLyricLine = liveLyricLine
        )
        mainHandler.post {
            hostAppContext = appCtx
            if (isSysInterruptActive && state.isMedia) {
                // 设备监控状态插播展示期间，静默更新媒体断点快照（兼容插播中切歌/换封面），不打断当前状态展示
                val oldCp = interruptedMediaCheckpoint
                interruptedMediaCheckpoint = InterruptedMediaCheckpoint(
                    state = state,
                    wasCollapsedPill = oldCp?.wasCollapsedPill ?: false,
                    savedAtElapsedMs = SystemClock.elapsedRealtime()
                )
                return@post
            }
            if (!state.isMedia && state.notificationKey != "sys_status_snapshot") {
                isSysInterruptActive = false
            }
            val prev = currentState
            val sameAppOrKey = currentView != null && prev != null && (
                (!notificationKey.isNullOrBlank() && currentKey == notificationKey) ||
                    (prev.isMedia && state.isMedia && prev.packageName == state.packageName)
            )
            currentState = state
            currentKey = notificationKey
            if (sameAppOrKey && isCollapsedPill && collapsibleToPill) {
                // 已收起为迷你胶囊时，仅原地更新迷你胶囊图标、曲名与进度锚点，绝不重新弹出打扰
                syncMediaAnchor(mediaPositionMs, mediaDurationMs, mediaPlaying, 1f)
                if (state.iconBitmap != null && !state.iconBitmap.isRecycled) currentPillIconView?.setImageBitmap(state.iconBitmap)
                setTextSmooth(currentPillText, buildPillTitle(state))
                return@post
            }
            val canUpdateInPlace = sameAppOrKey && !isCollapsedPill && prev != null &&
                prev.isMedia == state.isMedia &&
                prev.mediaContentType == state.mediaContentType &&
                prev.mediaSupportsSeek == state.mediaSupportsSeek &&
                (prev.mediaDurationMs > 0L) == (state.mediaDurationMs > 0L)
            if (canUpdateInPlace) {
                // 同一媒体/通知已处于展开态：原地更新零窗口重建；导航高频刷新直接静音赋值杜绝闪回
                if (state.iconBitmap != null && !state.iconBitmap.isRecycled) {
                    currentIconView?.setImageBitmap(state.iconBitmap)
                }
                val newTitle = if (state.title == state.appName) state.appName else "${state.appName} · ${state.title}"
                if (state.notificationKey == "magic_capsule_web_nav") {
                    if (currentTitleView?.text?.toString() != newTitle) currentTitleView?.text = newTitle
                    if (currentDetailView?.text?.toString() != state.text) currentDetailView?.text = state.text
                } else {
                    setTextSmooth(currentTitleView, newTitle)
                    setTextSmooth(currentDetailView, state.text)
                }
                currentPlayPauseBtn?.text = if (state.mediaPlaying) "暂停" else "播放"
                if (state.isMedia && state.mediaDurationMs > 0L) {
                    syncMediaAnchor(state.mediaPositionMs, state.mediaDurationMs, state.mediaPlaying, 1f)
                    val permille = ((mediaPosMs * 1000L) / mediaDurMs).toInt().coerceIn(0, 1000)
                    currentProgressBar?.progress = permille
                    currentTimeText?.text = "${formatMediaMs(mediaPosMs)} / ${formatMediaMs(mediaDurMs)}"
                }
                return@post
            }
            renderExpandedBanner(appCtx, state)
        }
        return true
    }

    fun updateMediaProgress(
        key: String?,
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
        speed: Float = 1f
    ) {
        if (key.isNullOrBlank()) return
        mainHandler.post {
            if (isSysInterruptActive) {
                val cp = interruptedMediaCheckpoint
                if (cp != null && cp.state.notificationKey == key && durationMs > 0L) {
                    interruptedMediaCheckpoint = cp.copy(
                        state = cp.state.copy(
                            mediaPositionMs = positionMs,
                            mediaDurationMs = durationMs,
                            mediaPlaying = playing
                        )
                    )
                }
                return@post
            }
            if (currentView == null || currentKey != key) return@post
            currentPlayPauseBtn?.text = if (playing) "暂停" else "播放"
            currentState = currentState?.copy(
                mediaPlaying = playing,
                mediaPositionMs = positionMs,
                mediaDurationMs = if (durationMs > 0L) durationMs else (currentState?.mediaDurationMs ?: 0L)
            )
            if (durationMs <= 0L) return@post
            syncMediaAnchor(positionMs, durationMs, playing, speed)
            val lowPower = hostAppContext?.let { RenderPowerGovernor.isSystemPowerSaveMode(it) } ?: false
            seekBarResetAnimator = NativeMediaSeekBarObserver.startSmoothLinearProgress(
                currentProgressBar,
                mediaPosMs,
                durationMs,
                playing,
                if (speed > 0f) speed else 1f,
                seekBarResetAnimator,
                lowPowerStepMode = lowPower
            )
            currentTimeText?.text = "${NativeMediaSeekBarObserver.formatElapsedTime(mediaPosMs)} / ${NativeMediaSeekBarObserver.formatElapsedTime(durationMs)}"
        }
    }

    fun refreshLyricLabel(packageName: String, label: String) {
        mainHandler.post {
            if (currentState?.packageName == packageName) {
                currentLyricBtn?.text = label
            }
        }
    }

    /**
     * 复用胶囊歌词引擎（LRC 5源 / 蓝牙 / 状态栏）的实时歌词行，原地平滑刷新悬浮横幅副标题或迷你胶囊文案（零重建）。
     */
    /**
     * 切歌瞬间立即清空上一首旧封面与旧歌名/歌词，置为默认占位图与「加载中...」，并打上当前曲目 Tag 防异步错图。
     */
    fun onTrackSwitchingPlaceholder(
        context: Context,
        key: String?,
        packageName: String,
        appName: String,
        newTitle: String,
        trackToken: String
    ) {
        if (key.isNullOrBlank()) return
        val appCtx = context.applicationContext
        val placeholderBmp = NotificationIconStore.resolveAppIconBitmap(appCtx, packageName)
        mainHandler.post {
            if (isSysInterruptActive) {
                val cp = interruptedMediaCheckpoint
                if (cp != null && (cp.state.notificationKey == key || cp.state.packageName == packageName)) {
                    interruptedMediaCheckpoint = cp.copy(
                        state = cp.state.copy(
                            title = newTitle.ifBlank { "加载中..." },
                            text = "加载中...",
                            iconBitmap = placeholderBmp,
                            liveLyricLine = "",
                            mediaPositionMs = 0L
                        )
                    )
                }
                return@post
            }
            val st = currentState ?: return@post
            if (currentView == null || !st.isMedia) return@post
            if (currentKey != key && st.packageName != packageName) return@post
            currentTrackToken = trackToken
            val displayTitle = newTitle.ifBlank { "加载中..." }
            val updated = st.copy(
                title = displayTitle,
                text = "加载中...",
                iconBitmap = placeholderBmp,
                liveLyricLine = "",
                mediaPositionMs = 0L
            )
            currentState = updated
            currentIconView?.tag = trackToken
            currentPillIconView?.tag = trackToken
            if (placeholderBmp != null && !placeholderBmp.isRecycled) {
                currentIconView?.setImageBitmap(placeholderBmp)
                currentPillIconView?.setImageBitmap(placeholderBmp)
            }
            if (isCollapsedPill) {
                setTextSmooth(currentPillText, buildPillTitle(updated))
            } else {
                val fullTitle = if (displayTitle == appName) appName else "$appName · $displayTitle"
                setTextSmooth(currentTitleView, fullTitle)
                setTextSmooth(currentDetailView, "加载中...")
                currentProgressBar?.progress = 0
                currentTimeText?.text = "00:00 / ${formatMediaMs(st.mediaDurationMs)}"
            }
        }
    }

    /**
     * 异步封面加载完成后带 View Tag 校验写入：若加载期间用户已再次切歌（Tag 不匹配），直接废弃旧图绝不错乱。
     */
    fun applyTaggedCoverBitmap(key: String?, trackToken: String, bmp: Bitmap?) {
        if (key.isNullOrBlank() || bmp == null || bmp.isRecycled) return
        mainHandler.post {
            if (currentView == null || currentKey != key) return@post
            if (currentTrackToken.isNotBlank() && currentTrackToken != trackToken) return@post
            val iv = currentIconView
            if (iv != null && (iv.tag == null || iv.tag == trackToken)) {
                iv.tag = trackToken
                iv.setImageBitmap(bmp)
            }
            val piv = currentPillIconView
            if (piv != null && (piv.tag == null || piv.tag == trackToken)) {
                piv.tag = trackToken
                piv.setImageBitmap(bmp)
            }
            currentState = currentState?.copy(iconBitmap = bmp)
        }
    }

    fun updateLiveLyric(key: String?, lyricLine: String) {
        if (key.isNullOrBlank() || lyricLine.isBlank()) return
        val formatted = LrcCapsuleLyricEngine.formatLyricForDisplay(lyricLine, maxCharsPerLine = 30, maxLines = 3)
        if (formatted.isBlank()) return
        mainHandler.post {
            if (isSysInterruptActive) {
                val cp = interruptedMediaCheckpoint
                if (cp != null && cp.state.notificationKey == key) {
                    interruptedMediaCheckpoint = cp.copy(
                        state = cp.state.copy(text = formatted, liveLyricLine = formatted)
                    )
                }
                return@post
            }
            val st = currentState ?: return@post
            val rootGroup = currentView as? ViewGroup ?: return@post
            if (!st.isMedia || st.mediaContentType != MediaContentType.MUSIC) return@post
            if (currentKey != key) return@post
            if (st.liveLyricLine == formatted) return@post
            val updated = st.copy(text = formatted, liveLyricLine = formatted)
            currentState = updated
            val animMs = hostAppContext?.let { SystemPropertyProfile.scaledAnimDurationMs(it, 220L) } ?: 220L
            if (animMs > 0L) {
                runCatching {
                    TransitionManager.beginDelayedTransition(
                        rootGroup,
                        ChangeBounds().setDuration(animMs).setInterpolator(
                        if (isCollapsedPill) RenderPowerGovernor.powerSavingLyricInterpolator else smoothTextInterpolator
                    )
                    )
                }
            }
            if (isCollapsedPill) {
                setTextSmooth(currentPillText, buildPillTitle(updated))
                // 通知 WindowManager 立即重新测量收起态胶囊宽度，确保横/竖屏空间足够时充分伸展
                val wm = hostAppContext?.getSystemService(WindowManager::class.java)
                val lpNow = rootGroup.layoutParams
                if (wm != null && lpNow != null) {
                    runCatching { wm.updateViewLayout(rootGroup, lpNow) }
                }
            } else {
                setTextSmooth(currentDetailView, formatted)
            }
        }
    }

    /**
     * 导航指南针/GPS 高频刷新专用：仅当当前悬浮窗正显示该导航通知（展开态或迷你胶囊态）时原地更新，
     * 绝不重新弹窗、绝不重建窗口、绝不抢占其他通知，彻底消除通用模式导航横幅闪回与重复弹出重绘。
     */
    fun updateNavBannerInPlace(
        key: String,
        title: String,
        text: String,
        iconBitmap: Bitmap?,
        pillText: String = ""
    ) {
        mainHandler.post {
            val st = currentState ?: return@post
            if (currentView == null || currentKey != key) return@post
            val updated = st.copy(
                title = title,
                text = text,
                iconBitmap = if (iconBitmap != null && !iconBitmap.isRecycled) iconBitmap else st.iconBitmap,
                liveLyricLine = pillText.ifBlank { st.liveLyricLine }
            )
            currentState = updated
            if (isCollapsedPill) {
                if (iconBitmap != null && !iconBitmap.isRecycled) {
                    currentPillIconView?.setImageBitmap(iconBitmap)
                }
                val pillDisplay = pillText.ifBlank { buildPillTitle(updated) }
                if (currentPillText?.text?.toString() != pillDisplay) {
                    currentPillText?.text = pillDisplay
                }
            } else {
                if (iconBitmap != null && !iconBitmap.isRecycled) {
                    currentIconView?.setImageBitmap(iconBitmap)
                }
                val fullTitle = if (title == st.appName) st.appName else "${st.appName} · $title"
                if (currentTitleView?.text?.toString() != fullTitle) {
                    currentTitleView?.text = fullTitle
                }
                if (currentDetailView?.text?.toString() != text) {
                    currentDetailView?.text = text
                }
            }
        }
    }

    fun dismiss() {
        mainHandler.post { removeViewImmediate() }
    }

    fun dismissIfKey(key: String?) {
        if (key.isNullOrBlank()) return
        mainHandler.post {
            if (interruptedMediaCheckpoint?.state?.notificationKey == key) {
                interruptedMediaCheckpoint = null
            }
            if (currentKey == key) {
                removeViewImmediate()
            }
        }
    }

    private val smoothTextInterpolator = DecelerateInterpolator()

    private fun setTextSmooth(tv: TextView?, newText: CharSequence) {
        if (tv == null) return
        val next = newText.toString()
        if (tv.text?.toString() == next) return
        tv.text = next
        tv.animate().cancel()
        val ctx = hostAppContext
        val animMs = ctx?.let { SystemPropertyProfile.scaledAnimDurationMs(it, 180L) } ?: 180L
        if (animMs <= 0L) {
            tv.alpha = 1f
            return
        }
        // 收起态或静止省电时采用量化步进节能插值器，降低 VSync CPU 唤醒；仅展开媒体态走全帧平滑曲线
        val interp = if (isCollapsedPill || currentState?.isMedia != true) {
            RenderPowerGovernor.powerSavingLyricInterpolator
        } else {
            RenderPowerGovernor.fullRateSmoothInterpolator
        }
        tv.alpha = 0.68f
        tv.animate()
            .alpha(1f)
            .setDuration(animMs)
            .setInterpolator(interp)
            .start()
    }

    private fun syncMediaAnchor(positionMs: Long, durationMs: Long, playing: Boolean, speed: Float) {
        mediaDurMs = durationMs.coerceAtLeast(0L)
        mediaPosMs = if (mediaDurMs > 0L) positionMs.coerceIn(0L, mediaDurMs) else positionMs.coerceAtLeast(0L)
        mediaAnchorElapsedMs = SystemClock.elapsedRealtime()
        mediaPlaying = playing
        mediaSpeed = if (playing && speed > 0f) speed else 0f
        mainHandler.removeCallbacks(mediaTicker)
        if (playing && mediaSpeed > 0f && mediaDurMs > 0L && !isCollapsedPill) {
            mainHandler.postDelayed(mediaTicker, 1000L)
        }
    }

    private fun renderExpandedBanner(ctx: Context, st: BannerState, overrideDurationMs: Long? = null) {
        mainHandler.removeCallbacks(autoCollapseOrDismissRunnable)
        removeViewOnly()
        isCollapsedPill = false

        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val bounds = wm.currentWindowMetrics.bounds
        val screenW = bounds.width()
        val screenH = bounds.height()
        val isLandscape = screenW > screenH || ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val bannerWidth = if (isLandscape) {
            val pct = st.config.landscapeBannerWidthPercent.coerceIn(32, 75) / 100f
            (screenW * pct).toInt().coerceIn(dp(ctx, 240f), (screenW - dp(ctx, 32f)).coerceAtLeast(dp(ctx, 240f)))
        } else {
            (screenW - dp(ctx, 20f)).coerceAtLeast(dp(ctx, 260f))
        }
        val offsetX = if (isLandscape) st.config.landscapeBannerOffsetXPx.coerceIn(-400, 400)
        else st.config.overlayBannerOffsetXPx.coerceIn(-240, 240)
        val safeTopPx = getSafeTopOffsetPx(ctx, wm)
        val topY = if (isLandscape) {
            maxOf(safeTopPx, dp(ctx, 8f) + st.config.landscapeBannerOffsetYPx.coerceIn(0, 240))
        } else {
            maxOf(safeTopPx, safeTopPx + st.config.overlayBannerOffsetYPx.coerceIn(0, 320))
        }
        currentDurationMs = overrideDurationMs ?: if (st.isPersistent) 60_000L else st.config.overlayBannerDurationSec.coerceIn(2, 15) * 1000L

        val view = buildExpandedView(ctx, st, isLandscape)
        val lp = WindowManager.LayoutParams(
            bannerWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = offsetX
            y = topY
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            val initLod = if (st.isMedia) RenderPowerGovernor.RenderLod.HIGH_FPS else RenderPowerGovernor.RenderLod.IDLE_LOW_POWER
            RenderPowerGovernor.applyOverlayParamsLod(this, ctx, initLod)
        }

        runCatching {
            view.alpha = 1f
            view.translationX = 0f
            view.translationY = -dp(ctx, 18f).toFloat()
            wm.addView(view, lp)
            _lastAddViewError.value = ""
            currentView = view
            val enterMs = SystemPropertyProfile.scaledAnimDurationMs(ctx, 190L)
            if (enterMs > 0L) {
                view.animate().withLayer().translationY(0f).setDuration(enterMs).setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        // 入场动画结束后自动降档：媒体播放态回落至 BALANCED (60Hz)，非媒体静态横幅回落至 IDLE_LOW_POWER
                        val steadyLod = if (st.isMedia && st.mediaPlaying) {
                            RenderPowerGovernor.RenderLod.BALANCED
                        } else {
                            RenderPowerGovernor.RenderLod.IDLE_LOW_POWER
                        }
                        // 严格校验仍是同一个已挂载视图，防止 190ms 内已收起/销毁导致误改新视图 LOD
                        if (currentView === view && view.isAttachedToWindow) {
                            val lpNow = view.layoutParams as? WindowManager.LayoutParams
                            if (lpNow != null) {
                                RenderPowerGovernor.applyOverlayParamsLod(lpNow, ctx, steadyLod)
                                runCatching { wm.updateViewLayout(view, lpNow) }
                            }
                        }
                    }.start()
            } else {
                view.translationY = 0f
            }
            mainHandler.postDelayed(autoCollapseOrDismissRunnable, currentDurationMs)
            if (st.isMedia && st.mediaDurationMs > 0L) {
                syncMediaAnchor(st.mediaPositionMs, st.mediaDurationMs, st.mediaPlaying, 1f)
            }
            val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            _lastStatus.value = "$now 通用悬浮横幅已弹出：${st.appName} · ${st.title}"
            AppLogger.i("Banner", "通用悬浮横幅弹出(TYPE_APPLICATION_OVERLAY) app=${st.appName} isMedia=${st.isMedia} type=${st.mediaContentType} acts=${st.actions.size} pill=${st.collapsibleToPill}")
        }.onFailure { e ->
            val errMsg = "${e.javaClass.simpleName}: ${e.message ?: "addView 被系统拦截"}"
            _lastAddViewError.value = errMsg
            _lastStatus.value = "通用悬浮横幅异常：$errMsg"
            AppLogger.e("Banner", "通用悬浮横幅 addView 失败(尝试无障碍兜底)", e)
            // 若系统在无 Shizuku 下拦截 TYPE_APPLICATION_OVERLAY，自动无缝回退无障碍顶层横幅
            if (CapsuleOverlayAccessibilityService.connected.value) {
                CapsuleOverlayAccessibilityService.showBanner(
                    appName = st.appName,
                    packageName = st.packageName,
                    title = st.title,
                    text = st.text,
                    contentIntent = st.contentIntent,
                    iconBitmap = st.iconBitmap,
                    actions = st.actions,
                    config = st.config,
                    notificationKey = st.notificationKey,
                    isPersistent = st.isPersistent,
                    isMedia = st.isMedia,
                    mediaDurationMs = st.mediaDurationMs,
                    mediaPositionMs = st.mediaPositionMs,
                    mediaPlaying = st.mediaPlaying,
                    mediaContentType = st.mediaContentType,
                    mediaSupportsSeek = st.mediaSupportsSeek
                )
            }
        }
    }

    private fun renderCollapsedPill(ctx: Context, st: BannerState) {
        mainHandler.removeCallbacks(autoCollapseOrDismissRunnable)
        mainHandler.removeCallbacks(mediaTicker)
        removeViewOnly()
        isCollapsedPill = true
        currentIconView = null
        currentTitleView = null
        currentDetailView = null
        currentPlayPauseBtn = null
        currentLyricBtn = null
        seekBarResetAnimator?.cancel()
        seekBarResetAnimator = null
        currentProgressBar = null
        currentTimeText = null

        val wm = ctx.getSystemService(WindowManager::class.java) ?: return

        val bgInt = if (st.notificationKey == "sys_status_snapshot") {
            st.config.resolveSysCapsuleBgColor()
        } else {
            st.config.resolveOverlayBannerBgColor()
        }
        val titleColor = if (st.notificationKey == "sys_status_snapshot") {
            CapsuleConfig.parseColorSafe(st.config.sysCapsuleTitleColorHex, Color.WHITE)
        } else {
            CapsuleConfig.parseColorSafe(st.config.overlayBannerTitleColorHex, Color.WHITE)
        }
        val accentColor = CapsuleConfig.parseColorSafe(st.config.accentColorHex, Color.parseColor("#FF34D399"))

        val pill = SwipeableOverlayLayout(ctx, isPill = true).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 10f), dp(ctx, 6f), dp(ctx, 12f), dp(ctx, 6f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, 20f).toFloat()
                setColor(bgInt)
                setStroke(dp(ctx, 1f), Color.argb(95, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
            }
            elevation = dp(ctx, 8f).toFloat()
            setOnClickListener {
                currentState?.let { renderExpandedBanner(ctx, it) }
            }
        }

        if (st.iconBitmap != null && !st.iconBitmap.isRecycled) {
            val iv = ImageView(ctx).apply {
                val sz = dp(ctx, 20f)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(ctx, 6f) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(st.iconBitmap)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setOval(0, 0, v.width, v.height)
                    }
                }
            }
            currentPillIconView = iv
            pill.addView(iv)
        }

        val screenW = wm.currentWindowMetrics.bounds.width()
        // 废除固定 162dp/42% 窄宽限制：竖屏与横屏均按实际可用屏幕宽度自适应伸展，空间足够时尽量显示完整歌词
        val adaptivePillTextMaxW = (screenW - dp(ctx, 48f)).coerceAtLeast(dp(ctx, 200f))
        // 统一由 TransitionManager.beginDelayedTransition 驱动弹性缩放，移除重复的 LayoutTransition 杜绝双动画器竞态
        val tv = TextView(ctx).apply {
            text = buildPillTitle(st)
            setTextColor(titleColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, st.config.capsuleLyricTextSizePx.coerceIn(16f, 56f))
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            maxWidth = adaptivePillTextMaxW
            ellipsize = TextUtils.TruncateAt.END
        }
        currentPillText = tv
        pill.addView(tv)

        val lp = buildPillLayoutParams(ctx, wm, st.config)

        runCatching {
            wm.addView(pill, lp)
            currentView = pill
            AppLogger.d("Banner", "通用悬浮胶囊已收起为迷你态 app=${st.appName}")
        }.onFailure { e ->
            AppLogger.w("Banner", "迷你悬浮胶囊创建失败：${e.message}")
        }
    }

    private fun buildPillLayoutParams(
        ctx: Context,
        wm: WindowManager,
        cfg: CapsuleConfig
    ): WindowManager.LayoutParams {
        val offX = cfg.universalPillOffsetXPx.coerceIn(-360, 360)
        val offY = cfg.universalPillOffsetYPx.coerceIn(0, 240)
        val (grav, baseX, baseY) = when (cfg.universalPillAnchor) {
            "top_left" -> Triple(Gravity.TOP or Gravity.START, dp(ctx, 12f) + offX, dp(ctx, 6f) + offY)
            "top_right" -> Triple(Gravity.TOP or Gravity.END, dp(ctx, 12f) - offX, dp(ctx, 6f) + offY)
            "below_notch" -> Triple(Gravity.TOP or Gravity.CENTER_HORIZONTAL, offX, getSafeTopOffsetPx(ctx, wm) + offY)
            else -> Triple(Gravity.TOP or Gravity.CENTER_HORIZONTAL, offX, dp(ctx, 6f) + offY)
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = grav
            x = baseX
            y = baseY.coerceAtLeast(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // 收起态迷你胶囊默认锁定低功耗 LOD 刷新率，杜绝后台常驻拉高屏显帧率
            RenderPowerGovernor.applyOverlayParamsLod(this, ctx, RenderPowerGovernor.RenderLod.IDLE_LOW_POWER)
        }
    }

    fun showPositionPreviewPill(context: Context, config: CapsuleConfig): Boolean {
        val appCtx = context.applicationContext
        if (!canDrawOverlay(appCtx)) return false
        val previewState = BannerState(
            appName = "灵动胶囊",
            packageName = appCtx.packageName,
            title = "位置预览 · 拖动滑块对齐",
            text = "点击展开预览，左右滑动关闭",
            contentIntent = null,
            iconBitmap = NotificationIconStore.resolveManualIconBitmap(appCtx, config),
            actions = emptyList(),
            config = config,
            notificationKey = "__pill_position_preview__",
            isPersistent = false,
            collapsibleToPill = true,
            isMedia = false,
            mediaDurationMs = 0L,
            mediaPositionMs = 0L,
            mediaPlaying = false,
            mediaContentType = MediaContentType.MUSIC,
            mediaSupportsSeek = false
        )
        mainHandler.post {
            hostAppContext = appCtx
            currentState = previewState
            currentKey = previewState.notificationKey
            renderCollapsedPill(appCtx, previewState)
        }
        return true
    }

    fun updatePillPositionLive(context: Context, config: CapsuleConfig) {
        val appCtx = context.applicationContext
        mainHandler.post {
            val view = currentView ?: return@post
            val wm = appCtx.getSystemService(WindowManager::class.java) ?: return@post
            currentState = currentState?.copy(config = config)
            if (isCollapsedPill) {
                val lp = buildPillLayoutParams(appCtx, wm, config)
                runCatching { wm.updateViewLayout(view, lp) }
            }
        }
    }

    private fun buildPillTitle(st: BannerState): String {
        val raw = when {
            st.isMedia && st.mediaContentType == MediaContentType.MUSIC && st.liveLyricLine.isNotBlank() -> st.liveLyricLine.trim()
            st.title.isNotBlank() && st.title != st.appName -> st.title
            else -> st.appName
        }
        // 废除 16 字符硬截断，保留完整单行文本交由屏幕可用空间自然自适应展示
        return raw.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun buildExpandedView(ctx: Context, st: BannerState, isLandscape: Boolean): View {
        val isSys = st.notificationKey == "sys_status_snapshot"
        val bgInt = if (isSys) st.config.resolveSysCapsuleBgColor() else st.config.resolveOverlayBannerBgColor()
        val titleColor = if (isSys) {
            CapsuleConfig.parseColorSafe(st.config.sysCapsuleTitleColorHex, Color.WHITE)
        } else {
            CapsuleConfig.parseColorSafe(st.config.overlayBannerTitleColorHex, Color.WHITE)
        }
        val detailColor = if (isSys) {
            CapsuleConfig.parseColorSafe(st.config.sysCapsuleDetailColorHex, Color.parseColor("#FF38BDF8"))
        } else {
            CapsuleConfig.parseColorSafe(st.config.overlayBannerDetailColorHex, Color.parseColor("#FFB8D8CC"))
        }
        val btnBg = CapsuleConfig.parseColorSafe(st.config.btnBgHex, Color.parseColor("#FF065F46"))
        val accentColor = CapsuleConfig.parseColorSafe(st.config.accentColorHex, Color.parseColor("#FF34D399"))
        val borderColor = Color.argb(80, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))

        val padV = if (isLandscape) st.config.landscapeBannerPaddingVPx.coerceIn(6, 60)
        else st.config.overlayBannerPaddingVPx.coerceIn(8, 120)
        val padH = if (isLandscape) dp(ctx, 12f) else dp(ctx, 16f)

        val root = SwipeableOverlayLayout(ctx, isPill = false).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, if (isLandscape) 18f else 24f).toFloat()
                setColor(bgInt)
                setStroke(dp(ctx, 1f), borderColor)
            }
            elevation = dp(ctx, 10f).toFloat()
            setOnClickListener {
                openSourceApp(ctx, st.contentIntent, st.packageName)
                if (st.collapsibleToPill) renderCollapsedPill(ctx, st) else removeViewImmediate()
            }
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        if (st.iconBitmap != null && !st.iconBitmap.isRecycled) {
            val iconSz = dp(ctx, if (isLandscape) 30f else 40f)
            val cornerR = dp(ctx, if (isLandscape) 8f else 11f).toFloat()
            val iv = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(iconSz, iconSz).apply { marginEnd = dp(ctx, 12f) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(st.iconBitmap)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, cornerR)
                    }
                }
            }
            currentIconView = iv
            row.addView(iv)
        }

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(ctx, 8f)
            }
        }
        val tvTitle = TextView(ctx).apply {
            text = if (st.title == st.appName) st.appName else "${st.appName} · ${st.title}"
            setTextColor(titleColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, st.config.titleSizeSp)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val formattedDetail = if (st.isMedia) {
            LrcCapsuleLyricEngine.formatLyricForDisplay(st.text, maxCharsPerLine = 30, maxLines = if (isLandscape) 2 else 3)
        } else {
            st.text
        }
        val tvDetail = TextView(ctx).apply {
            text = formattedDetail
            setTextColor(detailColor)
            if (st.isMedia) {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, st.config.bannerLyricTextSizePx.coerceIn(20f, 72f))
            } else {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isLandscape) (st.config.detailSizeSp - 0.5f).coerceAtLeast(9f) else st.config.detailSizeSp)
            }
            maxLines = if (isLandscape) 2 else 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(ctx, 2f).toFloat(), 1.0f)
            setPadding(0, dp(ctx, if (isLandscape) 1f else 3f), 0, 0)
        }
        currentTitleView = tvTitle
        currentDetailView = tvDetail
        textCol.addView(tvTitle)
        textCol.addView(tvDetail)
        row.addView(textCol)

        // 常驻悬浮胶囊卡片右上角提供「收起」快捷按钮
        if (st.collapsibleToPill) {
            val btnMin = makePillButton(ctx, "收起", Color.parseColor("#E6E6E6"), Color.argb(42, 255, 255, 255), 11.5f, 30, padHDp = 12f) {
                renderCollapsedPill(ctx, st)
            }
            row.addView(btnMin)
        }
        root.addView(row)

        if (st.isMedia && st.mediaDurationMs > 0L) {
            val dur = st.mediaDurationMs.coerceAtLeast(1L)
            val pos = st.mediaPositionMs.coerceIn(0L, dur)
            val progressRow = LinearLayout(ctx).apply {
                isClickable = true
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(ctx, if (isLandscape) 6f else 8f) }
            }
            val pb = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 5f), 1f).apply { marginEnd = dp(ctx, 8f) }
                max = 1000
                progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                ContextCompat.getDrawable(ctx, R.drawable.bg_media_progress)?.let { progressDrawable = it }
                progressTintList = ColorStateList.valueOf(accentColor)
            }
            val tvTime = TextView(ctx).apply {
                text = "${formatMediaMs(pos)} / ${formatMediaMs(dur)}"
                setTextColor(detailColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                fontFeatureSettings = "tnum"
                maxLines = 1
            }
            currentProgressBar = pb
            currentTimeText = tvTime
            progressRow.addView(pb)
            progressRow.addView(tvTime)
            root.addView(progressRow)
        }

        val displayActions: List<BannerAction> = if (st.isMedia) {
            val playLabel = if (st.mediaPlaying) "暂停" else "播放"
            val list = mutableListOf<BannerAction>()
            when (st.mediaContentType) {
                MediaContentType.VIDEO, MediaContentType.RECORDER -> {
                    if (st.mediaSupportsSeek) list.add(BannerAction("快退", null))
                    list.add(BannerAction(playLabel, null))
                    if (st.mediaSupportsSeek) list.add(BannerAction("快进", null))
                }
                MediaContentType.MUSIC -> {
                    list.add(BannerAction("上一曲", null))
                    if (st.mediaSupportsSeek) list.add(BannerAction("快退", null))
                    list.add(BannerAction(playLabel, null))
                    if (st.mediaSupportsSeek) list.add(BannerAction("快进", null))
                    list.add(BannerAction("下一曲", null))
                    if (CapsuleNotificationListener.shouldShowLyricButton(st.packageName, st.actions)) {
                        val lyricLabel = CapsuleNotificationListener.getLyricButtonLabel(st.packageName)
                        val rawAct = st.actions.firstOrNull { it.title.contains("词") || it.title.contains("解锁") }
                        list.add(BannerAction(lyricLabel, rawAct?.intent))
                    }
                }
            }
            list
        } else {
            st.actions
        }

        if (displayActions.isNotEmpty()) {
            val btnHeightDp = if (isLandscape) (st.config.bannerBtnSizeDp - 2).coerceIn(32, 46) else st.config.bannerBtnSizeDp.coerceIn(38, 56)
            val effectiveTextSp = if (displayActions.size >= 5) (st.config.btnSizeSp - 0.5f).coerceAtLeast(11f) else st.config.btnSizeSp.coerceAtLeast(12.5f)
            val singleAction = displayActions.size == 1
            val actionRow = LinearLayout(ctx).apply {
                isClickable = true // 防误触盾：按钮间隙吞噬点击，绝不穿透误跳原应用
                orientation = LinearLayout.HORIZONTAL
                gravity = if (singleAction) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(ctx, if (isLandscape) 8f else 10f) }
            }
            displayActions.take(6).forEachIndexed { idx, act ->
                val pillBg = when {
                    act.isPositive -> Color.parseColor("#FF15803D")
                    act.isDestructive -> Color.parseColor("#52EF4444") // 精致半透酒红底，告别刺眼通栏大红条
                    else -> btnBg
                }
                val pillFg = when {
                    act.isPositive -> Color.WHITE
                    act.isDestructive -> Color.parseColor("#FFFCA5A5")
                    else -> accentColor
                }
                val compactRow = displayActions.size >= 5
                val btn = makePillButton(
                    ctx,
                    act.title,
                    pillFg,
                    pillBg,
                    effectiveTextSp,
                    btnHeightDp,
                    padHDp = if (singleAction) 20f else if (compactRow) 4f else 12f
                ) {
                    executeAction(ctx, act, currentState ?: st)
                    if (st.isMedia && (act.title in setOf("快退", "快进", "暂停", "播放", "播放/暂停", "上一曲", "下一曲", "歌词", "锁词", "关词") || act.title.contains("词"))) {
                        mainHandler.removeCallbacks(autoCollapseOrDismissRunnable)
                        mainHandler.postDelayed(autoCollapseOrDismissRunnable, currentDurationMs)
                    } else if (!st.collapsibleToPill) {
                        removeViewImmediate()
                    }
                }.apply {
                    layoutParams = if (singleAction) {
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    } else {
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (idx > 0) marginStart = dp(ctx, if (compactRow) 5f else 8f)
                        }
                    }
                    if (singleAction) minWidth = dp(ctx, 96f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                if (st.isMedia && (act.title == "暂停" || act.title == "播放")) {
                    currentPlayPauseBtn = btn
                }
                if (st.isMedia && (act.title == "歌词" || act.title == "锁词" || act.title == "关词" || act.title.contains("词"))) {
                    currentLyricBtn = btn
                }
                actionRow.addView(btn)
            }
            root.addView(actionRow)
        }

        return root
    }

    private fun executeAction(ctx: Context, act: BannerAction, st: BannerState) {
        val mediaCmd = when (act.title) {
            "上一曲" -> CapsuleKeepAliveService.ACTION_MEDIA_PREV to KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "下一曲" -> CapsuleKeepAliveService.ACTION_MEDIA_NEXT to KeyEvent.KEYCODE_MEDIA_NEXT
            "暂停", "播放", "播放/暂停" -> CapsuleKeepAliveService.ACTION_MEDIA_PLAY_PAUSE to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "快退" -> CapsuleKeepAliveService.ACTION_MEDIA_REWIND to KeyEvent.KEYCODE_MEDIA_REWIND
            "快进" -> CapsuleKeepAliveService.ACTION_MEDIA_FAST_FORWARD to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            else -> null
        }
        if (mediaCmd != null) {
            val handled = CapsuleNotificationListener.dispatchPinnedMediaCommand(ctx, mediaCmd.first)
            if (handled) {
                CapsuleNotificationListener.scheduleImmediateMediaResync()
                return
            }
            if (act.title == "快退" || act.title == "快进") return
            if (sendPendingIntentCompat(ctx, act.intent)) {
                CapsuleNotificationListener.scheduleImmediateMediaResync()
                return
            }
            runCatching {
                val am = ctx.getSystemService(AudioManager::class.java)
                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, mediaCmd.second))
                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, mediaCmd.second))
            }
            return
        }
        if (act.title == "歌词" || act.title == "锁词" || act.title == "关词" || act.title == "解锁歌词" || act.title.contains("词")) {
            CapsuleNotificationListener.triggerLyricCycleAction(ctx, st.notificationKey, st.packageName)
            return
        }
        if (act.title == "结束导航") {
            CapsuleNavigationEngine.stopNavigation(ctx)
            return
        }
        if (act.title == "喜欢" || act.title == "已赞" || act.title == "取消喜欢") {
            if (CapsuleNotificationListener.triggerNativeFavoriteAction(ctx, st.notificationKey, st.packageName)) return
        }
        if (CapsuleNotificationListener.triggerFreshNotificationAction(ctx, st.notificationKey, st.packageName, act.title)) {
            return
        }
        if (!act.hasRemoteInput && sendPendingIntentCompat(ctx, act.intent)) return
        val isNonJump = act.title.contains("词") || act.title == "标为已读" || act.title == "喜欢" ||
            act.title.contains("暂停") || act.title.contains("继续") || act.title.contains("停止")
        if (!isNonJump) {
            openSourceApp(ctx, st.contentIntent, st.packageName)
        }
    }

    private fun makePillButton(
        ctx: Context,
        label: String,
        textColor: Int,
        bgColor: Int,
        textSp: Float,
        heightDp: Int,
        padHDp: Float = 10f,
        onClick: () -> Unit
    ): TextView {
        return TextView(ctx).apply {
            tag = "banner_action_btn"
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp.coerceIn(10f, 16f))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(ctx, heightDp.toFloat())
            setPadding(dp(ctx, padHDp), dp(ctx, 4f), dp(ctx, padHDp), dp(ctx, 4f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, (heightDp / 2f).coerceAtLeast(12f)).toFloat()
                setColor(bgColor)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun sendPendingIntentCompat(ctx: Context, pi: PendingIntent?): Boolean {
        if (pi == null) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && pi.isActivity) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(ctx, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
            true
        }.getOrDefault(false)
    }

    private fun openSourceApp(ctx: Context, contentIntent: PendingIntent?, packageName: String) {
        if (sendPendingIntentCompat(ctx, contentIntent)) return
        runCatching {
            val launch = ctx.packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launch)
            }
        }
    }

    private fun removeViewOnly() {
        mainHandler.removeCallbacks(mediaTicker)
        seekBarResetAnimator?.cancel()
        seekBarResetAnimator = null
        val v = currentView ?: return
        currentView = null
        val wm = hostAppContext?.getSystemService(WindowManager::class.java)
        runCatching { wm?.removeViewImmediate(v) }
    }

    private fun removeViewImmediate() {
        mainHandler.removeCallbacks(autoCollapseOrDismissRunnable)
        mainHandler.removeCallbacks(mediaTicker)
        removeViewOnly()
        currentKey = null
        currentState = null
        isCollapsedPill = false
        currentIconView = null
        currentTitleView = null
        currentDetailView = null
        currentPlayPauseBtn = null
        currentLyricBtn = null
        seekBarResetAnimator?.cancel()
        seekBarResetAnimator = null
        currentProgressBar = null
        currentTimeText = null
        currentPillIconView = null
        currentPillText = null
        mediaDurMs = 0L
        mediaPlaying = false
    }

    private fun getSafeTopOffsetPx(ctx: Context, wm: WindowManager): Int {
        val metrics = wm.currentWindowMetrics
        val cutoutTop = metrics.windowInsets.displayCutout?.safeInsetTop ?: 0
        val statusBarTop = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        val hwNotchTop = SystemPropertyProfile.notchHeightPxFromProp
        val base = maxOf(cutoutTop, statusBarTop, hwNotchTop)
        return (if (base > 0) base else dp(ctx, 32f)) + dp(ctx, 4f)
    }

    private fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).toInt()

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

    private class SwipeableOverlayLayout(
        private val ctx: Context,
        private val isPill: Boolean
    ) : LinearLayout(ctx) {
        private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val swipeThresholdPx = maxOf(touchSlop * 2.2f, dp(ctx, 24f).toFloat())
        private val buttonSwipeThresholdPx = maxOf(touchSlop * 3.5f, dp(ctx, 42f).toFloat())
        private var downRawX = 0f
        private var downRawY = 0f
        private var isSwiping = false
        private var startedOnBtn = false

        private fun isInsideBtn(v: View, rawX: Float, rawY: Float): Boolean {
            if (v.tag == "banner_action_btn" && v.visibility == View.VISIBLE) {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                return rawX >= loc[0] && rawX <= loc[0] + v.width && rawY >= loc[1] && rawY <= loc[1] + v.height
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    if (isInsideBtn(v.getChildAt(i), rawX, rawY)) return true
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
                    startedOnBtn = isInsideBtn(this, ev.rawX, ev.rawY)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    val th = if (startedOnBtn) buttonSwipeThresholdPx else swipeThresholdPx
                    if (abs(dx) > th && abs(dx) > abs(dy) * 1.6f) {
                        isSwiping = true
                        mainHandler.removeCallbacks(autoCollapseOrDismissRunnable)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isSwiping = false
                    startedOnBtn = false
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun performClick(): Boolean = super.performClick()

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
                        mainHandler.removeCallbacks(autoCollapseOrDismissRunnable)
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
                            animate().translationX(targetX).alpha(0f).setDuration(150L)
                                .setInterpolator(AccelerateInterpolator())
                                .withEndAction { finishCurrentBannerOrResumeInterrupt() }.start()
                        } else {
                            animate().translationX(0f).alpha(1f).setDuration(170L)
                                .setInterpolator(DecelerateInterpolator()).start()
                            if (!isPill) {
                                mainHandler.postDelayed(autoCollapseOrDismissRunnable, currentDurationMs)
                            }
                        }
                        isSwiping = false
                        return true
                    } else {
                        performClick()
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    alpha = 1f
                    translationX = 0f
                    isSwiping = false
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }
    }
}
