package com.app.magiccapsule.capsule.vivo

import com.app.magiccapsule.capsule.DeviceBrandRouter

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.service.ActiveMirroredPayload
import java.util.concurrent.atomic.AtomicBoolean

/**
 * vivo OriginOS SuperX 原子通知 / 实况胶囊分支构造器（基于 vivo-services.jar VivoSuperXNotificationManager 协议）：
 * - 通过标准 Notification.extras 注入 notification.superx.* 结构化 Bundle，无需私有 jar 反射
 * - 支持 BASE_TEMPLATE(4)、EMPHASIS_TEMPLATE(1)、PROGRESS_TEMPLATE(2)（媒体播放进度条）
 * - 监听 android.app.action.SUPERX_STATE 服务端回执广播，自动识别 2208(NOTINLIST) 等状态并平滑回退通用悬浮横幅
 */
object VivoSuperXBridge {

    // 顶层 Operation 常量
    const val OPERATION_CREATE = 0
    const val OPERATION_UPDATE = 1
    const val OPERATION_FINISH = 2

    // 模板常量
    const val TEMPLATE_EMPHASIS = 1
    const val TEMPLATE_PROGRESS = 2
    const val TEMPLATE_SYMMETRIC = 3
    const val TEMPLATE_BASE = 4
    const val TEMPLATE_NAVIGATION = 5

    // 场景常量
    const val SCENE_DELIVERY = "DELIVERY"
    const val SCENE_TAKEOUT = "TAKEOUT"
    const val SCENE_TAXI = "TAXI"
    const val SCENE_FLIGHT = "FLIGHT"
    const val SCENE_TRAIN = "TRAIN"
    const val SCENE_MOVIE = "MOVIE"
    const val SCENE_HEALTH = "HEALTH_REGISTER"
    const val SCENE_NAVIGATION = "NAVIGATION"

    // 服务端回执广播与错误码
    const val ACTION_SUPERX_STATE = "android.app.action.SUPERX_STATE"
    const val EXTRA_SUPERX_STATE = "SUPERX_STATE"
    const val REASON_SUCCESS = 0
    const val REASON_OVERSPEED = 2207
    const val REASON_NOT_IN_LIST = 2208
    const val REASON_USER_REMOVE = 2209
    const val REASON_INVALID_APP = 2210
    const val REASON_SCENE_CLOSE = 2211

    // 顶层 Bundle Key
    private const val KEY_OPERATION = "notification.superx.operation"
    private const val KEY_TEMPLATE = "notification.superx.template"
    private const val KEY_SCENE = "notification.superx.scene"
    private const val KEY_KEEP_DURATION = "notification.superx.keepDuration"
    private const val KEY_SHOW_NOTIFY = "notification.superx.showNotify"
    private const val KEY_CLICK_RESP = "notification.superx.clickResp"
    private const val KEY_CHANGED_RECORD = "notification.superx.changedRecord"
    private const val KEY_CUSTOM_SUPERX = "notification.superx.customSuperX"
    private const val KEY_DISMISS_WHEN_KILL = "notification.superx.dismissWhenKill"

    // 嵌套 Bundle Key
    private const val KEY_BASE_INFO = "notification.superx.baseInfos"
    private const val KEY_BASE_TITLE = "notification.superx.baseInfos.title"
    private const val KEY_BASE_CONTENT = "notification.superx.baseInfos.content"
    private const val KEY_BASE_SUBINFO = "notification.superx.baseInfos.subInfo"
    private const val KEY_BASE_ICON = "notification.superx.baseInfos.icon"

    private const val KEY_CAPSULE = "notification.superx.capsule"
    private const val KEY_CAPSULE_ICON = "notification.superx.capsule.icon"
    private const val KEY_CAPSULE_STATE = "notification.superx.capsule.state"
    private const val KEY_CAPSULE_CONTENT = "notification.superx.capsule.content"
    private const val KEY_CAPSULE_CONTENT_COLOR = "notification.superx.capsule.contentColor"
    private const val KEY_CAPSULE_BG_COLOR = "notification.superx.capsule.bgColor"

    private const val KEY_INFO = "notification.superx.infos"
    private const val KEY_INFO_CORE = "notification.superx.infos.coreInfo"
    private const val KEY_INFO_DESCRIBE = "notification.superx.infos.describe"
    private const val KEY_INFO_PROGRESS = "notification.superx.infos.progress"
    private const val KEY_INFO_PROGRESS_COLOR = "notification.superx.infos.progressColor"
    private const val KEY_INFO_IMAGE = "notification.superx.infos.image"

    private const val KEY_SHORT_INFO = "notification.superx.shortInfos"
    private const val KEY_SHORT_CORE = "notification.superx.shortInfos.coreInfoShort"
    private const val KEY_SHORT_DESCRIBE = "notification.superx.shortInfos.describeShort"
    private const val KEY_SHORT_ICON = "notification.superx.shortInfos.icon"

    private val receiverRegistered = AtomicBoolean(false)

    @Volatile
    var lastResultCode: Int = -1
        private set

    @Volatile
    var rejectedByWhitelist: Boolean = false
        private set

    private var hasCreatedOnce: Boolean = false

