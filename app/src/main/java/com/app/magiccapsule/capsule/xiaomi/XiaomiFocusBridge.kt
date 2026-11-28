package com.app.magiccapsule.capsule.xiaomi

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.model.CapsuleConfig
import org.json.JSONObject

/**
 * 小米 HyperOS / MIUI 焦点通知（Focus Notification）桥接器：
 * 对齐高德地图 classes5.dex 中 `XIAOMIFootRideConnectDevice` / `XiaomiUAConnectedDevice` 官方协议：
 * - `miui.focus.param`：标准焦点通知 JSON（protocol/scene/ticker/aodTitle/title/content/colorTitle/colorBg/updatable 等）
 * - `miui.focus.pics`：Bundle 封装 `miui.focus.pic_large` 与 `miui.focus.tickerPicDark` Icon 图标资源
 * - 未加入小米焦点通知白名单的设备仍由 UniversalBannerHost 同步无缝兜底显示
 */
object XiaomiFocusBridge {

    private const val KEY_FOCUS_PARAM = "miui.focus.param"
    private const val KEY_FOCUS_PICS = "miui.focus.pics"
    private const val PIC_KEY_LARGE = "miui.focus.pic_large"
    private const val PIC_KEY_TICKER_DARK = "miui.focus.tickerPicDark"
    private const val DEFAULT_SCENE = "templateRevertProgressScene"

    fun attach(
        context: Context,
        notification: Notification,
        config: CapsuleConfig,
        capsuleIconBitmap: Bitmap? = null
    ): Result<Unit> = runCatching {
        val titleText = config.mainText.ifBlank { config.businessName }.take(28)
        val contentText = config.subText.ifBlank { config.businessName }.take(48)
        val tickerText = "${config.businessName} · $titleText".take(24)

        val paramJson = JSONObject().apply {
            put("protocol", 1)
            put("scene", DEFAULT_SCENE)
            put("aodTitle", titleText)
            put("aodPic", PIC_KEY_LARGE)
            put("ticker", tickerText)
            put("tickerPic", PIC_KEY_LARGE)
            put("tickerPicDark", PIC_KEY_TICKER_DARK)
            put("title", titleText)
            put("content", contentText)
            put("colorTitle", "#FFFFFF")
            put("colorContent", "#B8D8CC")
            put("colorBg", "#000000")
            put("showSmallIcon", true)
            put("enableFloat", true)
            put("padding", true)
            put("updatable", true)
            put("reopen", false)
        }

        val icon = if (capsuleIconBitmap != null && !capsuleIconBitmap.isRecycled) {
            Icon.createWithBitmap(capsuleIconBitmap)
        } else {
            Icon.createWithResource(context, android.R.drawable.ic_dialog_info)
        }

        val picsBundle = Bundle().apply {
            putParcelable(PIC_KEY_LARGE, icon)
            putParcelable(PIC_KEY_TICKER_DARK, icon)
        }

        val extras = notification.extras ?: Bundle().also { notification.extras = it }
        extras.putString(KEY_FOCUS_PARAM, paramJson.toString())
        extras.putBundle(KEY_FOCUS_PICS, picsBundle)

        AppLogger.i("Capsule", "XiaomiFocusBridge.attach 完成 title=$titleText scene=$DEFAULT_SCENE")
    }
}
