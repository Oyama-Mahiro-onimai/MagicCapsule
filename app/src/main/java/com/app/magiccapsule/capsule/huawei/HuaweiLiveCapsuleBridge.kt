package com.app.magiccapsule.capsule.huawei

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.service.ActiveMirroredPayload
import com.app.magiccapsule.service.NotificationIconStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 华为 HarmonyOS / EMUI 原生实况窗（Live View / Live Capsule）桥接实现：
 * 严格对齐 framework.jar 中 android.app.Notification$CapsuleStatus / CapsuleType / LiveOperation / LiveType
 * 及 Notification.checkLiveNotification() 校验规则：
 * - 文本长度严格截断至 MAX_LIVE_CHAR_SEQUENCE_LENGTH (128)
 * - 图标尺寸严格缩放至 <= 256px（低于 MAX_LIVE_ICON_SIZE = 512）
 * - 驻留时长严格钳位至 <= LIVE_MAX_KEEP_TIME (3,600,000ms)
 * - 维护单调递增 notification.live.version 序号与 CREATE/UPDATE/FINISH 生命周期状态
 */
object HuaweiLiveCapsuleBridge {

    // 对齐 android.app.Notification$CapsuleStatus
    const val STATUS_SHOW = 1
    const val STATUS_HIDE = -1

    // 对齐 android.app.Notification$CapsuleType
    const val CAPSULE_TYPE_ICON_TITLE = 1
    const val CAPSULE_TYPE_TIMER = 2
    const val CAPSULE_TYPE_PROGRESS = 3

    // 对齐 android.app.Notification$LiveOperation
    const val OPERATION_CREATE = 0
    const val OPERATION_UPDATE = 1
    const val OPERATION_FINISH = 2

    // 对齐 android.app.Notification$LiveType
    const val LIVE_TYPE_COMMON = 1
    const val LIVE_TYPE_MEDIA_CONTROL = 2
    const val LIVE_TYPE_NODE_PROGRESS = 3

    // 对齐 android.app.Notification 校验常量
    // 字节码实测 getLiveKeepTime() 内部执行 keepTime * 1000L 并钳位至 3,600,000ms，因此传入单位为秒（最大 3600L）
    const val LIVE_MAX_KEEP_TIME_SEC = 3_600L
    const val LIVE_MAX_KEEP_TIME_MS = 3_600_000L
    const val MAX_LIVE_CHAR_SEQUENCE_LENGTH = 128
    const val MAX_LIVE_ICON_SIZE = 512

    // 对齐 android.app.Notification.EXTRA_LIVE_*
    const val EXTRA_LIVE_TYPE = "notification.live.type"
    const val EXTRA_LIVE_OPERATION = "notification.live.operation"
    const val EXTRA_LIVE_ORDER = "notification.live.version"
    const val EXTRA_LIVE_KEEP_TIME = "notification.live.keepTime"
    const val EXTRA_LIVE_SCENE = "notification.live.event"
    const val EXTRA_LIVE_EXTERNAL_ENABLE = "notification.live.externalEnable"
    const val EXTRA_LIVE_EXTERNAL_TITLE = "notification.live.externalTitle"
    const val EXTRA_LIVE_EXTERNAL_BODY = "notification.live.externalBody"
    const val EXTRA_LIVE_TITLE_OVERLAY = "notification.live.titleOverlay"
    const val EXTRA_LIVE_CONTENT_OVERLAY = "notification.live.contentOverlay"
    const val EXTRA_LIVE_ICON_OVERLAY = "notification.live.iconOverlay"
    const val EXTRA_LIVE_FEATURE_INDICATOR = "notification.live.feature.indicatorIcon"

    const val EXTRA_LIVE_CAPSULE = "notification.live.capsule"
    const val EXTRA_LIVE_CAPSULE_STATUS = "notification.live.capsuleStatus"
    const val EXTRA_LIVE_CAPSULE_TYPE = "notification.live.capsuleType"
    const val EXTRA_LIVE_CAPSULE_ICON = "notification.live.capsuleIcon"
    const val EXTRA_LIVE_CAPSULE_TITLE = "notification.live.capsuleTitle"
    const val EXTRA_LIVE_CAPSULE_CONTENT = "notification.live.capsuleContent"
    const val EXTRA_LIVE_CAPSULE_BG_COLOR = "notification.live.capsuleBgColor"
    const val EXTRA_LIVE_CAPSULE_TIME = "notification.live.capsuleTime"
    const val EXTRA_LIVE_CAPSULE_COUNTDOWN = "notification.live.capsuleCountdown"
    const val EXTRA_LIVE_CAPSULE_PAUSE = "notification.live.capsulePause"
    const val EXTRA_LIVE_CAPSULE_REMIND = "notification.live.capsuleRemind"

