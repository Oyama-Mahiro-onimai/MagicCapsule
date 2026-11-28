package com.app.magiccapsule.ui

import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.app.magiccapsule.service.NotificationIconStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.app.magiccapsule.R
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.crash.GlobalCrashHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.model.CapturedNotification
import com.app.magiccapsule.model.InstalledApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
    // 0=灵动胶囊, 1=顶层横幅, 2=设备监控胶囊（三套颜色完全分离）
    val scopes = remember { listOf("灵动胶囊", "顶层横幅", "设备监控胶囊") }
    var selectedScope by remember { mutableIntStateOf(0) }
    var selectedTarget by remember { mutableIntStateOf(0) }

    val targets = when (selectedScope) {
        0 -> listOf("通知/卡片底色", "标题色", "副文色", "按钮底色", "强调色")
        1 -> listOf("横幅底色", "横幅标题色", "横幅副文色")
        else -> listOf("监控底色", "监控标题色", "监控副文色")
    }
    if (selectedTarget >= targets.size) selectedTarget = 0

    val currentHex = when (selectedScope) {
        0 -> when (selectedTarget) {
            0 -> config.cardBgHex
            1 -> config.titleColorHex
            2 -> config.detailColorHex
            3 -> config.btnBgHex
            else -> config.accentColorHex
        }
        1 -> when (selectedTarget) {
            0 -> config.overlayBannerBgHex
            1 -> config.overlayBannerTitleColorHex
            else -> config.overlayBannerDetailColorHex
        }
        else -> when (selectedTarget) {
            0 -> config.sysCapsuleBgHex
            1 -> config.sysCapsuleTitleColorHex
            else -> config.sysCapsuleDetailColorHex
        }
    }
    val currentFallback = when (selectedScope) {
        0 -> when (selectedTarget) {
            0 -> AndroidColor.parseColor("#FF0D1F18")
            1 -> AndroidColor.parseColor("#FFECFDF5")
            2 -> AndroidColor.parseColor("#FF6EE7B7")
            3 -> AndroidColor.parseColor("#FF065F46")
            else -> AndroidColor.parseColor("#FF34D399")
        }
        1 -> when (selectedTarget) {
            0 -> AndroidColor.parseColor("#FF101816")
            1 -> AndroidColor.parseColor("#FFFFFFFF")
            else -> AndroidColor.parseColor("#FFB8D8CC")
        }
        else -> when (selectedTarget) {
            0 -> AndroidColor.parseColor("#FF0F172A")
            1 -> AndroidColor.parseColor("#FFF8FAFC")
            else -> AndroidColor.parseColor("#FF38BDF8")
        }
    }

    val previewConfig = when (selectedScope) {
        1 -> config.copy(
            cardBgHex = config.overlayBannerBgHex,
            cardBgAlphaPercent = config.overlayBannerAlphaPercent,
            cardBgGrayscalePercent = 0,
            titleColorHex = config.overlayBannerTitleColorHex,
            detailColorHex = config.overlayBannerDetailColorHex
        )
        2 -> config.copy(
            businessName = config.sysCapsuleTitlePrefix.ifBlank { "设备监控" },
            mainText = "23% -837mA",
            subText = "32.1°C · 120Hz · RAM 58%",
            cardBgHex = config.sysCapsuleBgHex,
            cardBgAlphaPercent = config.sysCapsuleAlphaPercent,
            cardBgGrayscalePercent = 0,
            titleColorHex = config.sysCapsuleTitleColorHex,
            detailColorHex = config.sysCapsuleDetailColorHex
        )
        else -> config
    }

    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("选择独立配色对象（三套配色互不干扰）", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            scopes.forEachIndexed { idx, label ->
                FilterChip(
                    selected = selectedScope == idx,
                    onClick = {
                        selectedScope = idx
                        selectedTarget = 0
                    },
                    label = { Text(label) }
                )
            }
        }

        Text("实时效果预览（${scopes[selectedScope]}）", style = MaterialTheme.typography.labelLarge)
        LiveCardPreview(previewConfig)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusIconRow(
                    iconRes = R.drawable.ic_px_tune,
                    text = "${scopes[selectedScope]}背景透明度与专属参数",
                    tint = MaterialTheme.colorScheme.primary
                )
                when (selectedScope) {
                    0 -> {
                        Column {
                            Text(
                                text = "胶囊背景不透明度：${config.cardBgAlphaPercent}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = config.cardBgAlphaPercent.toFloat(),
                                onValueChange = { v ->
                                    onUpdateConfig(false) { it.copy(cardBgAlphaPercent = v.roundToInt().coerceIn(0, 100)) }
                                },
                                onValueChangeFinished = { onUpdateConfig(true) { it } },
                                valueRange = 0f..100f
                            )
                        }
                        Column {
                            Text(
                                text = "胶囊背景灰度：${config.cardBgGrayscalePercent}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = config.cardBgGrayscalePercent.toFloat(),
                                onValueChange = { v ->
                                    onUpdateConfig(false) { it.copy(cardBgGrayscalePercent = v.roundToInt().coerceIn(0, 100)) }
                                },
                                onValueChangeFinished = { onUpdateConfig(true) { it } },
                                valueRange = 0f..100f
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("通知中心跟随系统毛玻璃", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "下拉通知栏背景透明融合系统毛玻璃，顶部胶囊仍保留自定义底色",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = config.compactBgFollowSystemGlass,
                                onCheckedChange = { c -> onUpdateConfig(true) { it.copy(compactBgFollowSystemGlass = c) } }
                            )
                        }
                    }
                    1 -> {
                        Column {
                            Text(
                                text = "横幅背景不透明度：${config.overlayBannerAlphaPercent}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = config.overlayBannerAlphaPercent.toFloat(),
                                onValueChange = { v ->
                                    onUpdateConfig(false) { it.copy(overlayBannerAlphaPercent = v.roundToInt().coerceIn(20, 100)) }
                                },
                                onValueChangeFinished = { onUpdateConfig(true) { it } },
                                valueRange = 20f..100f
                            )
                        }
                    }
                    else -> {
                        Column {
                            Text(
                                text = "监控胶囊背景不透明度：${config.sysCapsuleAlphaPercent}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = config.sysCapsuleAlphaPercent.toFloat(),
                                onValueChange = { v ->
                                    onUpdateConfig(false) { it.copy(sysCapsuleAlphaPercent = v.roundToInt().coerceIn(20, 100)) }
                                },
                                onValueChangeFinished = { onUpdateConfig(true) { it } },
                                valueRange = 20f..100f
                            )
                        }
                        Text("监控状态栏胶囊品牌色号（1–12）：", style = MaterialTheme.typography.bodySmall)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            (1..12).forEach { c ->
                                FilterChip(
                                    selected = config.sysCapsuleBrandColor == c,
                                    onClick = { onUpdateConfig(true) { it.copy(sysCapsuleBrandColor = c) } },
                                    label = { Text("色#$c") }
                                )
                            }
                        }
                    }
                }
            }
        }

        Text("选择要调色的部位", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            targets.forEachIndexed { idx, label ->
                FilterChip(
                    selected = selectedTarget == idx,
                    onClick = { selectedTarget = idx },
                    label = { Text(label) }
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                InteractiveColorBoard(
                    label = targets[selectedTarget],
                    hexValue = currentHex,
                    fallbackColor = currentFallback,
                    onHexChange = { newHex ->
                        onUpdateConfig(false) { cfg ->
                            when (selectedScope) {
                                0 -> when (selectedTarget) {
                                    0 -> cfg.copy(cardBgHex = newHex)
                                    1 -> cfg.copy(titleColorHex = newHex)
                                    2 -> cfg.copy(detailColorHex = newHex)
                                    3 -> cfg.copy(btnBgHex = newHex)
                                    else -> cfg.copy(accentColorHex = newHex)
                                }
                                1 -> when (selectedTarget) {
                                    0 -> cfg.copy(overlayBannerBgHex = newHex)
                                    1 -> cfg.copy(overlayBannerTitleColorHex = newHex)
                                    else -> cfg.copy(overlayBannerDetailColorHex = newHex)
                                }
                                else -> when (selectedTarget) {
                                    0 -> cfg.copy(sysCapsuleBgHex = newHex)
                                    1 -> cfg.copy(sysCapsuleTitleColorHex = newHex)
                                    else -> cfg.copy(sysCapsuleDetailColorHex = newHex)
                                }
                            }
                        }
                    }
                )
            }
        }

        Button(
            onClick = onApply,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_px_check_circle),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("保存并应用配色到灵动胶囊")
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
        Text("胶囊与横幅弹性实时预览", style = MaterialTheme.typography.labelLarge)
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
        PxSizeControl("横幅歌词字号", config.bannerLyricTextSizePx, 20f..72f) { px ->
            onUpdateConfig(false) { it.copy(bannerLyricTextSizePx = px) }
        }
        PxSizeControl("胶囊歌词字号", config.capsuleLyricTextSizePx, 16f..56f) { px ->
            onUpdateConfig(false) { it.copy(capsuleLyricTextSizePx = px) }
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
                        it.copy(titleSizeSp = 15f, detailSizeSp = 12f, btnSizeSp = 12f, bannerLyricTextSizePx = 36f, capsuleLyricTextSizePx = 30f)
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

        val manualCtx = LocalContext.current
        val iconPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                val saved = NotificationIconStore.saveCustomManualIcon(manualCtx, uri)
                if (saved != null) {
                    onUpdateConfig(true) { it.copy(manualIconPreset = CapsuleConfig.ICON_CUSTOM_IMAGE) }
                }
            }
        }

        Text("手动胶囊图标（无通知时展示；有通知时自动替换为通知图标）", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CapsuleConfig.MANUAL_ICON_PRESETS.forEach { (presetKey, presetLabel) ->
                FilterChip(
                    selected = config.manualIconPreset == presetKey,
                    onClick = {
                        if (presetKey == CapsuleConfig.ICON_CUSTOM_IMAGE) {
                            iconPickerLauncher.launch("image/*")
                        } else {
                            onUpdateConfig(true) { it.copy(manualIconPreset = presetKey) }
                        }
                    },
                    label = { Text(presetLabel) }
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("有通知时自动套用横幅样式并替换文案与图标", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "沿用手动胶囊引擎启动，实时替换为来信应用的横幅配色、文本与图标",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.capsuleFollowBannerStyle,
                    onCheckedChange = { c ->
                        onUpdateConfig(true) { it.copy(capsuleFollowBannerStyle = c) }
                    }
                )
            }
        }

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


@Composable
fun BannerHijackSettingsPage(
    state: CapsuleUiState,
    overlayConnected: Boolean,
    overlayStatus: String,
    capsuleStatus: String,
    lastInterceptedSummary: String,
    shizukuAlive: Boolean,
    shizukuGranted: Boolean,
    shizukuStatus: String,
    onRequestShizuku: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onCopyAdbCommand: () -> Unit,
    onToggleBanner: (Boolean, Boolean) -> Unit,
    onDurationChange: (Int) -> Unit,
    onUpdateConfig: (Boolean, (CapsuleConfig) -> CapsuleConfig) -> Unit,
    onTestBanner: () -> Unit,
    onRestoreSystemHeadsUp: () -> Unit
) {
    val config = state.config
    val adbCmd = "adb shell pm grant com.app.magiccapsule android.permission.WRITE_SECURE_SETTINGS"

    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("方案一：无障碍顶层覆盖横幅", style = MaterialTheme.typography.labelLarge)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusIconRow(
                    iconRes = if (overlayConnected) R.drawable.ic_px_check_circle else R.drawable.ic_px_info,
                    text = when {
                        overlayConnected -> "无障碍置顶层服务运行中（TYPE_ACCESSIBILITY_OVERLAY，不读取屏幕内容）"
                        state.hasAccessibilityEnabled -> "无障碍服务已开启，等待系统绑定"
                        else -> "尚未开启「灵动胶囊顶层横幅」无障碍服务"
                    },
                    tint = if (overlayConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                StatusIconRow(
                    iconRes = if (state.hasWriteSecureSettings) R.drawable.ic_px_shield else R.drawable.ic_px_info,
                    text = if (state.hasWriteSecureSettings) {
                        "WRITE_SECURE_SETTINGS 已授权 · 当前系统横幅：${if (state.isSystemHeadsUpEnabled) "开启" else "已屏蔽"}"
                    } else {
                        "未授予 WRITE_SECURE_SETTINGS（可选，授权后可自动屏蔽重复的系统原生横幅）"
                    },
                    tint = if (state.hasWriteSecureSettings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusIconRow(
                    iconRes = if (shizukuGranted) R.drawable.ic_px_check_circle else R.drawable.ic_px_shield,
                    text = "Shizuku：$shizukuStatus",
                    tint = if (shizukuGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onRequestShizuku,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_px_shield),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            state.hasWriteSecureSettings -> "已获得 WRITE_SECURE_SETTINGS（点击复检）"
                            shizukuGranted -> "通过 Shizuku 一键授予系统横幅控制权"
                            shizukuAlive -> "申请 Shizuku 授权并自动配置权限"
                            else -> "检测并申请 Shizuku 授权"
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("开启无障碍服务")
                    }
                    OutlinedButton(
                        onClick = onCopyAdbCommand,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("复制 ADB 命令")
                    }
                }
                Text(
                    text = adbCmd,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

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
                        Text("启用顶层覆盖横幅", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "收到新通知时，在状态栏/挖孔与系统横幅层级之上弹出自定义横幅",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.enableOverlayBanner,
                        onCheckedChange = { c ->
                            onToggleBanner(c, if (c) config.suppressSystemHeadsUp else false)
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("屏蔽重复的系统原生横幅", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "需先通过 ADB 授予 WRITE_SECURE_SETTINGS；服务停止时自动恢复",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.suppressSystemHeadsUp,
                        enabled = state.hasWriteSecureSettings && config.enableOverlayBanner,
                        onCheckedChange = { c -> onToggleBanner(config.enableOverlayBanner, c) }
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "顶层横幅停留时长：${config.overlayBannerDurationSec} 秒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = config.overlayBannerDurationSec.toFloat(),
                        onValueChange = { onDurationChange(it.roundToInt().coerceIn(2, 15)) },
                        valueRange = 2f..15f
                    )
                }
            }
        }

        // 迷你悬浮胶囊屏幕位置适配（避开水滴/挖孔屏）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val pillCtx = androidx.compose.ui.platform.LocalContext.current
                StatusIconRow(
                    iconRes = R.drawable.ic_px_tune,
                    text = "迷你悬浮胶囊屏幕位置（避开水滴/挖孔）",
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "根据手机前摄水滴/挖孔位置选择停靠区域，拖动滑块时可实时预览悬浮位置做到零遮挡。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val anchorOptions = listOf(
                    "below_notch" to "水滴/刘海下方",
                    "top_left" to "顶部靠左",
                    "top_center" to "顶部居中",
                    "top_right" to "顶部靠右"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    anchorOptions.forEach { (key, label) ->
                        FilterChip(
                            selected = config.universalPillAnchor == key,
                            onClick = {
                                val next = config.copy(universalPillAnchor = key)
                                onUpdateConfig(true) { next }
                                com.app.magiccapsule.service.UniversalBannerHost.updatePillPositionLive(pillCtx, next)
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "迷你胶囊左右微调 (X)：${config.universalPillOffsetXPx} px",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = config.universalPillOffsetXPx.toFloat(),
                        onValueChange = { v ->
                            val next = config.copy(universalPillOffsetXPx = v.roundToInt().coerceIn(-360, 360))
                            onUpdateConfig(false) { next }
                            com.app.magiccapsule.service.UniversalBannerHost.updatePillPositionLive(pillCtx, next)
                        },
                        onValueChangeFinished = { onUpdateConfig(true) { it } },
                        valueRange = -360f..360f
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "迷你胶囊上下微调 (Y)：${config.universalPillOffsetYPx} px",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = config.universalPillOffsetYPx.toFloat(),
                        onValueChange = { v ->
                            val next = config.copy(universalPillOffsetYPx = v.roundToInt().coerceIn(0, 240))
                            onUpdateConfig(false) { next }
                            com.app.magiccapsule.service.UniversalBannerHost.updatePillPositionLive(pillCtx, next)
                        },
                        onValueChangeFinished = { onUpdateConfig(true) { it } },
                        valueRange = 0f..240f
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            com.app.magiccapsule.service.UniversalBannerHost.showPositionPreviewPill(pillCtx, config)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("实时预览位置")
                    }
                    OutlinedButton(
                        onClick = {
                            com.app.magiccapsule.service.UniversalBannerHost.dismiss()
                        }
                    ) {
                        Text("关闭预览")
                    }
                    OutlinedButton(
                        onClick = {
                            val reset = config.copy(
                                universalPillAnchor = "below_notch",
                                universalPillOffsetXPx = 0,
                                universalPillOffsetYPx = 0
                            )
                            onUpdateConfig(true) { reset }
                            com.app.magiccapsule.service.UniversalBannerHost.updatePillPositionLive(pillCtx, reset)
                        }
                    ) {
                        Text("重置")
                    }
                }
            }
        }

        // 横幅上下 px 位置与厚度控制
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusIconRow(
                    iconRes = R.drawable.ic_px_tune,
                    text = "横幅上下像素（px）精确调节",
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "竖屏左右位置偏移：${config.overlayBannerOffsetXPx} px",
                            modifier = Modifier.weight(1f).padding(end = 6.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(overlayBannerOffsetXPx = (it.overlayBannerOffsetXPx - 8).coerceIn(-240, 240))
                                    }
                                }
                            ,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("-8px", maxLines = 1, softWrap = false) }
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(overlayBannerOffsetXPx = (it.overlayBannerOffsetXPx + 8).coerceIn(-240, 240))
                                    }
                                }
                            ,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("+8px", maxLines = 1, softWrap = false) }
                        }
                    }
                    Slider(
                        value = config.overlayBannerOffsetXPx.toFloat(),
                        onValueChange = { v ->
                            onUpdateConfig(false) {
                                it.copy(overlayBannerOffsetXPx = v.roundToInt().coerceIn(-240, 240))
                            }
                        },
                        onValueChangeFinished = { onUpdateConfig(true) { it } },
                        valueRange = -240f..240f
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "上下位置偏移：${config.overlayBannerOffsetYPx} px",
                            modifier = Modifier.weight(1f).padding(end = 6.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(overlayBannerOffsetYPx = (it.overlayBannerOffsetYPx - 10).coerceIn(-120, 360))
                                    }
                                }
                            ,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("-10px", maxLines = 1, softWrap = false) }
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(overlayBannerOffsetYPx = (it.overlayBannerOffsetYPx + 10).coerceIn(-120, 360))
                                    }
                                }
                            ,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("+10px", maxLines = 1, softWrap = false) }
                        }
                    }
                    Slider(
                        value = config.overlayBannerOffsetYPx.toFloat(),
                        onValueChange = { v ->
                            onUpdateConfig(false) {
                                it.copy(overlayBannerOffsetYPx = v.roundToInt().coerceIn(-120, 360))
                            }
                        },
                        valueRange = -120f..360f
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "上下内边距（厚度）：${config.overlayBannerPaddingVPx} px",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(overlayBannerPaddingVPx = (it.overlayBannerPaddingVPx - 4).coerceIn(8, 120))
                                    }
                                }
                            ) { Text("-4px") }
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(overlayBannerPaddingVPx = (it.overlayBannerPaddingVPx + 4).coerceIn(8, 120))
                                    }
                                }
                            ) { Text("+4px") }
                        }
                    }
                    Slider(
                        value = config.overlayBannerPaddingVPx.toFloat(),
                        onValueChange = { v ->
                            onUpdateConfig(false) {
                                it.copy(overlayBannerPaddingVPx = v.roundToInt().coerceIn(8, 120))
                            }
                        },
                        valueRange = 8f..120f
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "横屏横幅独立适配（不继承竖屏大边距与挖孔偏移）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "横屏宽度占比：${config.landscapeBannerWidthPercent}%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(landscapeBannerWidthPercent = (it.landscapeBannerWidthPercent - 2).coerceIn(32, 75))
                                    }
                                }
                            ) { Text("-2%") }
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(landscapeBannerWidthPercent = (it.landscapeBannerWidthPercent + 2).coerceIn(32, 75))
                                    }
                                }
                            ) { Text("+2%") }
                        }
                    }
                    Slider(
                        value = config.landscapeBannerWidthPercent.toFloat(),
                        onValueChange = { v ->
                            onUpdateConfig(false) {
                                it.copy(landscapeBannerWidthPercent = v.roundToInt().coerceIn(32, 75))
                            }
                        },
                        onValueChangeFinished = { onUpdateConfig(true) { it } },
                        valueRange = 32f..75f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "横屏左右位置偏移：${config.landscapeBannerOffsetXPx} px",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(landscapeBannerOffsetXPx = (it.landscapeBannerOffsetXPx - 10).coerceIn(-400, 400))
                                    }
                                }
                            ,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("-10px", maxLines = 1, softWrap = false) }
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(landscapeBannerOffsetXPx = (it.landscapeBannerOffsetXPx + 10).coerceIn(-400, 400))
                                    }
                                }
                            ,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("+10px", maxLines = 1, softWrap = false) }
                        }
                    }
                    Slider(
                        value = config.landscapeBannerOffsetXPx.toFloat(),
                        onValueChange = { v ->
                            onUpdateConfig(false) {
                                it.copy(landscapeBannerOffsetXPx = v.roundToInt().coerceIn(-400, 400))
                            }
                        },
                        onValueChangeFinished = { onUpdateConfig(true) { it } },
                        valueRange = -400f..400f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "横屏顶部距离：${config.landscapeBannerOffsetYPx} px",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(landscapeBannerOffsetYPx = (it.landscapeBannerOffsetYPx - 4).coerceIn(0, 240))
                                    }
                                }
                            ) { Text("-4px") }
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(landscapeBannerOffsetYPx = (it.landscapeBannerOffsetYPx + 4).coerceIn(0, 240))
                                    }
                                }
                            ) { Text("+4px") }
                        }
                    }
                    Slider(
                        value = config.landscapeBannerOffsetYPx.toFloat(),
                        onValueChange = { v ->
                            onUpdateConfig(false) {
                                it.copy(landscapeBannerOffsetYPx = v.roundToInt().coerceIn(0, 240))
                            }
                        },
                        onValueChangeFinished = { onUpdateConfig(true) { it } },
                        valueRange = 0f..240f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "横屏上下内边距（厚度）：${config.landscapeBannerPaddingVPx} px",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(landscapeBannerPaddingVPx = (it.landscapeBannerPaddingVPx - 2).coerceIn(6, 60))
                                    }
                                }
                            ) { Text("-2px") }
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(landscapeBannerPaddingVPx = (it.landscapeBannerPaddingVPx + 2).coerceIn(6, 60))
                                    }
                                }
                            ) { Text("+2px") }
                        }
                    }
                    Slider(
                        value = config.landscapeBannerPaddingVPx.toFloat(),
                        onValueChange = { v ->
                            onUpdateConfig(false) {
                                it.copy(landscapeBannerPaddingVPx = v.roundToInt().coerceIn(6, 60))
                            }
                        },
                        onValueChangeFinished = { onUpdateConfig(true) { it } },
                        valueRange = 6f..60f
                    )
                }
            }
        }

        // 横幅独立颜色与不透明度自定义
        val bannerTargets = remember { listOf("横幅底色", "横幅标题色", "横幅副文色") }
        var bannerColorTarget by remember { mutableIntStateOf(0) }
        val bannerCurrentHex = when (bannerColorTarget) {
            0 -> config.overlayBannerBgHex
            1 -> config.overlayBannerTitleColorHex
            else -> config.overlayBannerDetailColorHex
        }
        val bannerFallback = when (bannerColorTarget) {
            0 -> AndroidColor.parseColor("#FF101816")
            1 -> AndroidColor.parseColor("#FFFFFFFF")
            else -> AndroidColor.parseColor("#FFB8D8CC")
        }

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
                    Text("横幅颜色自定义", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    TextButton(
                        onClick = {
                            onUpdateConfig(true) {
                                it.copy(
                                    overlayBannerBgHex = it.cardBgHex,
                                    overlayBannerTitleColorHex = it.titleColorHex,
                                    overlayBannerDetailColorHex = it.detailColorHex
                                )
                            }
                        }
                    ) { Text("同步外观页配色") }
                }

                Column {
                    Text(
                        text = "横幅背景不透明度：${config.overlayBannerAlphaPercent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = config.overlayBannerAlphaPercent.toFloat(),
                        onValueChange = { v ->
                            onUpdateConfig(false) {
                                it.copy(overlayBannerAlphaPercent = v.roundToInt().coerceIn(20, 100))
                            }
                        },
                        valueRange = 20f..100f
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    bannerTargets.forEachIndexed { idx, name ->
                        FilterChip(
                            selected = bannerColorTarget == idx,
                            onClick = { bannerColorTarget = idx },
                            label = { Text(name) }
                        )
                    }
                }

                InteractiveColorBoard(
                    label = bannerTargets[bannerColorTarget],
                    hexValue = bannerCurrentHex,
                    fallbackColor = bannerFallback,
                    onHexChange = { hex ->
                        onUpdateConfig(false) { cfg ->
                            when (bannerColorTarget) {
                                0 -> cfg.copy(overlayBannerBgHex = hex)
                                1 -> cfg.copy(overlayBannerTitleColorHex = hex)
                                else -> cfg.copy(overlayBannerDetailColorHex = hex)
                            }
                        }
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onTestBanner,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_px_play),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("测试弹出横幅")
            }
            OutlinedButton(
                onClick = onRestoreSystemHeadsUp,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_px_refresh),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("恢复原生横幅")
            }
        }

        StatusIconRow(
            iconRes = R.drawable.ic_px_info,
            text = "监听捕获：$lastInterceptedSummary\n顶层横幅：$overlayStatus\n灵动胶囊：$capsuleStatus",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


@Composable
fun CrashLogViewerPage(
    onStatusMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var logText by remember { mutableStateOf("正在读取崩溃日志...") }
    var reloadTick by remember { mutableIntStateOf(0) }
    var showCrashConfirm by remember { mutableStateOf(false) }
    val logPath = remember { GlobalCrashHandler.getLogFile(context).absolutePath }

    if (showCrashConfirm) {
        AlertDialog(
            onDismissRequest = { showCrashConfirm = false },
            title = { Text("确认触发模拟崩溃？") },
            text = { Text("将抛出一条测试未捕获异常，用来验证 GlobalCrashHandler 是否正常弹窗提示、写入 crash_log.txt 并安全退出。重启应用后回到此页即可核对日志。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCrashConfirm = false
                        val ver = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown" }.getOrDefault("unknown"); throw RuntimeException("MagicCapsule 手动崩溃自检测试 (v$ver)")
                    }
                ) { Text("确认触发", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showCrashConfirm = false }) { Text("取消") }
            }
        )
    }

    LaunchedEffect(reloadTick) {
        logText = withContext(Dispatchers.IO) {
            GlobalCrashHandler.readCrashLog(context).ifBlank {
                "暂无崩溃日志记录（应用运行稳定）。"
            }
        }
    }

    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "日志文件：$logPath",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { reloadTick++ },
                modifier = Modifier.weight(1f)
            ) { Text("刷新") }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(logText))
                    onStatusMessage("已复制崩溃日志到剪贴板")
                },
                modifier = Modifier.weight(1f)
            ) { Text("复制") }
            OutlinedButton(
                onClick = {
                    GlobalCrashHandler.clearCrashLog(context)
                    reloadTick++
                    onStatusMessage("已清空崩溃日志")
                },
                modifier = Modifier.weight(1f)
            ) { Text("清空") }
        }
        Button(
            onClick = { showCrashConfirm = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("手动触发模拟崩溃（自检崩溃捕获与日志写入）")
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = logText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}


@Composable
fun RuntimeLogViewerPage(
    onStatusMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val filters = remember { listOf("全部", "Notif", "Capsule", "Banner", "[E/") }
    val filterLabels = remember { listOf("全部", "通知监听", "灵动胶囊", "顶层横幅", "仅错误") }
    var selectedFilter by remember { mutableIntStateOf(0) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(reloadTick) {
        lines = withContext(Dispatchers.IO) { AppLogger.readLogs(context) }
    }

    val filteredLines = remember(lines, selectedFilter) {
        val key = filters[selectedFilter]
        if (selectedFilter == 0) lines else lines.filter { it.contains(key) }
    }
    val displayText = remember(filteredLines) {
        if (filteredLines.isEmpty()) "暂无匹配的运行日志。" else filteredLines.joinToString("\n")
    }

    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filterLabels.forEachIndexed { idx, label ->
                FilterChip(
                    selected = selectedFilter == idx,
                    onClick = { selectedFilter = idx },
                    label = { Text(label) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { reloadTick++ },
                modifier = Modifier.weight(1f)
            ) { Text("刷新(${filteredLines.size})") }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(displayText))
                    onStatusMessage("已复制运行日志到剪贴板")
                },
                modifier = Modifier.weight(1f)
            ) { Text("复制") }
            OutlinedButton(
                onClick = {
                    AppLogger.clear(context)
                    reloadTick++
                    onStatusMessage("已清空运行日志")
                },
                modifier = Modifier.weight(1f)
            ) { Text("清空") }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
