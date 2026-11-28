package com.app.magiccapsule.service

import android.media.session.PlaybackState
import android.os.SystemClock
import kotlin.math.abs

/**
 * 高精度低功耗媒体进度推算与校准引擎（已融合荣耀原生 SeekBarViewModelKt / HnSeekBarObserver 算法）：
 * 1. 严格限制推算条件：调用 NativeMediaSeekBarObserver.isInMotion(state)（PLAYING / FAST_FORWARDING / REWINDING）且 playbackSpeed != 0 时才按时间差推算；
 *    缓冲（STATE_BUFFERING）、切歌（STATE_SKIPPING_TO_NEXT/PREVIOUS）、暂停（STATE_PAUSED）等状态立刻冻结，直接返回 position
 * 2. 切歌瞬间归零并重置锚点：比对 MEDIA_ID / TITLE，换歌立即清零基准与旧 lastPositionUpdateTime，杜绝旧歌污染新歌
 * 3. 配合防抖延迟校准（600ms / 1500ms）：支持强制覆盖本地基准值拉平缓冲误差，日常播放零 Binder 轮询
 * 4. 边界钳位 + 大偏差瞬时对齐：所有计算结果严格 coerceIn(0L, totalDuration)，偏差 > 1.5s 直接以系统回调为准不做平滑过渡
 */
object MediaProgressSyncEngine {

    data class SyncSnapshot(
        val positionMs: Long,
        val durationMs: Long,
        val isActivelyPlaying: Boolean,
        val playbackSpeed: Float,
        val state: Int,
        val snappedBigJump: Boolean = false
    ) {
        fun toObserverProgress(seekAvailable: Boolean): NativeMediaSeekBarObserver.Progress {
            return NativeMediaSeekBarObserver.Progress(
                enabled = durationMs > 0L,
                seekAvailable = seekAvailable,
                playing = state == PlaybackState.STATE_PLAYING,
                inMotion = isActivelyPlaying,
                elapsedTimeMs = positionMs,
                durationMs = durationMs
            )
        }
    }

    @Volatile private var currentTrackIdentity: String = ""
    @Volatile private var anchorDurationMs: Long = 0L
    @Volatile private var anchorPosMs: Long = 0L
    @Volatile private var anchorElapsedMs: Long = 0L
    @Volatile private var anchorState: Int = PlaybackState.STATE_NONE
    @Volatile private var anchorSpeed: Float = 0f
    @Volatile private var lastRawPosMs: Long = -1L
    @Volatile private var lastRawUpdateTimeMs: Long = -1L

    /**
     * 快速连点快退/快进时的乐观锚点同步：
     * 立即将本地基准位置对齐至目标 seek 位置，保证进度条与 Chronometer 读秒同步跳变，
     * 随后由 600ms / 1500ms 延迟校准以播放器真实 PlaybackState 强制拉平。
     */
    @Synchronized
    fun applyOptimisticSeek(targetPosMs: Long): SyncSnapshot {
        val now = SystemClock.elapsedRealtime()
        anchorPosMs = clampToDuration(targetPosMs, anchorDurationMs)
        anchorElapsedMs = now
        return SyncSnapshot(
            positionMs = anchorPosMs,
            durationMs = anchorDurationMs,
            isActivelyPlaying = isActivelyPlaying(),
            playbackSpeed = anchorSpeed,
            state = anchorState,
            snappedBigJump = true
        )
    }

    @Synchronized
    fun resetAll() {
        currentTrackIdentity = ""
        anchorDurationMs = 0L
        anchorPosMs = 0L
        anchorElapsedMs = 0L
        anchorState = PlaybackState.STATE_NONE
        anchorSpeed = 0f
        lastRawPosMs = -1L
        lastRawUpdateTimeMs = -1L
    }

    /**
     * 监听切歌事件：对比 MEDIA_ID 或 TITLE。
     * 一旦发现换歌，立即归零并清空旧的基准时间（防止旧歌的 lastPositionUpdateTime 污染新歌）。
     * @return true 表示发生了换歌并已归零重置
     */
    @Synchronized
    fun onTrackMetadataCheck(mediaId: String, title: String, durationMs: Long): Boolean {
        val cleanId = mediaId.trim()
        val cleanTitle = title.trim()
        val identity = if (cleanId.isNotBlank()) "id:$cleanId" else "title:$cleanTitle"
        if (identity == "title:") {
            if (durationMs > 0L) anchorDurationMs = durationMs
            return false
        }
        val changed = currentTrackIdentity.isNotBlank() && currentTrackIdentity != identity
        currentTrackIdentity = identity
        if (changed) {
            anchorDurationMs = durationMs.coerceAtLeast(0L)
            anchorPosMs = 0L
            anchorElapsedMs = 0L
            anchorState = PlaybackState.STATE_NONE
            anchorSpeed = 0f
            lastRawPosMs = -1L
            lastRawUpdateTimeMs = -1L
            return true
        }
        if (durationMs > 0L) {
            anchorDurationMs = durationMs
        }
        return false
    }