    const val EXTRA_LIVE_FEATURE = "notification.live.feature"
    const val EXTRA_LIVE_FEATURE_TITLE = "notification.live.feature.titleText"
    const val EXTRA_LIVE_FEATURE_CONTENT = "notification.live.feature.contentText"
    const val EXTRA_LIVE_FEATURE_PROGRESS = "notification.live.feature.progress"
    const val EXTRA_LIVE_FEATURE_PROGRESS_COLOR = "notification.live.feature.progressColor"
    const val EXTRA_LIVE_FEATURE_PROGRESS_BG_COLOR = "notification.live.feature.progressBgColor"
    const val EXTRA_LIVE_FEATURE_HIDE_PROGRESS = "notification.live.feature.hideProgress"

    private val versionOrder = AtomicInteger(0)
    private val sessionActive = AtomicBoolean(false)

    @Volatile private var cachedIconHash: Int = 0
    @Volatile private var cachedIcon: Icon? = null

    private val supportedFlag: Boolean by lazy {
        runCatching {
            Class.forName("android.app.Notification\$CapsuleStatus")
            Class.forName("android.app.Notification\$CapsuleType")
            true
        }.getOrElse {
            runCatching {
                Notification::class.java.getDeclaredField("EXTRA_LIVE_CAPSULE")
                true
            }.getOrDefault(false)
        }
    }

    fun isSupported(): Boolean = supportedFlag

    /**
     * 反射调用 framework.jar 中的 NotificationManager.areLiveNtfsEnabled()，查询本应用实况窗开关是否已打开。
     */
    private val areLiveNtfsEnabledMethod by lazy {
        runCatching { NotificationManager::class.java.getMethod("areLiveNtfsEnabled") }.getOrNull()
    }

