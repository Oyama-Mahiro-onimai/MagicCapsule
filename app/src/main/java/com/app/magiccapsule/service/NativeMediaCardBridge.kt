package com.app.magiccapsule.service

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import com.app.magiccapsule.crash.AppLogger

/**
 * 荣耀控制中心媒体卡片 + 酷狗 KGMediaSession 双向协议桥接（对齐 SystemUI classes2.dex 与 酷狗 classes6.dex）：
 * 1. 歌词双向命令协议：
 *    - EVENT_COMMAND_QUERY_LYRIC / SHOW_LYRIC / HIDE_LYRIC / UNLOCK_LYRIC
 *    - EVENT_LYRIC_STATE_CHANGED（同时解析 KEY_LYRIC_STATE 与 KEY_LYRIC_LOCK_STATE）
 * 2. 原生收藏/红心协议：同时兼容 android.media.metadata.USER_RATING 与酷狗写入的 android.media.metadata.RATING
 * 3. 原生实时歌词元数据：读取 android.media.metadata.LYRIC
 */
object NativeMediaCardBridge {

    // 荣耀 SystemUI + 酷狗 KGMediaSession 原生歌词 4 指令 + 2 状态键
    const val EVENT_COMMAND_QUERY_LYRIC = "EVENT_COMMAND_QUERY_LYRIC"
    const val EVENT_COMMAND_SHOW_LYRIC = "EVENT_COMMAND_SHOW_LYRIC"
    const val EVENT_COMMAND_HIDE_LYRIC = "EVENT_COMMAND_HIDE_LYRIC"
    const val EVENT_COMMAND_UNLOCK_LYRIC = "EVENT_COMMAND_UNLOCK_LYRIC"
    const val EVENT_LYRIC_STATE_CHANGED = "EVENT_LYRIC_STATE_CHANGED"
    const val KEY_LYRIC_STATE = "KEY_LYRIC_STATE"
    const val KEY_LYRIC_LOCK_STATE = "KEY_LYRIC_LOCK_STATE"

    // 媒体元数据键（兼容 USER_RATING 与酷狗 RATING / LYRIC）
    const val METADATA_KEY_USER_RATING = MediaMetadata.METADATA_KEY_USER_RATING // "android.media.metadata.USER_RATING"
    const val METADATA_KEY_RATING = MediaMetadata.METADATA_KEY_RATING           // "android.media.metadata.RATING"
    const val METADATA_KEY_DURATION = MediaMetadata.METADATA_KEY_DURATION       // "android.media.metadata.DURATION"
    const val METADATA_KEY_LYRIC = "android.media.metadata.LYRIC"

    const val PKG_XIMALAYA = "com.ximalaya.ting.android"

    // 酷狗概念版/酷狗音乐（classes4.dex + classes6.dex 实测验证）：全局注册的桌面歌词锁词/解锁定向广播
    const val ACTION_KUGOU_LYRIC_LOCK = "com.kugou.android.minilyric.islock"
    const val EXTRA_KUGOU_IS_LOCK = "is_lock_extra"
    const val EXTRA_KUGOU_FROM_NOTIF = "is_from_notification"

    val LYRIC_CAPABLE_PACKAGES: Set<String> = setOf(
        "cn.kuwo.player",
        "cmccwm.mobilemusic",
        "com.kugou.android",
        "com.kugou.android.lite",
        "com.tencent.qqmusic",
        "com.netease.cloudmusic",
        "com.hihonor.cloudmusic",
        "com.android.mediacenter"
    )

    data class LyricEventState(
        val isShown: Boolean,
        val isLocked: Boolean?
    )

    fun isInLyricWhitelist(packageName: String?): Boolean {
        return !packageName.isNullOrBlank() && packageName in LYRIC_CAPABLE_PACKAGES
    }

    fun queryLyricState(controller: MediaController?, handler: Handler): Boolean {
        if (controller == null) return false
        return runCatching {
            controller.sendCommand(EVENT_COMMAND_QUERY_LYRIC, Bundle(), ResultReceiver(handler))
            true
        }.getOrDefault(false)
    }

    fun sendLyricCommand(controller: MediaController?, show: Boolean, handler: Handler): Boolean {
        if (controller == null) return false
        val cmd = if (show) EVENT_COMMAND_SHOW_LYRIC else EVENT_COMMAND_HIDE_LYRIC
        return runCatching {
            controller.sendCommand(cmd, Bundle(), ResultReceiver(handler))
            AppLogger.i("Capsule", "原生媒体卡片下发歌词指令 $cmd pkg=${controller.packageName}")
            true
        }.getOrDefault(false)
    }

