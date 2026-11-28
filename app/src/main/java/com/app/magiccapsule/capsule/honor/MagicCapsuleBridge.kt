package com.app.magiccapsule.capsule.honor

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.os.Parcelable
import android.util.TypedValue
import android.widget.RemoteViews
import com.app.magiccapsule.R
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.model.CapsuleConfig
import java.lang.reflect.Method

/**
 * 荣耀 framework-magic + HnMediaDynamicControllerImpl 原生灵动胶囊桥接：
 * - 首次加载缓存全部 Class/Method 句柄（零重复反射）
 * - 对齐 HnMediaDynamicControllerImpl.createCapsuleData / updateRightImage：
 *   支持 CapsuleData.setFirstIcon(Icon) + StatusBarData.setSecondIcon(Icon)（双图模板右侧动态音律/暂停徽标）
 *   以及 setIcon2 / setIconAnim1 / setIconAnim2 / NotificationEx.getHnParcelable 扩展句柄
 */
object MagicCapsuleBridge {

    private const val KEY_SMART_ISLAND_CAPSULE_DATA = "smart_island_capsule_data"
    private const val KEY_IS_CAPSULE_BUSINESS_KEY_STEP = "is_capsule_business_key_step"

    private class ReflectionHandles(
        val capsuleCls: Class<*>,
        val extraCls: Class<*>,
        val statusCls: Class<*>,
        val setBusinessName: Method,
        val setClickOpenActivity: Method,
        val setControlCapsuleState: Method,
        val setDegreeLevel: Method,
        val setChronometer: Method,
        val setTimePause: Method,
        val setSecondIcon: Method?,
        val setIcon2: Method?,
        val setIconAnim1: Method?,
        val setIconAnim2: Method?,
        val setStatusBarData: Method,
        val setStatusBarTemplate: Method,
        val setBrandColor: Method,
        val setFirstIcon: Method,
        val setMainContent: Method,
        val setSubContent: Method,
        val setMainContentRemoteViews: Method?,
        val setSubContentRemoteViews: Method?,
        val setExtraData: Method,
        val setHnFeatureParcelable: Method,
        val setHnFeatureString: Method,
        val setExpandButtonState: Method?,
        val getHnParcelable: Method?,
        val aodCls: Class<*>?,
        val setAodBackground: Method?,
        val setAodData: Method?
    )

