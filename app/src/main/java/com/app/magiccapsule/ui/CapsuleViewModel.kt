package com.app.magiccapsule.ui

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.app.magiccapsule.capsule.MagicCapsuleBridge
import com.app.magiccapsule.data.CapsulePrefs
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.model.CapturedNotification
import com.app.magiccapsule.service.CapsuleKeepAliveService
import com.app.magiccapsule.service.CapsuleNotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class CapsuleUiState(
    val config: CapsuleConfig = CapsuleConfig(),
    val isFrameworkSupported: Boolean = false,
    val hasNotificationAccess: Boolean = false,
    val statusMessage: String = "开启通知使用权后，新通知将自动同步展示在灵动胶囊中。"
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
        val intent = Intent(ctx, CapsuleKeepAliveService::class.java)
            .setAction(CapsuleKeepAliveService.ACTION_START)
        ContextCompat.startForegroundService(ctx, intent)
        setStatus("已启动：${cfg.businessName} · ${cfg.mainText}")
    }

    fun mirrorToCapsule(item: CapturedNotification) {
        val ctx = getApplication<Application>()
        prefs.save(_uiState.value.config)
        val intent = Intent(ctx, CapsuleKeepAliveService::class.java).apply {
            action = CapsuleKeepAliveService.ACTION_MIRROR_NOTIFICATION
            putExtra(CapsuleKeepAliveService.EXTRA_APP_NAME, item.appName)
            putExtra(CapsuleKeepAliveService.EXTRA_TITLE, item.title)
            putExtra(CapsuleKeepAliveService.EXTRA_TEXT, item.text)
        }
        ContextCompat.startForegroundService(ctx, intent)
        setStatus("已推送到灵动胶囊：${item.appName} - ${item.title}")
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
        val intent = Intent(ctx, CapsuleKeepAliveService::class.java)
            .setAction(CapsuleKeepAliveService.ACTION_STOP)
        ctx.startService(intent)
        setStatus("已停止灵动胶囊服务")
    }

    fun setStatus(msg: String) {
        _uiState.update { it.copy(statusMessage = msg) }
    }
}
