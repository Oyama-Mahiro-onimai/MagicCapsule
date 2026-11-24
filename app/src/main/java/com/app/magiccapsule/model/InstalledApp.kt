package com.app.magiccapsule.model

import androidx.compose.runtime.Immutable

@Immutable
data class InstalledApp(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean
)
