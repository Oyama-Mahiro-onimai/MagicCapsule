package com.app.magiccapsule.service

import android.app.NotificationManager
import android.os.SystemClock
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.model.CapturedNotification

/**
 * 模块化双引擎独立过滤决策器：
 * - 顶层横幅（Banner）与灵动胶囊（Capsule）各自维护独立的去重与限频状态，互不干扰
 * - 详细记录每条通知被放行或拦截的具体原因，便于快速定位问题
 */
object NotificationFilterEngine {

    @Volatile private var lastBannerSig: String? = null
    @Volatile private var lastBannerTimeMs: Long = 0L
    @Volatile private var lastBannerThrottleMs: Long = 0L

    @Volatile private var lastCapsuleSig: String? = null
    @Volatile private var lastCapsuleTimeMs: Long = 0L
    @Volatile private var lastCapsuleThrottleMs: Long = 0L

    fun passesChannelImportance(item: CapturedNotification, prefs: CapsuleConfig): Boolean {
        if (item.isCall) return true
        val minLevel = prefs.minChannelImportance.coerceIn(
            NotificationManager.IMPORTANCE_LOW,
            NotificationManager.IMPORTANCE_HIGH
        )
        if (minLevel <= NotificationManager.IMPORTANCE_LOW) return true
        if (item.isSilent) return false
        return item.importance >= minLevel
    }

    fun shouldTriggerBanner(
        item: CapturedNotification,
        prefs: CapsuleConfig,
        isWhitelisted: Boolean,
        sig: String,
        forceUniversalFallback: Boolean = false
    ): Boolean {
        val bannerOrUniversalOn = prefs.enableOverlayBanner || prefs.enableUniversalBannerMode || forceUniversalFallback
        if (!bannerOrUniversalOn) {
            AppLogger.d("Banner", "横幅跳过(横幅与通用悬浮均未启用) app=${item.appName}")
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (sig == lastBannerSig && now - lastBannerTimeMs < 2000L) {
            AppLogger.d("Banner", "横幅跳过(2s同文案去重) app=${item.appName}")
            return false
        }
        if (!isWhitelisted && !item.isCall) {
            if (prefs.whitelistOnlyInAutoMode) {
                AppLogger.d("Banner", "横幅跳过(仅白名单) pkg=${item.packageName}")
                return false
            }
            if (!passesChannelImportance(item, prefs)) {
                AppLogger.d("Banner", "横幅跳过(渠道重要性=${item.importanceLabel()}/imp=${item.importance}<min=${prefs.minChannelImportance} silent=${item.isSilent}) pkg=${item.packageName}")
                return false
            }
            if (prefs.bannerIgnoreOngoing && item.isOngoing) {
                AppLogger.d("Banner", "横幅跳过(常驻通知) pkg=${item.packageName}")
                return false
            }
            val minIntervalMs = prefs.autoThrottleIntervalSec.coerceIn(1, 60) * 1000L
            val remain = minIntervalMs - (now - lastBannerThrottleMs)
            if (remain > 0L) {
                AppLogger.d("Banner", "横幅跳过(限频冷却剩${remain}ms) pkg=${item.packageName}")
                return false
            }
            lastBannerThrottleMs = now
        }
        lastBannerSig = sig
        lastBannerTimeMs = now
        AppLogger.d("Banner", "横幅放行 app=${item.appName} wl=$isWhitelisted call=${item.isCall}")
        return true
    }

    fun shouldTriggerCapsule(
        item: CapturedNotification,
        prefs: CapsuleConfig,
        isWhitelisted: Boolean,
        sig: String,
        bannerPassed: Boolean
    ): Boolean {
        if (!prefs.enableCapsuleEngine || !prefs.autoMirrorNotifications) {
            AppLogger.d("Notif", "胶囊跳过(engine=${prefs.enableCapsuleEngine}, autoMirror=${prefs.autoMirrorNotifications}) app=${item.appName}")
            return false
        }
        return when (prefs.capsuleAcquireMode) {
            CapsuleConfig.ACQUIRE_MANUAL_PIN_ONLY -> {
                AppLogger.d("Notif", "胶囊跳过(仅手动锁定模式) app=${item.appName}")
                false
            }
            CapsuleConfig.ACQUIRE_UNIFIED_BANNER -> {
                if (prefs.enableOverlayBanner) {
                    if (!bannerPassed) {
                        AppLogger.d("Notif", "胶囊跳过(统一横幅链路未通过) app=${item.appName}")
                    }
                    bannerPassed
                } else {
                    evaluateIndependentCapsule(item, prefs, isWhitelisted, sig, applyThrottle = true)
                }
            }
            else -> evaluateIndependentCapsule(item, prefs, isWhitelisted, sig, applyThrottle = false)
        }
    }

    private fun evaluateIndependentCapsule(
        item: CapturedNotification,
        prefs: CapsuleConfig,
        isWhitelisted: Boolean,
        sig: String,
        applyThrottle: Boolean
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (sig == lastCapsuleSig && now - lastCapsuleTimeMs < 1000L) {
            AppLogger.d("Notif", "胶囊跳过(1s同文案去重) app=${item.appName}")
            return false
        }
        if (!isWhitelisted && !item.isCall) {
            if (prefs.whitelistOnlyInAutoMode) {
                AppLogger.i("Notif", "胶囊跳过(仅白名单)：${item.packageName}")
                return false
            }
            if (!passesChannelImportance(item, prefs)) {
                AppLogger.i("Notif", "胶囊跳过(渠道重要性=${item.importanceLabel()}/imp=${item.importance}<min=${prefs.minChannelImportance})：${item.packageName}")
                return false
            }
            if (prefs.ignoreOngoingInAutoMode && item.isOngoing) {
                AppLogger.i("Notif", "胶囊跳过(常驻通知)：${item.packageName}")
                return false
            }
            if (applyThrottle) {
                val minIntervalMs = prefs.autoThrottleIntervalSec.coerceIn(1, 60) * 1000L
                val remain = minIntervalMs - (now - lastCapsuleThrottleMs)
                if (remain > 0L) {
                    AppLogger.d("Notif", "胶囊跳过(限频冷却剩${remain}ms)：${item.packageName}")
                    return false
                }
                lastCapsuleThrottleMs = now
            }
        }
        lastCapsuleSig = sig
        lastCapsuleTimeMs = now
        AppLogger.d("Notif", "胶囊放行 app=${item.appName} mode=${prefs.capsuleAcquireMode} wl=$isWhitelisted")
        return true
    }
}