    fun ensureStateReceiverRegistered(context: Context) {
        if (!DeviceBrandRouter.isVivoOriginOs()) return
        if (!receiverRegistered.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_SUPERX_STATE) return
                val code = intent.getIntExtra(EXTRA_SUPERX_STATE, -1)
                lastResultCode = code
                if (code == REASON_NOT_IN_LIST || code == REASON_INVALID_APP) {
                    rejectedByWhitelist = true
                } else if (code == REASON_SUCCESS) {
                    rejectedByWhitelist = false
                }
                AppLogger.i("Capsule", "vivo SuperX 服务端回执：code=$code (${describeReason(code)})")
            }
        }
        runCatching {
            val filter = IntentFilter(ACTION_SUPERX_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appCtx.registerReceiver(receiver, filter)
            }
        }.onFailure { e ->
            AppLogger.w("Capsule", "注册 vivo SuperX 回执监听失败：${e.message}")
        }
    }

    /**
     * 将通知按 vivo SuperX 协议封装进 Notification.extras：
     * - 媒体含进度时使用 PROGRESS_TEMPLATE(2) 注入 0..100 进度值
     * - 普通实时通知使用 EMPHASIS_TEMPLATE(1) 注入核心信息与描述
     * - 同步填充 baseInfos、capsule、shortInfos 确保通过 VivoSuperXNotificationManager 校验
     */
    fun attach(
        context: Context,
        notification: Notification,
        config: CapsuleConfig,
        mirroredPayload: ActiveMirroredPayload?,
        capsuleIconBitmap: Bitmap?,
        clickIntent: PendingIntent?,
        operation: Int = if (hasCreatedOnce) OPERATION_UPDATE else OPERATION_CREATE
    ): Result<Unit> = runCatching {
        ensureStateReceiverRegistered(context)
        val title = config.mainText.ifBlank { config.businessName }.take(24)
        val content = config.subText.ifBlank { config.businessName }.take(48)
        val subInfo = config.businessName.ifBlank { "灵动胶囊" }.take(16)
        val icon = if (capsuleIconBitmap != null) {
            Icon.createWithBitmap(capsuleIconBitmap)
        } else {
            Icon.createWithResource(context, android.R.drawable.ic_dialog_info)
        }

        val hasMediaProgress = mirroredPayload?.isMedia == true && mirroredPayload.mediaDurationMs > 0L
        val templateId = if (hasMediaProgress) TEMPLATE_PROGRESS else TEMPLATE_EMPHASIS

        val baseInfos = Bundle().apply {
            putString(KEY_BASE_TITLE, title)
            putString(KEY_BASE_CONTENT, content)
            putString(KEY_BASE_SUBINFO, subInfo)
            putParcelable(KEY_BASE_ICON, icon)
        }

        val capsuleBundle = Bundle().apply {
            putInt(KEY_CAPSULE_STATE, 1)
            putParcelable(KEY_CAPSULE_ICON, icon)
            putString(KEY_CAPSULE_CONTENT, config.mainText.ifBlank { config.businessName }.take(12))
            putString(KEY_CAPSULE_CONTENT_COLOR, "#FFFFFF")
            putString(KEY_CAPSULE_BG_COLOR, "#1A66FF")
        }

        val infos = Bundle().apply {
            putString(KEY_INFO_CORE, title)
            putString(KEY_INFO_DESCRIBE, content)
            putParcelable(KEY_INFO_IMAGE, icon)
            if (hasMediaProgress && mirroredPayload != null) {
                val dur = mirroredPayload.mediaDurationMs.coerceAtLeast(1L)
                val pct = ((mirroredPayload.mediaPositionMs.coerceIn(0L, dur) * 100L) / dur).toInt().coerceIn(0, 100)
                putInt(KEY_INFO_PROGRESS, pct)
                putString(KEY_INFO_PROGRESS_COLOR, "#34D399")
            }
        }

        val shortInfos = Bundle().apply {
            putString(KEY_SHORT_CORE, title.take(12))
            putString(KEY_SHORT_DESCRIBE, content.take(16))
            putParcelable(KEY_SHORT_ICON, icon)
        }

        val extras = notification.extras ?: Bundle().also { notification.extras = it }
        extras.putInt(KEY_OPERATION, operation)
        extras.putInt(KEY_TEMPLATE, templateId)
        extras.putString(KEY_SCENE, SCENE_DELIVERY)
        extras.putLong(KEY_KEEP_DURATION, (config.capsuleHoldDurationSec.coerceIn(5, 3600) * 1000L))
        extras.putBoolean(KEY_SHOW_NOTIFY, operation == OPERATION_CREATE)
        extras.putBoolean(KEY_CUSTOM_SUPERX, true)
        extras.putBoolean(KEY_DISMISS_WHEN_KILL, true)
        extras.putInt(KEY_CHANGED_RECORD, 1)
        if (clickIntent != null) {
            extras.putParcelable(KEY_CLICK_RESP, clickIntent)
        }
        extras.putBundle(KEY_BASE_INFO, baseInfos)
        extras.putBundle(KEY_CAPSULE, capsuleBundle)
        extras.putBundle(KEY_INFO, infos)
        extras.putBundle(KEY_SHORT_INFO, shortInfos)

        if (operation == OPERATION_CREATE) hasCreatedOnce = true
        if (operation == OPERATION_FINISH) hasCreatedOnce = false

        AppLogger.d(
            "Capsule",
            "VivoSuperXBridge.attach 完成 op=$operation tpl=$templateId title=$title wlRejected=$rejectedByWhitelist"
        )
    }

    fun resetSession() {
        hasCreatedOnce = false
    }

    fun describeReason(code: Int): String = when (code) {
        REASON_SUCCESS -> "成功"
        REASON_OVERSPEED -> "触发系统频率限制(OVERSPEED)"
        REASON_NOT_IN_LIST -> "包名不在 vivo SuperX 白名单(NOTINLIST)"
        REASON_USER_REMOVE -> "用户已手动移除"
        REASON_INVALID_APP -> "应用校验未通过(INVALIDAPP)"
        REASON_SCENE_CLOSE -> "对应场景开关关闭"
        else -> "状态码 $code"
    }
}
