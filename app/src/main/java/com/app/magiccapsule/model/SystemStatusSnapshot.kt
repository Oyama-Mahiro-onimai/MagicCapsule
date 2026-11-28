package com.app.magiccapsule.model

import androidx.compose.runtime.Immutable

@Immutable
data class SystemStatusSnapshot(
    val batteryPercent: Int = 0,
    val isCharging: Boolean = false,
    val chargeType: String = "未充电",
    val batteryTempC: Float = 0f,
    val batteryVoltageMv: Int = 0,
    val batteryCurrentMa: String = "--",
    val thermalLabel: String = "正常",
    val refreshRateLabel: String = "--",
    val ramUsedGb: Float = 0f,
    val ramTotalGb: Float = 0f,
    val networkLabel: String = "检测中",
    val linkSpeedMbps: Int = 0,
    val ringerLabel: String = "正常",
    val mediaVolumePercent: Int = 0,
    val shizukuEnhanced: Boolean = false,
    val dozeState: String = "--",
    val cpuTempLabel: String = "--",
    val updatedAt: String = "未刷新"
)
