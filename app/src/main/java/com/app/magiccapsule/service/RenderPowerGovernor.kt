package com.app.magiccapsule.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import com.app.magiccapsule.crash.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.floor

/**
 * 前台 UI 与悬浮窗动态 LOD（刷新率分级）与节能插值器调度器：
 * - HIGH_FPS：触摸交互、展开媒体横幅入场/拖拽动画时，请求屏幕峰值刷新率（如 120Hz）
 * - BALANCED：展开媒体横幅播放中（60fps 进度条推进）时，锁定 60Hz 避免 120Hz 冗余耗电
 * - IDLE_LOW_POWER：屏幕静止无触摸 2.5s 后、收起态迷你胶囊、或静态非媒体横幅时，降至屏幕支持的最低刷新率（LTPO 可自动下探）
 * - 提供量化步进节能插值器（SteppedPowerSavingInterpolator），减少歌词淡入/收起态更新时的无效 VSync CPU/GPU 唤醒
 */
object RenderPowerGovernor {

    enum class RenderLod(val label: String) {
        IDLE_LOW_POWER("静止省电"),
        BALANCED("媒体平衡"),
        HIGH_FPS("交互全帧")
    }

    private val _currentLod = MutableStateFlow(RenderLod.IDLE_LOW_POWER)
    val currentLod: StateFlow<RenderLod> = _currentLod.asStateFlow()

    private val _targetHz = MutableStateFlow(60f)
    val targetHz: StateFlow<Float> = _targetHz.asStateFlow()

    @Volatile
    private var cachedPeakHz: Float = 0f
    @Volatile
    private var cachedMinHz: Float = 0f

    /**
     * 节能步进缓出插值器：将 0..1 连续曲线量化为离散阶梯（默认 8 步），
     * 视觉上依然平滑自然，但避免在 120Hz 屏幕上每 8.3ms 产生亚像素级无效重绘，显著降低 CPU/RenderThread 唤醒频次。
     */
    class SteppedPowerSavingInterpolator(private val steps: Int = 8) : Interpolator {
        private val safeSteps = steps.coerceIn(4, 16).toFloat()
        override fun getInterpolation(input: Float): Float {
            if (input >= 1f) return 1f
            if (input <= 0f) return 0f
            val quantized = floor(input * safeSteps) / safeSteps
            val inv = 1f - quantized
            return 1f - inv * inv
        }
    }

    val powerSavingLyricInterpolator: Interpolator = SteppedPowerSavingInterpolator(steps = 8)
    val fullRateSmoothInterpolator: Interpolator = DecelerateInterpolator()

    fun resolveDisplayHzBounds(context: Context): Pair<Float, Float> {
        if (cachedPeakHz > 0f && cachedMinHz > 0f) {
            return cachedMinHz to cachedPeakHz
        }
        val display: Display? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display ?: context.getSystemService(WindowManager::class.java)?.defaultDisplay
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(WindowManager::class.java)?.defaultDisplay
            }
        }.getOrNull()
        val modes = runCatching { display?.supportedModes?.toList().orEmpty() }.getOrDefault(emptyList())
        val rates = modes.map { it.refreshRate }.filter { it >= 24f }
        val peak = rates.maxOrNull() ?: 60f
        val min = rates.minOrNull() ?: 60f
        cachedPeakHz = peak
        cachedMinHz = min
        AppLogger.d("RenderLOD", "检测到屏幕刷新率区间：min=${min}Hz, peak=${peak}Hz (modes=${rates.size})")
        return min to peak
    }

    fun isSystemPowerSaveMode(context: Context): Boolean {
        return runCatching {
            context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        }.getOrDefault(false)
    }

    fun getTargetHz(context: Context, lod: RenderLod): Float {
        val (minHz, peakHz) = resolveDisplayHzBounds(context)
        if (isSystemPowerSaveMode(context)) {
            return minHz.coerceAtMost(60f)
        }
        return when (lod) {
            RenderLod.IDLE_LOW_POWER -> minHz.coerceAtMost(60f)
            RenderLod.BALANCED -> 60f.coerceIn(minHz, peakHz)
            RenderLod.HIGH_FPS -> peakHz
        }
    }

    /**
     * 为前台 Activity Window 应用目标 LOD 刷新率（仅当目标帧率真正变化时才提交 WindowManager，零额外开销）
     */
    fun applyWindowLod(window: Window?, context: Context, lod: RenderLod) {
        if (window == null) return
        val hz = getTargetHz(context, lod)
        _currentLod.value = lod
        _targetHz.value = hz
        runCatching {
            val lp = window.attributes ?: return@runCatching
            if (kotlin.math.abs(lp.preferredRefreshRate - hz) > 0.5f) {
                lp.preferredRefreshRate = hz
                window.attributes = lp
            }
        }
    }

    /**
     * 为悬浮窗 WindowManager.LayoutParams 写入目标 LOD 刷新率
     */
    fun applyOverlayParamsLod(lp: WindowManager.LayoutParams, context: Context, lod: RenderLod): Float {
        val hz = getTargetHz(context, lod)
        lp.preferredRefreshRate = hz
        return hz
    }

    fun formatCurrentLodSummary(): String {
        return "${_currentLod.value.label} (${_targetHz.value.toInt()}Hz)"
    }
}
