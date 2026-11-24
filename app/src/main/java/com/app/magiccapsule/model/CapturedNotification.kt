package com.app.magiccapsule.model

import androidx.compose.runtime.Immutable

@Immutable
data class CapturedNotification(
    val key: String,
    val packageName: String,
    val opPkg: String = packageName,
    val notificationId: Int = 0,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val isOngoing: Boolean = false
)
