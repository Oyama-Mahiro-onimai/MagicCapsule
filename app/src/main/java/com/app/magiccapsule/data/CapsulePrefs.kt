package com.app.magiccapsule.data

import android.app.NotificationManager
import android.content.Context
import com.app.magiccapsule.model.CapsuleConfig

class CapsulePrefs(context: Context) {

    companion object {
        @Volatile
        private var memoryCache: CapsuleConfig? = null
    }

    private val sp = context.applicationContext
        .getSharedPreferences("magic_capsule_prefs", Context.MODE_PRIVATE)

    fun load(): CapsuleConfig {
        memoryCache?.let { return it }
        val rawWhitelist = sp.getStringSet("auto_whitelist_packages", emptySet())
        val whitelistCopy = rawWhitelist?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val loaded = CapsuleConfig(
            businessName = sp.getString("business_name", "通知") ?: "通知",
            mainText = sp.getString("main_text", "等待新通知") ?: "等待新通知",
            subText = sp.getString("sub_text", "点击卡片返回应用") ?: "点击卡片返回应用",
            template = sp.getString("template", CapsuleConfig.TEMPLATE_IMAGE_TEXT)
                ?: CapsuleConfig.TEMPLATE_IMAGE_TEXT,
            brandColor = sp.getInt("brand_color", 1).coerceIn(1, 12),
            enableTimer = sp.getBoolean("enable_timer", false),
            timerMinutes = sp.getInt("timer_minutes", 5).coerceAtLeast(1),
            refreshIntervalSec = sp.getInt("refresh_interval_sec", 30).coerceIn(10, 300),
            autoMirrorNotifications = sp.getBoolean("auto_mirror_notifications", true),
            ignoreOngoingInAutoMode = sp.getBoolean("ignore_ongoing_in_auto", true),
            autoThrottleIntervalSec = sp.getInt("auto_throttle_interval_sec", 5).coerceIn(1, 60),
            whitelistOnlyInAutoMode = sp.getBoolean("whitelist_only_in_auto", false),
            autoWhitelistPackages = whitelistCopy,
            capsuleAutoDismiss = sp.getBoolean("capsule_auto_dismiss", true),
            capsuleHoldDurationSec = sp.getInt("capsule_hold_duration_sec", 8).coerceIn(2, 60),
            manualIconPreset = sp.getString("manual_icon_preset", CapsuleConfig.ICON_APP)
                ?: CapsuleConfig.ICON_APP,
            capsuleFollowBannerStyle = sp.getBoolean("capsule_follow_banner_style", true),
            capsuleAcquireMode = sp.getString("capsule_acquire_mode", CapsuleConfig.ACQUIRE_UNIFIED_BANNER)
                ?: CapsuleConfig.ACQUIRE_UNIFIED_BANNER,
            capsuleDeliveryMode = sp.getString("capsule_delivery_mode", CapsuleConfig.DELIVERY_DIRECT_NOTIFY)
                ?: CapsuleConfig.DELIVERY_DIRECT_NOTIFY,
            minChannelImportance = sp.getInt("min_channel_importance", NotificationManager.IMPORTANCE_DEFAULT)
                .coerceIn(NotificationManager.IMPORTANCE_LOW, NotificationManager.IMPORTANCE_HIGH),
            autoLaunchCallFullScreen = sp.getBoolean("auto_launch_call_fullscreen", true),
            capsuleQuietChannelWhenBannerOn = sp.getBoolean("capsule_quiet_channel_when_banner_on", true),
            enableOverlayBanner = sp.getBoolean("enable_overlay_banner", false),
            suppressSystemHeadsUp = sp.getBoolean("suppress_system_heads_up", false),
            overlayBannerDurationSec = sp.getInt("overlay_banner_duration_sec", 4).coerceIn(2, 15),
            overlayBannerOffsetYPx = sp.getInt("overlay_banner_offset_y_px", 0).coerceIn(-120, 360),
            overlayBannerPaddingVPx = sp.getInt("overlay_banner_padding_v_px", 36).coerceIn(8, 120),
            overlayBannerBgHex = sp.getString("overlay_banner_bg_hex", "#FF101816") ?: "#FF101816",
            overlayBannerTitleColorHex = sp.getString("overlay_banner_title_hex", "#FFFFFFFF") ?: "#FFFFFFFF",
            overlayBannerDetailColorHex = sp.getString("overlay_banner_detail_hex", "#FFB8D8CC") ?: "#FFB8D8CC",
            overlayBannerAlphaPercent = sp.getInt("overlay_banner_alpha_percent", 92).coerceIn(20, 100),
            cardLayoutStyle = sp.getString("card_layout_style", CapsuleConfig.LAYOUT_COMPACT_ROW)
                ?: CapsuleConfig.LAYOUT_COMPACT_ROW,
            cardBgHex = sp.getString("card_bg_hex", "#FF0D1F18") ?: "#FF0D1F18",
            cardBgAlphaPercent = sp.getInt("card_bg_alpha_percent", 100).coerceIn(0, 100),
            cardBgGrayscalePercent = sp.getInt("card_bg_grayscale_percent", 0).coerceIn(0, 100),
            compactBgFollowSystemGlass = sp.getBoolean("compact_bg_follow_system_glass", false),
            titleColorHex = sp.getString("title_color_hex", "#FFECFDF5") ?: "#FFECFDF5",
            detailColorHex = sp.getString("detail_color_hex", "#FF6EE7B7") ?: "#FF6EE7B7",
            btnBgHex = sp.getString("btn_bg_hex", "#FF065F46") ?: "#FF065F46",
            accentColorHex = sp.getString("accent_color_hex", "#FF34D399") ?: "#FF34D399",
            titleSizeSp = sp.getFloat("title_size_sp", 15f).coerceIn(10f, 26f),
            detailSizeSp = sp.getFloat("detail_size_sp", 12f).coerceIn(8f, 20f),
            btnSizeSp = sp.getFloat("btn_size_sp", 12f).coerceIn(9f, 16f),
            showAccentBar = sp.getBoolean("show_accent_bar", false),
            showTimestamp = sp.getBoolean("show_timestamp", false),
            showActions = sp.getBoolean("show_actions", true),
            renderOwnNotificationButtons = sp.getBoolean("render_own_notification_buttons", false),
            bannerBtnSizeDp = sp.getInt("banner_btn_size_dp", 34).coerceIn(26, 56),
            capsuleBtnSizeDp = sp.getInt("capsule_btn_size_dp", 32).coerceIn(24, 48)
        )
        memoryCache = loaded
        return loaded
    }

