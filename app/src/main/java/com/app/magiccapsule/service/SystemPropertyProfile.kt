package com.app.magiccapsule.service

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.app.magiccapsule.crash.AppLogger

/**
 * Android 系统属性表（android.os.SystemProperties）与系统配置表（Settings.Global/System/Secure）统一读取器：
 * - 静态只读属性（ro.* / msc.*）仅在首次访问时反射读取并缓存，零重复反射开销
 * - 动态配置（动画缩放、字体缩放、勿扰/省电模式、原生浮动通知开关）按需轻量读取
 * - 用于驱动：动画时长同步、挖孔安全高度兜底、SoC 线程池调优、厂商 ROM 版本识别与诊断展示
 */
object SystemPropertyProfile {

    private val spClass by lazy {
        runCatching { Class.forName("android.os.SystemProperties") }.getOrNull()
    }

    private val spGetMethod by lazy {
        runCatching { spClass?.getMethod("get", String::class.java, String::class.java) }.getOrNull()
    }

    private val spGetIntMethod by lazy {
        runCatching { spClass?.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType) }.getOrNull()
    }

    private val spGetBoolMethod by lazy {
        runCatching { spClass?.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType) }.getOrNull()
    }

    fun getProp(key: String, defaultValue: String = ""): String {
        val m = spGetMethod ?: return defaultValue
        return runCatching {
            (m.invoke(null, key, defaultValue) as? String)?.trim().orEmpty().ifEmpty { defaultValue }
        }.getOrDefault(defaultValue)
    }

    fun getIntProp(key: String, defaultValue: Int): Int {
        val m = spGetIntMethod ?: return defaultValue
        return runCatching {
            (m.invoke(null, key, defaultValue) as? Int) ?: defaultValue
        }.getOrDefault(defaultValue)
    }

    fun getBoolProp(key: String, defaultValue: Boolean): Boolean {
        val m = spGetBoolMethod ?: return defaultValue
        return runCatching {
            (m.invoke(null, key, defaultValue) as? Boolean) ?: defaultValue
        }.getOrDefault(defaultValue)
    }

    // --- 1. 厂商 ROM / 系统版本属性 ---
    val magicOsVersion: String by lazy {
        getProp("ro.build.version.magic")
            .ifBlank { getProp("msc.build.version.magic") }
            .ifBlank { getProp("ro.build.version.emui") }
    }

    val magicOsMajorVersion: Int by lazy {
        val raw = magicOsVersion
        Regex("""(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    val hyperOsVersion: String by lazy {
        getProp("ro.mi.os.version.name").ifBlank { getProp("ro.miui.ui.version.name") }
    }

    val originOsVersion: String by lazy {
        getProp("ro.vivo.os.version")
    }

    val colorOsVersion: String by lazy {
        getProp("ro.build.version.oplusrom").ifBlank { getProp("ro.build.version.opporom") }
    }

    val romDisplayName: String by lazy {
        when {
            magicOsVersion.isNotBlank() -> when {
                magicOsVersion.startsWith("MagicOS", ignoreCase = true) -> magicOsVersion
                magicOsVersion.startsWith("EmotionUI", ignoreCase = true) ||
                    magicOsVersion.startsWith("HarmonyOS", ignoreCase = true) ||
                    Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) -> "HarmonyOS / $magicOsVersion"
                else -> "MagicOS $magicOsVersion"
            }
            hyperOsVersion.isNotBlank() -> "HyperOS/MIUI $hyperOsVersion"
            originOsVersion.isNotBlank() -> "OriginOS $originOsVersion"
            colorOsVersion.isNotBlank() -> "ColorOS $colorOsVersion"
            else -> "Android ${Build.VERSION.RELEASE} (${Build.DISPLAY})"
        }
    }

    // --- 2. SoC 硬件平台与内存规格 ---
    val socManufacturer: String by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER.orEmpty().ifBlank { getProp("ro.soc.manufacturer") }
        } else {
            getProp("ro.soc.manufacturer")
        }
    }

    val socModel: String by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.orEmpty().ifBlank { getProp("ro.soc.model") }
        } else {
            getProp("ro.soc.model")
        }
    }

    val boardPlatform: String by lazy {
        getProp("ro.board.platform").ifBlank { Build.HARDWARE }
    }

    val socDisplayName: String by lazy {
        val parts = listOf(socManufacturer, socModel).filter { it.isNotBlank() && it != "unknown" }
        if (parts.isNotEmpty()) parts.joinToString(" ") else boardPlatform.ifBlank { Build.HARDWARE }
    }

    val isLowRamDevice: Boolean by lazy {
        getBoolProp("ro.config.low_ram", false)
    }

    // --- 3. 显示密度与硬件挖孔属性 ---
    val lcdDensityDpi: Int by lazy {
        getIntProp("ro.sf.lcd_density", 0)
    }

    /**
     * 解析荣耀/华为系统属性 ro.config.hw_notch_size（典型格式："width,height,top,bottom"），
     * 返回挖孔高度像素值，用作 DisplayCutout 不可用时的硬件级安全避让兜底。
     */
    val notchHeightPxFromProp: Int by lazy {
        val raw = getProp("ro.config.hw_notch_size")
        if (raw.isBlank()) return@lazy 0
        val nums = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        // 典型格式 [width, height, top, bottom] -> height + top
        when {
            nums.size >= 3 -> (nums[1] + nums[2]).coerceIn(0, 220)
            nums.size == 2 -> nums[1].coerceIn(0, 220)
            else -> 0
        }
    }

    val a2dpOffloadDisabled: Boolean by lazy {
        getBoolProp("persist.bluetooth.a2dp_offload.disabled", false)
    }

    val audioOffloadDisabled: Boolean by lazy {
        getBoolProp("audio.offload.disable", false) || getBoolProp("ro.audio.offload_disable", false)
    }

    // --- 4. 动态系统配置表（Settings.Global / System / Secure） ---
    fun getAnimatorDurationScale(context: Context): Float {
        return runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
        }.getOrDefault(1.0f).coerceIn(0f, 2.5f)
    }

    fun getTransitionAnimationScale(context: Context): Float {
        return runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1.0f)
        }.getOrDefault(1.0f).coerceIn(0f, 2.5f)
    }

    /**
     * 将横幅/胶囊动效基础时长按系统动画缩放倍率自适应调整：
     * - 用户在开发者选项关闭动画（0x）时直接返回 0ms，零卡顿瞬间响应
     * - 用户开启 0.5x 快动画时自动减半，与系统原生过渡节奏保持完全一致
     */
    fun scaledAnimDurationMs(context: Context, baseMs: Long): Long {
        val scale = minOf(getAnimatorDurationScale(context), getTransitionAnimationScale(context))
        if (scale <= 0.01f) return 0L
        return (baseMs * scale).toLong().coerceIn(40L, baseMs * 2L)
    }

    fun getSystemFontScale(context: Context): Float {
        return runCatching {
            Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1.0f)
        }.getOrDefault(1.0f).coerceIn(0.75f, 2.0f)
    }

    fun getNavigationModeLabel(context: Context): String {
        val mode = runCatching {
            Settings.Secure.getInt(context.contentResolver, "navigation_mode", -1)
        }.getOrDefault(-1)
        return when (mode) {
            0 -> "经典三键导航"
            1 -> "两键导航"
            2 -> "全面屏手势"
            else -> "未知($mode)"
        }
    }

    fun isSystemHeadsUpEnabled(context: Context): Boolean {
        return runCatching {
            Settings.Global.getInt(context.contentResolver, "heads_up_notifications_enabled", 1) == 1
        }.getOrDefault(true)
    }

    /**
     * 输出用于设备监控页展示的精简系统属性自适应摘要列表（每项为 标题 to 值）
     */
    fun collectDiagnosticEntries(context: Context): List<Pair<String, String>> {
        val animScale = getAnimatorDurationScale(context)
        val fontScale = getSystemFontScale(context)
        val dpi = if (lcdDensityDpi > 0) "${lcdDensityDpi} dpi" else "${context.resources.displayMetrics.densityDpi} dpi"
        val notchDesc = if (notchHeightPxFromProp > 0) "${notchHeightPxFromProp}px (hw_notch_size)" else "系统 DisplayCutout"
        val headsUp = if (isSystemHeadsUpEnabled(context)) "开启" else "已屏蔽"
        val offload = if (a2dpOffloadDisabled) "软件解码" else "硬件卸载(A2DP)"
        return listOf(
            "系统 ROM" to romDisplayName,
            "SoC 平台" to "$socDisplayName${if (isLowRamDevice) " · 低内存优化" else ""}",
            "屏幕密度 / 字体缩放" to "$dpi · ${String.format("%.2f", fontScale)}x",
            "系统动效同步" to if (animScale <= 0.01f) "已关闭(0x 瞬切)" else "${String.format("%.1f", animScale)}x",
            "挖孔安全基准" to notchDesc,
            "导航模式 / 原生横幅" to "${getNavigationModeLabel(context)} · $headsUp",
            "蓝牙音频通道" to offload,
            "前台渲染 LOD" to RenderPowerGovernor.formatCurrentLodSummary(),
            "智能刷新率 (HwDisplay)" to queryHwSmartRefreshLabel()
        )
    }

    private val hwSmartRefreshMethod by lazy {
        runCatching {
            Class.forName("com.huawei.android.hardware.display.HwDisplayManager")
                .getMethod("isInSmartRefreashMode")
        }.getOrNull()
    }

    private fun queryHwSmartRefreshLabel(): String {
        val m = hwSmartRefreshMethod ?: return "标准 DisplayManager"
        return runCatching {
            val active = (m.invoke(null) as? Boolean) == true
            if (active) "已启用(SmartRefresh)" else "未启用"
        }.getOrDefault("标准 DisplayManager")
    }

    fun logStartupSummary(context: Context) {
        AppLogger.i(
            "SysProp",
            "系统属性加载完成 rom=$romDisplayName soc=$socDisplayName dpi=$lcdDensityDpi " +
                "notchPx=$notchHeightPxFromProp animScale=${getAnimatorDurationScale(context)} lowRam=$isLowRamDevice"
        )
    }
}