    fun areLiveNotificationsEnabled(context: Context): Boolean {
        if (!supportedFlag) return false
        val m = areLiveNtfsEnabledMethod ?: return false
        return runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            (m.invoke(nm) as? Boolean) ?: false
        }.getOrDefault(false)
    }

    fun resetSession() {
        sessionActive.set(false)
    }

    private fun clampLiveText(raw: String): String {
        val clean = raw.replace('\r', ' ').replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (clean.length > MAX_LIVE_CHAR_SEQUENCE_LENGTH) {
            clean.take(MAX_LIVE_CHAR_SEQUENCE_LENGTH - 1) + "…"
        } else {
            clean
        }
    }

    private fun buildCompliantIcon(context: Context, bmp: Bitmap?): Icon {
        if (bmp != null && !bmp.isRecycled) {
            val hash = NotificationIconStore.bitmapFingerprint(bmp)
            val prev = cachedIcon
            if (hash == cachedIconHash && prev != null) return prev
            // 严格遵守 MAX_LIVE_ICON_SIZE (512)，缩放到 192px 兼顾清晰度与 Binder 传输轻量化
            val maxDim = 192.coerceAtMost(MAX_LIVE_ICON_SIZE)
            val scaled = if (bmp.width > maxDim || bmp.height > maxDim) {
                runCatching {
                    Bitmap.createScaledBitmap(bmp, maxDim, maxDim, true).also { it.prepareToDraw() }
                }.getOrDefault(bmp)
            } else {
                bmp
            }
            return Icon.createWithBitmap(scaled).also {
                cachedIconHash = hash
                cachedIcon = it
            }
        }
        return Icon.createWithResource(context, android.R.drawable.ic_dialog_info)
    }

    fun attach(
        context: Context,
        notification: Notification,
        config: CapsuleConfig,
        mirroredPayload: ActiveMirroredPayload? = null,
        capsuleIconBitmap: Bitmap? = null,
        overrideChronometerBaseMs: Long = 0L,
        overrideChronometerPaused: Boolean = false
    ): Result<Unit> = runCatching {
        val extras = notification.extras ?: Bundle().also { notification.extras = it }
        val isMedia = mirroredPayload?.isMedia == true
        val isRecorderTimer = mirroredPayload?.isRecorder == true && overrideChronometerBaseMs > 0L
        val isNav = mirroredPayload?.key == "magic_capsule_web_nav"

        val progressPercent = if (isMedia && mirroredPayload != null && mirroredPayload.mediaDurationMs > 0L) {
            ((mirroredPayload.mediaPositionMs.coerceAtLeast(0L) * 100L) / mirroredPayload.mediaDurationMs)
                .toInt()
                .coerceIn(0, 100)
        } else {
            -1
        }

        val liveType = when {
            isMedia -> LIVE_TYPE_MEDIA_CONTROL
            isNav -> LIVE_TYPE_NODE_PROGRESS
            else -> LIVE_TYPE_COMMON
        }
        // 媒体/歌词场景必须使用 CAPSULE_TYPE_ICON_TITLE (1)，避免 TYPE_PROGRESS (3) 在 HarmonyOS 状态栏反复切回百分比/隐藏歌词文本
        val capsuleType = when {
            isRecorderTimer || config.enableTimer -> CAPSULE_TYPE_TIMER
            isMedia -> CAPSULE_TYPE_ICON_TITLE
            progressPercent >= 0 -> CAPSULE_TYPE_PROGRESS
            else -> CAPSULE_TYPE_ICON_TITLE
        }
        val operation = if (sessionActive.compareAndSet(false, true)) OPERATION_CREATE else OPERATION_UPDATE
        val ver = versionOrder.incrementAndGet()

        val title = clampLiveText(config.mainText.ifBlank { config.businessName.ifBlank { "灵动胶囊" } })
        val content = clampLiveText(config.subText.ifBlank { title })
        val icon = buildCompliantIcon(context, capsuleIconBitmap)
        val bgColor = config.resolveCardBgColor()
        val accentColor = CapsuleConfig.parseColorSafe(config.accentColorHex, 0xFF34D399.toInt())

        val capsuleBundle = Bundle().apply {
            putInt(EXTRA_LIVE_CAPSULE_STATUS, STATUS_SHOW)
            putInt(EXTRA_LIVE_CAPSULE_TYPE, capsuleType)
            putParcelable(EXTRA_LIVE_CAPSULE_ICON, icon)
            putString(EXTRA_LIVE_CAPSULE_TITLE, title)
            putString(EXTRA_LIVE_CAPSULE_CONTENT, content)
            putInt(EXTRA_LIVE_CAPSULE_BG_COLOR, bgColor)
            putBoolean(EXTRA_LIVE_CAPSULE_REMIND, false)
            if (capsuleType == CAPSULE_TYPE_TIMER) {
                val baseTime = if (overrideChronometerBaseMs > 0L) {
                    overrideChronometerBaseMs
                } else {
                    System.currentTimeMillis() + config.timerMinutes * 60_000L
                }
                putLong(EXTRA_LIVE_CAPSULE_TIME, baseTime)
                putBoolean(EXTRA_LIVE_CAPSULE_COUNTDOWN, overrideChronometerBaseMs <= 0L)
                putBoolean(EXTRA_LIVE_CAPSULE_PAUSE, overrideChronometerPaused)
            }
        }

        val featureBundle = Bundle().apply {
            putString(EXTRA_LIVE_FEATURE_TITLE, title)
            putString(EXTRA_LIVE_FEATURE_CONTENT, content)
            if (progressPercent >= 0) {
                putInt(EXTRA_LIVE_FEATURE_PROGRESS, progressPercent)
                putInt(EXTRA_LIVE_FEATURE_PROGRESS_COLOR, accentColor)
                putInt(EXTRA_LIVE_FEATURE_PROGRESS_BG_COLOR, 0x33FFFFFF)
                putBoolean(EXTRA_LIVE_FEATURE_HIDE_PROGRESS, false)
            } else {
                putBoolean(EXTRA_LIVE_FEATURE_HIDE_PROGRESS, true)
            }
        }

        if (isNav) {
            featureBundle.putParcelable(EXTRA_LIVE_FEATURE_INDICATOR, icon)
        }
        extras.putInt(EXTRA_LIVE_TYPE, liveType)
        extras.putInt(EXTRA_LIVE_OPERATION, operation)
        extras.putInt(EXTRA_LIVE_ORDER, ver)
        extras.putLong(EXTRA_LIVE_KEEP_TIME, LIVE_MAX_KEEP_TIME_SEC)
        extras.putString(
            EXTRA_LIVE_SCENE,
            when {
                isMedia -> "MEDIA"
                isNav -> "NAVIGATION"
                isRecorderTimer -> "RECORDER"
                else -> "COMMON"
            }
        )
        // 外屏/锁屏实况卡片与通知中心覆盖层完整透传（均已通过 128 字符 / <=512px 图标合规校验）
        extras.putBoolean(EXTRA_LIVE_EXTERNAL_ENABLE, true)
        extras.putString(EXTRA_LIVE_EXTERNAL_TITLE, title)
        extras.putString(EXTRA_LIVE_EXTERNAL_BODY, content)
        extras.putString(EXTRA_LIVE_TITLE_OVERLAY, title)
        extras.putString(EXTRA_LIVE_CONTENT_OVERLAY, content)
        extras.putParcelable(EXTRA_LIVE_ICON_OVERLAY, icon)
        extras.putBundle(EXTRA_LIVE_CAPSULE, capsuleBundle)
        extras.putBundle(EXTRA_LIVE_FEATURE, featureBundle)

        val liveEnabled = areLiveNotificationsEnabled(context)
        AppLogger.i(
            "Capsule",
            "HuaweiLiveCapsuleBridge.attach 成功 op=$operation ver=$ver liveType=$liveType capType=$capsuleType " +
                "liveOn=$liveEnabled prog=$progressPercent title=$title"
        )
    }
}
