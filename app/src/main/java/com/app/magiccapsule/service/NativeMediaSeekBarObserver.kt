package com.app.magiccapsule.service

import android.animation.Animator
import android.animation.ObjectAnimator
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.ProgressBar
import kotlin.math.abs

/**
 * 原生媒体进度条观察器（对齐荣耀 SystemUI `HnSeekBarObserver` + `SeekBarViewModelKt`）：
 * - 常量：`RESET_ANIMATION_THRESHOLD_MS = 250`、`RESET_ANIMATION_DURATION_MS = 750`
 * - 动画：回退/切歌重置进度超过阈值时使用 Material Emphasized 插值器平滑过渡，常规步进零动画开销
 * - 运动判定：`isInMotion()` 支持 PLAYING / FAST_FORWARDING / REWINDING，缓冲/切歌/暂停立刻冻结
 * - 时间格式化：严格对齐 `HnSeekBarObserver.formatElapsedTime`（支持 >= 1 小时显示 `H:MM:SS`）
 */
object NativeMediaSeekBarObserver {

    // 对齐 com.android.systemui.media.HnSeekBarObserver.<clinit> 常量
    const val RESET_ANIMATION_THRESHOLD_MS = 250
    const val RESET_ANIMATION_DURATION_MS = 750L

    // 对齐 SeekBarViewModel.updateController 位掩码（PlaybackState.ACTION_SEEK_TO = 256L）
    private const val ACTION_SEEK_MASK =
        PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND

    private val EMPHASIZED_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val LINEAR_INTERPOLATOR = LinearInterpolator()

    data class Progress(
        val enabled: Boolean,
        val seekAvailable: Boolean,
        val playing: Boolean,
        val inMotion: Boolean,
        val elapsedTimeMs: Long,
        val durationMs: Long
    )

    /**
     * 对齐 SeekBarViewModelKt.isInMotion(PlaybackState)：
     * 仅播放中、快进、快退属于推进态；缓冲/切歌/暂停均视为静止，防止进度条虚跑。
     */
    fun isInMotion(state: Int): Boolean {
        return state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING
    }

    fun isInMotion(ps: PlaybackState?): Boolean {
        return ps != null && isInMotion(ps.state)
    }

    /**
     * 对齐 SeekBarViewModel.updateController：判断当前媒体会话是否支持进度拖拽/快进快退。
     */
    fun isSeekAvailable(ps: PlaybackState?): Boolean {
        if (ps == null) return false
        return (ps.actions and ACTION_SEEK_MASK) != 0L
    }

    /**
     * 对齐 SeekBarViewModelKt.computePosition(PlaybackState, durationMs)：
     * 使用 SystemClock.elapsedRealtime() - lastPositionUpdateTime 与 playbackSpeed 推算并钳位。
     */
    fun computePosition(
        ps: PlaybackState?,
        durationMs: Long,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        if (ps == null) return 0L
        var position = ps.position.coerceAtLeast(0L)
        if (isInMotion(ps) && ps.lastPositionUpdateTime > 0L) {
            val speed = ps.playbackSpeed
            if (speed != 0f) {
                val delta = (nowElapsedMs - ps.lastPositionUpdateTime).coerceAtLeast(0L)
                position += (delta * speed).toLong()
            }
        }
        return if (durationMs > 0L) position.coerceIn(0L, durationMs) else position.coerceAtLeast(0L)
    }

    /**
     * 对齐 HnSeekBarObserver.formatElapsedTime(seconds)：
     * - < 1 小时：MM:SS（如 03:25）
     * - >= 1 小时：H:MM:SS（如 1:05:12）
     */
    fun formatElapsedTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    /**
     * 对齐 HnSeekBarObserver.onChanged + buildResetAnimator：
     * - 当进度大幅回退（如切歌归零、点击快退）超过阈值时，用 750ms Emphasized 动画平滑复位；
     * - 日常每秒前进 1 步直接 setProgress，避免无谓的动画帧唤醒 GPU。
     */
    fun applyProgress(
        bar: ProgressBar?,
        elapsedMs: Long,
        durationMs: Long,
        previousAnimator: Animator?
    ): Animator? {
        if (bar == null || durationMs <= 0L) return previousAnimator
        val targetPermille = ((elapsedMs.coerceIn(0L, durationMs) * 1000L) / durationMs)
            .toInt()
            .coerceIn(0, 1000)
        val currentPermille = bar.progress
        val deltaPermille = currentPermille - targetPermille
        // 将 250ms 阈值映射到千分比（同时至少 40‰ 才触发复位动画，避免微小抖动误触发）
        val thresholdPermille = ((RESET_ANIMATION_THRESHOLD_MS * 1000L) / durationMs)
            .toInt()
            .coerceIn(40, 200)

        if (deltaPermille > thresholdPermille && abs(deltaPermille) <= 1000) {
            previousAnimator?.cancel()
            return ObjectAnimator.ofInt(bar, "progress", currentPermille, targetPermille).apply {
                setAutoCancel(true)
                duration = RESET_ANIMATION_DURATION_MS
                interpolator = EMPHASIZED_INTERPOLATOR
                start()
            }
        }
        if (previousAnimator?.isRunning != true) {
            bar.progress = targetPermille
        }
        return previousAnimator
    }

    /**
     * 60fps 硬件连续进度条驱动（用于悬浮横幅实视图 ProgressBar）：
     * - 播放中：启动单次 LinearInterpolator ObjectAnimator 从当前千分比平滑推进至 1000‰，彻底消除 1s/2s 跳变；
     * - 暂停/缓冲：立即取消动画并冻结在精确位置；回退超过阈值时使用 750ms Emphasized 平滑复位。
     */
    fun startSmoothLinearProgress(
        bar: ProgressBar?,
        elapsedMs: Long,
        durationMs: Long,
        playing: Boolean,
        speed: Float,
        previousAnimator: Animator?,
        lowPowerStepMode: Boolean = false
    ): Animator? {
        if (bar == null || durationMs <= 0L) {
            previousAnimator?.cancel()
            return null
        }
        val clampedElapsed = elapsedMs.coerceIn(0L, durationMs)
        val targetPermille = ((clampedElapsed * 1000L) / durationMs).toInt().coerceIn(0, 1000)
        if (!playing || speed <= 0f || lowPowerStepMode) {
            previousAnimator?.cancel()
            return applyProgress(bar, clampedElapsed, durationMs, null)
        }
        val remainingMs = ((durationMs - clampedElapsed) / speed).toLong().coerceAtLeast(0L)
        if (remainingMs <= 100L || targetPermille >= 1000) {
            previousAnimator?.cancel()
            bar.progress = targetPermille
            return null
        }
        previousAnimator?.cancel()
        bar.progress = targetPermille
        return ObjectAnimator.ofInt(bar, "progress", targetPermille, 1000).apply {
            setAutoCancel(true)
            duration = remainingMs
            interpolator = LINEAR_INTERPOLATOR
            start()
        }
    }
}