    /**
     * 对齐酷狗 com.kugou.framework.player.c$a.onCommand("EVENT_COMMAND_UNLOCK_LYRIC")：
     * 通过跨进程 MediaSession Binder 原生一键解锁桌面歌词（内部转发 com.kugou.android.minilyric.islock, is_lock_extra=false）。
     */
    fun isKugouPackage(packageName: String?): Boolean {
        return !packageName.isNullOrBlank() && packageName.startsWith("com.kugou.")
    }

    /**
     * 对齐酷狗 classes4.dex(com.kugou.common.b.a.c) + classes6.dex(com.kugou.framework.service.g.e$1)：
     * 发送定向广播 com.kugou.android.minilyric.islock（is_lock_extra=true 锁定 / false 解锁）；
     * 若已授权 Shizuku，则额外异步通过 shell am broadcast 双保险送达（绕过跨 UID 后台广播限制）。
     */
    fun sendKugouLyricLockBroadcast(
        context: Context,
        packageName: String,
        lock: Boolean,
        onCompleted: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (!isKugouPackage(packageName)) return false
        // 实时刷新 Shizuku 绑定与授权状态，避免后台进程刚拉起时缓存为 false 导致跳过 Shizuku 通道
        runCatching { ShizukuBridge.refreshState(autoRequest = false, triggerAutoGrantIfNeeded = false) }
        val shizukuReady = ShizukuBridge.binderAlive.value && ShizukuBridge.permissionGranted.value

        if (shizukuReady) {
            SoCThreadPools.monitorIoExecutor.execute {
                val (ok, out) = ShizukuBridge.runShellCommand(
                    "am", "broadcast",
                    "-a", ACTION_KUGOU_LYRIC_LOCK,
                    "-p", packageName,
                    "--ez", EXTRA_KUGOU_IS_LOCK, lock.toString()
                )
                AppLogger.i("Capsule", "Shizuku 酷狗锁词广播结果 pkg=$packageName lock=$lock ok=$ok out=${out.take(100)}")
                if (!ok) {
                    // Shizuku 命令异常时再退回普通显式广播（不附加 is_from_notification，与实测命令 100% 一致）
                    runCatching {
                        val intent = Intent(ACTION_KUGOU_LYRIC_LOCK).apply {
                            setPackage(packageName)
                            putExtra(EXTRA_KUGOU_IS_LOCK, lock)
                        }
                        context.sendBroadcast(intent)
                    }
                }
                onCompleted?.invoke(ok)
            }
            AppLogger.i("Capsule", "已派发 Shizuku 酷狗锁词广播 pkg=$packageName lock=$lock")
            return true
        }

        val directOk = runCatching {
            val intent = Intent(ACTION_KUGOU_LYRIC_LOCK).apply {
                setPackage(packageName)
                putExtra(EXTRA_KUGOU_IS_LOCK, lock)
            }
            context.sendBroadcast(intent)
            true
        }.getOrDefault(false)
        AppLogger.w("Capsule", "Shizuku 未就绪(alive=${ShizukuBridge.binderAlive.value}, grant=${ShizukuBridge.permissionGranted.value})，已降级普通广播 pkg=$packageName lock=$lock directOk=$directOk")
        onCompleted?.invoke(directOk)
        return directOk
    }

    fun sendUnlockLyricCommand(controller: MediaController?, handler: Handler): Boolean {
        if (controller == null) return false
        return runCatching {
            controller.sendCommand(EVENT_COMMAND_UNLOCK_LYRIC, Bundle(), ResultReceiver(handler))
            AppLogger.i("Capsule", "已通过 MediaSession 原生下发 EVENT_COMMAND_UNLOCK_LYRIC pkg=${controller.packageName}")
            true
        }.getOrDefault(false)
    }

    private fun parseFlag(extras: Bundle, key: String): Boolean? {
        if (!extras.containsKey(key)) return null
        val raw = extras.get(key)
        return when (raw) {
            is Boolean -> raw
            is Int -> raw != 0
            is String -> raw.equals("true", ignoreCase = true) || raw == "1"
            else -> extras.getBoolean(key, false)
        }
    }

