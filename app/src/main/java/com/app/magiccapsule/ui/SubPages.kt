package com.app.magiccapsule.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.magiccapsule.R
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.model.CapturedNotification
import com.app.magiccapsule.model.InstalledApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WhitelistManagerPage(
    installedApps: List<InstalledApp>,
    isLoading: Boolean,
    whitelist: Set<String>,
    onTogglePackage: (String) -> Unit,
    onRefreshApps: () -> Unit,
    onClearWhitelist: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }

    val filtered = remember(installedApps, searchQuery, showSystemApps, whitelist) {
        val q = searchQuery.trim().lowercase()
        installedApps
            .filter { showSystemApps || !it.isSystemApp || it.packageName in whitelist }
            .filter {
                q.isEmpty() ||
                    it.appName.lowercase().contains(q) ||
                    it.packageName.lowercase().contains(q)
            }
            .sortedWith(
                compareByDescending<InstalledApp> { it.packageName in whitelist }
                    .thenBy { it.isSystemApp }
                    .thenBy { it.appName.lowercase() }
            )
    }

    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatusIconRow(
            iconRes = R.drawable.ic_px_shield,
            text = "白名单应用在自动模式下不受刷新频率限制与常驻过滤，且存活期间不会被非白名单通知顶掉。",
            tint = MaterialTheme.colorScheme.primary,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("搜索应用名称或包名") },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_px_search),
                    contentDescription = "搜索",
                    modifier = Modifier.size(20.dp)
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = showSystemApps,
                onClick = { showSystemApps = !showSystemApps },
                label = { Text("显示系统应用") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (whitelist.isNotEmpty()) {
                    TextButton(onClick = onClearWhitelist) { Text("清空(${whitelist.size})") }
                }
                TextButton(onClick = onRefreshApps) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_px_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isLoading) "读取中..." else "刷新(${installedApps.size})")
                }
            }
        }

        if (isLoading && installedApps.isEmpty()) {
            Text(
                text = "正在读取手机已安装应用列表与包名...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (filtered.isEmpty()) {
            Text(
                text = "未找到匹配的应用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val checked = app.packageName in whitelist
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onTogglePackage(app.packageName) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PixelIconBadge(
                                iconRes = if (checked) R.drawable.ic_px_shield else R.drawable.ic_px_apps,
                                containerColor = if (checked) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                contentColor = if (checked) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                badgeSizeDp = 34,
                                iconSizeDp = 18
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (app.isSystemApp) "${app.appName} [系统]" else app.appName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onTogglePackage(app.packageName) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ColorEditorPage(
    config: CapsuleConfig,
    onUpdateConfig: (Boolean, (CapsuleConfig) -> CapsuleConfig) -> Unit,
    onApply: () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("实时效果预览", style = MaterialTheme.typography.labelLarge)
        LiveCardPreview(config)

        Text("颜色参数（支持色块点选或输入 #AARRGGBB）", style = MaterialTheme.typography.labelLarge)
        ColorPickerField("卡片背景色 / 底色", config.cardBgHex, AndroidColor.parseColor("#FF0D1F18")) { hex ->
            onUpdateConfig(false) { it.copy(cardBgHex = hex) }
        }
        ColorPickerField("标题字体颜色", config.titleColorHex, AndroidColor.parseColor("#FFECFDF5")) { hex ->
            onUpdateConfig(false) { it.copy(titleColorHex = hex) }
        }
        ColorPickerField("副文 / 详情字体颜色", config.detailColorHex, AndroidColor.parseColor("#FF6EE7B7")) { hex ->
            onUpdateConfig(false) { it.copy(detailColorHex = hex) }
        }
        ColorPickerField("按钮底色", config.btnBgHex, AndroidColor.parseColor("#FF065F46")) { hex ->
            onUpdateConfig(false) { it.copy(btnBgHex = hex) }
        }
        ColorPickerField("前景色 / 强调色", config.accentColorHex, AndroidColor.parseColor("#FF34D399")) { hex ->
            onUpdateConfig(false) { it.copy(accentColorHex = hex) }
        }

        Button(
            onClick = onApply,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 16.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_px_check_circle),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("保存并立即应用到灵动胶囊")
        }
    }
}

@Composable
fun TypographyEditorPage(
    config: CapsuleConfig,
    onUpdateConfig: (Boolean, (CapsuleConfig) -> CapsuleConfig) -> Unit,
    onApply: () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("实时效果预览（整体展开高度已锁定不变）", style = MaterialTheme.typography.labelLarge)
        LiveCardPreview(config)

        SpSizeControl("标题字号", config.titleSizeSp, 10f..26f) { sp ->
            onUpdateConfig(false) { it.copy(titleSizeSp = sp) }
        }
        SpSizeControl("副文字号", config.detailSizeSp, 8f..20f) { sp ->
            onUpdateConfig(false) { it.copy(detailSizeSp = sp) }
        }
        SpSizeControl("按钮字号", config.btnSizeSp, 9f..16f) { sp ->
            onUpdateConfig(false) { it.copy(btnSizeSp = sp) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    onUpdateConfig(true) {
                        it.copy(titleSizeSp = 15f, detailSizeSp = 12f, btnSizeSp = 12f)
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_px_refresh),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("恢复默认")
            }
            Button(
                onClick = onApply,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_px_check_circle),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("立即应用")
            }
        }
    }
}

