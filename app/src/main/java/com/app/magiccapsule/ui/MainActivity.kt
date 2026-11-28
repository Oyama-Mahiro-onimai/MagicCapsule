package com.app.magiccapsule.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
import com.app.magiccapsule.service.CapsuleNotificationListener
import com.app.magiccapsule.model.CapturedNotification
import com.app.magiccapsule.model.SystemStatusSnapshot
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val lodHandler = Handler(Looper.getMainLooper())
    private val idleLodRunnable = Runnable {
        com.app.magiccapsule.service.RenderPowerGovernor.applyWindowLod(
            window,
            this,
            com.app.magiccapsule.service.RenderPowerGovernor.RenderLod.IDLE_LOW_POWER
        )
    }

    private fun scheduleIdleLodDrop(delayMs: Long = 2500L) {
        lodHandler.removeCallbacks(idleLodRunnable)
        lodHandler.postDelayed(idleLodRunnable, delayMs)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        com.app.magiccapsule.service.RenderPowerGovernor.applyWindowLod(
            window,
            this,
            com.app.magiccapsule.service.RenderPowerGovernor.RenderLod.HIGH_FPS
        )
        scheduleIdleLodDrop(2500L)

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

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    lodHandler.removeCallbacks(idleLodRunnable)
                    com.app.magiccapsule.service.RenderPowerGovernor.applyWindowLod(
                        window,
                        this,
                        com.app.magiccapsule.service.RenderPowerGovernor.RenderLod.HIGH_FPS
                    )
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scheduleIdleLodDrop(2500L)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        super.onPause()
        lodHandler.removeCallbacks(idleLodRunnable)
        com.app.magiccapsule.service.RenderPowerGovernor.applyWindowLod(
            window,
            this,
            com.app.magiccapsule.service.RenderPowerGovernor.RenderLod.IDLE_LOW_POWER
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapsuleAppRoot(vm: CapsuleViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    var activeSubPage by remember { mutableStateOf<SubPage?>(null) }
    // 精确裁剪根作用域状态：仅保留低频全局状态，高频通知/歌词/导航/系统监控流全部下沉到叶子 Composable 订阅，杜绝整树无效重组
    val listenerConnected by vm.listenerConnected.collectAsState()
    val overlayConnected by vm.overlayConnected.collectAsState()
    val shizukuAlive by vm.shizukuAlive.collectAsState()
    val shizukuGranted by vm.shizukuGranted.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val activeSourcePackage by vm.activeSourcePackage.collectAsState()
    val installedApps by vm.installedApps.collectAsState()
    val isLoadingApps by vm.isLoadingApps.collectAsState()
    val config = state.config
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current


    var forceShowOnboarding by remember { mutableStateOf(false) }
    var postNotifCheckTick by remember { mutableIntStateOf(0) }
    val needsOnboarding = !config.onboardingCompleted || config.acceptedPolicyVersion < CapsuleConfig.CURRENT_POLICY_VERSION
    // 首次打开（或未同意新版协议）强制先走方案 A 品牌开屏；以后启动按用户所选的 A/B 方案决定
    var showingSplash by remember { mutableStateOf(needsOnboarding || config.showLaunchSplash) }
    var legalDialogTab by remember { mutableStateOf<Int?>(null) }

    BackHandler(enabled = activeSubPage != null) {
        activeSubPage = null
    }

    val appNameMap = remember(installedApps) {
        installedApps.associate { it.packageName to it.appName }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> CapsuleNotificationListener.setUiVisible(true)
                Lifecycle.Event.ON_RESUME -> { vm.recheckAllPermissions(); postNotifCheckTick++ }
                Lifecycle.Event.ON_STOP -> CapsuleNotificationListener.setUiVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        postNotifCheckTick++
        if (granted) {
            if (config.onboardingCompleted && !forceShowOnboarding) {
                vm.startCapsule()
            } else {
                vm.setStatus("已授予发送通知权限")
            }
        } else {
            vm.setStatus("未授予通知权限，无法展示灵动胶囊")
        }
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

    fun openAccessibilitySettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            vm.setStatus("请前往：设置 → 辅助功能 → 无障碍 → 已下载的服务 → 开启「灵动胶囊顶层横幅」")
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

    val onboardingLocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        postNotifCheckTick++
    }

    fun openOverlayPermissionSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            )
        }.onFailure {
            vm.setStatus("请前往系统设置手动开启「显示在其他应用上层」悬浮窗权限")
        }
    }

    fun openGithubRepo() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(CapsuleConfig.GITHUB_REPO_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            clipboardManager.setText(AnnotatedString(CapsuleConfig.GITHUB_REPO_URL))
            vm.setStatus("未找到浏览器，已复制 GitHub 仓库地址到剪贴板")
        }
    }

    val topBarTitle = when (activeSubPage) {
        null -> when (currentTab) {
            MainTab.HOME -> "灵动胶囊助手"
            MainTab.NOTIFICATIONS -> "通知与白名单"
            MainTab.APPEARANCE -> "卡片外观定制"
            MainTab.SETTINGS -> "系统与高级设置"
        }
        SubPage.WhitelistManager -> "应用列表白名单"
        SubPage.BannerHijackSettings -> "顶层横幅接管设置"
        SubPage.SystemMonitorSettings -> "设备状态监控与自定义"
        SubPage.ColorEditor -> "颜色高度自定义"
        SubPage.TypographyEditor -> "文字 sp 字号调节"
        SubPage.ManualConfig -> "手动文案与模板"
        SubPage.CrashLogViewer -> "崩溃日志（隐藏调试）"
        SubPage.RuntimeLogViewer -> "运行与调试日志"
        SubPage.SelfCheckCenter -> "系统全链路自检"
        is SubPage.NotificationDetail -> "通知来源详情"
    }

    if (showingSplash) {
        LaunchSplashOverlay(
            isFrameworkSupported = state.isFrameworkSupported,
            onFinished = { showingSplash = false }
        )
        return
    }

    legalDialogTab?.let { tab ->
        LegalPolicyDialog(
            initialTab = tab,
            onOpenGithub = { openGithubRepo() },
            onDismiss = { legalDialogTab = null }
        )
    }

    val showOnboarding = needsOnboarding || forceShowOnboarding
    if (showOnboarding) {
        val hasPostNotif = remember(postNotifCheckTick) {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        val hasLocPerm = remember(postNotifCheckTick) {
            com.app.magiccapsule.service.CapsuleNavigationEngine.hasLocationPermission(context)
        }
        val hasOverlayPerm = remember(postNotifCheckTick) {
            com.app.magiccapsule.service.UniversalBannerHost.canDrawOverlay(context)
        }
        val isIgnoringBattery = remember(postNotifCheckTick) {
            runCatching {
                context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
            }.getOrDefault(false)
        }
        Scaffold(contentWindowInsets = WindowInsets.systemBars) { pad ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentAlignment = Alignment.TopCenter
            ) {
                OnboardingPage(
                    hasPostNotifications = hasPostNotif,
                    hasNotificationAccess = state.hasNotificationAccess,
                    hasLocationPermission = hasLocPerm,
                    hasOverlayPermission = hasOverlayPerm,
                    hasAccessibilityEnabled = state.hasAccessibilityEnabled,
                    hasWriteSecureSettings = state.hasWriteSecureSettings,
                    shizukuGranted = shizukuGranted,
                    isIgnoringBatteryOpt = isIgnoringBattery,
                    isFrameworkSupported = state.isFrameworkSupported,
                    showLaunchSplash = config.showLaunchSplash,
                    onSelectLaunchSplash = { useSplash ->
                        vm.updateConfig(saveImmediately = true) { it.copy(showLaunchSplash = useSplash) }
                    },
                    onRequestPostNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onOpenNotifAccess = { openNotificationAccessSettings() },
                    onRequestLocationPermission = {
                        onboardingLocLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    onOpenOverlaySettings = { openOverlayPermissionSettings() },
                    onOpenAccessibility = { openAccessibilitySettings() },
                    onRequestShizuku = { vm.requestShizukuAndGrant() },
                    onRequestBatteryOpt = { requestIgnoreBatteryOpt() },
                    onOpenGithub = { openGithubRepo() },
                    onRefreshStatus = {
                        vm.recheckAllPermissions()
                        postNotifCheckTick++
                    },
                    onComplete = {
                        forceShowOnboarding = false
                        vm.updateConfig(saveImmediately = true) {
                            it.copy(
                                onboardingCompleted = true,
                                acceptedPolicyVersion = CapsuleConfig.CURRENT_POLICY_VERSION
                            )
                        }
                    }
                )
            }
        }
        return
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
                    if (installedApps.isEmpty() && !isLoadingApps) {
                        vm.loadInstalledApps()
                    }
                    WhitelistManagerPage(
                        installedApps = installedApps,
                        isLoading = isLoadingApps,
                        whitelist = config.autoWhitelistPackages,
                        onTogglePackage = { vm.toggleWhitelistPackage(it) },
                        onRefreshApps = { vm.loadInstalledApps() },
                        onClearWhitelist = { vm.clearWhitelist() }
                    )
                }
                SubPage.SystemMonitorSettings -> {
                    val systemStatus by vm.systemStatus.collectAsState()
                    SystemMonitorPage(
                        config = config,
                        snapshot = systemStatus,
                        onUpdateMonitorConfig = { transform -> vm.updateSystemMonitorConfig(transform) },
                        onRefreshNow = { vm.refreshSystemStatus() },
                        onPushToCapsule = { runWithNotifPermission { vm.pushSystemStatusToCapsule() } },
                        onRequestShizuku = { vm.requestShizukuAndGrant() }
                    )
                }
                SubPage.BannerHijackSettings -> {
                    val overlayStatus by vm.overlayStatus.collectAsState()
                    val capsuleStatus by vm.capsuleStatus.collectAsState()
                    val lastInterceptedSummary by vm.lastInterceptedSummary.collectAsState()
                    val shizukuStatus by vm.shizukuStatus.collectAsState()
                    BannerHijackSettingsPage(
                        state = state,
                        overlayConnected = overlayConnected,
                        overlayStatus = overlayStatus,
                        capsuleStatus = capsuleStatus,
                        lastInterceptedSummary = lastInterceptedSummary,
                        shizukuAlive = shizukuAlive,
                        shizukuGranted = shizukuGranted,
                        shizukuStatus = shizukuStatus,
                        onRequestShizuku = { vm.requestShizukuAndGrant() },
                        onOpenAccessibilitySettings = { openAccessibilitySettings() },
                        onCopyAdbCommand = {
                            clipboardManager.setText(
                                AnnotatedString("adb shell pm grant com.app.magiccapsule android.permission.WRITE_SECURE_SETTINGS")
                            )
                            vm.setStatus("已复制 ADB 授权命令到剪贴板")
                        },
                        onToggleBanner = { en, sup -> vm.updateOverlayBannerConfig(en, sup) },
                        onDurationChange = { sec ->
                            vm.updateConfig(saveImmediately = false) { it.copy(overlayBannerDurationSec = sec) }
                        },
                        onUpdateConfig = { saveNow, transform -> vm.updateConfig(saveNow, transform) },
                        onTestBanner = { vm.testOverlayBanner() },
                        onRestoreSystemHeadsUp = { vm.restoreSystemHeadsUp() }
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
                SubPage.RuntimeLogViewer -> {
                    RuntimeLogViewerPage(onStatusMessage = { vm.setStatus(it) })
                }
                SubPage.SelfCheckCenter -> {
                    SelfCheckPage(
                        onOpenRuntimeLog = { activeSubPage = SubPage.RuntimeLogViewer },
                        onStatusMessage = { vm.setStatus(it) }
                    )
                }
                SubPage.CrashLogViewer -> {
                    CrashLogViewerPage(onStatusMessage = { vm.setStatus(it) })
                }
                SubPage.ManualConfig -> {
                    ManualConfigPage(
                        config = config,
                        onUpdateConfig = { saveNow, transform -> vm.updateConfig(saveNow, transform) },
                        onStartCapsule = { runWithNotifPermission { vm.startCapsule() } }
                    )
                }
                is SubPage.NotificationDetail -> {
                    val notifications by vm.capturedNotifications.collectAsState()
                    val pinnedKey by vm.pinnedKey.collectAsState()
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
                            systemStatusFlow = vm.systemStatus,
                            onRefreshSystemStatus = { vm.refreshSystemStatus() },
                            onToggleSystemStatusMonitor = { vm.toggleSystemStatusMonitor(it) },
                            onPushSystemStatusToCapsule = { runWithNotifPermission { vm.pushSystemStatusToCapsule() } },
                            onOpenNotifSettings = { openNotificationAccessSettings() },
                            onRefreshAll = { runWithNotifPermission { vm.refreshAll() } },
                            onStartManual = { runWithNotifPermission { vm.startCapsule() } },
                            onStopCapsule = { vm.stopCapsule() },
                            onOpenSubPage = { activeSubPage = it }
                        )
                        MainTab.NOTIFICATIONS -> NotificationsTabContent(
                            config = config,
                            notificationsFlow = vm.capturedNotifications,
                            pinnedKeyFlow = vm.pinnedKey,
                            appNameMap = appNameMap,
                            installedCount = installedApps.size,
                            onUpdateConfig = { saveNow, transform -> vm.updateConfig(saveNow, transform) },
                            onToggleCapsuleEngine = { vm.toggleCapsuleEngine(it) },
                            onToggleOverlayBanner = { en, sup -> vm.updateOverlayBannerConfig(en, sup) },
                            onToggleWhitelist = { vm.toggleWhitelistPackage(it) },
                            onPinOrUnpin = { item, isSelected ->
                                runWithNotifPermission {
                                    if (isSelected) vm.unpinNotification() else vm.selectAndPinNotification(item)
                                }
                            },
                            onUnpin = { vm.unpinNotification() },
                            onRefresh = { vm.refreshAll() },
                            overlayConnected = overlayConnected,
                            onOpenWhitelistPage = { activeSubPage = SubPage.WhitelistManager },
                            onOpenBannerHijackPage = { activeSubPage = SubPage.BannerHijackSettings },
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
                            onOpenCrashLog = { activeSubPage = SubPage.CrashLogViewer },
                            onOpenRuntimeLog = { activeSubPage = SubPage.RuntimeLogViewer },
                            onOpenSelfCheck = { activeSubPage = SubPage.SelfCheckCenter },
                            onOpenNotifSettings = { openNotificationAccessSettings() },
                            onRequestIgnoreBatteryOpt = { requestIgnoreBatteryOpt() },
                            onStopCapsule = { vm.stopCapsule() },
                            onOpenOnboarding = { forceShowOnboarding = true },
                            onOpenLegalPolicy = { tab -> legalDialogTab = tab },
                            onOpenGithubRepo = { openGithubRepo() }
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
    systemStatusFlow: StateFlow<SystemStatusSnapshot>,
    onRefreshSystemStatus: () -> Unit,
    onToggleSystemStatusMonitor: (Boolean) -> Unit,
    onPushSystemStatusToCapsule: () -> Unit,
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
        Text("胶囊与横幅弹性实时预览", style = MaterialTheme.typography.labelLarge)
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
                StatusIconRow(
                    iconRes = R.drawable.ic_px_tune,
                    text = "获取方式：${CapsuleConfig.acquireModeLabel(config.capsuleAcquireMode)} · ${CapsuleConfig.deliveryModeLabel(config.capsuleDeliveryMode)}",
                    tint = MaterialTheme.colorScheme.secondary,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
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

        HomeSystemStatusCard(
            config = config,
            systemStatusFlow = systemStatusFlow,
            onToggleSystemStatusMonitor = onToggleSystemStatusMonitor,
            onRefreshSystemStatus = onRefreshSystemStatus,
            onPushSystemStatusToCapsule = onPushSystemStatusToCapsule,
            onOpenSubPage = onOpenSubPage
        )

        Text("快捷二级页面", style = MaterialTheme.typography.labelLarge)
        SectionEntryCard(
            iconRes = R.drawable.ic_px_check_circle,
            title = "手动系统自检（全链路诊断）",
            subtitle = "一键检测所有调用、传入/传出数据，并实机测试横幅、导航横幅与胶囊轮换文本",
            badge = "手动自检",
            iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = { onOpenSubPage(SubPage.SelfCheckCenter) }
        )
        SectionEntryCard(
            iconRes = R.drawable.ic_px_shield,
            title = "顶层横幅接管 (Overlay)",
            subtitle = "无障碍顶层覆盖横幅 + 可选 ADB 屏蔽重复系统横幅",
            badge = if (config.enableOverlayBanner) "已启用" else "未启用",
            iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = { onOpenSubPage(SubPage.BannerHijackSettings) }
        )
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
    notificationsFlow: StateFlow<List<CapturedNotification>>,
    pinnedKeyFlow: StateFlow<String?>,
    appNameMap: Map<String, String>,
    installedCount: Int,
    onUpdateConfig: (Boolean, (CapsuleConfig) -> CapsuleConfig) -> Unit,
    onToggleCapsuleEngine: (Boolean) -> Unit,
    onToggleOverlayBanner: (Boolean, Boolean) -> Unit,
    onToggleWhitelist: (String) -> Unit,
    onPinOrUnpin: (CapturedNotification, Boolean) -> Unit,
    onUnpin: () -> Unit,
    onRefresh: () -> Unit,
    overlayConnected: Boolean,
    onOpenWhitelistPage: () -> Unit,
    onOpenBannerHijackPage: () -> Unit,
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
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusIconRow(
                    iconRes = R.drawable.ic_px_tune,
                    text = "双引擎独立开关（胶囊与横幅互不影响）",
                    tint = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("1. 灵动胶囊引擎总开关", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "关闭后完全停用状态栏胶囊，仅保留顶层横幅（零胶囊渲染开销）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.enableCapsuleEngine,
                        onCheckedChange = onToggleCapsuleEngine
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("2. 顶层横幅引擎总开关", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (overlayConnected) {
                                "无障碍置顶层已就绪；关闭时自动恢复系统原生横幅"
                            } else {
                                "需先在下方开启无障碍顶层横幅服务"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.enableOverlayBanner,
                        onCheckedChange = { c ->
                            onToggleOverlayBanner(c, if (c) config.suppressSystemHeadsUp else false)
                        }
                    )
                }
                if (config.enableOverlayBanner) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("横幅独立忽略高频常驻通知", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = config.bannerIgnoreOngoing,
                            onCheckedChange = { c -> onUpdateConfig(true) { it.copy(bannerIgnoreOngoing = c) } }
                        )
                    }
                }
            }
        }

        SectionEntryCard(
            iconRes = R.drawable.ic_px_apps,
            title = "顶层横幅接管（显示在系统横幅之上）",
            subtitle = if (overlayConnected) "无障碍置顶层已就绪，支持屏蔽重复原生横幅" else "点此开启无障碍置顶层与 ADB 屏蔽系统横幅",
            badge = if (config.enableOverlayBanner && overlayConnected) "运行中" else "去设置",
            iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            onClick = onOpenBannerHijackPage
        )
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusIconRow(
                    iconRes = R.drawable.ic_px_tune,
                    text = "胶囊通知获取与推送方式（可按机型自由切换）",
                    tint = MaterialTheme.colorScheme.primary
                )

                Text("1. 通知获取来源", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CapsuleConfig.ALL_ACQUIRE_MODES.forEach { (modeKey, modeLabel) ->
                        FilterChip(
                            selected = config.capsuleAcquireMode == modeKey,
                            onClick = {
                                onUpdateConfig(true) { cfg ->
                                    cfg.copy(
                                        capsuleAcquireMode = modeKey,
                                        autoMirrorNotifications = modeKey != CapsuleConfig.ACQUIRE_MANUAL_PIN_ONLY
                                    )
                                }
                            },
                            label = { Text(modeLabel) }
                        )
                    }
                }
                Text(
                    text = CapsuleConfig.acquireModeDesc(config.capsuleAcquireMode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("2. 胶囊拉起与渠道方式", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CapsuleConfig.ALL_DELIVERY_MODES.forEach { (deliveryKey, deliveryLabel) ->
                        FilterChip(
                            selected = config.capsuleDeliveryMode == deliveryKey,
                            onClick = {
                                onUpdateConfig(true) { cfg ->
                                    cfg.copy(capsuleDeliveryMode = deliveryKey)
                                }
                            },
                            label = { Text(deliveryLabel) }
                        )
                    }
                }
                Text(
                    text = CapsuleConfig.deliveryModeDesc(config.capsuleDeliveryMode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("有通知时套用横幅样式并替换文案与图标", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "来信时沿用横幅底色与文字配色，并替换手动文案和图标",
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("开启横幅时胶囊走静默通道（防下方重复同名通知）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "避免胶囊自身弹出系统横幅叠加在自定义横幅下方（若状态栏胶囊不显示可关闭）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.capsuleQuietChannelWhenBannerOn,
                        onCheckedChange = { c ->
                            onUpdateConfig(true) { it.copy(capsuleQuietChannelWhenBannerOn = c) }
                        }
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
                        checked = config.autoMirrorNotifications && config.capsuleAcquireMode != CapsuleConfig.ACQUIRE_MANUAL_PIN_ONLY,
                        onCheckedChange = { c ->
                            onUpdateConfig(true) { cfg ->
                                val nextAcquire = when {
                                    !c -> CapsuleConfig.ACQUIRE_MANUAL_PIN_ONLY
                                    cfg.capsuleAcquireMode == CapsuleConfig.ACQUIRE_MANUAL_PIN_ONLY -> CapsuleConfig.ACQUIRE_UNIFIED_BANNER
                                    else -> cfg.capsuleAcquireMode
                                }
                                cfg.copy(
                                    autoMirrorNotifications = c,
                                    capsuleAcquireMode = nextAcquire
                                )
                            }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("胶囊即弹出即销毁（复用横幅链路）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "新通知弹出胶囊并在保持时间后自动销毁（手动锁定保持常驻）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.capsuleAutoDismiss,
                        onCheckedChange = { c -> onUpdateConfig(true) { it.copy(capsuleAutoDismiss = c) } }
                    )
                }
                if (config.capsuleAutoDismiss) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "胶囊保持时间：${config.capsuleHoldDurationSec} 秒",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        onUpdateConfig(true) {
                                            it.copy(capsuleHoldDurationSec = (it.capsuleHoldDurationSec - 1).coerceIn(2, 60))
                                        }
                                    }
                                ) { Text("-1s") }
                                OutlinedButton(
                                    onClick = {
                                        onUpdateConfig(true) {
                                            it.copy(capsuleHoldDurationSec = (it.capsuleHoldDurationSec + 1).coerceIn(2, 60))
                                        }
                                    }
                                ) { Text("+1s") }
                            }
                        }
                        Slider(
                            value = config.capsuleHoldDurationSec.toFloat(),
                            onValueChange = { v ->
                                onUpdateConfig(false) {
                                    it.copy(capsuleHoldDurationSec = v.roundToInt().coerceIn(2, 60))
                                }
                            },
                            onValueChangeFinished = { onUpdateConfig(true) { it } },
                            valueRange = 2f..60f
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("按系统通知渠道划分重要性（过滤静默通知）", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CapsuleConfig.ALL_IMPORTANCE_LEVELS.forEach { (lvl, lvlLabel) ->
                            FilterChip(
                                selected = config.minChannelImportance == lvl,
                                onClick = {
                                    onUpdateConfig(true) { it.copy(minChannelImportance = lvl) }
                                },
                                label = { Text(lvlLabel) }
                            )
                        }
                    }
                    Text(
                        text = CapsuleConfig.importanceFilterDesc(config.minChannelImportance),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("微信/QQ来电自动拉起通话界面", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "来电通知强制豁免常驻/静默过滤；开启屏蔽系统横幅时自动拉起通话页防止无法接听",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.autoLaunchCallFullScreen,
                        onCheckedChange = { c ->
                            onUpdateConfig(true) { it.copy(autoLaunchCallFullScreen = c) }
                        }
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("自动拦截媒体播放与录音机到胶囊", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "豁免常驻通知过滤，自动在胶囊展示音乐/播客/视频播放与录音机计时，停止后自动收回",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val mediaToggleCtx = androidx.compose.ui.platform.LocalContext.current
                    Switch(
                        checked = config.enableMediaRecorderCapsule,
                        onCheckedChange = { c ->
                            onUpdateConfig(true) { it.copy(enableMediaRecorderCapsule = c) }
                            if (c) {
                                CapsuleNotificationListener.rescanAndRestoreActiveMedia(mediaToggleCtx)
                            } else {
                                CapsuleNotificationListener.clearAutoMediaIntercept()
                            }
                        }
                    )
                }
                if (config.enableMediaRecorderCapsule) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("拦截媒体播放（含无通知音频会话）", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = config.interceptMediaPlayback,
                            onCheckedChange = { c -> onUpdateConfig(true) { it.copy(interceptMediaPlayback = c) } }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("拦截录音机 / 录屏（状态栏实时计时 + 按钮）", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = config.interceptRecorder,
                            onCheckedChange = { c -> onUpdateConfig(true) { it.copy(interceptRecorder = c) } }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("媒体暂停时自动收回胶囊", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = config.mediaCapsuleAutoHideOnPause,
                            onCheckedChange = { c -> onUpdateConfig(true) { it.copy(mediaCapsuleAutoHideOnPause = c) } }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("媒体卡片交由系统原生渲染（连续进度条/零轮询）", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "使用系统 MediaStyle + 原生进度条观察器 60fps 连续渲染，彻底停止 2.5s 定时刷新，大幅降低耗电",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.useNativeSystemMediaCard,
                            onCheckedChange = { c -> onUpdateConfig(true) { it.copy(useNativeSystemMediaCard = c) } }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("胶囊实时显示歌词（LRC 时间轴 + 多协议）", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "优先捕获状态栏/实况字段/蓝牙歌词，无原生歌词时自动按歌名拉取 LRC 时间轴并随进度逐句同步到胶囊药丸（免蓝牙全应用通用）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.showMediaMetadataLyricInCapsule,
                            onCheckedChange = { c -> onUpdateConfig(true) { it.copy(showMediaMetadataLyricInCapsule = c) } }
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                // 多地图 Web API 胶囊导航卡片（独立 Composable 边界 + 叶子状态订阅，零父树重组）
                NavigationCapsuleCard(
                    config = config,
                    onUpdateConfig = onUpdateConfig
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                val hasNativeCapsule = remember {
                    com.app.magiccapsule.capsule.honor.MagicCapsuleBridge.isSupported() ||
                        com.app.magiccapsule.capsule.huawei.HuaweiLiveCapsuleBridge.isSupported() ||
                        com.app.magiccapsule.capsule.android16.AndroidLiveUpdateBridge.isSupported()
                }
                val overlayCtx = androidx.compose.ui.platform.LocalContext.current
                val canSystemOverlay = com.app.magiccapsule.service.UniversalBannerHost.canDrawOverlay(overlayCtx)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("通用机型悬浮横幅 / 迷你胶囊模式", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (hasNativeCapsule) {
                                "使用 SYSTEM_ALERT_WINDOW 在任意应用外弹出悬浮横幅与可收起迷你胶囊（原生胶囊机型可按需自由开关）"
                            } else {
                                "当前为通用机型，已自动启用通用悬浮横幅/迷你胶囊模式，套用媒体识别、同步进度条与播控逻辑"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = !hasNativeCapsule || config.enableUniversalBannerMode,
                        enabled = hasNativeCapsule,
                        onCheckedChange = { c ->
                            onUpdateConfig(true) { it.copy(enableUniversalBannerMode = c) }
                            if (c) {
                                CapsuleNotificationListener.rescanAndRestoreActiveMedia(overlayCtx)
                            } else {
                                com.app.magiccapsule.service.UniversalBannerHost.dismiss()
                            }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (canSystemOverlay) "悬浮窗权限 (SYSTEM_ALERT_WINDOW)：已授权" else "悬浮窗权限 (SYSTEM_ALERT_WINDOW)：未授权",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (canSystemOverlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    if (!canSystemOverlay) {
                        OutlinedButton(onClick = {
                            runCatching {
                                val intent = Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${overlayCtx.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                overlayCtx.startActivity(intent)
                            }
                        }) {
                            Text("去授权")
                        }
                    }
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
                            onUpdateConfig(false) {
                                it.copy(autoThrottleIntervalSec = v.roundToInt().coerceIn(1, 60))
                            }
                        },
                        onValueChangeFinished = { onUpdateConfig(true) { it } },
                        valueRange = 1f..60f
                    )
                }
            }
        }

        CapturedNotificationsSection(
            config = config,
            notificationsFlow = notificationsFlow,
            pinnedKeyFlow = pinnedKeyFlow,
            onPinOrUnpin = onPinOrUnpin,
            onUnpin = onUnpin,
            onRefresh = onRefresh,
            onToggleWhitelist = onToggleWhitelist,
            onOpenNotificationDetail = onOpenNotificationDetail
        )
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
        Text("胶囊与横幅弹性实时预览", style = MaterialTheme.typography.labelLarge)
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
            subtitle = "2D 调色板、背景不透明度 (${config.cardBgAlphaPercent}%) 与灰度 (${config.cardBgGrayscalePercent}%)",
            badge = "${config.cardBgAlphaPercent}%",
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("渲染本应用自带按钮（打开/关闭 · 刷新/停止）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "关闭后仅显示原通知透传出的真实按钮；点击通知本体或透传按钮直接跳转",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.renderOwnNotificationButtons,
                        onCheckedChange = { c -> onUpdateConfig(true) { it.copy(renderOwnNotificationButtons = c) } }
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "横幅通知按钮大小：${config.bannerBtnSizeDp} dp",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(bannerBtnSizeDp = (it.bannerBtnSizeDp - 2).coerceIn(26, 56))
                                    }
                                }
                            ) { Text("-2dp") }
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(bannerBtnSizeDp = (it.bannerBtnSizeDp + 2).coerceIn(26, 56))
                                    }
                                }
                            ) { Text("+2dp") }
                        }
                    }
                    Slider(
                        value = config.bannerBtnSizeDp.toFloat(),
                        onValueChange = { v ->
                            onUpdateConfig(false) {
                                it.copy(bannerBtnSizeDp = v.roundToInt().coerceIn(26, 56))
                            }
                        },
                        onValueChangeFinished = { onUpdateConfig(true) { it } },
                        valueRange = 26f..56f
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "胶囊卡片按钮大小：${config.capsuleBtnSizeDp} dp",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(capsuleBtnSizeDp = (it.capsuleBtnSizeDp - 2).coerceIn(24, 48))
                                    }
                                }
                            ) { Text("-2dp") }
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(capsuleBtnSizeDp = (it.capsuleBtnSizeDp + 2).coerceIn(24, 48))
                                    }
                                }
                            ) { Text("+2dp") }
                        }
                    }
                    Slider(
                        value = config.capsuleBtnSizeDp.toFloat(),
                        onValueChange = { v ->
                            onUpdateConfig(false) {
                                it.copy(capsuleBtnSizeDp = v.roundToInt().coerceIn(24, 48))
                            }
                        },
                        onValueChangeFinished = { onUpdateConfig(true) { it } },
                        valueRange = 24f..48f
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusIconRow(
                    iconRes = R.drawable.ic_px_format_size,
                    text = "收起态状态栏胶囊文字（setMainContentRemoteViews）",
                    tint = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("收起态启用紧凑小字号 RemoteViews", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "用自定义紧凑字号注入状态栏胶囊，解决监控文本几个字就省略成「…」的问题",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.useCompactPillRemoteViews,
                        onCheckedChange = { c -> onUpdateConfig(true) { it.copy(useCompactPillRemoteViews = c) } }
                    )
                }
                if (config.useCompactPillRemoteViews) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "收起态胶囊字号：${"%.1f".format(config.pillTextSizeSp)} sp",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(pillTextSizeSp = (it.pillTextSizeSp - 0.5f).coerceIn(8f, 14f))
                                    }
                                }
                            ) { Text("-0.5") }
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(pillTextSizeSp = (it.pillTextSizeSp + 0.5f).coerceIn(8f, 14f))
                                    }
                                }
                            ) { Text("+0.5") }
                        }
                    }
                    Slider(
                        value = config.pillTextSizeSp,
                        onValueChange = { v ->
                            onUpdateConfig(false) {
                                it.copy(pillTextSizeSp = ((v * 2f).roundToInt() / 2f).coerceIn(8f, 14f))
                            }
                        },
                        onValueChangeFinished = { onUpdateConfig(true) { it } },
                        valueRange = 8f..14f
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "竖屏收起态文本宽度上限：${config.portraitPillMaxUnits} 单位（约 ${config.portraitPillMaxUnits / 2} 字）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(portraitPillMaxUnits = (it.portraitPillMaxUnits - 2).coerceIn(12, 48))
                                    }
                                }
                            ) { Text("-2") }
                            OutlinedButton(
                                onClick = {
                                    onUpdateConfig(true) {
                                        it.copy(portraitPillMaxUnits = (it.portraitPillMaxUnits + 2).coerceIn(12, 48))
                                    }
                                }
                            ) { Text("+2") }
                        }
                    }
                    Slider(
                        value = config.portraitPillMaxUnits.toFloat(),
                        onValueChange = { v ->
                            onUpdateConfig(false) {
                                it.copy(portraitPillMaxUnits = v.roundToInt().coerceIn(12, 48))
                            }
                        },
                        onValueChangeFinished = { onUpdateConfig(true) { it } },
                        valueRange = 12f..48f
                    )
                }

                StatusIconRow(
                    iconRes = R.drawable.ic_px_tune,
                    text = "横 / 竖屏展开胶囊位置微调（px）",
                    tint = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "竖屏展开左右偏移：${config.portraitCapsuleOffsetXPx} px",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = config.portraitCapsuleOffsetXPx.toFloat(),
                    onValueChange = { v ->
                        onUpdateConfig(false) { it.copy(portraitCapsuleOffsetXPx = v.roundToInt().coerceIn(-120, 120)) }
                    },
                    onValueChangeFinished = { onUpdateConfig(true) { it } },
                    valueRange = -120f..120f
                )

                Text(
                    text = "竖屏展开上下偏移：${config.portraitCapsuleOffsetYPx} px",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = config.portraitCapsuleOffsetYPx.toFloat(),
                    onValueChange = { v ->
                        onUpdateConfig(false) { it.copy(portraitCapsuleOffsetYPx = v.roundToInt().coerceIn(-60, 140)) }
                    },
                    onValueChangeFinished = { onUpdateConfig(true) { it } },
                    valueRange = -60f..140f
                )

                Text(
                    text = "横屏展开左右偏移：${config.landscapeCapsuleOffsetXPx} px",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = config.landscapeCapsuleOffsetXPx.toFloat(),
                    onValueChange = { v ->
                        onUpdateConfig(false) { it.copy(landscapeCapsuleOffsetXPx = v.roundToInt().coerceIn(-160, 160)) }
                    },
                    onValueChangeFinished = { onUpdateConfig(true) { it } },
                    valueRange = -160f..160f
                )

                Text(
                    text = "横屏展开上下偏移：${config.landscapeCapsuleOffsetYPx} px",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = config.landscapeCapsuleOffsetYPx.toFloat(),
                    onValueChange = { v ->
                        onUpdateConfig(false) { it.copy(landscapeCapsuleOffsetYPx = v.roundToInt().coerceIn(-40, 120)) }
                    },
                    onValueChangeFinished = { onUpdateConfig(true) { it } },
                    valueRange = -40f..120f
                )
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
    onOpenCrashLog: () -> Unit,
    onOpenRuntimeLog: () -> Unit,
    onOpenSelfCheck: () -> Unit,
    onOpenNotifSettings: () -> Unit,
    onRequestIgnoreBatteryOpt: () -> Unit,
    onStopCapsule: () -> Unit,
    onOpenOnboarding: () -> Unit,
    onOpenLegalPolicy: (Int) -> Unit,
    onOpenGithubRepo: () -> Unit
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
            iconRes = R.drawable.ic_px_shield,
            title = "启动引导与全量权限一键补授",
            subtitle = "重新打开开屏权限引导页，查看 7 项权限状态并一键补授",
            badge = "引导",
            iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            onClick = onOpenOnboarding
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusIconRow(
                    iconRes = R.drawable.ic_px_tune,
                    text = "冷启动开屏显示方案（可随时切换）",
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "方案 A：每次冷启动展示约 0.8s 品牌开屏动画；方案 B：冷启动跳过开屏直达主页。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = config.showLaunchSplash,
                        onClick = { onUpdateConfig(true) { it.copy(showLaunchSplash = true) } },
                        label = { Text("方案 A：品牌开屏") }
                    )
                    FilterChip(
                        selected = !config.showLaunchSplash,
                        onClick = { onUpdateConfig(true) { it.copy(showLaunchSplash = false) } },
                        label = { Text("方案 B：直达主页") }
                    )
                }
            }
        }

        SectionEntryCard(
            iconRes = R.drawable.ic_px_info,
            title = "用户协议与隐私政策（含信息收集说明）",
            subtitle = "查阅完整用户协议、通知/定位/罗盘/联网用途及本地清理机制",
            badge = "v${CapsuleConfig.CURRENT_POLICY_VERSION}",
            iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = { onOpenLegalPolicy(1) }
        )

        SectionEntryCard(
            iconRes = R.drawable.ic_px_package,
            title = "GitHub 开源仓库",
            subtitle = "github.com/Oyama-Mahiro-onimai/MagicCapsule（查看源码与提交反馈）",
            badge = "开源",
            iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = onOpenGithubRepo
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val guardCtx = androidx.compose.ui.platform.LocalContext.current
                StatusIconRow(
                    iconRes = R.drawable.ic_px_shield,
                    text = "后台防杀与服务自愈（提升稳定性）",
                    tint = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("独立静默前台守护服务", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "使用最低优先级静默通知维持前台服务级别（零定时唤醒/零弹窗），防止荣耀/vivo/小米系统杀后台导致监听失效",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.enableBackgroundKeepAlive,
                        onCheckedChange = { c ->
                            onUpdateConfig(true) { latest ->
                                val next = latest.copy(enableBackgroundKeepAlive = c)
                                com.app.magiccapsule.service.ResidentKeepAliveService.sync(guardCtx, next)
                                next
                            }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            com.app.magiccapsule.service.CapsuleNotificationListener.requestRebindNow(guardCtx)
                            com.app.magiccapsule.service.SystemStatusMonitor.syncMonitorRegistration(guardCtx, config)
                            com.app.magiccapsule.service.ResidentKeepAliveService.sync(guardCtx, config)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("一键自愈重绑服务")
                    }
                    OutlinedButton(
                        onClick = onRequestIgnoreBatteryOpt
                    ) {
                        Text("电池白名单")
                    }
                }
            }
        }

        SectionEntryCard(
            iconRes = R.drawable.ic_px_info,
            title = "运行与调试日志（测试排障）",
            subtitle = "按通知监听、灵动胶囊、顶层横幅和错误实时筛选查看运行轨迹",
            badge = "日志",
            iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onOpenRuntimeLog
        )

        SectionEntryCard(
            iconRes = R.drawable.ic_px_check_circle,
            title = "系统全链路自检中心",
            subtitle = "手动自检权限调用、传入解析、传出数据校验、横幅/导航弹出与胶囊文本轮换",
            badge = "自检",
            iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = onOpenSelfCheck
        )

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
                        onUpdateConfig(false) {
                            it.copy(refreshIntervalSec = v.roundToInt().coerceIn(10, 300))
                        }
                    },
                    onValueChangeFinished = { onUpdateConfig(true) { it } },
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

        var secretTapCount by remember { mutableIntStateOf(0) }
        val crashEntryUnlocked = secretTapCount >= 5

        if (crashEntryUnlocked) {
            SectionEntryCard(
                iconRes = R.drawable.ic_px_info,
                title = "崩溃日志（隐藏入口）",
                subtitle = "查看本地 crash_log.txt 完整崩溃堆栈与胶囊现场状态",
                badge = "调试",
                iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                iconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                onClick = onOpenCrashLog
            )
        }

        val creditsCtx = LocalContext.current
        fun openExternalLink(url: String) {
            runCatching {
                creditsCtx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        val appVersionName = remember(creditsCtx) {
            runCatching {
                creditsCtx.packageManager.getPackageInfo(creditsCtx.packageName, 0).versionName ?: "149.0.0"
            }.getOrDefault("149.0.0")
        }

        Text("制作人与致谢名单", style = MaterialTheme.typography.labelLarge)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 制作人（GitHub）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { openExternalLink(CapsuleConfig.GITHUB_REPO_URL) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("制作人 · Oyama Mahiro", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "MagicCapsule 开源项目作者与维护者（点击访问 GitHub）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "GitHub",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // 制作人：北辞☪꯭᭄ꦿ（仅展示名称，移除个人名片外链防打扰）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text("制作人 · 北辞☪꯭᭄ꦿ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "灵动胶囊助手作者与维护者",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 致谢：Chan（仅展示名称）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text("特别致谢 · Chan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "提供了 Pro 订阅支持与宝贵反馈",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 致谢：Frazil 🧊（仅展示名称）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text("特别致谢 · Frazil 🧊", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "提供了首批实机测试反馈",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (secretTapCount < 5) secretTapCount++
                }
        ) {
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
                        Text("灵动胶囊助手 v$appVersionName", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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


@Composable
private fun HomeSystemStatusCard(
    config: CapsuleConfig,
    systemStatusFlow: StateFlow<SystemStatusSnapshot>,
    onToggleSystemStatusMonitor: (Boolean) -> Unit,
    onRefreshSystemStatus: () -> Unit,
    onPushSystemStatusToCapsule: () -> Unit,
    onOpenSubPage: (SubPage) -> Unit
) {
    val systemStatus by systemStatusFlow.collectAsState()
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
                StatusIconRow(
                    iconRes = R.drawable.ic_px_battery,
                    text = if (systemStatus.shizukuEnhanced) {
                        "系统状态监控（Shizuku 已增强）"
                    } else {
                        "系统状态监控（基础模式）"
                    },
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = systemStatus.updatedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "电池直读：${systemStatus.batteryPercent}% · ${systemStatus.chargeType} · ${systemStatus.batteryCurrentMa} · ${"%.1f".format(systemStatus.batteryTempC)}°C · 温控 ${systemStatus.thermalLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "性能与网络：${systemStatus.refreshRateLabel} · RAM ${"%.1f".format(systemStatus.ramUsedGb)}/${"%.1f".format(systemStatus.ramTotalGb)}GB · ${systemStatus.networkLabel}${if (systemStatus.linkSpeedMbps > 0) " (${systemStatus.linkSpeedMbps}Mbps)" else ""} · ${systemStatus.ringerLabel} ${systemStatus.mediaVolumePercent}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Shizuku 单次合并缓存：CPU 温区 ${systemStatus.cpuTempLabel} · Doze ${systemStatus.dozeState}",
                style = MaterialTheme.typography.bodySmall,
                color = if (systemStatus.shizukuEnhanced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("监听充放电/响铃并自动弹胶囊", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "纯事件广播监听，零后台轮询耗电",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.enableSystemStatusMonitor,
                    onCheckedChange = onToggleSystemStatusMonitor
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onRefreshSystemStatus, modifier = Modifier.weight(1f)) {
                    Text("刷新状态")
                }
                Button(onClick = onPushSystemStatusToCapsule, modifier = Modifier.weight(1f)) {
                    Text("推送到胶囊")
                }
            }
            OutlinedButton(
                onClick = { onOpenSubPage(SubPage.SystemMonitorSettings) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (config.sysContinuousMonitor) {
                        "自定义触发条件与常时监控（常时监控运行中）"
                    } else {
                        "自定义触发条件与常时监控"
                    }
                )
            }
        }
    }
}

@Composable
private fun NavigationCapsuleCard(
    config: CapsuleConfig,
    onUpdateConfig: (Boolean, (CapsuleConfig) -> CapsuleConfig) -> Unit
) {
    val navCtx = LocalContext.current
    var destInput by remember(config.navLastDestination) { mutableStateOf(config.navLastDestination) }
    val curKey = when (config.navProvider) {
        "tencent" -> config.navTencentKey
        "baidu" -> config.navBaiduKey
        "amap" -> config.navAmapKey
        else -> ""
    }
    var keyInput by remember(config.navProvider, curKey) { mutableStateOf(curKey) }
    val providers = remember { listOf("amap" to "高德", "tencent" to "腾讯", "baidu" to "百度", "osrm" to "免Key") }
    val modes = remember { listOf("driving" to "驾车", "bicycling" to "骑行", "walking" to "步行") }

    Text("多地图 Web API 胶囊导航（绕开 YOYO / 全机型通用）", style = MaterialTheme.typography.titleSmall)
    Text(
        "填入免费 Web 服务 API Key（或直接选免 Key OSRM）即可通过本机 GPS + 指南针实时推送转向指令（息屏自动释放传感器）",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        providers.forEach { (id, label) ->
            FilterChip(
                selected = config.navProvider == id,
                onClick = { onUpdateConfig(false) { it.copy(navProvider = id) } },
                label = { Text(label, maxLines = 1) }
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        modes.forEach { (id, label) ->
            FilterChip(
                selected = config.navMode == id,
                onClick = { onUpdateConfig(false) { it.copy(navMode = id) } },
                label = { Text(label, maxLines = 1) }
            )
        }
    }
    if (config.navProvider != "osrm") {
        OutlinedTextField(
            value = keyInput,
            onValueChange = { v ->
                keyInput = v
                onUpdateConfig(false) {
                    when (it.navProvider) {
                        "tencent" -> it.copy(navTencentKey = v)
                        "baidu" -> it.copy(navBaiduKey = v)
                        else -> it.copy(navAmapKey = v)
                    }
                }
            },
            label = { Text("${com.app.magiccapsule.service.CapsuleNavigationEngine.providerLabel(config.navProvider)} API Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
    OutlinedTextField(
        value = destInput,
        onValueChange = { v ->
            destInput = v
            onUpdateConfig(false) { it.copy(navLastDestination = v) }
        },
        label = { Text("输入目的地（如：北京南站 / 人民广场）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    NavigationLiveStatusSection(
        onStartNav = {
            val latestCfg = config.copy(
                navLastDestination = destInput,
                navAmapKey = if (config.navProvider == "amap") keyInput else config.navAmapKey,
                navTencentKey = if (config.navProvider == "tencent") keyInput else config.navTencentKey,
                navBaiduKey = if (config.navProvider == "baidu") keyInput else config.navBaiduKey
            )
            com.app.magiccapsule.service.CapsuleNavigationEngine.startNavigation(navCtx, destInput, latestCfg)
        },
        onStopNav = {
            com.app.magiccapsule.service.CapsuleNavigationEngine.stopNavigation(navCtx)
        }
    )
}

@Composable
private fun NavigationLiveStatusSection(
    onStartNav: () -> Unit,
    onStopNav: () -> Unit
) {
    val navCtx = LocalContext.current
    val navState by com.app.magiccapsule.service.CapsuleNavigationEngine.state.collectAsState()
    var hasLocPerm by remember { mutableStateOf(com.app.magiccapsule.service.CapsuleNavigationEngine.hasLocationPermission(navCtx)) }
    val locLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasLocPerm = com.app.magiccapsule.service.CapsuleNavigationEngine.hasLocationPermission(navCtx)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!hasLocPerm) {
            OutlinedButton(
                onClick = {
                    locLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) { Text("授予定位权限") }
        }
        if (navState.running) {
            if (navState.routeCount > 1) {
                OutlinedButton(
                    onClick = { com.app.magiccapsule.service.CapsuleNavigationEngine.switchToNextAlternativeRoute(navCtx) },
                    modifier = Modifier.weight(1f)
                ) { Text("绕行换线(${navState.activeRouteIndex + 1}/${navState.routeCount})") }
            }
            Button(
                onClick = onStopNav,
                modifier = Modifier.weight(1f)
            ) { Text("结束胶囊导航") }
        } else {
            Button(
                enabled = !navState.loading,
                onClick = onStartNav,
                modifier = Modifier.weight(1f)
            ) { Text(if (navState.loading) "规划中..." else "开始胶囊导航") }
        }
    }
    val displayStatus = remember(navState.statusText, navState.currentDetailText) {
        if (navState.currentDetailText.isNotBlank()) {
            "${navState.statusText}\n${navState.currentDetailText}"
        } else navState.statusText
    }
    AnimatedContent(
        targetState = displayStatus,
        transitionSpec = {
            fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) togetherWith
                fadeOut(animationSpec = tween(140, easing = FastOutSlowInEasing))
        },
        label = "nav_status_smooth"
    ) { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
    if (navState.sensorSummary.isNotBlank()) {
        Text(
            text = navState.sensorSummary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (navState.originLabel.isNotBlank() || navState.destinationLabel.isNotBlank()) {
        var showRouteSteps by remember { mutableStateOf(false) }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (navState.originLabel.isNotBlank()) {
                    Text(
                        text = "起点定位：${navState.originLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (navState.destinationLabel.isNotBlank()) {
                    Text(
                        text = "解析终点：${navState.destinationLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (navState.routeStepsPreview.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showRouteSteps = !showRouteSteps },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (showRouteSteps) "收起路线详情" else "查看路线(${navState.routeStepsPreview.size}步)")
                        }
                    }
                    if (navState.destLat != 0.0 || navState.destLon != 0.0) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    val uri = Uri.parse("geo:${navState.destLat},${navState.destLon}?q=${navState.destLat},${navState.destLon}(${Uri.encode(navState.destinationLabel)})")
                                    navCtx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("地图核对终点") }
                    }
                }
                if (showRouteSteps && navState.routeStepsPreview.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        navState.routeStepsPreview.take(25).forEach { stepLine ->
                            Text(
                                text = stepLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (navState.routeStepsPreview.size > 25) {
                            Text(
                                text = "…其余 ${navState.routeStepsPreview.size - 25} 步已省略",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapturedNotificationsSection(
    config: CapsuleConfig,
    notificationsFlow: StateFlow<List<CapturedNotification>>,
    pinnedKeyFlow: StateFlow<String?>,
    onPinOrUnpin: (CapturedNotification, Boolean) -> Unit,
    onUnpin: () -> Unit,
    onRefresh: () -> Unit,
    onToggleWhitelist: (String) -> Unit,
    onOpenNotificationDetail: (String) -> Unit
) {
    val notifications by notificationsFlow.collectAsState()
    val pinnedKey by pinnedKeyFlow.collectAsState()
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
                                    append("[${item.importanceLabel()}] ")
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
                        if (item.channelName.isNotBlank()) {
                            Text(
                                text = "系统渠道：${item.channelName}（重要性 ${item.importance} · ${item.importanceLabel()}）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (item.actionTitles.isNotEmpty()) {
                            val listCtx = LocalContext.current
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item.actionTitles.forEachIndexed { idx, actLabel ->
                                    OutlinedButton(
                                        onClick = {
                                            CapsuleNotificationListener.triggerActionFromList(listCtx, item.key, idx)
                                        }
                                    ) {
                                        Text(actLabel, maxLines = 1)
                                    }
                                }
                            }
                        }
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