    fun save(config: CapsuleConfig) {
        memoryCache = config
        sp.edit()
            .putString("business_name", config.businessName)
            .putString("main_text", config.mainText)
            .putString("sub_text", config.subText)
            .putString("template", config.template)
            .putInt("brand_color", config.brandColor)
            .putBoolean("enable_timer", config.enableTimer)
            .putInt("timer_minutes", config.timerMinutes)
            .putInt("refresh_interval_sec", config.refreshIntervalSec)
            .putBoolean("auto_mirror_notifications", config.autoMirrorNotifications)
            .putBoolean("ignore_ongoing_in_auto", config.ignoreOngoingInAutoMode)
            .putInt("auto_throttle_interval_sec", config.autoThrottleIntervalSec.coerceIn(1, 60))
            .putBoolean("whitelist_only_in_auto", config.whitelistOnlyInAutoMode)
            .putStringSet("auto_whitelist_packages", HashSet(config.autoWhitelistPackages))
            .putBoolean("capsule_auto_dismiss", config.capsuleAutoDismiss)
            .putInt("capsule_hold_duration_sec", config.capsuleHoldDurationSec.coerceIn(2, 60))
            .putString("manual_icon_preset", config.manualIconPreset)
            .putBoolean("capsule_follow_banner_style", config.capsuleFollowBannerStyle)
            .putString("capsule_acquire_mode", config.capsuleAcquireMode)
            .putString("capsule_delivery_mode", config.capsuleDeliveryMode)
            .putInt("min_channel_importance", config.minChannelImportance.coerceIn(NotificationManager.IMPORTANCE_LOW, NotificationManager.IMPORTANCE_HIGH))
            .putBoolean("auto_launch_call_fullscreen", config.autoLaunchCallFullScreen)
            .putBoolean("capsule_quiet_channel_when_banner_on", config.capsuleQuietChannelWhenBannerOn)
            .putBoolean("enable_overlay_banner", config.enableOverlayBanner)
            .putBoolean("suppress_system_heads_up", config.suppressSystemHeadsUp)
            .putInt("overlay_banner_duration_sec", config.overlayBannerDurationSec.coerceIn(2, 15))
            .putInt("overlay_banner_offset_y_px", config.overlayBannerOffsetYPx.coerceIn(-120, 360))
            .putInt("overlay_banner_padding_v_px", config.overlayBannerPaddingVPx.coerceIn(8, 120))
            .putString("overlay_banner_bg_hex", config.overlayBannerBgHex)
            .putString("overlay_banner_title_hex", config.overlayBannerTitleColorHex)
            .putString("overlay_banner_detail_hex", config.overlayBannerDetailColorHex)
            .putInt("overlay_banner_alpha_percent", config.overlayBannerAlphaPercent.coerceIn(20, 100))
            .putString("card_layout_style", config.cardLayoutStyle)
            .putString("card_bg_hex", config.cardBgHex)
            .putInt("card_bg_alpha_percent", config.cardBgAlphaPercent.coerceIn(0, 100))
            .putInt("card_bg_grayscale_percent", config.cardBgGrayscalePercent.coerceIn(0, 100))
            .putBoolean("compact_bg_follow_system_glass", config.compactBgFollowSystemGlass)
            .putString("title_color_hex", config.titleColorHex)
            .putString("detail_color_hex", config.detailColorHex)
            .putString("btn_bg_hex", config.btnBgHex)
            .putString("accent_color_hex", config.accentColorHex)
            .putFloat("title_size_sp", config.titleSizeSp)
            .putFloat("detail_size_sp", config.detailSizeSp)
            .putFloat("btn_size_sp", config.btnSizeSp)
            .putBoolean("show_accent_bar", config.showAccentBar)
            .putBoolean("show_timestamp", config.showTimestamp)
            .putBoolean("show_actions", config.showActions)
            .putBoolean("render_own_notification_buttons", config.renderOwnNotificationButtons)
            .putInt("banner_btn_size_dp", config.bannerBtnSizeDp.coerceIn(26, 56))
            .putInt("capsule_btn_size_dp", config.capsuleBtnSizeDp.coerceIn(24, 48))
            .apply()
    }
}
