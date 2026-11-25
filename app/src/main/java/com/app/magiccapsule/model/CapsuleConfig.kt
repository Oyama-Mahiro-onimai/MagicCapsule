package com.app.magiccapsule.model

import android.app.NotificationManager
import android.graphics.Color
import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt

@Immutable
data class CardStylePreset(
    val id: String,
    val label: String,
    val cardBgHex: String,
    val titleColorHex: String,
    val detailColorHex: String,
    val btnBgHex: String,
    val accentColorHex: String
)

@Immutable
data class CapsuleConfig(
    val businessName: String = "通知",
    val mainText: String = "等待新通知",
    val subText: String = "点击卡片返回应用",
    val template: String = TEMPLATE_IMAGE_TEXT,
    val brandColor: Int = 1,
    val enableTimer: Boolean = false,
    val timerMinutes: Int = 5,
    val refreshIntervalSec: Int = 30,
    val autoMirrorNotifications: Boolean = true,
    val ignoreOngoingInAutoMode: Boolean = true,
    val autoThrottleIntervalSec: Int = 5,
    val whitelistOnlyInAutoMode: Boolean = false,
    val autoWhitelistPackages: Set<String> = emptySet(),
    // 复用横幅链路：胶囊即弹出即销毁与保持时间（秒，仅自动模式生效；手动锁定保持常驻）
    val capsuleAutoDismiss: Boolean = true,
    val capsuleHoldDurationSec: Int = 8,
    // 手动胶囊图标预设（app_icon / notif / shield / palette / tune / custom_image / none）
    val manualIconPreset: String = ICON_APP,
    // 有通知时直接引用横幅通知配色样式，并替换手动文案与手动图标
    val capsuleFollowBannerStyle: Boolean = true,
    // 胶囊通知获取方式（unified_banner / direct_listener / manual_pin_only）
    val capsuleAcquireMode: String = ACQUIRE_UNIFIED_BANNER,
    // 胶囊拉起与渠道策略（direct_notify / foreground_service / media_style）
    val capsuleDeliveryMode: String = DELIVERY_DIRECT_NOTIFY,
    // 按系统通知渠道重要性过滤（IMPORTANCE_HIGH=4 / IMPORTANCE_DEFAULT=3 / IMPORTANCE_LOW=2）
    val minChannelImportance: Int = NotificationManager.IMPORTANCE_DEFAULT,
    // 微信/QQ等语音视频来电时自动拉起通话界面（防止屏蔽系统横幅后无法接听）
    val autoLaunchCallFullScreen: Boolean = true,
    // 开启自定义顶层横幅时，胶囊通知走 IMPORTANCE_DEFAULT 静默通道且不附加系统 HeadsUp 视图，彻底杜绝横幅下方出现同名重复通知
    val capsuleQuietChannelWhenBannerOn: Boolean = true,
    // 方案一：无障碍 TYPE_ACCESSIBILITY_OVERLAY 顶层横幅接管、上下 px 与独立配色
    val enableOverlayBanner: Boolean = false,
    val suppressSystemHeadsUp: Boolean = false,
    val overlayBannerDurationSec: Int = 4,
    val overlayBannerOffsetYPx: Int = 0,
    val overlayBannerPaddingVPx: Int = 36,
    val overlayBannerBgHex: String = "#FF101816",
    val overlayBannerTitleColorHex: String = "#FFFFFFFF",
    val overlayBannerDetailColorHex: String = "#FFB8D8CC",
    val overlayBannerAlphaPercent: Int = 92,
    // 默认即「森林翡翠 + 紧凑单行」固定尺寸效果
    val cardLayoutStyle: String = LAYOUT_COMPACT_ROW,
    val cardBgHex: String = "#FF0D1F18",
    val cardBgAlphaPercent: Int = 100,
    val cardBgGrayscalePercent: Int = 0,
    val compactBgFollowSystemGlass: Boolean = false,
    val titleColorHex: String = "#FFECFDF5",
    val detailColorHex: String = "#FF6EE7B7",
    val btnBgHex: String = "#FF065F46",
    val accentColorHex: String = "#FF34D399",
    val titleSizeSp: Float = 15f,
    val detailSizeSp: Float = 12f,
    val btnSizeSp: Float = 12f,
    val showAccentBar: Boolean = false,
    val showTimestamp: Boolean = false,
    val showActions: Boolean = true,
    // 是否渲染本应用自带按钮（打开/关闭、刷新/停止）；关闭时仅渲染原通知透传的真实按钮，点击通知或透传按钮直接跳转
    val renderOwnNotificationButtons: Boolean = false,
    // 横幅通知按钮高度大小（dp，26–56）
    val bannerBtnSizeDp: Int = 34,
    // 胶囊卡片按钮高度大小（dp，24–48）
    val capsuleBtnSizeDp: Int = 32
) {
    fun resolveCardBgColor(): Int {
        val base = parseColorSafe(cardBgHex, Color.parseColor("#FF0D1F18"))
        return applyGrayscaleAndAlpha(base, cardBgGrayscalePercent, cardBgAlphaPercent)
    }

    fun resolveOverlayBannerBgColor(): Int {
        val base = parseColorSafe(overlayBannerBgHex, Color.parseColor("#FF101816"))
        return applyGrayscaleAndAlpha(base, 0, overlayBannerAlphaPercent.coerceIn(20, 100))
    }

    companion object {
        const val TEMPLATE_IMAGE_TEXT = "image_text"
        const val TEMPLATE_IMAGE_TEXT_TEXT = "image_text_text"
        const val TEMPLATE_IMAGE_TIMER = "image_timer"
        const val TEMPLATE_IMAGE_TEXT_TIMER = "image_text_timer"

        const val LAYOUT_COMPACT_ROW = "compact_row"
        const val LAYOUT_STANDARD = "standard"
        const val LAYOUT_HERO = "centered_hero"
        const val LAYOUT_MINIMAL = "minimal_info"

        const val ICON_APP = "app_icon"
        const val ICON_NOTIF = "notif"
        const val ICON_SHIELD = "shield"
        const val ICON_PALETTE = "palette"
        const val ICON_TUNE = "tune"
        const val ICON_CUSTOM_IMAGE = "custom_image"
        const val ICON_NONE = "none"

        const val ACQUIRE_UNIFIED_BANNER = "unified_banner"
        const val ACQUIRE_DIRECT_LISTENER = "direct_listener"
        const val ACQUIRE_MANUAL_PIN_ONLY = "manual_pin_only"

        val ALL_ACQUIRE_MODES = listOf(
            ACQUIRE_UNIFIED_BANNER to "复用横幅链路",
            ACQUIRE_DIRECT_LISTENER to "独立监听获取",
            ACQUIRE_MANUAL_PIN_ONLY to "仅手动/锁定"
        )

        const val DELIVERY_DIRECT_NOTIFY = "direct_notify"
        const val DELIVERY_FOREGROUND_SERVICE = "foreground_service"
        const val DELIVERY_MEDIA_STYLE = "media_style"

        val ALL_IMPORTANCE_LEVELS = listOf(
            NotificationManager.IMPORTANCE_HIGH to "仅重要/横幅",
            NotificationManager.IMPORTANCE_DEFAULT to "过滤静默（推荐）",
            NotificationManager.IMPORTANCE_LOW to "全部（含静默）"
        )

        fun importanceFilterDesc(level: Int): String = when {
            level >= NotificationManager.IMPORTANCE_HIGH ->
                "仅系统渠道为「紧急/重要（横幅）」的通知和来电会弹出横幅与胶囊，其余普通及静默通知仅留在列表。"
            level <= NotificationManager.IMPORTANCE_LOW ->
                "所有渠道的通知（含静默通知）均允许触发；来电始终最高优先级直通。"
            else ->
                "自动过滤系统「静默/低重要性」渠道通知，保留普通、重要横幅与来电通知（白名单应用不受限）。"
        }

        val ALL_DELIVERY_MODES = listOf(
            DELIVERY_DIRECT_NOTIFY to "直接推送（推荐）",
            DELIVERY_FOREGROUND_SERVICE to "前台服务替换",
            DELIVERY_MEDIA_STYLE to "仿媒体强推"
        )

        fun acquireModeLabel(mode: String): String =
            ALL_ACQUIRE_MODES.firstOrNull { it.first == mode }?.second ?: "复用横幅链路"

        fun acquireModeDesc(mode: String): String = when (mode) {
            ACQUIRE_DIRECT_LISTENER ->
                "直接从系统通知监听独立抓取新通知，不受横幅防重与冷却间隔限制，仅按白名单与常驻开关过滤。"
            ACQUIRE_MANUAL_PIN_ONLY ->
                "关闭自动抓取后台新通知，仅在你手动启动自定义胶囊或在下方通知列表点击锁定时展示。"
            else ->
                "与顶层横幅共用同一套监听、白名单、防重与频率过滤链路，收到新通知即同步触发胶囊。"
        }

        fun deliveryModeLabel(mode: String): String =
            ALL_DELIVERY_MODES.firstOrNull { it.first == mode }?.second ?: "直接推送（推荐）"

        fun deliveryModeDesc(mode: String): String = when (mode) {
            DELIVERY_FOREGROUND_SERVICE ->
                "通过前台保活服务原地替换手动胶囊的文案与图标（若后台启动被系统拦截，自动回退直接推送）。"
            DELIVERY_MEDIA_STYLE ->
                "使用仿媒体播放器渠道（IMPORTANCE_MAX + 空 MediaSession），适用于对普通服务渠道强校验的系统版本。"
            else ->
                "直接通过 NotificationManager 弹出并定时销毁（magic_capsule_live_channel），彻底避开后台启动限制。"
        }

        val MANUAL_ICON_PRESETS = listOf(
            ICON_APP to "胶囊图标",
            ICON_NOTIF to "铃铛",
            ICON_SHIELD to "护盾",
            ICON_PALETTE to "调色板",
            ICON_TUNE to "设置",
            ICON_CUSTOM_IMAGE to "相册自选",
            ICON_NONE to "不显示"
        )

        val ALL_TEMPLATES = listOf(
            TEMPLATE_IMAGE_TEXT to "图文",
            TEMPLATE_IMAGE_TEXT_TEXT to "双文案轮播",
            TEMPLATE_IMAGE_TIMER to "图标+计时",
            TEMPLATE_IMAGE_TEXT_TIMER to "图文+计时"
        )

        val ALL_LAYOUT_STYLES = listOf(
            LAYOUT_COMPACT_ROW to "紧凑单行（推荐）",
            LAYOUT_STANDARD to "上下双按钮",
            LAYOUT_HERO to "居中大字",
            LAYOUT_MINIMAL to "极简纯信息"
        )

        val STYLE_PRESETS = listOf(
            CardStylePreset("forest", "森林翡翠", "#FF0D1F18", "#FFECFDF5", "#FF6EE7B7", "#FF065F46", "#FF34D399"),
            CardStylePreset("dark", "暗夜极黑", "#FF1C1C1E", "#FFFFFFFF", "#FFB8B8C0", "#FF2C2C30", "#FF60A5FA"),
            CardStylePreset("light", "极简纯白", "#FFFFFFFF", "#FF111111", "#FF555555", "#FFE8EAED", "#FF1A73E8"),
            CardStylePreset("ocean", "深海蓝调", "#FF0F172A", "#FFF8FAFC", "#FF94A3B8", "#FF1E3A8A", "#FF38BDF8"),
            CardStylePreset("cyber", "霓虹紫夜", "#FF181024", "#FFFDF4FF", "#FFD8B4FE", "#FF4C1D95", "#FFE879F9")
        )

        val QUICK_SWATCHES = listOf(
            "#FF0D1F18", "#FF065F46", "#FF34D399", "#FF6EE7B7",
            "#FF1C1C1E", "#FF000000", "#FFFFFFFF", "#FFF2F4F7",
            "#FF0F172A", "#FF181024", "#FF60A5FA", "#FFFBBF24", "#FFFF6B6B"
        )

        fun parseColorSafe(hex: String, fallback: Int): Int {
            val cleaned = hex.trim().let { if (it.startsWith("#")) it else "#$it" }
            return runCatching { Color.parseColor(cleaned) }.getOrDefault(fallback)
        }

        fun toArgbHex(colorInt: Int): String = String.format("#%08X", colorInt)

        fun applyGrayscaleAndAlpha(colorInt: Int, grayscalePercent: Int, alphaPercent: Int): Int {
            val gFactor = grayscalePercent.coerceIn(0, 100) / 100f
            val aFactor = alphaPercent.coerceIn(0, 100) / 100f
            val r = Color.red(colorInt)
            val g = Color.green(colorInt)
            val b = Color.blue(colorInt)
            val baseAlpha = Color.alpha(colorInt)

            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val outR = (r + (luma - r) * gFactor).roundToInt().coerceIn(0, 255)
            val outG = (g + (luma - g) * gFactor).roundToInt().coerceIn(0, 255)
            val outB = (b + (luma - b) * gFactor).roundToInt().coerceIn(0, 255)
            val outA = (baseAlpha * aFactor).roundToInt().coerceIn(0, 255)
            return Color.argb(outA, outR, outG, outB)
        }
    }
}
