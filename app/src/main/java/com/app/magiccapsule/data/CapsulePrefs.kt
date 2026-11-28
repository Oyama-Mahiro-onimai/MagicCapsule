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
            enableCapsuleEngine = sp.getBoolean("enable_capsule_engine", true),
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
            bannerIgnoreOngoing = sp.getBoolean("banner_ignore_ongoing", true),
            suppressSystemHeadsUp = sp.getBoolean("suppress_system_heads_up", false),
            overlayBannerDurationSec = sp.getInt("overlay_banner_duration_sec", 4).coerceIn(2, 15),
            overlayBannerOffsetXPx = sp.getInt("overlay_banner_offset_x_px", 0).coerceIn(-240, 240),
            overlayBannerOffsetYPx = sp.getInt("overlay_banner_offset_y_px", 0).coerceIn(-120, 360),
            overlayBannerPaddingVPx = sp.getInt("overlay_banner_padding_v_px", 36).coerceIn(8, 120),
            landscapeBannerWidthPercent = sp.getInt("landscape_banner_width_percent", 46).coerceIn(32, 75),
            landscapeBannerOffsetXPx = sp.getInt("landscape_banner_offset_x_px", 0).coerceIn(-400, 400),
            landscapeBannerOffsetYPx = sp.getInt("landscape_banner_offset_y_px", 32).coerceIn(0, 240),
            landscapeBannerPaddingVPx = sp.getInt("landscape_banner_padding_v_px", 14).coerceIn(6, 60),
            portraitCapsuleOffsetXPx = sp.getInt("portrait_capsule_offset_x_px", 0).coerceIn(-120, 120),
            portraitCapsuleOffsetYPx = sp.getInt("portrait_capsule_offset_y_px", 0).coerceIn(-60, 140),
            landscapeCapsuleOffsetXPx = sp.getInt("landscape_capsule_offset_x_px", 0).coerceIn(-160, 160),
            landscapeCapsuleOffsetYPx = sp.getInt("landscape_capsule_offset_y_px", 0).coerceIn(-40, 120),
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
            bannerLyricTextSizePx = sp.getFloat("banner_lyric_text_size_px", 36f).coerceIn(20f, 72f),
            capsuleLyricTextSizePx = sp.getFloat("capsule_lyric_text_size_px", 30f).coerceIn(16f, 56f),
            showAccentBar = sp.getBoolean("show_accent_bar", false),
            showTimestamp = sp.getBoolean("show_timestamp", false),
            showActions = sp.getBoolean("show_actions", true),
            renderOwnNotificationButtons = sp.getBoolean("render_own_notification_buttons", false),
            bannerBtnSizeDp = sp.getInt("banner_btn_size_dp", 34).coerceIn(26, 56),
            capsuleBtnSizeDp = sp.getInt("capsule_btn_size_dp", 40).coerceIn(32, 52),
            enableSystemStatusMonitor = sp.getBoolean("enable_system_status_monitor", false),
            onboardingCompleted = sp.getBoolean("onboarding_completed", false),
            acceptedPolicyVersion = sp.getInt("accepted_policy_version", 0),
            showLaunchSplash = sp.getBoolean("show_launch_splash", true),
            sysContinuousMonitor = sp.getBoolean("sys_continuous_monitor", false),
            sysMonitorIntervalSec = sp.getInt("sys_monitor_interval_sec", 10).coerceIn(3, 60),
            sysCapsuleTitlePrefix = sp.getString("sys_capsule_title_prefix", "设备监控") ?: "设备监控",
            sysTriggerPowerChange = sp.getBoolean("sys_trigger_power_change", true),
            sysTriggerLowBattery = sp.getBoolean("sys_trigger_low_battery", true),
            sysLowBatteryThreshold = sp.getInt("sys_low_battery_threshold", 20).coerceIn(5, 50),
            sysTriggerThermalAlert = sp.getBoolean("sys_trigger_thermal_alert", true),
            sysThermalTempThresholdC = sp.getInt("sys_thermal_temp_threshold_c", 42).coerceIn(36, 52),
            sysTriggerRingerChange = sp.getBoolean("sys_trigger_ringer_change", true),
            sysTriggerNetworkChange = sp.getBoolean("sys_trigger_network_change", false),
            sysTriggerRefreshRateChange = sp.getBoolean("sys_trigger_refresh_rate_change", false),
            sysTriggerHighRam = sp.getBoolean("sys_trigger_high_ram", false),
            sysHighRamThresholdPercent = sp.getInt("sys_high_ram_threshold_percent", 85).coerceIn(60, 95),
            sysTriggerDozeChange = sp.getBoolean("sys_trigger_doze_change", false),
            sysTriggerAllBroadcasts = sp.getBoolean("sys_trigger_all_broadcasts", true),
            sysTriggerBluetooth = sp.getBoolean("sys_trigger_bluetooth", true),
            sysTriggerWifiAirplane = sp.getBoolean("sys_trigger_wifi_airplane", true),
            sysTriggerPowerSave = sp.getBoolean("sys_trigger_power_save", true),
            sysTriggerDnd = sp.getBoolean("sys_trigger_dnd", true),
            sysTriggerHeadsetAudio = sp.getBoolean("sys_trigger_headset_audio", true),
            sysTriggerSystemToggles = sp.getBoolean("sys_trigger_system_toggles", true),
            sysTriggerAppStorage = sp.getBoolean("sys_trigger_app_storage", true),
            sysShowBatteryCurrent = sp.getBoolean("sys_show_battery_current", true),
            sysShowTempThermal = sp.getBoolean("sys_show_temp_thermal", true),
            sysShowRam = sp.getBoolean("sys_show_ram", true),
            sysShowRefreshRate = sp.getBoolean("sys_show_refresh_rate", true),
            sysShowNetwork = sp.getBoolean("sys_show_network", false),
            sysShowDoze = sp.getBoolean("sys_show_doze", false),
            useCompactPillRemoteViews = sp.getBoolean("use_compact_pill_remote_views", true),
            pillTextSizeSp = sp.getFloat("pill_text_size_sp", 10f).coerceIn(8f, 14f),
            portraitPillMaxUnits = sp.getInt("portrait_pill_max_units", 30).coerceIn(12, 48),
            sysMonitorDualTextCarousel = sp.getBoolean("sys_monitor_dual_text_carousel", true),
            sysCapsuleBgHex = sp.getString("sys_capsule_bg_hex", "#FF0F172A") ?: "#FF0F172A",
            sysCapsuleTitleColorHex = sp.getString("sys_capsule_title_hex", "#FFF8FAFC") ?: "#FFF8FAFC",
            sysCapsuleDetailColorHex = sp.getString("sys_capsule_detail_hex", "#FF38BDF8") ?: "#FF38BDF8",
            sysCapsuleAlphaPercent = sp.getInt("sys_capsule_alpha_percent", 95).coerceIn(20, 100),
            sysCapsuleBrandColor = sp.getInt("sys_capsule_brand_color", 2).coerceIn(1, 12),
            enableMediaRecorderCapsule = sp.getBoolean("enable_media_recorder_capsule", true),
            interceptMediaPlayback = sp.getBoolean("intercept_media_playback", true),
            interceptRecorder = sp.getBoolean("intercept_recorder", true),
            mediaCapsuleAutoHideOnPause = sp.getBoolean("media_capsule_auto_hide_on_pause", false),
            useNativeSystemMediaCard = sp.getBoolean("use_native_system_media_card", false),
            showMediaMetadataLyricInCapsule = sp.getBoolean("show_media_metadata_lyric_in_capsule", false),
            navProvider = sp.getString("nav_provider", "amap") ?: "amap",
            navMode = sp.getString("nav_mode", "driving") ?: "driving",
            navAmapKey = sp.getString("nav_amap_key", "") ?: "",
            navTencentKey = sp.getString("nav_tencent_key", "") ?: "",
            navBaiduKey = sp.getString("nav_baidu_key", "") ?: "",
            navLastDestination = sp.getString("nav_last_destination", "") ?: "",
            enableUniversalBannerMode = sp.getBoolean("enable_universal_banner_mode", false),
            universalPillAnchor = sp.getString("universal_pill_anchor", "below_notch") ?: "below_notch",
            universalPillOffsetXPx = sp.getInt("universal_pill_offset_x_px", 0).coerceIn(-360, 360),
            universalPillOffsetYPx = sp.getInt("universal_pill_offset_y_px", 0).coerceIn(0, 240),
            enableBackgroundKeepAlive = sp.getBoolean("enable_background_keep_alive", true)
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
            .putBoolean("enable_capsule_engine", config.enableCapsuleEngine)
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
            .putBoolean("banner_ignore_ongoing", config.bannerIgnoreOngoing)
            .putBoolean("suppress_system_heads_up", config.suppressSystemHeadsUp)
            .putInt("overlay_banner_duration_sec", config.overlayBannerDurationSec.coerceIn(2, 15))
            .putInt("overlay_banner_offset_x_px", config.overlayBannerOffsetXPx.coerceIn(-240, 240))
            .putInt("overlay_banner_offset_y_px", config.overlayBannerOffsetYPx.coerceIn(-120, 360))
            .putInt("overlay_banner_padding_v_px", config.overlayBannerPaddingVPx.coerceIn(8, 120))
            .putInt("landscape_banner_width_percent", config.landscapeBannerWidthPercent.coerceIn(32, 75))
            .putInt("landscape_banner_offset_x_px", config.landscapeBannerOffsetXPx.coerceIn(-400, 400))
            .putInt("landscape_banner_offset_y_px", config.landscapeBannerOffsetYPx.coerceIn(0, 240))
            .putInt("landscape_banner_padding_v_px", config.landscapeBannerPaddingVPx.coerceIn(6, 60))
            .putInt("portrait_capsule_offset_x_px", config.portraitCapsuleOffsetXPx.coerceIn(-120, 120))
            .putInt("portrait_capsule_offset_y_px", config.portraitCapsuleOffsetYPx.coerceIn(-60, 140))
            .putInt("landscape_capsule_offset_x_px", config.landscapeCapsuleOffsetXPx.coerceIn(-160, 160))
            .putInt("landscape_capsule_offset_y_px", config.landscapeCapsuleOffsetYPx.coerceIn(-40, 120))
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
            .putFloat("banner_lyric_text_size_px", config.bannerLyricTextSizePx.coerceIn(20f, 72f))
            .putFloat("capsule_lyric_text_size_px", config.capsuleLyricTextSizePx.coerceIn(16f, 56f))
            .putBoolean("show_accent_bar", config.showAccentBar)
            .putBoolean("show_timestamp", config.showTimestamp)
            .putBoolean("show_actions", config.showActions)
            .putBoolean("render_own_notification_buttons", config.renderOwnNotificationButtons)
            .putInt("banner_btn_size_dp", config.bannerBtnSizeDp.coerceIn(26, 56))
            .putInt("capsule_btn_size_dp", config.capsuleBtnSizeDp.coerceIn(32, 52))
            .putBoolean("enable_system_status_monitor", config.enableSystemStatusMonitor)
            .putBoolean("onboarding_completed", config.onboardingCompleted)
            .putInt("accepted_policy_version", config.acceptedPolicyVersion)
            .putBoolean("show_launch_splash", config.showLaunchSplash)
            .putBoolean("sys_continuous_monitor", config.sysContinuousMonitor)
            .putInt("sys_monitor_interval_sec", config.sysMonitorIntervalSec.coerceIn(3, 60))
            .putString("sys_capsule_title_prefix", config.sysCapsuleTitlePrefix)
            .putBoolean("sys_trigger_power_change", config.sysTriggerPowerChange)
            .putBoolean("sys_trigger_low_battery", config.sysTriggerLowBattery)
            .putInt("sys_low_battery_threshold", config.sysLowBatteryThreshold.coerceIn(5, 50))
            .putBoolean("sys_trigger_thermal_alert", config.sysTriggerThermalAlert)
            .putInt("sys_thermal_temp_threshold_c", config.sysThermalTempThresholdC.coerceIn(36, 52))
            .putBoolean("sys_trigger_ringer_change", config.sysTriggerRingerChange)
            .putBoolean("sys_trigger_network_change", config.sysTriggerNetworkChange)
            .putBoolean("sys_trigger_refresh_rate_change", config.sysTriggerRefreshRateChange)
            .putBoolean("sys_trigger_high_ram", config.sysTriggerHighRam)
            .putInt("sys_high_ram_threshold_percent", config.sysHighRamThresholdPercent.coerceIn(60, 95))
            .putBoolean("sys_trigger_doze_change", config.sysTriggerDozeChange)
            .putBoolean("sys_trigger_all_broadcasts", config.sysTriggerAllBroadcasts)
            .putBoolean("sys_trigger_bluetooth", config.sysTriggerBluetooth)
            .putBoolean("sys_trigger_wifi_airplane", config.sysTriggerWifiAirplane)
            .putBoolean("sys_trigger_power_save", config.sysTriggerPowerSave)
            .putBoolean("sys_trigger_dnd", config.sysTriggerDnd)
            .putBoolean("sys_trigger_headset_audio", config.sysTriggerHeadsetAudio)
            .putBoolean("sys_trigger_system_toggles", config.sysTriggerSystemToggles)
            .putBoolean("sys_trigger_app_storage", config.sysTriggerAppStorage)
            .putBoolean("sys_show_battery_current", config.sysShowBatteryCurrent)
            .putBoolean("sys_show_temp_thermal", config.sysShowTempThermal)
            .putBoolean("sys_show_ram", config.sysShowRam)
            .putBoolean("sys_show_refresh_rate", config.sysShowRefreshRate)
            .putBoolean("sys_show_network", config.sysShowNetwork)
            .putBoolean("sys_show_doze", config.sysShowDoze)
            .putBoolean("use_compact_pill_remote_views", config.useCompactPillRemoteViews)
            .putFloat("pill_text_size_sp", config.pillTextSizeSp.coerceIn(8f, 14f))
            .putInt("portrait_pill_max_units", config.portraitPillMaxUnits.coerceIn(12, 48))
            .putBoolean("sys_monitor_dual_text_carousel", config.sysMonitorDualTextCarousel)
            .putString("sys_capsule_bg_hex", config.sysCapsuleBgHex)
            .putString("sys_capsule_title_hex", config.sysCapsuleTitleColorHex)
            .putString("sys_capsule_detail_hex", config.sysCapsuleDetailColorHex)
            .putInt("sys_capsule_alpha_percent", config.sysCapsuleAlphaPercent.coerceIn(20, 100))
            .putInt("sys_capsule_brand_color", config.sysCapsuleBrandColor.coerceIn(1, 12))
            .putBoolean("enable_media_recorder_capsule", config.enableMediaRecorderCapsule)
            .putBoolean("intercept_media_playback", config.interceptMediaPlayback)
            .putBoolean("intercept_recorder", config.interceptRecorder)
            .putBoolean("media_capsule_auto_hide_on_pause", config.mediaCapsuleAutoHideOnPause)
            .putBoolean("use_native_system_media_card", config.useNativeSystemMediaCard)
            .putBoolean("show_media_metadata_lyric_in_capsule", config.showMediaMetadataLyricInCapsule)
            .putString("nav_provider", config.navProvider)
            .putString("nav_mode", config.navMode)
            .putString("nav_amap_key", config.navAmapKey)
            .putString("nav_tencent_key", config.navTencentKey)
            .putString("nav_baidu_key", config.navBaiduKey)
            .putString("nav_last_destination", config.navLastDestination)
            .putBoolean("enable_universal_banner_mode", config.enableUniversalBannerMode)
            .putString("universal_pill_anchor", config.universalPillAnchor)
            .putInt("universal_pill_offset_x_px", config.universalPillOffsetXPx.coerceIn(-360, 360))
            .putInt("universal_pill_offset_y_px", config.universalPillOffsetYPx.coerceIn(0, 240))
            .putBoolean("enable_background_keep_alive", config.enableBackgroundKeepAlive)
            .apply()
    }
}
