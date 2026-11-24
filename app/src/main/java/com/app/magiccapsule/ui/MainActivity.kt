package com.app.magiccapsule.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.magiccapsule.R
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.model.CapturedNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = if (darkTheme) {
                dynamicDarkColorScheme(this)
            } else {
                dynamicLightColorScheme(this)
            }
            MaterialTheme(colorScheme = colorScheme) {
                CapsuleAppRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapsuleAppRoot(vm: CapsuleViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val notifications by vm.capturedNotifications.collectAsState()
    val listenerConnected by vm.listenerConnected.collectAsState()
    val pinnedKey by vm.pinnedKey.collectAsState()
    val activeSourcePackage by vm.activeSourcePackage.collectAsState()
    val installedApps by vm.installedApps.collectAsState()
    val isLoadingApps by vm.isLoadingApps.collectAsState()
    val config = state.config
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    var activeSubPage by remember { mutableStateOf<SubPage?>(null) }

    BackHandler(enabled = activeSubPage != null) {
        activeSubPage = null
    }

    val appNameMap = remember(installedApps) {
        installedApps.associate { it.packageName to it.appName }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.recheckNotificationAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startCapsule() else vm.setStatus("未授予通知权限，无法展示灵动胶囊")
    }

    fun runWithNotifPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    fun openNotificationAccessSettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.onFailure {
            vm.setStatus("请前往：设置 → 隐私/权限 → 通知使用权 → 开启「灵动胶囊助手」")
        }
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOpt() {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
            vm.setStatus("已在电池优化白名单中")
            return
        }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        }.onFailure {
            vm.setStatus("请手动在系统「应用启动管理」中允许后台活动")
        }
    }

    val topBarTitle = when (activeSubPage) {
        null -> when (currentTab) {
            MainTab.HOME -> "灵动胶囊助手 v18"
            MainTab.NOTIFICATIONS -> "通知与白名单"
            MainTab.APPEARANCE -> "卡片外观定制"
            MainTab.SETTINGS -> "系统与高级设置"
        }
        SubPage.WhitelistManager -> "应用列表白名单"
        SubPage.ColorEditor -> "颜色高度自定义"
        SubPage.TypographyEditor -> "文字 sp 字号调节"
        SubPage.ManualConfig -> "手动文案与模板"
        is SubPage.NotificationDetail -> "通知来源详情"
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text(topBarTitle) },
                navigationIcon = {
                    if (activeSubPage != null) {
                        IconButton(onClick = { activeSubPage = null }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_px_arrow_back),
                                contentDescription = "返回",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (activeSubPage == null) {
                NavigationBar {
                    MainTab.entries.forEach { tab ->
                        val selected = currentTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { currentTab = tab },
                            icon = {
                                Icon(
                                    painter = painterResource(
                                        id = if (selected) tab.selectedIconRes else tab.iconRes
                                    ),
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = { Text(tab.title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            when (val sub = activeSubPage) {
                SubPage.WhitelistManager -> {
                    WhitelistManagerPage(
                        installedApps = installedApps,
                        isLoading = isLoadingApps,
                        whitelist = config.autoWhitelistPackages,
                        onTogglePackage = { vm.toggleWhitelistPackage(it) },
                        onRefreshApps = { vm.loadInstalledApps() },
                        onClearWhitelist = { vm.clearWhitelist() }
                    )
                }
                SubPage.ColorEditor -> {
                    ColorEditorPage(
                        config = config,
                        onUpdateConfig = { saveNow, transform -> vm.updateConfig(saveNow, transform) },
                        onApply = { runWithNotifPermission { vm.refreshAll() } }
                    )
                }
                SubPage.TypographyEditor -> {
                    TypographyEditorPage(
                        config = config,
                        onUpdateConfig = { saveNow, transform -> vm.updateConfig(saveNow, transform) },
                        onApply = { runWithNotifPermission { vm.refreshAll() } }
                    )
                }
                SubPage.ManualConfig -> {
                    ManualConfigPage(
                        config = config,
                        onUpdateConfig = { saveNow, transform -> vm.updateConfig(saveNow, transform) },
                        onStartCapsule = { runWithNotifPermission { vm.startCapsule() } }
                    )
                }
                is SubPage.NotificationDetail -> {
                    val item = notifications.find { it.key == sub.key }
                    NotificationDetailPage(
                        item = item,
                        isPinned = item != null && item.key == pinnedKey,
                        isWhitelisted = item != null && item.packageName in config.autoWhitelistPackages,
                        onPinOrUnpin = { target ->
                            runWithNotifPermission {
                                if (target.key == pinnedKey) vm.unpinNotification()
                                else vm.selectAndPinNotification(target)
                            }
                        },
                        onToggleWhitelist = { pkg -> vm.toggleWhitelistPackage(pkg) }
                    )
                }
                null -> {
                    when (currentTab) {
                        MainTab.HOME -> HomeTabContent(
                            state = state,
                            listenerConnected = listenerConnected,
                            activeSourcePackage = activeSourcePackage,
                            appNameMap = appNameMap,
                            installedCount = installedApps.size,
                            onOpenNotifSettings = { openNotificationAccessSettings() },
                            onRefreshAll = { runWithNotifPermission { vm.refreshAll() } },
                            onStartManual = { runWithNotifPermission { vm.startCapsule() } },
                            onStopCapsule = { vm.stopCapsule() },
                            onOpenSubPage = { activeSubPage = it }
                        )
                        MainTab.NOTIFICATIONS -> NotificationsTabContent(
                            config = config,
                            notifications = notifications,
                            pinnedKey = pinnedKey,
                            appNameMap = appNameMap,
                            installedCount = installedApps.size,
                            onUpdateConfig = { saveNow, transform -> vm.updateConfig(saveNow, transform) },
                            onToggleWhitelist = { vm.toggleWhitelistPackage(it) },
                            onPinOrUnpin = { item, isSelected ->
                                runWithNotifPermission {
                                    if (isSelected) vm.unpinNotification() else vm.selectAndPinNotification(item)
                                }
                            },
                            onUnpin = { vm.unpinNotification() },
                            onRefresh = { vm.refreshAll() },
                            onOpenWhitelistPage = { activeSubPage = SubPage.WhitelistManager },
                            onOpenNotificationDetail = { key -> activeSubPage = SubPage.NotificationDetail(key) }
                        )
                        MainTab.APPEARANCE -> AppearanceTabContent(
                            config = config,
                            onUpdateConfig = { saveNow, transform -> vm.updateConfig(saveNow, transform) },
                            onApply = { runWithNotifPermission { vm.refreshAll() } },
                            onOpenColorEditor = { activeSubPage = SubPage.ColorEditor },
                            onOpenTypographyEditor = { activeSubPage = SubPage.TypographyEditor }
                        )
                        MainTab.SETTINGS -> SettingsTabContent(
                            config = config,
                            isFrameworkSupported = state.isFrameworkSupported,
                            installedCount = installedApps.size,
                            statusMessage = state.statusMessage,
                            onUpdateConfig = { saveNow, transform -> vm.updateConfig(saveNow, transform) },
                            onOpenManualConfig = { activeSubPage = SubPage.ManualConfig },
                            onOpenNotifSettings = { openNotificationAccessSettings() },
                            onRequestIgnoreBatteryOpt = { requestIgnoreBatteryOpt() },
                            onStopCapsule = { vm.stopCapsule() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTabContent(
    state: CapsuleUiState,
    listenerConnected: Boolean,
    activeSourcePackage: String?,
    appNameMap: Map<String, String>,
    installedCount: Int,
    onOpenNotifSettings: () -> Unit,
    onRefreshAll: () -> Unit,
    onStartManual: () -> Unit,
    onStopCapsule: () -> Unit,
    onOpenSubPage: (SubPage) -> Unit
) {
    val config = state.config
    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("展开卡片实时预览（整体高度已锁定）", style = MaterialTheme.typography.labelLarge)
        LiveCardPreview(config)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusIconRow(
                    iconRes = if (state.isFrameworkSupported) R.drawable.ic_px_check_circle else R.drawable.ic_px_info,
                    text = if (state.isFrameworkSupported) {
                        "荣耀 framework-magic 接口可用"
                    } else {
                        "未检测到 framework-magic"
                    },
                    tint = if (state.isFrameworkSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                StatusIconRow(
                    iconRes = if (state.hasNotificationAccess && listenerConnected) {
                        R.drawable.ic_px_notifications_filled
                    } else {
                        R.drawable.ic_px_notifications
                    },
                    text = when {
                        state.hasNotificationAccess && listenerConnected ->
                            "通知使用权已授权（监听服务运行中）"
                        state.hasNotificationAccess ->
                            "通知使用权已授权（等待系统绑定监听）"
                        else ->
                            "尚未授予「通知使用权」，无法读取其他应用通知"
                    },
                    tint = if (state.hasNotificationAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusIconRow(
                    iconRes = R.drawable.ic_px_package,
                    text = if (activeSourcePackage != null) {
                        val srcName = appNameMap[activeSourcePackage] ?: activeSourcePackage
                        "当前胶囊来源：$srcName ($activeSourcePackage)"
                    } else {
                        "当前胶囊来源：手动模式 / 暂无镜像通知"
                    },
                    tint = MaterialTheme.colorScheme.primary,
                    textColor = MaterialTheme.colorScheme.primary
                )
                if (!state.hasNotificationAccess) {
                    OutlinedButton(
                        onClick = onOpenNotifSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_px_notifications),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("前往开启通知使用权")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(onClick = onRefreshAll, modifier = Modifier.weight(1f)) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_px_refresh),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("刷新胶囊")
            }
            OutlinedButton(onClick = onStartManual, modifier = Modifier.weight(1f)) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_px_play),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("手动胶囊")
            }
        }
        OutlinedButton(onClick = onStopCapsule, modifier = Modifier.fillMaxWidth()) {
            Icon(
                painter = painterResource(id = R.drawable.ic_px_stop),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("停止灵动胶囊服务")
        }

        Text("快捷二级页面", style = MaterialTheme.typography.labelLarge)
        SectionEntryCard(
            iconRes = R.drawable.ic_px_shield,
            title = "应用列表白名单",
            subtitle = "管理免刷新频率限制的应用（已读取 $installedCount 个应用）",
            badge = "${config.autoWhitelistPackages.size} 个",
            iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = { onOpenSubPage(SubPage.WhitelistManager) }
        )
        SectionEntryCard(
            iconRes = R.drawable.ic_px_palette,
            title = "颜色与字号精调",
            subtitle = "自定义卡片底色、字体色与精确 sp 字号",
            badge = "${config.titleSizeSp.roundToInt()}sp",
            iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            onClick = { onOpenSubPage(SubPage.ColorEditor) }
        )
        SectionEntryCard(
            iconRes = R.drawable.ic_px_edit_note,
            title = "手动文案与胶囊模板",
            subtitle = "编辑业务名称、主副文案、荣耀模板与品牌色号",
            onClick = { onOpenSubPage(SubPage.ManualConfig) }
        )

        StatusIconRow(
            iconRes = R.drawable.ic_px_info,
            text = state.statusMessage,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NotificationsTabContent(
    config: CapsuleConfig,
    notifications: List<CapturedNotification>,
    pinnedKey: String?,
    appNameMap: Map<String, String>,
    installedCount: Int,
    onUpdateConfig: (Boolean, (CapsuleConfig) -> CapsuleConfig) -> Unit,
    onToggleWhitelist: (String) -> Unit,
    onPinOrUnpin: (CapturedNotification, Boolean) -> Unit,
    onUnpin: () -> Unit,
    onRefresh: () -> Unit,
    onOpenWhitelistPage: () -> Unit,
    onOpenNotificationDetail: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionEntryCard(
            iconRes = R.drawable.ic_px_apps,
            title = "应用列表白名单（免限频）",
            subtitle = "已读取 $installedCount 个手机应用，白名单应用不受自动模式刷新频率限制",
            badge = "已选 ${config.autoWhitelistPackages.size}",
            iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = onOpenWhitelistPage
        )

        if (config.autoWhitelistPackages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                config.autoWhitelistPackages.sorted().forEach { pkg ->
                    val label = appNameMap[pkg]?.let { "$it ($pkg)" } ?: pkg
                    FilterChip(
                        selected = true,
                        onClick = { onToggleWhitelist(pkg) },
                        label = { Text("$label ×") }
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("未锁定时自动同步新通知", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = config.autoMirrorNotifications,
                        onCheckedChange = { c -> onUpdateConfig(true) { it.copy(autoMirrorNotifications = c) } }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("自动模式忽略高频常驻通知", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = config.ignoreOngoingInAutoMode,
                        onCheckedChange = { c -> onUpdateConfig(true) { it.copy(ignoreOngoingInAutoMode = c) } }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("自动模式仅同步白名单应用", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = config.whitelistOnlyInAutoMode,
                        onCheckedChange = { c -> onUpdateConfig(true) { it.copy(whitelistOnlyInAutoMode = c) } }
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "非白名单自动刷新间隔：${config.autoThrottleIntervalSec} 秒（白名单不受限）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = config.autoThrottleIntervalSec.toFloat(),
                        onValueChange = { v ->
                            onUpdateConfig(true) {
                                it.copy(autoThrottleIntervalSec = v.roundToInt().coerceIn(1, 60))
                            }
                        },
                        valueRange = 1f..60f
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    painter = painterResource(
                        id = if (pinnedKey != null) R.drawable.ic_px_pin else R.drawable.ic_px_notifications
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (pinnedKey != null) "已锁定通知（共 ${notifications.size} 条）" else "通知选择器（点击锁定）",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pinnedKey != null) {
                    OutlinedButton(onClick = onUnpin) { Text("解锁") }
                }
                OutlinedButton(onClick = onRefresh) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_px_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("刷新")
                }
            }
        }

        if (notifications.isEmpty()) {
            StatusIconRow(
                iconRes = R.drawable.ic_px_info,
                text = "当前暂无可读取的活跃通知，请确认已开启通知使用权或点击右上角刷新。",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            notifications.take(15).forEach { item ->
                val isSelected = item.key == pinnedKey
                val isWhitelisted = item.packageName in config.autoWhitelistPackages
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPinOrUnpin(item, isSelected) },
                    border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    colors = if (isSelected) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        CardDefaults.cardColors()
                    }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (isSelected) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_px_pin),
                                        contentDescription = "已锁定",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                } else if (isWhitelisted) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_px_shield),
                                        contentDescription = "白名单",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                val prefix = buildString {
                                    if (isWhitelisted) append("[白名单] ")
                                    if (item.isOngoing) append("[常驻] ")
                                }
                                Text(
                                    text = "$prefix${item.appName} · ${item.title}",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(
                                text = timeFmt.format(Date(item.postTime)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_px_package),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = item.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row {
                                TextButton(onClick = { onOpenNotificationDetail(item.key) }) {
                                    Text("详情")
                                }
                                TextButton(onClick = { onToggleWhitelist(item.packageName) }) {
                                    Text(if (isWhitelisted) "移出白名单" else "+白名单")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceTabContent(
    config: CapsuleConfig,
    onUpdateConfig: (Boolean, (CapsuleConfig) -> CapsuleConfig) -> Unit,
    onApply: () -> Unit,
    onOpenColorEditor: () -> Unit,
    onOpenTypographyEditor: () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("实时效果预览（整体高度已锁定不变）", style = MaterialTheme.typography.labelLarge)
        LiveCardPreview(config)

        Text("一键主题预设", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CapsuleConfig.STYLE_PRESETS.forEach { preset ->
                val selected = config.cardBgHex.equals(preset.cardBgHex, ignoreCase = true) &&
                    config.accentColorHex.equals(preset.accentColorHex, ignoreCase = true)
                FilterChip(
                    selected = selected,
                    onClick = {
                        onUpdateConfig(true) {
                            it.copy(
                                cardBgHex = preset.cardBgHex,
                                titleColorHex = preset.titleColorHex,
                                detailColorHex = preset.detailColorHex,
                                btnBgHex = preset.btnBgHex,
                                accentColorHex = preset.accentColorHex
                            )
                        }
                    },
                    label = { Text(preset.label) }
                )
            }
        }

        Text("卡片排版格式", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CapsuleConfig.ALL_LAYOUT_STYLES.forEach { (styleKey, styleLabel) ->
                FilterChip(
                    selected = config.cardLayoutStyle == styleKey,
                    onClick = { onUpdateConfig(true) { it.copy(cardLayoutStyle = styleKey) } },
                    label = { Text(styleLabel) }
                )
            }
        }

        Text("深度定制二级页面", style = MaterialTheme.typography.labelLarge)
        SectionEntryCard(
            iconRes = R.drawable.ic_px_palette,
            title = "颜色高度自定义",
            subtitle = "独立设置底色、标题色、副文色、按钮底色与前景色",
            badge = config.cardBgHex,
            iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            onClick = onOpenColorEditor
        )
        SectionEntryCard(
            iconRes = R.drawable.ic_px_format_size,
            title = "文字 sp 字号精确调节",
            subtitle = "分别微调标题、副文与按钮的 sp 大小",
            badge = "${config.titleSizeSp.roundToInt()}/${config.detailSizeSp.roundToInt()}/${config.btnSizeSp.roundToInt()}sp",
            iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = onOpenTypographyEditor
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("显示顶部强调色条", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = config.showAccentBar,
                        onCheckedChange = { c -> onUpdateConfig(true) { it.copy(showAccentBar = c) } }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("显示刷新时间与计数", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = config.showTimestamp,
                        onCheckedChange = { c -> onUpdateConfig(true) { it.copy(showTimestamp = c) } }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("显示操作按钮", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = config.showActions,
                        onCheckedChange = { c -> onUpdateConfig(true) { it.copy(showActions = c) } }
                    )
                }
            }
        }

        Button(
            onClick = onApply,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_px_check_circle),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("立即应用自定义样式到灵动胶囊")
        }
    }
}

@Composable
private fun SettingsTabContent(
    config: CapsuleConfig,
    isFrameworkSupported: Boolean,
    installedCount: Int,
    statusMessage: String,
    onUpdateConfig: (Boolean, (CapsuleConfig) -> CapsuleConfig) -> Unit,
    onOpenManualConfig: () -> Unit,
    onOpenNotifSettings: () -> Unit,
    onRequestIgnoreBatteryOpt: () -> Unit,
    onStopCapsule: () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionEntryCard(
            iconRes = R.drawable.ic_px_edit_note,
            title = "手动自定义文案与胶囊模板",
            subtitle = "自定义未镜像时的业务名、主副文案、4 种胶囊模板与 12 种品牌色",
            iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = onOpenManualConfig
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusIconRow(
                    iconRes = R.drawable.ic_px_tune,
                    text = "前台保活静默刷新周期：${config.refreshIntervalSec} 秒",
                    tint = MaterialTheme.colorScheme.primary
                )
                Slider(
                    value = config.refreshIntervalSec.toFloat(),
                    onValueChange = { v ->
                        onUpdateConfig(true) {
                            it.copy(refreshIntervalSec = v.roundToInt().coerceIn(10, 300))
                        }
                    },
                    valueRange = 10f..300f
                )
            }
        }

        Text("权限与后台保活", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = onOpenNotifSettings, modifier = Modifier.fillMaxWidth()) {
            Icon(
                painter = painterResource(id = R.drawable.ic_px_notifications),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("打开系统「通知使用权」设置")
        }
        OutlinedButton(onClick = onRequestIgnoreBatteryOpt, modifier = Modifier.fillMaxWidth()) {
            Icon(
                painter = painterResource(id = R.drawable.ic_px_battery),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("申请忽略电池优化（防杀后台）")
        }
        OutlinedButton(onClick = onStopCapsule, modifier = Modifier.fillMaxWidth()) {
            Icon(
                painter = painterResource(id = R.drawable.ic_px_stop),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("停止灵动胶囊前台服务")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PixelIconBadge(
                        iconRes = R.drawable.ic_px_info,
                        badgeSizeDp = 36,
                        iconSizeDp = 18
                    )
                    Column {
                        Text("灵动胶囊助手 v18.0.0", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Pixel Material Symbols Rounded 图标风格",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = "荣耀 framework-magic：${if (isFrameworkSupported) "已就绪" else "未检测到"} · 已读取应用：$installedCount 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "最新状态：$statusMessage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
