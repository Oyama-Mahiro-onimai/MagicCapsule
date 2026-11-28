package com.app.magiccapsule.capsule

import com.app.magiccapsule.capsule.android16.AndroidLiveUpdateBridge
import com.app.magiccapsule.capsule.honor.MagicCapsuleBridge
import com.app.magiccapsule.capsule.huawei.HuaweiLiveCapsuleBridge

import android.content.Context
import android.os.Build

/**
 * 多品牌厂商胶囊引擎路由：
 * - 荣耀 MagicOS：走 framework-magic 反射桥接（MagicCapsuleBridge）
 * - vivo / iQOO OriginOS：走 notification.superx.* 原生原子通知分支（VivoSuperXBridge），
 *   若服务端返回 2208（非白名单）自动无缝回退至通用悬浮横幅/迷你胶囊（UniversalBannerHost）
 * - 其他品牌（小米 / OPPO / 三星 / Pixel 等）：直接走全品牌通用悬浮横幅/迷你胶囊
 */
object DeviceBrandRouter {

    enum class BrandEngine {
        HONOR_MAGIC_CAPSULE,
        HUAWEI_LIVE_CAPSULE,
        VIVO_SUPERX,
        XIAOMI_HYPEROS_FOCUS,
        ANDROID_LIVE_UPDATES,
        UNIVERSAL_OVERLAY
    }

    val currentEngine: BrandEngine by lazy {
        when {
            MagicCapsuleBridge.isSupported() -> BrandEngine.HONOR_MAGIC_CAPSULE
            HuaweiLiveCapsuleBridge.isSupported() -> BrandEngine.HUAWEI_LIVE_CAPSULE
            isVivoOriginOs() -> BrandEngine.VIVO_SUPERX
            isXiaomiHyperOs() -> BrandEngine.XIAOMI_HYPEROS_FOCUS
            AndroidLiveUpdateBridge.isSupported() -> BrandEngine.ANDROID_LIVE_UPDATES
            else -> BrandEngine.UNIVERSAL_OVERLAY
        }
    }

    fun isXiaomiHyperOs(): Boolean {
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        if (brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")) return true
        return com.app.magiccapsule.service.SystemPropertyProfile.hyperOsVersion.isNotBlank()
    }

    fun isVivoOriginOs(): Boolean {
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        if (brand.contains("vivo") || brand.contains("iqoo")) return true
        return com.app.magiccapsule.service.SystemPropertyProfile.originOsVersion.isNotBlank()
    }

    fun isNativeCapsuleEngine(): Boolean =
        currentEngine == BrandEngine.HONOR_MAGIC_CAPSULE ||
            currentEngine == BrandEngine.HUAWEI_LIVE_CAPSULE ||
            currentEngine == BrandEngine.ANDROID_LIVE_UPDATES

    /**
     * 判断当前机型的原生状态栏胶囊是否真正具备展示条件：
     * - 华为 HarmonyOS 若系统「实况窗开关 (areLiveNotificationsEnabled)」为 false，原生胶囊会被系统静默丢弃，
     *   此时自动判定为未就绪，无缝回退至通用悬浮横幅/迷你胶囊，避免用户端“既无实况窗又无悬浮窗”。
     */
    fun isNativeCapsuleReady(context: Context): Boolean = when (currentEngine) {
        BrandEngine.HONOR_MAGIC_CAPSULE -> true
        BrandEngine.HUAWEI_LIVE_CAPSULE -> HuaweiLiveCapsuleBridge.areLiveNotificationsEnabled(context)
        BrandEngine.ANDROID_LIVE_UPDATES -> AndroidLiveUpdateBridge.canPostPromotedNotifications(context)
        else -> false
    }

    fun engineDisplayName(): String = when (currentEngine) {
        BrandEngine.HONOR_MAGIC_CAPSULE -> "荣耀 MagicOS 原生灵动胶囊"
        BrandEngine.HUAWEI_LIVE_CAPSULE -> "华为 HarmonyOS / EMUI 实况窗原生胶囊（notification.live.capsule）"
        BrandEngine.VIVO_SUPERX -> "vivo OriginOS SuperX 分支（带通用悬浮兜底）"
        BrandEngine.XIAOMI_HYPEROS_FOCUS -> "小米 HyperOS 焦点通知分支（带通用悬浮兜底）"
        BrandEngine.ANDROID_LIVE_UPDATES -> "Android 16+ 原生实时更新胶囊（Live Updates Chip）"
        BrandEngine.UNIVERSAL_OVERLAY -> "全品牌通用悬浮横幅 / 迷你胶囊"
    }
}
