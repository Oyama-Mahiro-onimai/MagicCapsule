package com.app.magiccapsule.service

import android.app.Notification
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.os.Build
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.service.notification.StatusBarNotification
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.app.magiccapsule.R
import com.app.magiccapsule.model.CapsuleConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

enum class MediaContentType {
    MUSIC,
    VIDEO,
    RECORDER
}

data class ActiveMirroredPayload(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val iconBitmap: Bitmap?,
    val contentIntent: PendingIntent?,
    val actions: List<BannerAction>,
    val fullScreenIntent: PendingIntent? = null,
    val isCall: Boolean = false,
    val importance: Int = 3,
    val channelName: String = "",
    val isMedia: Boolean = false,
    val mediaDurationMs: Long = 0L,
    val mediaPositionMs: Long = 0L,
    val mediaPlaying: Boolean = false,
    val isRecorder: Boolean = false,
    val chronometerBaseMs: Long = 0L,
    val chronometerPaused: Boolean = false,
    val mediaContentType: MediaContentType = MediaContentType.MUSIC,
    val mediaSupportsSeek: Boolean = false,
    val mediaSessionToken: MediaSession.Token? = null,
    val liveLyricLine: String = "",
    val bannerNativeLyric: String = "",
    val livePillText: String = ""
)

/**
 * 统一的图标解码、圆角裁剪、手动图标加载与镜像负载缓存：
 * - 灵动胶囊（状态栏胶囊图标 + 展开卡片图标 + 通知中心卡片）与顶层横幅共用同一套 96px 圆角位图
 * - 支持手动胶囊选择内置矢量图标或从相册导入自定义图标
 */
object NotificationIconStore {

    private const val ICON_SIZE_PX = 72
    private const val ICON_RADIUS_PX = 16f
    private const val CUSTOM_ICON_FILE = "manual_capsule_icon.png"

    private val appIconCache = object : LruCache<String, Bitmap>(12) {}
    private val recentPayloads = ConcurrentHashMap<String, ActiveMirroredPayload>()

    @Volatile
    var currentActivePayload: ActiveMirroredPayload? = null

    @Volatile
    private var cachedCustomManualIcon: Bitmap? = null

    fun putPayload(payload: ActiveMirroredPayload) {
        if (recentPayloads.size > 12) {
            recentPayloads.clear()
        }
        recentPayloads[payload.key] = payload
    }

    fun getPayload(key: String): ActiveMirroredPayload? = recentPayloads[key]

    fun removePayload(key: String) {
        recentPayloads.remove(key)
        if (currentActivePayload?.key == key) {
            currentActivePayload = null
        }
    }

    fun trimMemory() {
        appIconCache.evictAll()
        val active = currentActivePayload
        recentPayloads.clear()
        if (active != null) {
            recentPayloads[active.key] = active
        }
    }

