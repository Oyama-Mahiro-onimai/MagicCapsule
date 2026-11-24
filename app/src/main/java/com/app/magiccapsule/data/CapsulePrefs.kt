package com.app.magiccapsule.data

import android.content.Context
import com.app.magiccapsule.model.CapsuleConfig

class CapsulePrefs(context: Context) {

    private val sp = context.getSharedPreferences("magic_capsule_prefs", Context.MODE_PRIVATE)

    fun load(): CapsuleConfig {
        val rawWhitelist = sp.getStringSet("auto_whitelist_packages", emptySet())
        val whitelistCopy = rawWhitelist?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        return CapsuleConfig(
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
            cardLayoutStyle = sp.getString("card_layout_style", CapsuleConfig.LAYOUT_COMPACT_ROW)
                ?: CapsuleConfig.LAYOUT_COMPACT_ROW,
            cardBgHex = sp.getString("card_bg_hex", "#FF0D1F18") ?: "#FF0D1F18",
            titleColorHex = sp.getString("title_color_hex", "#FFECFDF5") ?: "#FFECFDF5",
            detailColorHex = sp.getString("detail_color_hex", "#FF6EE7B7") ?: "#FF6EE7B7",
            btnBgHex = sp.getString("btn_bg_hex", "#FF065F46") ?: "#FF065F46",
            accentColorHex = sp.getString("accent_color_hex", "#FF34D399") ?: "#FF34D399",
            titleSizeSp = sp.getFloat("title_size_sp", 15f).coerceIn(10f, 26f),
            detailSizeSp = sp.getFloat("detail_size_sp", 12f).coerceIn(8f, 20f),
            btnSizeSp = sp.getFloat("btn_size_sp", 12f).coerceIn(9f, 16f),
            showAccentBar = sp.getBoolean("show_accent_bar", false),
            showTimestamp = sp.getBoolean("show_timestamp", false),
            showActions = sp.getBoolean("show_actions", true)
        )
    }

    fun save(config: CapsuleConfig) {
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
            .putString("card_layout_style", config.cardLayoutStyle)
            .putString("card_bg_hex", config.cardBgHex)
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
            .apply()
    }
}
