package com.app.magiccapsule.model

import android.graphics.Color
import androidx.compose.runtime.Immutable

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
    // 自动模式刷新频率限制（秒，仅作用于非白名单应用；白名单应用完全不受此限制）
    val autoThrottleIntervalSec: Int = 5,
    // 是否仅自动同步白名单内的应用
    val whitelistOnlyInAutoMode: Boolean = false,
    // 自动模式白名单应用包名集合
    val autoWhitelistPackages: Set<String> = emptySet(),
    // 默认即图中的「森林翡翠 + 紧凑单行」固定尺寸效果
    val cardLayoutStyle: String = LAYOUT_COMPACT_ROW,
    val cardBgHex: String = "#FF0D1F18",
    val titleColorHex: String = "#FFECFDF5",
    val detailColorHex: String = "#FF6EE7B7",
    val btnBgHex: String = "#FF065F46",
    val accentColorHex: String = "#FF34D399",
    val titleSizeSp: Float = 15f,
    val detailSizeSp: Float = 12f,
    val btnSizeSp: Float = 12f,
    val showAccentBar: Boolean = false,
    val showTimestamp: Boolean = false,
    val showActions: Boolean = true
) {
    companion object {
        const val TEMPLATE_IMAGE_TEXT = "image_text"
        const val TEMPLATE_IMAGE_TEXT_TEXT = "image_text_text"
        const val TEMPLATE_IMAGE_TIMER = "image_timer"
        const val TEMPLATE_IMAGE_TEXT_TIMER = "image_text_timer"

        const val LAYOUT_COMPACT_ROW = "compact_row"
        const val LAYOUT_STANDARD = "standard"
        const val LAYOUT_HERO = "centered_hero"
        const val LAYOUT_MINIMAL = "minimal_info"

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
    }
}
