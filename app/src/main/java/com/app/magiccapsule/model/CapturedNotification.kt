package com.app.magiccapsule.model

import android.app.NotificationManager
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
    val isOngoing: Boolean = false,
    val importance: Int = NotificationManager.IMPORTANCE_DEFAULT,
    val channelName: String = "",
    val isCall: Boolean = false,
    val isSilent: Boolean = false,
    val actionTitles: List<String> = emptyList()
) {
    fun importanceRank(): Int = when {
        isCall -> 4
        !isSilent && importance >= NotificationManager.IMPORTANCE_HIGH -> 3
        !isSilent && importance >= NotificationManager.IMPORTANCE_DEFAULT -> 2
        else -> 1
    }

    fun importanceLabel(): String = when {
        isCall -> "来电"
        !isSilent && importance >= NotificationManager.IMPORTANCE_HIGH -> "重要"
        !isSilent && importance >= NotificationManager.IMPORTANCE_DEFAULT -> "普通"
        else -> "静默"
    }
}
