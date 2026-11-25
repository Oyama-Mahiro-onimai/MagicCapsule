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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
            isPersistent: Boolean = false
        ): Boolean {
            val svc = instance
            if (svc == null) {
                _lastOverlayStatus.value = "触发失败：无障碍服务未绑定"
                return false
            }
            svc.postShowBanner(
                appName, packageName, title, text, contentIntent,
                iconBitmap, actions, config, notificationKey, isPersistent
            )
            return true
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
        isPersistent: Boolean
    ) {
        mainHandler.post {
            mainHandler.removeCallbacks(autoDismissRunnable)
            removeViewImmediate()

            val wm = getSystemService(WindowManager::class.java)
            if (wm == null) {
                _lastOverlayStatus.value = "触发失败：无法获取 WindowManager"
                return@post
            }

            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val bounds = wm.currentWindowMetrics.bounds
            val screenWidth = bounds.width()
            val bannerWidth = if (isLandscape) {
                (screenWidth * 0.62f).toInt()
            } else {
                (screenWidth - dp(20f)).coerceAtLeast(dp(260f))
            }
            val baseTopY = if (isLandscape) dp(8f) else getSafeTopOffsetPx(wm)
            val topY = (baseTopY + config.overlayBannerOffsetYPx).coerceAtLeast(0)
            currentDurationMs = if (isPersistent) 60_000L else config.overlayBannerDurationSec.coerceIn(2, 15) * 1000L

            val resolvedIcon = iconBitmap ?: NotificationIconStore.resolveAppIconBitmap(this, packageName)

            val view = buildBannerView(
                appName = appName,
                packageName = packageName,
                title = title,
                text = text,
                contentIntent = contentIntent,
                iconBitmap = resolvedIcon,
                actions = actions,
                config = config
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
                y = topY
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }

            runCatching {
                view.alpha = 1f
                view.translationX = 0f
                view.translationY = -dp(20f).toFloat()
                wm.addView(view, lp)
                currentOverlayView = view
                currentBannerKey = notificationKey
                view.animate()
                    .translationY(0f)
                    .setDuration(200L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()

                mainHandler.postDelayed(autoDismissRunnable, currentDurationMs)

                val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val actInfo = if (actions.isNotEmpty()) " · 含 ${actions.size} 个交互按钮" else ""
                _lastOverlayStatus.value = "$now 已弹出：$appName · $title$actInfo"
                AppLogger.i("Banner", "弹出横幅 app=$appName y=${topY}px padV=${config.overlayBannerPaddingVPx}px actions=${actions.size}")
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

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    isSwiping = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!isSwiping && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
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
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
                    }
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
        config: CapsuleConfig
    ): View {
        val bgInt = config.resolveOverlayBannerBgColor()
        val titleColor = CapsuleConfig.parseColorSafe(config.overlayBannerTitleColorHex, Color.parseColor("#FFFFFFFF"))
        val detailColor = CapsuleConfig.parseColorSafe(config.overlayBannerDetailColorHex, Color.parseColor("#FFB8D8CC"))
        val btnBg = CapsuleConfig.parseColorSafe(config.btnBgHex, Color.parseColor("#FF065F46"))
        val accentColor = CapsuleConfig.parseColorSafe(config.accentColorHex, Color.parseColor("#FF34D399"))
        val borderColor = Color.argb(0x40, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))

        val padV = config.overlayBannerPaddingVPx.coerceIn(8, 120)
        val root = SwipeDismissBannerLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), padV, dp(16f), padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24f).toFloat()
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

        if (iconBitmap != null) {
            val iconSize = dp(40f)
            val cornerR = dp(11f).toFloat()
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
            this.text = text
            setTextColor(detailColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, config.detailSizeSp)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3f), 0, 0)
        }
        textCol.addView(tvTitle)
        textCol.addView(tvDetail)
        row.addView(textCol)

        val btnHeightDp = config.bannerBtnSizeDp.coerceIn(26, 56)

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

        // 读取并展示通知自带的交互按钮（如标记已读、复制验证码、接听、拒接、回复等）
        if (actions.isNotEmpty()) {
            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(10f)
                }
            }
            actions.take(3).forEachIndexed { idx, act ->
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
                    textSp = config.btnSizeSp,
                    heightDp = btnHeightDp
                ) {
                    AppLogger.i("Banner", "点击透传按钮[${act.title}] pkg=$packageName remoteInput=${act.hasRemoteInput}")
                    triggerNotificationAction(act, contentIntent, packageName)
                    postDismissBanner()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx > 0) marginStart = dp(8f)
                    }
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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

    private fun triggerNotificationAction(
        action: BannerAction,
        fallbackContentIntent: PendingIntent?,
        packageName: String
    ) {
        if (action.hasRemoteInput || action.intent == null) {
            // 直接回复类按钮（需键盘输入）或空 Intent：跳转回原通知/应用
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
        return maxOf(insetsTop, dp(38f)) + dp(4f)
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
}