@Composable
fun ManualConfigPage(
    config: CapsuleConfig,
    onUpdateConfig: (Boolean, (CapsuleConfig) -> CapsuleConfig) -> Unit,
    onStartCapsule: () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("实时预览", style = MaterialTheme.typography.labelLarge)
        LiveCardPreview(config)

        Text("手动文案", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = config.businessName,
            onValueChange = { v -> onUpdateConfig(false) { it.copy(businessName = v) } },
            label = { Text("业务名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = config.mainText,
            onValueChange = { v -> onUpdateConfig(false) { it.copy(mainText = v) } },
            label = { Text("主文案") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = config.subText,
            onValueChange = { v -> onUpdateConfig(false) { it.copy(subText = v) } },
            label = { Text("副文案 / 展开详情") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("荣耀状态栏胶囊模板", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CapsuleConfig.ALL_TEMPLATES.forEach { (tplKey, tplLabel) ->
                FilterChip(
                    selected = config.template == tplKey,
                    onClick = {
                        onUpdateConfig(false) {
                            it.copy(template = tplKey, enableTimer = tplKey.contains("timer"))
                        }
                    },
                    label = { Text(tplLabel) }
                )
            }
        }

        Text("荣耀胶囊品牌色号（1–12）", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            (1..12).forEach { c ->
                FilterChip(
                    selected = config.brandColor == c,
                    onClick = { onUpdateConfig(false) { it.copy(brandColor = c) } },
                    label = { Text("胶囊色#$c") }
                )
            }
        }

        Button(
            onClick = onStartCapsule,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 16.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_px_play),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("保存并启动自定义灵动胶囊")
        }
    }
}

@Composable
fun NotificationDetailPage(
    item: CapturedNotification?,
    isPinned: Boolean,
    isWhitelisted: Boolean,
    onPinOrUnpin: (CapturedNotification) -> Unit,
    onToggleWhitelist: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (item == null) {
            StatusIconRow(
                iconRes = R.drawable.ic_px_info,
                text = "该通知已被系统移除或已过期。",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return
        }

        val timeStr = remember(item.postTime) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(item.postTime))
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PixelIconBadge(
                        iconRes = if (isWhitelisted) R.drawable.ic_px_shield else R.drawable.ic_px_package,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Column {
                        Text(
                            text = item.appName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = item.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (item.opPkg != item.packageName) {
                    Text("代理包名 (opPkg)：${item.opPkg}", style = MaterialTheme.typography.bodySmall)
                }
                Text("通知 ID：${item.notificationId}", style = MaterialTheme.typography.bodySmall)
                Text("发送时间：$timeStr", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "属性：${if (item.isOngoing) "常驻 / 前台通知" else "普通通知"} · ${if (isWhitelisted) "已在白名单" else "未在白名单"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("通知标题", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("通知正文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.text, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { onPinOrUnpin(item) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_px_pin),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (isPinned) "取消锁定" else "锁定到胶囊")
            }
            OutlinedButton(
                onClick = { onToggleWhitelist(item.packageName) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_px_shield),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (isWhitelisted) "移出白名单" else "加入白名单")
            }
        }
    }
}