    /**
     * 同时解析 EVENT_LYRIC_STATE_CHANGED 中的 KEY_LYRIC_STATE 与 KEY_LYRIC_LOCK_STATE。
     */
    fun parseLyricEventFull(event: String, extras: Bundle?): LyricEventState? {
        if (event != EVENT_LYRIC_STATE_CHANGED || extras == null) return null
        val shown = parseFlag(extras, KEY_LYRIC_STATE) ?: false
        val locked = parseFlag(extras, KEY_LYRIC_LOCK_STATE)
        return LyricEventState(isShown = shown, isLocked = locked)
    }

    fun parseLyricEventState(event: String, extras: Bundle?): Boolean? {
        return parseLyricEventFull(event, extras)?.isShown
    }

    fun extractLiveLyricLine(metadata: MediaMetadata?): String {
        if (metadata == null) return ""
        return runCatching {
            metadata.getString(METADATA_KEY_LYRIC)?.replace('\n', ' ')?.trim().orEmpty()
        }.getOrDefault("")
    }

    fun isNativeRatingSupported(controller: MediaController?): Boolean {
        if (controller == null) return false
        return runCatching { controller.ratingType != Rating.RATING_NONE }.getOrDefault(false)
    }

    private fun readAnyRating(metadata: MediaMetadata?): Rating? {
        if (metadata == null) return null
        return runCatching {
            metadata.getRating(METADATA_KEY_USER_RATING) ?: metadata.getRating(METADATA_KEY_RATING)
        }.getOrNull()
    }

    fun isCurrentlyFavorited(controller: MediaController?): Boolean {
        if (controller == null) return false
        return runCatching {
            val rating = readAnyRating(controller.metadata) ?: return false
            when (rating.ratingStyle) {
                Rating.RATING_HEART -> rating.hasHeart()
                Rating.RATING_THUMB_UP_DOWN -> rating.isThumbUp
                Rating.RATING_3_STARS, Rating.RATING_4_STARS, Rating.RATING_5_STARS -> rating.starRating > 0f
                Rating.RATING_PERCENTAGE -> rating.percentRating > 0f
                else -> false
            }
        }.getOrDefault(false)
    }

    fun toggleNativeFavorite(controller: MediaController?): Pair<Boolean, Boolean> {
        if (controller == null) return Pair(false, false)
        return runCatching {
            val ratingType = controller.ratingType
            if (ratingType == Rating.RATING_NONE) return Pair(false, false)
            val current = readAnyRating(controller.metadata)
            val style = current?.ratingStyle?.takeIf { it != Rating.RATING_NONE } ?: ratingType
            val (nextRating, nextFav) = when (style) {
                Rating.RATING_HEART -> {
                    val target = !(current?.hasHeart() ?: false)
                    Rating.newHeartRating(target) to target
                }
                Rating.RATING_THUMB_UP_DOWN -> {
                    val target = !(current?.isThumbUp ?: false)
                    Rating.newThumbRating(target) to target
                }
                Rating.RATING_3_STARS -> {
                    val target = (current?.starRating ?: 0f) <= 0f
                    Rating.newStarRating(Rating.RATING_3_STARS, if (target) 3f else 0f) to target
                }
                Rating.RATING_4_STARS -> {
                    val target = (current?.starRating ?: 0f) <= 0f
                    Rating.newStarRating(Rating.RATING_4_STARS, if (target) 4f else 0f) to target
                }
                Rating.RATING_5_STARS -> {
                    val target = (current?.starRating ?: 0f) <= 0f
                    Rating.newStarRating(Rating.RATING_5_STARS, if (target) 5f else 0f) to target
                }
                Rating.RATING_PERCENTAGE -> {
                    val nextPct = if ((current?.percentRating ?: 0f) >= 100f) 0f else 100f
                    Rating.newPercentageRating(nextPct) to (nextPct > 0f)
                }
                else -> return Pair(false, false)
            }
            controller.transportControls.setRating(nextRating)
            AppLogger.i(
                "Capsule",
                "原生媒体卡片切换收藏 pkg=${controller.packageName} ratingType=$style favorited=$nextFav"
            )
            Pair(true, nextFav)
        }.getOrDefault(Pair(false, false))
    }

    fun extractDurationMs(controller: MediaController?): Long {
        if (controller == null) return 0L
        return runCatching {
            controller.metadata?.getLong(METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
        }.getOrDefault(0L)
    }
}
