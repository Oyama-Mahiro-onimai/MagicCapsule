package com.app.magiccapsule.data

import android.content.Context
import com.app.magiccapsule.model.CapsuleConfig

class CapsulePrefs(context: Context) {

    private val sp = context.getSharedPreferences("magic_capsule_prefs", Context.MODE_PRIVATE)

    fun load(): CapsuleConfig = CapsuleConfig(
        businessName = sp.getString("business_name", "通知") ?: "通知",
        mainText = sp.getString("main_text", "等待新通知") ?: "等待新通知",
        subText = sp.getString("sub_text", "点击卡片返回应用") ?: "点击卡片返回应用",
        template = sp.getString("template", CapsuleConfig.TEMPLATE_IMAGE_TEXT)
            ?: CapsuleConfig.TEMPLATE_IMAGE_TEXT,
        brandColor = sp.getInt("brand_color", 1).coerceIn(1, 12),
        enableTimer = sp.getBoolean("enable_timer", false),
        timerMinutes = sp.getInt("timer_minutes", 5).coerceAtLeast(1),
        refreshIntervalSec = sp.getInt("refresh_interval_sec", 30).coerceIn(10, 300),
        autoMirrorNotifications = sp.getBoolean("auto_mirror_notifications", true)
    )

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
            .apply()
    }
}
