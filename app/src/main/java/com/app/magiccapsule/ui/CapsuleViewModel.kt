package com.app.magiccapsule.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.app.magiccapsule.capsule.CapsulePublisher
import com.app.magiccapsule.capsule.honor.MagicCapsuleBridge
import com.app.magiccapsule.data.CapsulePrefs
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.model.CapturedNotification
import com.app.magiccapsule.model.InstalledApp
import com.app.magiccapsule.model.SystemStatusSnapshot
import com.app.magiccapsule.service.CapsuleKeepAliveService
import com.app.magiccapsule.service.CapsuleNotificationListener
import com.app.magiccapsule.service.BannerAction
import com.app.magiccapsule.service.CapsuleOverlayAccessibilityService
import com.app.magiccapsule.service.ActiveMirroredPayload
import com.app.magiccapsule.service.NotificationIconStore
import com.app.magiccapsule.service.ShizukuBridge
import com.app.magiccapsule.service.SystemHeadsUpController
import com.app.magiccapsule.service.SystemStatusMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class CapsuleUiState(
    val config: CapsuleConfig = CapsuleConfig(),
    val isFrameworkSupported: Boolean = false,
    val hasNotificationAccess: Boolean = false,
    val hasAccessibilityEnabled: Boolean = false,
    val hasWriteSecureSettings: Boolean = false,
    val isSystemHeadsUpEnabled: Boolean = true,
    val statusMessage: String = "支持荣耀原生灵动胶囊与无障碍顶层覆盖横幅接管。"
)

class CapsuleViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = CapsulePrefs(application)

    private val _uiState = MutableStateFlow(
        CapsuleUiState(
            config = prefs.load(),
            isFrameworkSupported = MagicCapsuleBridge.isSupported(),
            hasNotificationAccess = CapsuleNotificationListener.isPermissionGranted(application),
            hasAccessibilityEnabled = CapsuleOverlayAccessibilityService.isServiceEnabled(application),
            hasWriteSecureSettings = SystemHeadsUpController.hasPermission(application),
            isSystemHeadsUpEnabled = SystemHeadsUpController.isSystemHeadsUpEnabled(application)
        )
    )
    val uiState: StateFlow<CapsuleUiState> = _uiState.asStateFlow()

    val capturedNotifications: StateFlow<List<CapturedNotification>> =
        CapsuleNotificationListener.notifications

    val listenerConnected: StateFlow<Boolean> =
        CapsuleNotificationListener.connected

    val overlayConnected: StateFlow<Boolean> =
        CapsuleOverlayAccessibilityService.connected

    val overlayStatus: StateFlow<String> =
        CapsuleOverlayAccessibilityService.lastOverlayStatus

    val capsuleStatus: StateFlow<String> =
        CapsulePublisher.lastCapsuleStatus

    val lastInterceptedSummary: StateFlow<String> =
        CapsuleNotificationListener.lastInterceptedSummary

    val shizukuAlive: StateFlow<Boolean> = ShizukuBridge.binderAlive
    val shizukuGranted: StateFlow<Boolean> = ShizukuBridge.permissionGranted
    val shizukuStatus: StateFlow<String> = ShizukuBridge.statusText

    val systemStatus: StateFlow<SystemStatusSnapshot> = SystemStatusMonitor.snapshot

    val pinnedKey: StateFlow<String?> =
        CapsuleNotificationListener.pinnedKey

    val activeSourcePackage: StateFlow<String?> =
        CapsuleNotificationListener.activeSourcePackage

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    init {
        val appCtx = getApplication<Application>()
        SystemStatusMonitor.syncMonitorRegistration(appCtx, _uiState.value.config)
        SystemStatusMonitor.refreshAsync(appCtx)
        viewModelScope.launch(Dispatchers.IO) {
            ShizukuBridge.init {
                autoGrantViaShizuku()
                SystemStatusMonitor.refreshAsync(appCtx)
            }
        }
    }

    fun refreshSystemStatus() {
        SystemStatusMonitor.refreshAsync(getApplication()) {
            setStatus("已刷新系统状态（${if (it.shizukuEnhanced) "含 Shizuku 增强数据" else "基础接口"}）")
        }
    }

    fun toggleSystemStatusMonitor(enabled: Boolean) {
        updateSystemMonitorConfig { it.copy(enableSystemStatusMonitor = enabled) }
        setStatus(if (enabled) "已开启系统状态自定义事件监听" else "已关闭系统状态事件监听")
    }

    fun updateSystemMonitorConfig(transform: (CapsuleConfig) -> CapsuleConfig) {
        val ctx = getApplication<Application>()
        updateConfig(saveImmediately = true, transform = transform)
        SystemStatusMonitor.syncMonitorRegistration(ctx, _uiState.value.config)
    }

    fun pushSystemStatusToCapsule() {
        val ctx = getApplication<Application>()
        val cfg = _uiState.value.config
        prefs.save(cfg)
        SystemStatusMonitor.pushCurrentStatusToCapsule(ctx, cfg)
        setStatus("已将当前系统状态推送到灵动胶囊")
    }

    fun requestShizukuAndGrant() {
        val aliveAndGranted = ShizukuBridge.binderAlive.value && ShizukuBridge.permissionGranted.value
        if (aliveAndGranted) {
            autoGrantViaShizuku()
        } else {
            val ok = ShizukuBridge.requestPermission()
            if (!ok) {
                setStatus(ShizukuBridge.statusText.value)
            }
        }
    }

    private fun autoGrantViaShizuku() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val (ok, msg) = ShizukuBridge.grantWriteSecureSettings(ctx)
            if (ok) {
                val cfg = _uiState.value.config
                if (cfg.enableOverlayBanner && cfg.suppressSystemHeadsUp &&
                    CapsuleOverlayAccessibilityService.connected.value
                ) {
                    SystemHeadsUpController.setSystemHeadsUpSuppressed(ctx, true)
                }
            }
            recheckAllPermissions()
            setStatus(msg)
        }
    }

    fun loadInstalledApps() {
        if (_isLoadingApps.value) return
        _isLoadingApps.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val pm = ctx.packageManager
            val selfPkg = ctx.packageName
            val list = runCatching {
                pm.getInstalledApplications(0)
                    .asSequence()
                    .filter { it.packageName != selfPkg }
                    .map { ai ->
                        val label = runCatching { pm.getApplicationLabel(ai).toString() }
                            .getOrElse { ai.packageName }
                            .ifBlank { ai.packageName }
                        val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        InstalledApp(
                            packageName = ai.packageName,
                            appName = label,
                            isSystemApp = isSys
                        )
                    }
                    .sortedWith(compareBy<InstalledApp> { it.isSystemApp }.thenBy { it.appName.lowercase() })
                    .toList()
            }.getOrDefault(emptyList())
            _installedApps.value = list
            _isLoadingApps.value = false
        }
    }

    fun toggleWhitelistPackage(pkg: String) {
        if (pkg.isBlank() || pkg == getApplication<Application>().packageName) return
        var added = false
        updateConfig(saveImmediately = true) { cfg ->
            val next = cfg.autoWhitelistPackages.toMutableSet()
            if (pkg in next) {
                next.remove(pkg)
                added = false
            } else {
                next.add(pkg)
                added = true
            }
            cfg.copy(autoWhitelistPackages = next)
        }
        setStatus(if (added) "已加入自动模式白名单（不受刷新频率限制）：$pkg" else "已移出白名单：$pkg")
    }

    fun clearWhitelist() {
        updateConfig(saveImmediately = true) { it.copy(autoWhitelistPackages = emptySet()) }
        setStatus("已清空自动模式白名单")
    }

    private val recheckInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastRecheckElapsedMs: Long = 0L

    fun recheckAllPermissions() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRecheckElapsedMs < 1500L) return
        if (!recheckInFlight.compareAndSet(false, true)) return
        lastRecheckElapsedMs = now
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                ShizukuBridge.refreshState(autoRequest = false, triggerAutoGrantIfNeeded = false)
                val notif = CapsuleNotificationListener.isPermissionGranted(ctx)
                val a11y = CapsuleOverlayAccessibilityService.isServiceEnabled(ctx)
                val secure = SystemHeadsUpController.hasPermission(ctx)
                val headsUp = SystemHeadsUpController.isSystemHeadsUpEnabled(ctx)
                _uiState.update {
                    it.copy(
                        hasNotificationAccess = notif,
                        hasAccessibilityEnabled = a11y,
                        hasWriteSecureSettings = secure,
                        isSystemHeadsUpEnabled = headsUp
                    )
                }
                if (notif) {
                    CapsuleNotificationListener.refreshSnapshot(force = false)
                }
            } finally {
                recheckInFlight.set(false)
            }
        }
    }

    fun persistConfig() {
        val cfg = _uiState.value.config
        com.app.magiccapsule.service.SoCThreadPools.monitorIoExecutor.execute {
            runCatching { prefs.save(cfg) }
        }
    }

    fun toggleCapsuleEngine(enabled: Boolean) {
        val ctx = getApplication<Application>()
        updateConfig(saveImmediately = true) { it.copy(enableCapsuleEngine = enabled) }
        if (!enabled) {
            CapsuleNotificationListener.dismissCapsuleOnly(ctx)
            setStatus("已关闭灵动胶囊引擎（顶层横幅与歌词引擎仍独立运行）")
        } else {
            CapsuleNotificationListener.rescanAndRestoreActiveMedia(ctx)
            setStatus("已开启灵动胶囊引擎并自动恢复活跃媒体")
        }
    }

    fun updateOverlayBannerConfig(enableOverlay: Boolean, suppressHeadsUp: Boolean) {
        val ctx = getApplication<Application>()
        updateConfig(saveImmediately = true) {
            it.copy(
                enableOverlayBanner = enableOverlay,
                suppressSystemHeadsUp = suppressHeadsUp
            )
        }
        val shouldSuppress = enableOverlay && suppressHeadsUp && CapsuleOverlayAccessibilityService.connected.value
        if (SystemHeadsUpController.hasPermission(ctx)) {
            SystemHeadsUpController.setSystemHeadsUpSuppressed(ctx, shouldSuppress)
        }
        if (enableOverlay) {
            CapsuleNotificationListener.rescanAndRestoreActiveMedia(ctx)
        } else {
            CapsuleOverlayAccessibilityService.dismissBanner()
            if (!_uiState.value.config.enableUniversalBannerMode) {
                com.app.magiccapsule.service.UniversalBannerHost.dismiss()
            }
        }
        recheckAllPermissions()
    }

    fun testOverlayBanner() {
        val cfg = _uiState.value.config
        prefs.save(cfg)
        val ctx = getApplication<Application>()
        val selfIcon = NotificationIconStore.resolveAppIconBitmap(ctx, ctx.packageName)
        val shown = CapsuleOverlayAccessibilityService.showBanner(
            appName = cfg.businessName.ifBlank { "灵动胶囊助手" },
            packageName = ctx.packageName,
            title = "顶层横幅测试",
            text = "上下偏移 ${cfg.overlayBannerOffsetYPx}px · 上下内边距 ${cfg.overlayBannerPaddingVPx}px",
            contentIntent = null,
            iconBitmap = selfIcon,
            actions = listOf(
                BannerAction("标记已读（示例）", null, hasRemoteInput = false),
                BannerAction("左右滑动可关闭", null, hasRemoteInput = false)
            ),
            config = cfg
        )
        setStatus(
            if (shown) "已弹出顶层覆盖横幅（停留 ${cfg.overlayBannerDurationSec} 秒）"
            else "请先在系统无障碍设置中开启「灵动胶囊顶层横幅」"
        )
    }

    fun restoreSystemHeadsUp() {
        val ctx = getApplication<Application>()
        updateConfig(saveImmediately = true) { it.copy(suppressSystemHeadsUp = false) }
        val ok = SystemHeadsUpController.restoreDefault(ctx)
        recheckAllPermissions()
        setStatus(if (ok) "已恢复系统原生横幅通知" else "未授予 WRITE_SECURE_SETTINGS，无需恢复或请通过 ADB 恢复")
    }

    fun updateConfig(saveImmediately: Boolean = false, transform: (CapsuleConfig) -> CapsuleConfig) {
        _uiState.update { state ->
            val updated = transform(state.config)
            if (saveImmediately) prefs.save(updated)
            state.copy(config = updated)
        }
    }

    fun startCapsule() {
        val ctx = getApplication<Application>()
        val cfg = _uiState.value.config
        prefs.save(cfg)
        CapsuleNotificationListener.pinNotification(null, null)
        CapsuleNotificationListener.clearAutoMediaIntercept(ctx)
        CapsulePublisher.setManualCapsulePinned(true)
        val intent = Intent(ctx, CapsuleKeepAliveService::class.java)
            .setAction(CapsuleKeepAliveService.ACTION_START)
        ContextCompat.startForegroundService(ctx, intent)
        setStatus("已启动自定义胶囊：${cfg.businessName} · ${cfg.mainText}")
    }

    fun selectAndPinNotification(item: CapturedNotification) {
        val ctx = getApplication<Application>()
        prefs.save(_uiState.value.config)
        val payload = NotificationIconStore.getPayload(item.key) ?: ActiveMirroredPayload(
            key = item.key,
            packageName = item.packageName,
            appName = item.appName,
            title = item.title,
            text = item.text,
            iconBitmap = NotificationIconStore.resolveAppIconBitmap(ctx, item.packageName),
            contentIntent = null,
            actions = emptyList()
        )
        CapsulePublisher.setManualCapsulePinned(false)
        CapsuleNotificationListener.pinNotification(item.key, item.packageName)
        CapsulePublisher.publishMirrored(
            context = ctx,
            baseConfig = _uiState.value.config,
            payload = payload,
            isPinned = true
        )
        setStatus("已锁定展示：${item.appName}（${item.packageName}）")
    }

    fun unpinNotification() {
        CapsuleNotificationListener.unpinAndDismiss(getApplication())
        setStatus("已取消选中并同步关闭当前胶囊显示")
    }

    fun refreshAll() {
        val ctx = getApplication<Application>()
        val cfg = _uiState.value.config
        prefs.save(cfg)
        CapsuleNotificationListener.refreshSnapshot(force = true)
        CapsulePublisher.refreshCurrent(ctx, cfg)
        setStatus("已刷新通知列表与灵动胶囊")
    }

    fun stopCapsule() {
        val ctx = getApplication<Application>()
        CapsulePublisher.setManualCapsulePinned(false)
        CapsuleNotificationListener.pinNotification(null, null)
        CapsulePublisher.dismiss(ctx, reason = "已手动停止灵动胶囊")
        val intent = Intent(ctx, CapsuleKeepAliveService::class.java)
            .setAction(CapsuleKeepAliveService.ACTION_STOP)
        runCatching { ctx.startService(intent) }
        recheckAllPermissions()
        setStatus("已停止灵动胶囊服务并恢复系统横幅")
    }

    fun setStatus(msg: String) {
        _uiState.update { it.copy(statusMessage = msg) }
    }
}