    /**
     * 应用系统 PlaybackState 回调或防抖校准（600ms / 1500ms）读取到的最新状态。
     * - forceOverwrite = true：延迟拉平校准时强制覆盖本地基准值
     * - 偏差 > 1500ms 时直接瞬时对齐系统值，不做平滑过渡
     */
    @Synchronized
    fun applySystemPlaybackState(
        ps: PlaybackState?,
        durationHintMs: Long = 0L,
        forceOverwrite: Boolean = false
    ): SyncSnapshot {
        val now = SystemClock.elapsedRealtime()
        val localBefore = computeClampedPositionMs(now)

        if (durationHintMs > 0L) {
            anchorDurationMs = durationHintMs
        }
        if (ps == null) {
            anchorState = PlaybackState.STATE_NONE
            anchorSpeed = 0f
            return SyncSnapshot(
                positionMs = computeClampedPositionMs(now),
                durationMs = anchorDurationMs,
                isActivelyPlaying = false,
                playbackSpeed = 0f,
                state = PlaybackState.STATE_NONE
            )
        }

        val state = ps.state
        val rawSpeed = ps.playbackSpeed
        val rawPos = ps.position.coerceAtLeast(0L)
        val rawUpdateTime = ps.lastPositionUpdateTime
        val canExtrapolate = NativeMediaSeekBarObserver.isInMotion(state) && rawSpeed != 0f

        val sysPosClamped = NativeMediaSeekBarObserver.computePosition(ps, anchorDurationMs, now)
        val bigJump = anchorElapsedMs > 0L && abs(sysPosClamped - localBefore) > 1500L

        val shouldOverwrite = forceOverwrite ||
            bigJump ||
            !canExtrapolate ||
            anchorElapsedMs <= 0L ||
            state != anchorState ||
            rawSpeed != anchorSpeed ||
            (rawUpdateTime > 0L && (rawUpdateTime != lastRawUpdateTimeMs || rawPos != lastRawPosMs)) ||
            (rawUpdateTime <= 0L && rawPos != lastRawPosMs)

        if (shouldOverwrite) {
            anchorPosMs = clampToDuration(rawPos, anchorDurationMs)
            anchorElapsedMs = if (rawUpdateTime > 0L) rawUpdateTime else now
            anchorState = state
            anchorSpeed = if (canExtrapolate) rawSpeed else 0f
            lastRawPosMs = rawPos
            lastRawUpdateTimeMs = rawUpdateTime
        }

        return SyncSnapshot(
            positionMs = computeClampedPositionMs(now),
            durationMs = anchorDurationMs,
            isActivelyPlaying = isActivelyPlaying(),
            playbackSpeed = anchorSpeed,
            state = anchorState,
            snappedBigJump = bigJump
        )
    }

    @Synchronized
    fun isActivelyPlaying(): Boolean {
        return NativeMediaSeekBarObserver.isInMotion(anchorState) && anchorSpeed != 0f
    }

    @Synchronized
    fun currentDurationMs(): Long = anchorDurationMs

    @Synchronized
    fun currentSpeed(): Float = anchorSpeed

    @Synchronized
    fun currentSnapshot(): SyncSnapshot {
        val now = SystemClock.elapsedRealtime()
        return SyncSnapshot(
            positionMs = computeClampedPositionMs(now),
            durationMs = anchorDurationMs,
            isActivelyPlaying = isActivelyPlaying(),
            playbackSpeed = anchorSpeed,
            state = anchorState
        )
    }

    /**
     * 纯本地推算当前毫秒进度（零 Binder 轮询）：
     * - 仅在 NativeMediaSeekBarObserver.isInMotion(anchorState) 且 speed != 0 时累加时间差
     * - 其他任意状态（缓冲/切歌/暂停）立刻冻结，直接返回 anchorPosMs
     * - 严格边界钳位至 [0, totalDuration]
     */
    @Synchronized
    fun computeClampedPositionMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        val raw = if (NativeMediaSeekBarObserver.isInMotion(anchorState) && anchorSpeed != 0f && anchorElapsedMs > 0L) {
            val delta = (nowElapsedMs - anchorElapsedMs).coerceAtLeast(0L)
            anchorPosMs + (delta * anchorSpeed).toLong()
        } else {
            anchorPosMs
        }
        return clampToDuration(raw, anchorDurationMs)
    }

    private fun clampToDuration(posMs: Long, durMs: Long): Long {
        return if (durMs > 0L) posMs.coerceIn(0L, durMs) else posMs.coerceAtLeast(0L)
    }
}
