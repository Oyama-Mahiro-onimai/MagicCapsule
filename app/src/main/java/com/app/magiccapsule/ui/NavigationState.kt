package com.app.magiccapsule.ui

import androidx.annotation.DrawableRes
import com.app.magiccapsule.R

enum class MainTab(
    val title: String,
    @DrawableRes val iconRes: Int,
    @DrawableRes val selectedIconRes: Int
) {
    HOME("概览", R.drawable.ic_px_home, R.drawable.ic_px_home_filled),
    NOTIFICATIONS("通知", R.drawable.ic_px_notifications, R.drawable.ic_px_notifications_filled),
    APPEARANCE("外观", R.drawable.ic_px_palette, R.drawable.ic_px_palette_filled),
    SETTINGS("设置", R.drawable.ic_px_settings, R.drawable.ic_px_settings_filled)
}

sealed interface SubPage {
    data object WhitelistManager : SubPage
    data object BannerHijackSettings : SubPage
    data object ColorEditor : SubPage
    data object TypographyEditor : SubPage
    data object ManualConfig : SubPage
    data object CrashLogViewer : SubPage
    data object RuntimeLogViewer : SubPage
    data class NotificationDetail(val key: String) : SubPage
}
