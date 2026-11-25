package com.app.magiccapsule.service

import android.app.PendingIntent
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
    val channelName: String = ""
)

/**
 * 统一的图标解码、圆角裁剪、手动图标加载与镜像负载缓存：
 * - 灵动胶囊（状态栏胶囊图标 + 展开卡片图标 + 通知中心卡片）与顶层横幅共用同一套 96px 圆角位图
 * - 支持手动胶囊选择内置矢量图标或从相册导入自定义图标
 */
object NotificationIconStore {

    private const val ICON_SIZE_PX = 96
    private const val ICON_RADIUS_PX = 22f
    private const val CUSTOM_ICON_FILE = "manual_capsule_icon.png"

    private val appIconCache = object : LruCache<String, Bitmap>(24) {}
    private val recentPayloads = ConcurrentHashMap<String, ActiveMirroredPayload>()

    @Volatile
    var currentActivePayload: ActiveMirroredPayload? = null

    @Volatile
    private var cachedCustomManualIcon: Bitmap? = null

    fun putPayload(payload: ActiveMirroredPayload) {
        if (recentPayloads.size > 40) {
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
        return out
    }

    fun resolveNotificationBitmap(context: Context, sbn: StatusBarNotification): Bitmap? {
        val n = sbn.notification
        val largeDrawable = runCatching { n?.getLargeIcon()?.loadDrawable(context) }.getOrNull()
        if (largeDrawable != null) {
            return drawableToRoundedBitmap(largeDrawable)
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
        return out
    }

    private fun bitmapToRoundedSquare(src: Bitmap, sizePx: Int, cornerRadiusPx: Float): Bitmap {
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
        return out
    }
}
