package com.app.magiccapsule.capsule

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Parcelable
import com.app.magiccapsule.model.CapsuleConfig
import java.lang.reflect.Method

/**
 * 荣耀 framework-magic 反射桥接（缓存反射句柄 + 动态胶囊图标版）：
 * - 首次加载缓存全部 Class/Method 句柄，避免每次刷新重复反射查找以节省 CPU 与电量
 * - 通过 framework-magic.jar 验证的 CapsuleData.setFirstIcon(Icon) 注入当前锁定/新通知的真实图标
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
        val setStatusBarData: Method,
        val setStatusBarTemplate: Method,
        val setBrandColor: Method,
        val setFirstIcon: Method,
        val setMainContent: Method,
        val setSubContent: Method,
        val setExtraData: Method,
        val setHnFeatureParcelable: Method,
        val setHnFeatureString: Method,
        val setExpandButtonState: Method?
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
                setStatusBarData = extraCls.getMethod("setStatusBarData", statusCls),
                setStatusBarTemplate = capsuleCls.getMethod("setStatusBarTemplate", String::class.java),
                setBrandColor = capsuleCls.getMethod("setBrandColor", Int::class.javaPrimitiveType),
                setFirstIcon = capsuleCls.getMethod("setFirstIcon", Icon::class.java),
                setMainContent = capsuleCls.getMethod("setMainContent", Array<String>::class.java),
                setSubContent = capsuleCls.getMethod("setSubContent", Array<String>::class.java),
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
                }.getOrNull()
            )
        }.getOrNull()
    }

    fun isSupported(): Boolean = handles != null

    private fun compactPillText(raw: String, maxLen: Int): String {
        val singleLine = raw.replace('\n', ' ').replace('\r', ' ').trim()
        if (singleLine.length <= maxLen) return singleLine
        return singleLine.take(maxLen - 1) + "…"
    }

    fun attach(
        context: Context,
        notification: Notification,
        config: CapsuleConfig,
        capsuleIconBitmap: Bitmap? = null
    ): Result<Unit> = runCatching {
        val h = handles ?: error("framework-magic not available")

        val pillBusiness = compactPillText(config.businessName, maxLen = 8)
        val pillMain = compactPillText(config.mainText, maxLen = 10)
        val pillSub = compactPillText(config.subText, maxLen = 10)

        val statusBarData = h.statusCls.getDeclaredConstructor().newInstance()
        h.setBusinessName.invoke(statusBarData, pillBusiness)
        h.setClickOpenActivity.invoke(statusBarData, false)
        h.setControlCapsuleState.invoke(statusBarData, false)
        h.setDegreeLevel.invoke(statusBarData, 1)

        val needTimer = config.enableTimer || config.template.contains("timer")
        if (needTimer) {
            val targetEpochMs = System.currentTimeMillis() + config.timerMinutes * 60_000L
            h.setChronometer.invoke(statusBarData, true, targetEpochMs)
            h.setTimePause.invoke(statusBarData, false)
        }

        val extraData = h.extraCls.getDeclaredConstructor().newInstance()
        h.setStatusBarData.invoke(extraData, statusBarData)

        val capsuleData = h.capsuleCls.getDeclaredConstructor().newInstance() as Parcelable
        val icon = if (capsuleIconBitmap != null) {
            Icon.createWithBitmap(capsuleIconBitmap)
        } else {
            Icon.createWithResource(context, android.R.drawable.ic_dialog_info)
        }

        h.setStatusBarTemplate.invoke(capsuleData, config.template)
        h.setBrandColor.invoke(capsuleData, config.brandColor)
        h.setFirstIcon.invoke(capsuleData, icon)
        h.setMainContent.invoke(capsuleData, arrayOf(pillMain))
        // 仅双文案轮播模板才写入 subContent，防止横屏未展开状态下荣耀状态栏胶囊右侧拉出大片空白灰底
        if (config.template == CapsuleConfig.TEMPLATE_IMAGE_TEXT_TEXT && pillSub.isNotBlank()) {
            h.setSubContent.invoke(capsuleData, arrayOf(pillSub))
        }
        h.setExtraData.invoke(capsuleData, extraData)

        h.setHnFeatureParcelable.invoke(null, notification, KEY_SMART_ISLAND_CAPSULE_DATA, capsuleData)
        h.setHnFeatureString.invoke(null, notification, KEY_IS_CAPSULE_BUSINESS_KEY_STEP, "true")
        runCatching { h.setExpandButtonState?.invoke(null, notification, true) }
    }
}