    fun saveCustomManualIcon(context: Context, uri: Uri): Bitmap? = runCatching {
        val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return null
        val rounded = bitmapToRoundedSquare(raw, ICON_SIZE_PX, ICON_RADIUS_PX)
        if (raw != rounded) raw.recycle()
        val outFile = File(context.filesDir, CUSTOM_ICON_FILE)
        FileOutputStream(outFile).use { fos ->
            rounded.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        cachedCustomManualIcon = rounded
        rounded
    }.getOrNull()

    fun resolveManualIconBitmap(context: Context, config: CapsuleConfig): Bitmap? {
        return when (config.manualIconPreset) {
            CapsuleConfig.ICON_NONE -> null
            CapsuleConfig.ICON_APP -> resolveAppIconBitmap(context, context.packageName)
            CapsuleConfig.ICON_CUSTOM_IMAGE -> {
                cachedCustomManualIcon?.let { return it }
                val f = File(context.filesDir, CUSTOM_ICON_FILE)
                if (f.exists()) {
                    runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()?.also {
                        cachedCustomManualIcon = it
                    }
                } else {
                    resolveAppIconBitmap(context, context.packageName)
                }
            }
            else -> {
                val resId = when (config.manualIconPreset) {
                    CapsuleConfig.ICON_NOTIF -> R.drawable.ic_px_notifications_filled
                    CapsuleConfig.ICON_SHIELD -> R.drawable.ic_px_shield
                    CapsuleConfig.ICON_PALETTE -> R.drawable.ic_px_palette_filled
                    CapsuleConfig.ICON_TUNE -> R.drawable.ic_px_tune
                    else -> R.drawable.ic_px_notifications_filled
                }
                val bg = CapsuleConfig.parseColorSafe(config.btnBgHex, Color.parseColor("#FF065F46"))
                val fg = CapsuleConfig.parseColorSafe(config.accentColorHex, Color.parseColor("#FF34D399"))
                renderVectorBadgeBitmap(context, resId, bg, fg)
            }
        }
    }

    private fun renderVectorBadgeBitmap(
        context: Context,
        resId: Int,
        bgColor: Int,
        tintColor: Int
    ): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, resId)?.mutate() ?: return null
        drawable.setTint(tintColor)
        val out = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        canvas.drawRoundRect(
            RectF(0f, 0f, ICON_SIZE_PX.toFloat(), ICON_SIZE_PX.toFloat()),
            ICON_RADIUS_PX,
            ICON_RADIUS_PX,
            paint
        )
        val pad = ICON_SIZE_PX / 5
        drawable.setBounds(pad, pad, ICON_SIZE_PX - pad, ICON_SIZE_PX - pad)
        drawable.draw(canvas)
        out.prepareToDraw()
        return out
    }

    fun bitmapFingerprint(bmp: Bitmap?): Int {
        if (bmp == null || bmp.isRecycled) return 0
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return 0
        var hash = w * 31 + h
        val total = w * h
        if (total <= 10_000) {
            // 小尺寸胶囊图标（如 72×72 导航罗盘）全图步长 2 采样，彻底消除外圈 0° 正上方盲区
            val pixels = IntArray(total)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            var idx = 0
            while (idx < total) {
                hash = hash * 31 + pixels[idx]
                idx += 2
            }
            return hash
        }
        // 大图封面：覆盖边缘至中心的 8×8 均匀网格采样
        for (gy in 0..7) {
            val y = (((h - 1) * gy) / 7).coerceIn(0, h - 1)
            for (gx in 0..7) {
                val x = (((w - 1) * gx) / 7).coerceIn(0, w - 1)
                hash = hash * 31 + bmp.getPixel(x, y)
            }
        }
        return hash
    }

    fun roundedFromBitmap(src: Bitmap): Bitmap =
        bitmapToRoundedSquare(src, ICON_SIZE_PX, ICON_RADIUS_PX, recycleSource = false)

    @Suppress("DEPRECATION")
    data class MediaPlaybackInfo(
        val isMedia: Boolean,
        val durationMs: Long,
        val positionMs: Long,
        val isPlaying: Boolean,
        val supportsSeek: Boolean = false,
        val sessionToken: MediaSession.Token? = null
    )

    @Suppress("DEPRECATION")
    fun extractMediaPlaybackInfo(context: Context, notification: Notification?): MediaPlaybackInfo {
        val extras = notification?.extras ?: return MediaPlaybackInfo(false, 0L, 0L, false)
        val token = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            }
        }.getOrNull() ?: return MediaPlaybackInfo(false, 0L, 0L, false)

        return runCatching {
            val ctrl = MediaController(context, token)
            val dur = ctrl.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
            val ps = ctrl.playbackState
            val playing = ps?.state == PlaybackState.STATE_PLAYING
            var pos = ps?.position?.coerceAtLeast(0L) ?: 0L
            if (playing && ps != null && ps.lastPositionUpdateTime > 0L) {
                val delta = (SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime).coerceAtLeast(0L)
                pos = (pos + (delta * ps.playbackSpeed).toLong()).coerceAtLeast(0L)
            }
            if (dur > 0L) pos = pos.coerceAtMost(dur)
            val acts = ps?.actions ?: 0L
            val supportsSeek = (acts and (
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_REWIND
            )) != 0L
            MediaPlaybackInfo(true, dur, pos, playing, supportsSeek, token)
        }.getOrDefault(MediaPlaybackInfo(true, 0L, 0L, false, false, token))
    }

    fun resolveMediaSessionArtwork(context: Context, notification: Notification?): Bitmap? {
        val extras = notification?.extras ?: return null
        val token = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            }
        }.getOrNull() ?: return null

        val meta = runCatching { MediaController(context, token).metadata }.getOrNull() ?: return null
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        if (art.isRecycled) return null
        return roundedFromBitmap(art)
    }

    @Suppress("DEPRECATION")
    fun resolveNotificationBitmap(context: Context, sbn: StatusBarNotification): Bitmap? {
        val n = sbn.notification

        // 1. 音乐/媒体通知优先从实时 MediaSession Metadata 读取当前曲目最新封面（避免异步换曲时通知自带旧图）
        val mediaArt = resolveMediaSessionArtwork(context, n)
        if (mediaArt != null) return mediaArt

        // 2. 读取通知最新 largeIcon
        val largeDrawable = runCatching { n?.getLargeIcon()?.loadDrawable(context) }.getOrNull()
        if (largeDrawable != null) {
            return drawableToRoundedBitmap(largeDrawable)
        }

        // 3. 兼容部分应用写在 extras 里的 EXTRA_LARGE_ICON_BIG / EXTRA_LARGE_ICON
        val extras = n?.extras
        if (extras != null) {
            val extraBig = runCatching { extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON_BIG) }.getOrNull()
                ?: runCatching { extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON) }.getOrNull()
            if (extraBig != null && !extraBig.isRecycled) {
                return roundedFromBitmap(extraBig)
            }
            val extraIcon = runCatching { extras.getParcelable<Icon>(Notification.EXTRA_LARGE_ICON_BIG) }.getOrNull()
                ?: runCatching { extras.getParcelable<Icon>(Notification.EXTRA_LARGE_ICON) }.getOrNull()
            val extraDrawable = runCatching { extraIcon?.loadDrawable(context) }.getOrNull()
            if (extraDrawable != null) {
                return drawableToRoundedBitmap(extraDrawable)
            }
        }

        val appBitmap = resolveAppIconBitmap(context, sbn.packageName)
        if (appBitmap != null) return appBitmap

        val smallDrawable = runCatching { n?.smallIcon?.loadDrawable(context) }.getOrNull()
        return smallDrawable?.let { drawableToRoundedBitmap(it) }
    }

    fun resolveAppIconBitmap(context: Context, packageName: String): Bitmap? {
        if (packageName.isBlank()) return null
        appIconCache.get(packageName)?.let { return it }
        val drawable = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
            ?: return null
        val bmp = drawableToRoundedBitmap(drawable)
        appIconCache.put(packageName, bmp)
        return bmp
    }

    fun drawableToRoundedBitmap(
        drawable: Drawable,
        sizePx: Int = ICON_SIZE_PX,
        cornerRadiusPx: Float = ICON_RADIUS_PX
    ): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()),
                cornerRadiusPx,
                cornerRadiusPx,
                Path.Direction.CW
            )
        }
        canvas.clipPath(path)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        out.prepareToDraw()
        return out
    }

    private fun bitmapToRoundedSquare(
        src: Bitmap,
        sizePx: Int,
        cornerRadiusPx: Float,
        recycleSource: Boolean = false
    ): Bitmap {
        val scaled = Bitmap.createScaledBitmap(src, sizePx, sizePx, true)
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()),
                cornerRadiusPx,
                cornerRadiusPx,
                Path.Direction.CW
            )
        }
        canvas.clipPath(path)
        canvas.drawBitmap(scaled, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        if (scaled != src) scaled.recycle()
        if (recycleSource && !src.isRecycled) src.recycle()
        out.prepareToDraw()
        return out
    }
}
