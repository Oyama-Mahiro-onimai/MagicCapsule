package com.app.magiccapsule.capsule

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Parcelable
import com.app.magiccapsule.model.CapsuleConfig

object MagicCapsuleBridge {

    private const val KEY_SMART_ISLAND_CAPSULE_DATA = "smart_island_capsule_data"
    private const val KEY_IS_CAPSULE_BUSINESS_KEY_STEP = "is_capsule_business_key_step"

    fun isSupported(): Boolean = runCatching {
        Class.forName("com.hihonor.android.app.CapsuleData")
        Class.forName("com.hihonor.android.app.NotificationEx")
        true
    }.getOrDefault(false)

    fun attach(
        context: Context,
        notification: Notification,
        config: CapsuleConfig
    ): Result<Unit> = runCatching {
        val capsuleCls = Class.forName("com.hihonor.android.app.CapsuleData")
        val extraCls = Class.forName("com.hihonor.android.app.CapsuleData\$ExtraData")
        val statusCls = Class.forName("com.hihonor.android.app.CapsuleData\$StatusBarData")
        val notifExCls = Class.forName("com.hihonor.android.app.NotificationEx")

        val statusBarData = statusCls.getDeclaredConstructor().newInstance()
        statusCls.getMethod("setBusinessName", String::class.java)
            .invoke(statusBarData, config.businessName)
        statusCls.getMethod("setClickOpenActivity", Boolean::class.javaPrimitiveType)
            .invoke(statusBarData, false)
        statusCls.getMethod("setControlCapsuleState", Boolean::class.javaPrimitiveType)
            .invoke(statusBarData, false)
        statusCls.getMethod("setDegreeLevel", Int::class.javaPrimitiveType)
            .invoke(statusBarData, 1)

        val needTimer = config.enableTimer || config.template.contains("timer")
        if (needTimer) {
            val targetEpochMs = System.currentTimeMillis() + config.timerMinutes * 60_000L
            statusCls.getMethod(
                "setChronometer",
                Boolean::class.javaPrimitiveType,
                Long::class.javaPrimitiveType
            ).invoke(statusBarData, true, targetEpochMs)
            statusCls.getMethod("setTimePause", Boolean::class.javaPrimitiveType)
                .invoke(statusBarData, false)
        }

        val extraData = extraCls.getDeclaredConstructor().newInstance()
        extraCls.getMethod("setStatusBarData", statusCls).invoke(extraData, statusBarData)

        val capsuleData = capsuleCls.getDeclaredConstructor().newInstance() as Parcelable
        val icon = Icon.createWithResource(context, android.R.drawable.ic_dialog_info)

        capsuleCls.getMethod("setStatusBarTemplate", String::class.java)
            .invoke(capsuleData, config.template)
        capsuleCls.getMethod("setBrandColor", Int::class.javaPrimitiveType)
            .invoke(capsuleData, config.brandColor)
        capsuleCls.getMethod("setFirstIcon", Icon::class.java).invoke(capsuleData, icon)
        capsuleCls.getMethod("setMainContent", Array<String>::class.java)
            .invoke(capsuleData, arrayOf(config.mainText))
        if (config.subText.isNotBlank()) {
            capsuleCls.getMethod("setSubContent", Array<String>::class.java)
                .invoke(capsuleData, arrayOf(config.subText))
        }
        capsuleCls.getMethod("setExtraData", extraCls).invoke(capsuleData, extraData)

        notifExCls.getMethod(
            "setHnFeature",
            Notification::class.java,
            String::class.java,
            Parcelable::class.java
        ).invoke(null, notification, KEY_SMART_ISLAND_CAPSULE_DATA, capsuleData)

        notifExCls.getMethod(
            "setHnFeature",
            Notification::class.java,
            String::class.java,
            String::class.java
        ).invoke(null, notification, KEY_IS_CAPSULE_BUSINESS_KEY_STEP, "true")

        runCatching {
            notifExCls.getMethod(
                "setExpandButtonState",
                Notification::class.java,
                Boolean::class.javaPrimitiveType
            ).invoke(null, notification, true)
        }
    }
}
