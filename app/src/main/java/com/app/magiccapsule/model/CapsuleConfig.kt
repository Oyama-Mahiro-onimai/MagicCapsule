package com.app.magiccapsule.model

import androidx.compose.runtime.Immutable

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
    val autoMirrorNotifications: Boolean = true
) {
    companion object {
        const val TEMPLATE_IMAGE_TEXT = "image_text"
        const val TEMPLATE_IMAGE_TEXT_TEXT = "image_text_text"
        const val TEMPLATE_IMAGE_TIMER = "image_timer"
        const val TEMPLATE_IMAGE_TEXT_TIMER = "image_text_timer"

        val ALL_TEMPLATES = listOf(
            TEMPLATE_IMAGE_TEXT to "图文",
            TEMPLATE_IMAGE_TEXT_TEXT to "双文案轮播",
            TEMPLATE_IMAGE_TIMER to "图标+计时",
            TEMPLATE_IMAGE_TEXT_TIMER to "图文+计时"
        )
    }
}
