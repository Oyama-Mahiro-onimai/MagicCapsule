package com.app.magiccapsule.ui

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.app.magiccapsule.capsule.MagicCapsuleBridge
import com.app.magiccapsule.data.CapsulePrefs
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.service.CapsuleKeepAliveService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class CapsuleUiState(
    val config: CapsuleConfig = CapsuleConfig(),
    val isFrameworkSupported: Boolean = false,
    val statusMessage: String = "点击顶部胶囊展开紧凑卡片，点击卡片区域即可返回应用。"
)

class CapsuleViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = CapsulePrefs(application)

    private val _uiState = MutableStateFlow(
        CapsuleUiState(
            config = prefs.load(),
            isFrameworkSupported = MagicCapsuleBridge.isSupported()
        )
    )
    val uiState: StateFlow<CapsuleUiState> = _uiState.asStateFlow()

    fun updateConfig(transform: (CapsuleConfig) -> CapsuleConfig) {
        _uiState.update { state ->
            val updated = transform(state.config)
            prefs.save(updated)
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

    fun refreshCapsule() {
        val ctx = getApplication<Application>()
        prefs.save(_uiState.value.config)
        val intent = Intent(ctx, CapsuleKeepAliveService::class.java)
            .setAction(CapsuleKeepAliveService.ACTION_REFRESH)
        ctx.startService(intent)
        setStatus("已发送静默刷新指令")
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
