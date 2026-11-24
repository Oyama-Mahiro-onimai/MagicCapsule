package com.app.magiccapsule.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.app.magiccapsule.capsule.MagicCapsuleBridge
import com.app.magiccapsule.data.CapsulePrefs
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.model.CapturedNotification
import com.app.magiccapsule.model.InstalledApp
import com.app.magiccapsule.service.CapsuleKeepAliveService
import com.app.magiccapsule.service.CapsuleNotificationListener
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
    val statusMessage: String = "白名单应用在自动模式下不受刷新频率限制；点选下方通知可单独锁定。"
)

class CapsuleViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = CapsulePrefs(application)

    private val _uiState = MutableStateFlow(
        CapsuleUiState(
            config = prefs.load(),
            isFrameworkSupported = MagicCapsuleBridge.isSupported(),
            hasNotificationAccess = CapsuleNotificationListener.isPermissionGranted(application)
        )
    )
    val uiState: StateFlow<CapsuleUiState> = _uiState.asStateFlow()

    val capturedNotifications: StateFlow<List<CapturedNotification>> =
        CapsuleNotificationListener.notifications

    val listenerConnected: StateFlow<Boolean> =
        CapsuleNotificationListener.connected

    val pinnedKey: StateFlow<String?> =
        CapsuleNotificationListener.pinnedKey

    val activeSourcePackage: StateFlow<String?> =
        CapsuleNotificationListener.activeSourcePackage

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    init {
        loadInstalledApps()
    }

    fun loadInstalledApps() {
        if (_isLoadingApps.value) return
        _isLoadingApps.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val pm = ctx.packageManager
            val selfPkg = ctx.packageName
            val list = runCatching {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
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
        setStatus(if (added) "✅ 已加入自动模式白名单（不受刷新频率限制）：$pkg" else "已移出白名单：$pkg")
    }

    fun clearWhitelist() {
        updateConfig(saveImmediately = true) { it.copy(autoWhitelistPackages = emptySet()) }
        setStatus("已清空自动模式白名单")
    }

    fun recheckNotificationAccess() {
        val granted = CapsuleNotificationListener.isPermissionGranted(getApplication())
        _uiState.update { it.copy(hasNotificationAccess = granted) }
        if (granted) {
            CapsuleNotificationListener.refreshSnapshot()
        }
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
        val intent = Intent(ctx, CapsuleKeepAliveService::class.java)
            .setAction(CapsuleKeepAliveService.ACTION_START)
        ContextCompat.startForegroundService(ctx, intent)
        setStatus("已启动自定义胶囊：${cfg.businessName} · ${cfg.mainText}")
    }

    fun selectAndPinNotification(item: CapturedNotification) {
        val ctx = getApplication<Application>()
        prefs.save(_uiState.value.config)
        // 同时锁定通知 key 与发起应用包名，防止冲突
        CapsuleNotificationListener.pinNotification(item.key, item.packageName)
        val intent = Intent(ctx, CapsuleKeepAliveService::class.java).apply {
            action = CapsuleKeepAliveService.ACTION_MIRROR_NOTIFICATION
            putExtra(CapsuleKeepAliveService.EXTRA_PACKAGE_NAME, item.packageName)
            putExtra(CapsuleKeepAliveService.EXTRA_APP_NAME, item.appName)
            putExtra(CapsuleKeepAliveService.EXTRA_TITLE, item.title)
            putExtra(CapsuleKeepAliveService.EXTRA_TEXT, item.text)
            putExtra(CapsuleKeepAliveService.EXTRA_IS_WHITELISTED, true)
        }
        ContextCompat.startForegroundService(ctx, intent)
        setStatus("📌 已锁定展示：${item.appName}（${item.packageName}）")
    }

    fun unpinNotification() {
        CapsuleNotificationListener.pinNotification(null, null)
        setStatus("已解除锁定，恢复按自动规则接收新通知")
    }

    fun refreshAll() {
        val ctx = getApplication<Application>()
        prefs.save(_uiState.value.config)
        CapsuleNotificationListener.refreshSnapshot()
        val intent = Intent(ctx, CapsuleKeepAliveService::class.java)
            .setAction(CapsuleKeepAliveService.ACTION_REFRESH)
        ctx.startService(intent)
        setStatus("已刷新通知列表与灵动胶囊")
    }

    fun stopCapsule() {
        val ctx = getApplication<Application>()
        CapsuleNotificationListener.pinNotification(null, null)
        val intent = Intent(ctx, CapsuleKeepAliveService::class.java)
            .setAction(CapsuleKeepAliveService.ACTION_STOP)
        ctx.startService(intent)
        setStatus("已停止灵动胶囊服务")
    }

    fun setStatus(msg: String) {
        _uiState.update { it.copy(statusMessage = msg) }
    }
}