    private val handles: ReflectionHandles? by lazy {
        runCatching {
            val capsuleCls = Class.forName("com.hihonor.android.app.CapsuleData")
            val extraCls = Class.forName("com.hihonor.android.app.CapsuleData\$ExtraData")
            val statusCls = Class.forName("com.hihonor.android.app.CapsuleData\$StatusBarData")
            val notifExCls = Class.forName("com.hihonor.android.app.NotificationEx")
            ReflectionHandles(
                capsuleCls = capsuleCls,
                extraCls = extraCls,
                statusCls = statusCls,
                setBusinessName = statusCls.getMethod("setBusinessName", String::class.java),
                setClickOpenActivity = statusCls.getMethod("setClickOpenActivity", Boolean::class.javaPrimitiveType),
                setControlCapsuleState = statusCls.getMethod("setControlCapsuleState", Boolean::class.javaPrimitiveType),
                setDegreeLevel = statusCls.getMethod("setDegreeLevel", Int::class.javaPrimitiveType),
                setChronometer = statusCls.getMethod("setChronometer", Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType),
                setTimePause = statusCls.getMethod("setTimePause", Boolean::class.javaPrimitiveType),
                setSecondIcon = runCatching { statusCls.getMethod("setSecondIcon", Icon::class.java) }.getOrNull(),
                setIcon2 = runCatching { statusCls.getMethod("setIcon2", String::class.java, String::class.java) }.getOrNull(),
                setIconAnim1 = runCatching { statusCls.getMethod("setIconAnim1", String::class.java) }.getOrNull(),
                setIconAnim2 = runCatching { statusCls.getMethod("setIconAnim2", String::class.java) }.getOrNull(),
                setStatusBarData = extraCls.getMethod("setStatusBarData", statusCls),
                setStatusBarTemplate = capsuleCls.getMethod("setStatusBarTemplate", String::class.java),
                setBrandColor = capsuleCls.getMethod("setBrandColor", Int::class.javaPrimitiveType),
                setFirstIcon = capsuleCls.getMethod("setFirstIcon", Icon::class.java),
                setMainContent = capsuleCls.getMethod("setMainContent", Array<String>::class.java),
                setSubContent = capsuleCls.getMethod("setSubContent", Array<String>::class.java),
                setMainContentRemoteViews = runCatching {
                    capsuleCls.getMethod("setMainContentRemoteViews", RemoteViews::class.java)
                }.getOrNull(),
                setSubContentRemoteViews = runCatching {
                    capsuleCls.getMethod("setSubContentRemoteViews", RemoteViews::class.java)
                }.getOrNull(),
                setExtraData = capsuleCls.getMethod("setExtraData", extraCls),
                setHnFeatureParcelable = notifExCls.getMethod(
                    "setHnFeature",
                    Notification::class.java,
                    String::class.java,
                    Parcelable::class.java
                ),
                setHnFeatureString = notifExCls.getMethod(
                    "setHnFeature",
                    Notification::class.java,
                    String::class.java,
                    String::class.java
                ),
                setExpandButtonState = runCatching {
                    notifExCls.getMethod(
                        "setExpandButtonState",
                        Notification::class.java,
                        Boolean::class.javaPrimitiveType
                    )
                }.getOrNull(),
                getHnParcelable = runCatching {
                    notifExCls.getMethod(
                        "getHnParcelable",
                        Notification::class.java,
                        String::class.java
                    )
                }.getOrNull(),
                aodCls = runCatching { Class.forName("com.hihonor.android.app.CapsuleData\$AodData") }.getOrNull(),
                setAodBackground = runCatching {
                    Class.forName("com.hihonor.android.app.CapsuleData\$AodData")
                        .getMethod("setBackground", String::class.java, String::class.java)
                }.getOrNull(),
                setAodData = runCatching {
                    val ac = Class.forName("com.hihonor.android.app.CapsuleData\$AodData")
                    extraCls.getMethod("setAodData", ac)
                }.getOrNull()
            )
        }.getOrNull()
    }

    @Volatile private var cachedPlayingBadgeIcon: Icon? = null
    @Volatile private var cachedPausedBadgeIcon: Icon? = null
    @Volatile private var cachedFirstIconHash: Int = 0
    @Volatile private var cachedFirstIcon: Icon? = null

    fun isSupported(): Boolean = handles != null

