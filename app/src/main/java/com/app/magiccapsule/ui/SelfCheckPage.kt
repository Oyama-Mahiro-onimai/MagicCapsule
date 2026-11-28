package com.app.magiccapsule.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.app.magiccapsule.R
import com.app.magiccapsule.service.SelfCheckEngine

@Composable
fun SelfCheckPage(
    onOpenRuntimeLog: () -> Unit,
    onStatusMessage: (String) -> Unit
) {
    val ctx = LocalContext.current
    val report by SelfCheckEngine.report.collectAsState()
    val expandedMap = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 顶部汇总与一键全量手动自检卡片
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PixelIconBadge(
                            iconRes = R.drawable.ic_px_check_circle,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            badgeSizeDp = 40
                        )
                        Column {
                            Text(
                                "系统全链路自检中心",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                report.currentStepLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val (badgeBg, badgeFg, badgeLabel) = when {
                        report.isRunning -> Triple(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                            "检测中"
                        )
                        report.items.isEmpty() -> Triple(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            "待自检"
                        )
                        report.failCount > 0 -> Triple(
                            Color(0xFF450A0A),
                            Color(0xFFFCA5A5),
                            "${report.failCount} 项异常"
                        )
                        report.warnCount > 0 -> Triple(
                            Color(0xFF451A03),
                            Color(0xFFFCD34D),
                            "${report.warnCount} 项警告"
                        )
                        else -> Triple(
                            Color(0xFF064E3B),
                            Color(0xFF6EE7B7),
                            "全部通过"
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = badgeBg
                    ) {
                        Text(
                            badgeLabel,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = badgeFg,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (report.isRunning) {
                    LinearProgressIndicator(
                        progress = { report.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (report.items.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SelfCheckMetricChip(
                            label = "通过",
                            value = "${report.passCount}",
                            bg = Color(0xFF064E3B).copy(alpha = 0.25f),
                            fg = Color(0xFF34D399),
                            modifier = Modifier.weight(1f)
                        )
                        SelfCheckMetricChip(
                            label = "警告",
                            value = "${report.warnCount}",
                            bg = Color(0xFF78350F).copy(alpha = 0.25f),
                            fg = Color(0xFFFBBF24),
                            modifier = Modifier.weight(1f)
                        )
                        SelfCheckMetricChip(
                            label = "异常",
                            value = "${report.failCount}",
                            bg = Color(0xFF7F1D1D).copy(alpha = 0.25f),
                            fg = Color(0xFFF87171),
                            modifier = Modifier.weight(1f)
                        )
                        SelfCheckMetricChip(
                            label = "耗时",
                            value = if (report.totalDurationMs > 0L) "${report.totalDurationMs}ms" else "--",
                            bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            fg = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Button(
                    onClick = {
                        SelfCheckEngine.runFullSelfCheck(ctx)
                        onStatusMessage("已启动 17 项全链路手动自检")
                    },
                    enabled = !report.isRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_px_play),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(if (report.isRunning) "自检执行中..." else "一键全量手动自检（17 项全覆盖）")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val txt = SelfCheckEngine.formatReportText(report)
                            val cm = ctx.getSystemService(ClipboardManager::class.java)
                            cm?.setPrimaryClip(ClipData.newPlainText("MagicCapsuleSelfCheck", txt))
                            onStatusMessage("已复制全量自检报告到剪贴板")
                        },
                        enabled = report.items.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("复制自检报告")
                    }
                    OutlinedButton(
                        onClick = onOpenRuntimeLog,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("查看运行日志")
                    }
                }
            }
        }

        // 单项实时手动测试按钮区
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "单项实时手动测试",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "可单独触发顶层横幅、导航横幅原地防闪回刷新、状态栏胶囊多段文本轮换，或仅审计应用内传出数据。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            SelfCheckEngine.runSingleBannerPopupTest(ctx)
                            onStatusMessage("正在执行：弹出横幅通知测试")
                        },
                        enabled = !report.isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("弹出横幅测试", maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = {
                            SelfCheckEngine.runSingleNavBannerPopupTest(ctx)
                            onStatusMessage("正在执行：弹出导航横幅通知测试")
                        },
                        enabled = !report.isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("导航横幅测试", maxLines = 1)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            SelfCheckEngine.runSingleCapsuleRotationTest(ctx)
                            onStatusMessage("正在执行：胶囊轮换文本测试")
                        },
                        enabled = !report.isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("胶囊轮换文本", maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = {
                            SelfCheckEngine.runOutboundDataAuditOnly(ctx)
                            onStatusMessage("正在执行：应用内传出数据校验")
                        },
                        enabled = !report.isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("传出数据体检", maxLines = 1)
                    }
                }
                OutlinedButton(
                    onClick = {
                        SelfCheckEngine.runSingleOverlayProbeTest(ctx)
                        onStatusMessage("正在执行：无 Shizuku 实际悬浮窗深度探针")
                    },
                    enabled = !report.isRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("实测悬浮窗真实权限探针（无 Shizuku 专项检查）")
                }
            }
        }

        // 检测明细分组列表
        if (report.items.isEmpty()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "自检覆盖范围说明",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "• 底层调用与权限：通知/监听/悬浮窗/无障碍/定位/Shizuku、厂商原生胶囊反射句柄、系统属性与 LTPO 帧率、9轴传感器。\n" +
                            "• 传入解析与过滤：微信/QQ 对话清洗、vivo SuperX / 小米 HyperOS 实况解析、LRC 歌词时间轴与智能换行、来电直通仲裁。\n" +
                            "• 应用内传出数据：11 项配色与开关一致性、胶囊 Notification 与协议 Bundle 校验、内存位图防回收/导航坐标 NaN 审计、设备监控遥测、四大地图 URL 编码防崩溃。\n" +
                            "• 实机交互测试：真实弹出横幅通知、弹出导航横幅并原地刷新防闪回、状态栏胶囊 3 帧文本连续轮换并自动恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            SelfCheckEngine.CheckCategory.entries.forEach { category ->
                val group = report.items.filter { it.category == category }
                if (group.isNotEmpty()) {
                    Text(
                        category.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    group.forEach { item ->
                        val defaultExpanded = item.status == SelfCheckEngine.CheckStatus.FAIL ||
                            item.status == SelfCheckEngine.CheckStatus.WARN
                        val isExpanded = expandedMap[item.id] ?: defaultExpanded
                        SelfCheckResultCard(
                            item = item,
                            expanded = isExpanded,
                            onToggle = { expandedMap[item.id] = !isExpanded }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SelfCheckMetricChip(
    label: String,
    value: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bg
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                color = fg,
                fontWeight = FontWeight.Bold
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun SelfCheckResultCard(
    item: SelfCheckEngine.SelfCheckItem,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val (chipBg, chipFg) = when (item.status) {
        SelfCheckEngine.CheckStatus.PASS -> Color(0xFF064E3B) to Color(0xFF6EE7B7)
        SelfCheckEngine.CheckStatus.WARN -> Color(0xFF451A03) to Color(0xFFFCD34D)
        SelfCheckEngine.CheckStatus.FAIL -> Color(0xFF450A0A) to Color(0xFFFCA5A5)
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { onToggle() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${item.durationMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = chipBg
                    ) {
                        Text(
                            item.status.label,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = chipFg,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Text(
                item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (expanded && item.detail.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Text(
                        item.detail,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
