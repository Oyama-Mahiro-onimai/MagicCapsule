package com.app.magiccapsule.service

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import org.json.JSONObject

/**
 * 第三方实况通知关联字段解析器（复用现有胶囊替换链路）：
 * 自动从任意第三方通知的 Notification.extras 中提取：
 * 1. vivo 原子岛协议（notification.superx.*：高德导航/打车、美团/饿了么外卖、航班/火车等）
 * 2. 小米 HyperOS 焦点通知协议（miui.focus.param + miui.focus.pics）
 * 提取出精简胶囊短文案（pillText）、展开标题/副标题、实时转向/状态图标与进度百分比，
 * 直接交由现有 CapsulePublisher -> MagicCapsuleBridge / UniversalBannerHost 替换展示。
 */
object LiveNotificationExtraParser {

    data class ParsedLiveExtra(
        val source: String,
        val pillText: String,
        val title: String,
        val subtitle: String,
        val iconBitmap: Bitmap?,
        val progressPercent: Int = -1,
        val isFinish: Boolean = false
    )

    fun parse(context: Context, notification: Notification?): ParsedLiveExtra? {
        val extras = notification?.extras ?: return null
        parseVivoSuperX(context, extras)?.let { return it }
        parseXiaomiFocus(context, extras)?.let { return it }
        return null
    }

    private fun parseVivoSuperX(context: Context, extras: Bundle): ParsedLiveExtra? {
        val hasSuperX = extras.containsKey("notification.superx.operation") ||
            extras.containsKey("notification.superx.capsule") ||
            extras.containsKey("notification.superx.infos") ||
            extras.containsKey("notification.superx.baseInfos")
        if (!hasSuperX) return null

        val op = extras.getInt("notification.superx.operation", -1)
        val isFinish = (op == 2)

        val capsule = extras.getBundle("notification.superx.capsule")
        val infos = extras.getBundle("notification.superx.infos")
        val baseInfos = extras.getBundle("notification.superx.baseInfos")
        val shortInfos = extras.getBundle("notification.superx.shortInfos")

        val capContent = cleanText(capsule?.getString("notification.superx.capsule.content"))
        val shortCore = cleanText(shortInfos?.getString("notification.superx.shortInfos.coreInfoShort"))
        val shortDesc = cleanText(shortInfos?.getString("notification.superx.shortInfos.describeShort"))
        val navMsg = cleanText(infos?.getString("notification.superx.infos.navMsg"))
        val infoCore = cleanText(infos?.getString("notification.superx.infos.coreInfo"))
        val infoDesc = cleanText(infos?.getString("notification.superx.infos.describe"))
        val baseTitle = cleanText(baseInfos?.getString("notification.superx.baseInfos.title"))
        val baseContent = cleanText(baseInfos?.getString("notification.superx.baseInfos.content"))
        val baseSub = cleanText(baseInfos?.getString("notification.superx.baseInfos.subInfo"))

        val progress = infos?.getInt("notification.superx.infos.progress", -1) ?: -1
        val validPct = if (progress in 0..100) progress else -1

        val pill = firstNonBlank(capContent, shortCore, navMsg, infoCore, baseTitle)
        val title = firstNonBlank(navMsg, infoCore, baseTitle, pill)
        val rawSub = firstNonBlank(infoDesc, baseContent, baseSub, shortDesc)
        val subtitle = if (validPct >= 0 && rawSub.isNotBlank() && !rawSub.contains("%")) {
            "$rawSub · $validPct%"
        } else if (validPct >= 0 && rawSub.isBlank()) {
            "进度 $validPct%"
        } else {
            rawSub
        }

        if (!isFinish && pill.isBlank() && title.isBlank()) return null

        val iconBmp = extractIconBitmap(context, infos, "notification.superx.infos.navIcon")
            ?: extractIconBitmap(context, capsule, "notification.superx.capsule.icon")
            ?: extractIconBitmap(context, baseInfos, "notification.superx.baseInfos.icon")
            ?: extractIconBitmap(context, shortInfos, "notification.superx.shortInfos.icon")
            ?: extractIconBitmap(context, infos, "notification.superx.infos.image")

        return ParsedLiveExtra(
            source = "superx",
            pillText = pill.ifBlank { title },
            title = title.ifBlank { pill },
            subtitle = subtitle,
            iconBitmap = iconBmp,
            progressPercent = validPct,
            isFinish = isFinish
        )
    }

    private fun parseXiaomiFocus(context: Context, extras: Bundle): ParsedLiveExtra? {
        val paramStr = extras.getString("miui.focus.param")
        if (paramStr.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(paramStr)
            val isFinish = obj.optBoolean("close", false)
            val ticker = cleanText(obj.optString("ticker"))
            val aodTitle = cleanText(obj.optString("aodTitle"))
            val title = cleanText(obj.optString("title"))
            val content = cleanText(obj.optString("content"))

            val pill = firstNonBlank(ticker, aodTitle, title)
            val resolvedTitle = firstNonBlank(title, aodTitle, ticker)
            if (!isFinish && pill.isBlank() && resolvedTitle.isBlank()) return null

            val pics = extras.getBundle("miui.focus.pics")
            val iconBmp = extractIconBitmap(context, pics, "miui.focus.pic_large")
                ?: extractIconBitmap(context, pics, "miui.focus.tickerPicDark")

            ParsedLiveExtra(
                source = "miui_focus",
                pillText = pill.ifBlank { resolvedTitle },
                title = resolvedTitle.ifBlank { pill },
                subtitle = content,
                iconBitmap = iconBmp,
                isFinish = isFinish
            )
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun extractIconBitmap(context: Context, bundle: Bundle?, key: String): Bitmap? {
        if (bundle == null || !bundle.containsKey(key)) return null
        return runCatching {
            val raw: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bundle.getParcelable(key, Icon::class.java)
                    ?: bundle.getParcelable(key, Bitmap::class.java)
            } else {
                bundle.getParcelable(key)
            }
            val bmp: Bitmap? = when (raw) {
                is Bitmap -> if (!raw.isRecycled) raw else null
                is Icon -> iconToBitmap(context, raw)
                else -> null
            }
            if (bmp != null && !bmp.isRecycled) NotificationIconStore.roundedFromBitmap(bmp) else null
        }.getOrNull()
    }

    private fun iconToBitmap(context: Context, icon: Icon): Bitmap? {
        val d = icon.loadDrawable(context) ?: return null
        if (d is BitmapDrawable && d.bitmap != null && !d.bitmap.isRecycled) {
            return d.bitmap
        }
        val w = d.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(128) ?: 96
        val h = d.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(128) ?: 96
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        return bmp
    }

    private fun cleanText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.replace('\n', ' ').replace('\r', ' ').trim()
    }

    private fun firstNonBlank(vararg values: String): String {
        for (v in values) {
            if (v.isNotBlank()) return v
        }
        return ""
    }
}