    /**
     * 对齐 HnMediaDynamicControllerImpl.updateRightImage：
     * 生成右侧音律条（播放中）或双竖暂停标（已暂停）硬件缓存 Icon，用于 image_image 模板的 StatusBarData.setSecondIcon。
     */
    private fun getMediaRightBadgeIcon(playing: Boolean): Icon {
        if (playing) {
            cachedPlayingBadgeIcon?.let { return it }
        } else {
            cachedPausedBadgeIcon?.let { return it }
        }
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (playing) Color.parseColor("#FF34D399") else Color.parseColor("#FFB0BEC5")
            style = Paint.Style.FILL
        }
        if (playing) {
            // 3 根错落音律柱，仿原生 ic_music_capsule_animation
            val r = 3.5f
            canvas.drawRoundRect(RectF(8f, 20f, 15f, 38f), r, r, paint)
            canvas.drawRoundRect(RectF(20.5f, 10f, 27.5f, 38f), r, r, paint)
            canvas.drawRoundRect(RectF(33f, 16f, 40f, 38f), r, r, paint)
        } else {
            // 双竖暂停图标，仿原生 ic_music_motion_00000 静止态
            val r = 3.5f
            canvas.drawRoundRect(RectF(13f, 12f, 20f, 36f), r, r, paint)
            canvas.drawRoundRect(RectF(28f, 12f, 35f, 36f), r, r, paint)
        }
        bmp.prepareToDraw()
        val icon = Icon.createWithBitmap(bmp)
        if (playing) cachedPlayingBadgeIcon = icon else cachedPausedBadgeIcon = icon
        return icon
    }

    private fun compactPillText(raw: String, maxVisualUnits: Int): String {
        val singleLine = raw.replace('\n', ' ').replace('\r', ' ').trim()
        if (singleLine.isEmpty()) return ""
        val sb = StringBuilder()
        var units = 0
        for (ch in singleLine) {
            val w = if (ch.code > 0x7F) 2 else 1
            if (units + w > maxVisualUnits) {
                if (sb.isNotEmpty()) sb.append('…')
                return sb.toString()
            }
            sb.append(ch)
            units += w
        }
        return sb.toString()
    }

    fun attach(
        context: Context,
        notification: Notification,
        config: CapsuleConfig,
        capsuleIconBitmap: Bitmap? = null,
        overrideChronometerBaseMs: Long = 0L,
        overrideChronometerPaused: Boolean = false,
        isMediaPlayback: Boolean = false,
        mediaPlaying: Boolean = false
    ): Result<Unit> = runCatching {
        val h = handles ?: error("framework-magic not available")

        // 废除人为字数/宽度硬截断：竖屏与横屏均传递完整单行文案，让系统胶囊在可用空间内自适应显示更多文字
        val pillBusiness = compactPillText(config.businessName, maxVisualUnits = 12)
        val pillMain = config.mainText.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()
        val pillSub = config.subText.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()
        val rvMainText = pillMain
        val rvSubText = pillSub
        val capsuleLyricPx = config.capsuleLyricTextSizePx.coerceIn(16f, 56f)

        val statusBarData = h.statusCls.getDeclaredConstructor().newInstance()
        h.setBusinessName.invoke(statusBarData, pillBusiness)
        h.setClickOpenActivity.invoke(statusBarData, false)
        h.setControlCapsuleState.invoke(statusBarData, false)
        h.setDegreeLevel.invoke(statusBarData, 1)

        // 对齐 HnMediaDynamicControllerImpl.updateRightImage：双图模板或媒体胶囊写入右侧状态第二图标
        var secondIconOk = false
        if (isMediaPlayback && config.template == CapsuleConfig.TEMPLATE_IMAGE_IMAGE && h.setSecondIcon != null) {
            secondIconOk = runCatching {
                h.setSecondIcon.invoke(statusBarData, getMediaRightBadgeIcon(mediaPlaying))
                true
            }.getOrDefault(false)
        }

        val needTimer = overrideChronometerBaseMs > 0L || config.enableTimer || config.template.contains("timer")
        if (needTimer) {
            if (overrideChronometerBaseMs > 0L) {
                // 录音机 / 录屏等正计时模式：isCountDown = false，从通知 when 时间戳起算
                h.setChronometer.invoke(statusBarData, false, overrideChronometerBaseMs)
                h.setTimePause.invoke(statusBarData, overrideChronometerPaused)
            } else {
                val targetEpochMs = System.currentTimeMillis() + config.timerMinutes * 60_000L
                h.setChronometer.invoke(statusBarData, true, targetEpochMs)
                h.setTimePause.invoke(statusBarData, false)
            }
        }

        val extraData = h.extraCls.getDeclaredConstructor().newInstance()
        h.setStatusBarData.invoke(extraData, statusBarData)
        // 接入荣耀 CapsuleData$AodData 息屏显示（AOD）胶囊背景色扩展
        val aodOk = if (h.aodCls != null && h.setAodBackground != null && h.setAodData != null) {
            runCatching {
                val aodData = h.aodCls.getDeclaredConstructor().newInstance()
                val bgHex = CapsuleConfig.toArgbHex(config.resolveCardBgColor())
                h.setAodBackground.invoke(aodData, bgHex, bgHex)
                h.setAodData.invoke(extraData, aodData)
                true
            }.getOrDefault(false)
        } else false

        val capsuleData = h.capsuleCls.getDeclaredConstructor().newInstance() as Parcelable
        val icon = if (capsuleIconBitmap != null && !capsuleIconBitmap.isRecycled) {
            val hBmp = com.app.magiccapsule.service.NotificationIconStore.bitmapFingerprint(capsuleIconBitmap)
            val prevIcon = cachedFirstIcon
            if (hBmp == cachedFirstIconHash && prevIcon != null) {
                prevIcon
            } else {
                val compact = if (capsuleIconBitmap.width > 72 || capsuleIconBitmap.height > 72) {
                    runCatching { Bitmap.createScaledBitmap(capsuleIconBitmap, 72, 72, true).also { it.prepareToDraw() } }
                        .getOrDefault(capsuleIconBitmap)
                } else capsuleIconBitmap
                Icon.createWithBitmap(compact).also {
                    cachedFirstIconHash = hBmp
                    cachedFirstIcon = it
                }
            }
        } else {
            Icon.createWithResource(context, android.R.drawable.ic_dialog_info)
        }

        h.setStatusBarTemplate.invoke(capsuleData, config.template)
        h.setBrandColor.invoke(capsuleData, config.brandColor)
        h.setFirstIcon.invoke(capsuleData, icon)
        h.setMainContent.invoke(capsuleData, arrayOf(pillMain))
        var pillRvOk = false
        if (config.useCompactPillRemoteViews && h.setMainContentRemoteViews != null) {
            pillRvOk = runCatching {
                val pillRv = RemoteViews(context.packageName, R.layout.layout_capsule_pill_text).apply {
                    setTextViewText(R.id.tv_pill_main, rvMainText)
                    setTextViewTextSize(
                        R.id.tv_pill_main,
                        TypedValue.COMPLEX_UNIT_PX,
                        capsuleLyricPx
                    )
                }
                h.setMainContentRemoteViews.invoke(capsuleData, pillRv)
                true
            }.onFailure { e ->
                AppLogger.w("Capsule", "setMainContentRemoteViews 失败：${e.message}")
            }.getOrDefault(false)
        }
        // 仅双文案轮播模板才写入 subContent（并同步注入小字号 SubContentRemoteViews），防止横屏拉出空白灰底
        if (config.template == CapsuleConfig.TEMPLATE_IMAGE_TEXT_TEXT && pillSub.isNotBlank()) {
            h.setSubContent.invoke(capsuleData, arrayOf(pillSub))
            if (config.useCompactPillRemoteViews && h.setSubContentRemoteViews != null) {
                runCatching {
                    val subRv = RemoteViews(context.packageName, R.layout.layout_capsule_pill_text).apply {
                        setTextViewText(R.id.tv_pill_main, rvSubText)
                        setTextViewTextSize(
                            R.id.tv_pill_main,
                            TypedValue.COMPLEX_UNIT_PX,
                            capsuleLyricPx
                        )
                    }
                    h.setSubContentRemoteViews.invoke(capsuleData, subRv)
                }
            }
        }
        h.setExtraData.invoke(capsuleData, extraData)

        h.setHnFeatureParcelable.invoke(null, notification, KEY_SMART_ISLAND_CAPSULE_DATA, capsuleData)
        h.setHnFeatureString.invoke(null, notification, KEY_IS_CAPSULE_BUSINESS_KEY_STEP, "true")
        val expandOk = runCatching {
            h.setExpandButtonState?.invoke(null, notification, true)
            h.setExpandButtonState != null
        }.getOrDefault(false)
        val hasSub = config.template == CapsuleConfig.TEMPLATE_IMAGE_TEXT_TEXT && pillSub.isNotBlank()
        AppLogger.i(
            "Capsule",
            "Bridge.attach 成功 tpl=${config.template} brand=${config.brandColor} biz=$pillBusiness " +
                "main=$pillMain pillRv=$pillRvOk(${capsuleLyricPx.toInt()}px) hasSub=$hasSub secondIcon=$secondIconOk expandBtn=$expandOk aod=$aodOk iconBmp=${capsuleIconBitmap != null}"
        )
    }
}
