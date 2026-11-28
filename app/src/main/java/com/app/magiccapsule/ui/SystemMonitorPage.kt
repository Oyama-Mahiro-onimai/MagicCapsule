package com.app.magiccapsule.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.app.magiccapsule.R
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.model.SystemStatusSnapshot
import com.app.magiccapsule.service.SystemPropertyProfile
import com.app.magiccapsule.service.SystemStatusMonitor
import kotlin.math.roundToInt

@Composable
fun SystemMonitorPage(
    config: CapsuleConfig,
    snapshot: SystemStatusSnapshot,
    onUpdateMonitorConfig: ((CapsuleConfig) -> CapsuleConfig) -> Unit,
    onRefreshNow: () -> Unit,
    onPushToCapsule: () -> Unit,
    onRequestShizuku: () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 实时状态与胶囊预览
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatusIconRow(
                        iconRes = R.drawable.ic_px_battery,
                        text = if (snapshot.shizukuEnhanced) "实时状态（Shizuku 增强已就绪）" else "实时状态（基础内核直读）",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(snapshot.updatedAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = "胶囊预览：${config.sysCapsuleTitlePrefix.ifBlank { "设备监控" }} · ${SystemStatusMonitor.buildCustomTitle(snapshot, config)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = SystemStatusMonitor.buildCustomSummaryLine(snapshot, config),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!snapshot.shizukuEnhanced) {
                    OutlinedButton(onClick = onRequestShizuku, modifier = Modifier.fillMaxWidth()) {
                        Text("授权 Shizuku 以解锁 CPU 温区与 Doze 状态")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onRefreshNow, modifier = Modifier.weight(1f)) {
                        Text("立即刷新")
                    }
                    Button(onClick = onPushToCapsule, modifier = Modifier.weight(1f)) {
                        Text("推送到胶囊")
                    }
                }
            }
        }

        // 1.5 系统属性表与硬件自适应摘要（SystemProperties + Settings）
        val sysPropCtx = LocalContext.current
        val sysPropEntries = remember(snapshot.updatedAt) {
            SystemPropertyProfile.collectDiagnosticEntries(sysPropCtx)
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusIconRow(
                    iconRes = R.drawable.ic_px_info,
                    text = "系统属性自适应（SystemProperties）",
                    tint = MaterialTheme.colorScheme.secondary
                )
                sysPropEntries.forEach { (k, v) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // 2. 常时监控与自定义指标
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusIconRow(
                    iconRes = R.drawable.ic_px_tune,
                    text = "常时监控与胶囊展示字段自定义",
                    tint = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("开启常时监控（常驻胶囊实时刷新）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "仅亮屏时按间隔刷新，息屏自动休眠；若已手动锁定音乐等通知则不会覆盖",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.sysContinuousMonitor,
                        onCheckedChange = { c -> onUpdateMonitorConfig { it.copy(sysContinuousMonitor = c) } }
                    )
                }
                if (config.sysContinuousMonitor) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "常时监控采样间隔：${config.sysMonitorIntervalSec} 秒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = config.sysMonitorIntervalSec.toFloat(),
                            onValueChange = { v ->
                                onUpdateMonitorConfig { it.copy(sysMonitorIntervalSec = v.roundToInt().coerceIn(3, 60)) }
                            },
                            valueRange = 3f..60f
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("监控数据自动走双文案轮播（image_text_text）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "自动拆分为主文案（如 23% -837mA）与副文案（如 32.1°C 120Hz）在胶囊中交替轮播",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.sysMonitorDualTextCarousel,
                        onCheckedChange = { c -> onUpdateMonitorConfig { it.copy(sysMonitorDualTextCarousel = c) } }
                    )
                }

                OutlinedTextField(
                    value = config.sysCapsuleTitlePrefix,
                    onValueChange = { v -> onUpdateMonitorConfig { it.copy(sysCapsuleTitlePrefix = v) } },
                    label = { Text("监控胶囊业务名称前缀") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("选择在胶囊中展示的指标字段：", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = config.sysShowBatteryCurrent,
                        onClick = { onUpdateMonitorConfig { it.copy(sysShowBatteryCurrent = !it.sysShowBatteryCurrent) } },
                        label = { Text("电量/电流") }
                    )
                    FilterChip(
                        selected = config.sysShowTempThermal,
                        onClick = { onUpdateMonitorConfig { it.copy(sysShowTempThermal = !it.sysShowTempThermal) } },
                        label = { Text("温度/温控") }
                    )
                    FilterChip(
                        selected = config.sysShowRam,
                        onClick = { onUpdateMonitorConfig { it.copy(sysShowRam = !it.sysShowRam) } },
                        label = { Text("内存 RAM") }
                    )
                    FilterChip(
                        selected = config.sysShowRefreshRate,
                        onClick = { onUpdateMonitorConfig { it.copy(sysShowRefreshRate = !it.sysShowRefreshRate) } },
                        label = { Text("刷新率 Hz") }
                    )
                    FilterChip(
                        selected = config.sysShowNetwork,
                        onClick = { onUpdateMonitorConfig { it.copy(sysShowNetwork = !it.sysShowNetwork) } },
                        label = { Text("网络带宽") }
                    )
                    FilterChip(
                        selected = config.sysShowDoze,
                        onClick = { onUpdateMonitorConfig { it.copy(sysShowDoze = !it.sysShowDoze) } },
                        label = { Text("Doze 状态") }
                    )
                }
            }
        }

        // 3. 自定义状态变化事件触发胶囊弹出
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("状态变化自动弹胶囊总开关", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "按下方勾选的条件在设备状态变化时自动弹出灵动胶囊",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.enableSystemStatusMonitor,
                        onCheckedChange = { c -> onUpdateMonitorConfig { it.copy(enableSystemStatusMonitor = c) } }
                    )
                }

                TriggerToggleRow(
                    "监听全量系统状态广播（总开关）",
                    "开启后按下方勾选的细分广播分类实时弹出胶囊（媒体播放中短时插播并自动恢复）",
                    config.sysTriggerAllBroadcasts
                ) { c ->
                    onUpdateMonitorConfig { it.copy(sysTriggerAllBroadcasts = c) }
                }
                if (config.sysTriggerAllBroadcasts) {
                    TriggerToggleRow("蓝牙开关与设备连接/断开", "蓝牙启停及蓝牙耳机/外设连接或断开时弹出", config.sysTriggerBluetooth) { c ->
                        onUpdateMonitorConfig { it.copy(sysTriggerBluetooth = c) }
                    }
                    TriggerToggleRow("Wi-Fi 与飞行模式切换", "Wi-Fi 启停或飞行模式切换时弹出", config.sysTriggerWifiAirplane) { c ->
                        onUpdateMonitorConfig { it.copy(sysTriggerWifiAirplane = c) }
                    }
                    TriggerToggleRow("省电模式与系统低电量警告", "系统省电模式开关、低电量警告及电量恢复时弹出", config.sysTriggerPowerSave) { c ->
                        onUpdateMonitorConfig { it.copy(sysTriggerPowerSave = c) }
                    }
                    TriggerToggleRow("勿扰模式状态变更", "开启优先/闹钟/完全静音或关闭勿扰时弹出", config.sysTriggerDnd) { c ->
                        onUpdateMonitorConfig { it.copy(sysTriggerDnd = c) }
                    }
                    TriggerToggleRow("有线耳机插拔与音频切回扬声器", "耳机插拔或音频输出切换至扬声器时弹出", config.sysTriggerHeadsetAudio) { c ->
                        onUpdateMonitorConfig { it.copy(sysTriggerHeadsetAudio = c) }
                    }
                    TriggerToggleRow("NFC / 定位服务 / 深色模式 / 时区语言", "NFC、系统定位开关、深色/浅色主题或时区变更时弹出", config.sysTriggerSystemToggles) { c ->
                        onUpdateMonitorConfig { it.copy(sysTriggerSystemToggles = c) }
                    }
                    TriggerToggleRow("应用安装卸载 / 底座 / 存储空间预警", "安装/更新/卸载应用、连接底座或存储空间不足时弹出", config.sysTriggerAppStorage) { c ->
                        onUpdateMonitorConfig { it.copy(sysTriggerAppStorage = c) }
                    }
                }
                RecentSystemBroadcastEventsSection()
                TriggerToggleRow("充放电接入 / 断开", "接入快充、USB/无线充或拔出电源时弹出", config.sysTriggerPowerChange) { c ->
                    onUpdateMonitorConfig { it.copy(sysTriggerPowerChange = c) }
                }
                TriggerToggleRow("低电量自定义预警", "未充电且电量低于阈值时弹出提醒", config.sysTriggerLowBattery) { c ->
                    onUpdateMonitorConfig { it.copy(sysTriggerLowBattery = c) }
                }
                if (config.sysTriggerLowBattery) {
                    Text(
                        text = "低电量触发阈值：${config.sysLowBatteryThreshold}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = config.sysLowBatteryThreshold.toFloat(),
                        onValueChange = { v ->
                            onUpdateMonitorConfig { it.copy(sysLowBatteryThreshold = v.roundToInt().coerceIn(5, 50)) }
                        },
                        valueRange = 5f..50f
                    )
                }

                TriggerToggleRow("高温 / 系统温控预警", "系统温控达偏热以上或电池温度超阈值时弹出", config.sysTriggerThermalAlert) { c ->
                    onUpdateMonitorConfig { it.copy(sysTriggerThermalAlert = c) }
                }
                if (config.sysTriggerThermalAlert) {
                    Text(
                        text = "温度触发阈值：${config.sysThermalTempThresholdC}°C",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = config.sysThermalTempThresholdC.toFloat(),
                        onValueChange = { v ->
                            onUpdateMonitorConfig { it.copy(sysThermalTempThresholdC = v.roundToInt().coerceIn(36, 52)) }
                        },
                        valueRange = 36f..52f
                    )
                }

                TriggerToggleRow("响铃 / 震动 / 静音切换", "切换系统情景模式时弹出确认", config.sysTriggerRingerChange) { c ->
                    onUpdateMonitorConfig { it.copy(sysTriggerRingerChange = c) }
                }
                TriggerToggleRow("网络切换（Wi-Fi / 移动数据 / 离线）", "网络类型变更时弹出提示", config.sysTriggerNetworkChange) { c ->
                    onUpdateMonitorConfig { it.copy(sysTriggerNetworkChange = c) }
                }
                TriggerToggleRow("屏幕刷新率切换（Hz）", "LTPO 刷新率档位变化时弹出提示", config.sysTriggerRefreshRateChange) { c ->
                    onUpdateMonitorConfig { it.copy(sysTriggerRefreshRateChange = c) }
                }
                TriggerToggleRow("高内存占用预警（RAM）", "系统内存占用超过阈值时弹出提醒", config.sysTriggerHighRam) { c ->
                    onUpdateMonitorConfig { it.copy(sysTriggerHighRam = c) }
                }
                if (config.sysTriggerHighRam) {
                    Text(
                        text = "内存占用触发阈值：${config.sysHighRamThresholdPercent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = config.sysHighRamThresholdPercent.toFloat(),
                        onValueChange = { v ->
                            onUpdateMonitorConfig { it.copy(sysHighRamThresholdPercent = v.roundToInt().coerceIn(60, 95)) }
                        },
                        valueRange = 60f..95f
                    )
                }
                TriggerToggleRow("Shizuku Doze 状态变更", "需已授权 Shizuku，检测到深睡状态变化时弹出", config.sysTriggerDozeChange) { c ->
                    onUpdateMonitorConfig { it.copy(sysTriggerDozeChange = c) }
                }
            }
        }
    }
}

@Composable
private fun TriggerToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}


@Composable
private fun RecentSystemBroadcastEventsSection() {
    val events by SystemStatusMonitor.recentBroadcastEvents.collectAsState()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "最近捕获的系统广播记录（实时更新）",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (events.isEmpty()) {
                Text(
                    text = "暂无新广播事件，切换蓝牙/Wi-Fi/勿扰/深色模式或插拔电源后将在此实时记录并弹胶囊。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                events.forEach { line ->
                    Text(
                        text = "• $line",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
