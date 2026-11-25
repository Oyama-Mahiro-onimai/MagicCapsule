package com.app.magiccapsule.capsule

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.service.ActiveMirroredPayload
import com.app.magiccapsule.service.CapsuleNotificationListener
import com.app.magiccapsule.service.NotificationIconStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 荣耀灵动胶囊直接发布与生命周期控制器：
 * - 直接通过 NotificationManager.notify / cancel 管理胶囊创建、更新与销毁，
 *   彻底绕开 Android 12–15 后台调用 startForegroundService() 抛出的 ForegroundServiceStartNotAllowedException
 * - 自动模式支持「即弹出即销毁 + 保持时间」，手动锁定模式保持常驻直到取消或原通知移除
 */
object CapsulePublisher {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshCounter = AtomicInteger(0)

    private val _lastCapsuleStatus = MutableStateFlow("尚未触发灵动胶囊")
    val lastCapsuleStatus: StateFlow<String> = _lastCapsuleStatus.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    private val autoDismissTask: Runnable = Runnable {
        val ctx = appContext ?: return@Runnable
        dismissInternal(ctx, reason = "保持时间到，胶囊已自动销毁")
    }

    fun publishMirrored(
        context: Context,
        baseConfig: CapsuleConfig,
        payload: ActiveMirroredPayload,
        isPinned: Boolean
    ) {
        val ctx = context.applicationContext
        appContext = ctx
        NotificationIconStore.currentActivePayload = payload

        val effectiveConfig = baseConfig.copy(
            businessName = payload.appName.ifBlank { baseConfig.businessName },
            mainText = payload.title.ifBlank { baseConfig.mainText },
            subText = payload.text.ifBlank { payload.title }
        )

        mainHandler.post {
            mainHandler.removeCallbacks(autoDismissTask)
            runCatching {
                val count = refreshCounter.incrementAndGet()
                val notification = CapsuleNotificationFactory.create(
                    context = ctx,
                    config = effectiveConfig,
                    refreshCount = count,
                    mirroredPayload = payload
                )
                ctx.getSystemService(NotificationManager::class.java)
                    .notify(CapsuleNotificationFactory.NOTIFY_ID, notification)

                val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                if (!isPinned && baseConfig.capsuleAutoDismiss) {
                    val holdSec = baseConfig.capsuleHoldDurationSec.coerceIn(2, 60)
                    mainHandler.postDelayed(autoDismissTask, holdSec * 1000L)
                    _lastCapsuleStatus.value = "$now 已弹出胶囊：${payload.appName}（保持 ${holdSec}s 后自动销毁）"
                    AppLogger.i("Capsule", "弹出即销毁胶囊 app=${payload.appName} pkg=${payload.packageName} hold=${holdSec}s mode=${baseConfig.capsuleDeliveryMode}")
                } else {
                    _lastCapsuleStatus.value = "$now 已更新常驻胶囊：${payload.appName}"
                    AppLogger.i("Capsule", "更新常驻胶囊 app=${payload.appName} pkg=${payload.packageName} pinned=$isPinned mode=${baseConfig.capsuleDeliveryMode}")
                }
            }.onFailure { e ->
                _lastCapsuleStatus.value = "胶囊发布异常：${e.javaClass.simpleName}: ${e.message}"
                AppLogger.e("Capsule", "发布胶囊失败", e)
            }
        }
    }

    fun refreshCurrent(context: Context, baseConfig: CapsuleConfig) {
        val ctx = context.applicationContext
        appContext = ctx
        val payload = NotificationIconStore.currentActivePayload
        if (payload != null) {
            val isPinned = CapsuleNotificationListener.pinnedKey.value == payload.key
            publishMirrored(ctx, baseConfig, payload, isPinned = isPinned)
        } else {
            mainHandler.post {
                runCatching {
                    val count = refreshCounter.incrementAndGet()
                    val notification = CapsuleNotificationFactory.create(ctx, baseConfig, count, null)
                    ctx.getSystemService(NotificationManager::class.java)
                        .notify(CapsuleNotificationFactory.NOTIFY_ID, notification)
                    val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    _lastCapsuleStatus.value = "$now 已刷新自定义胶囊"
                }.onFailure { e ->
                    _lastCapsuleStatus.value = "胶囊刷新异常：${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
    }

    fun dismiss(context: Context, reason: String = "胶囊已关闭") {
        val ctx = context.applicationContext
        appContext = ctx
        mainHandler.post {
            dismissInternal(ctx, reason)
        }
    }

    private fun dismissInternal(ctx: Context, reason: String) {
        mainHandler.removeCallbacks(autoDismissTask)
        NotificationIconStore.currentActivePayload = null
        CapsuleNotificationListener.clearActiveSource()
        CapsuleNotificationFactory.releaseMediaSession()
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)
                .cancel(CapsuleNotificationFactory.NOTIFY_ID)
        }
        val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _lastCapsuleStatus.value = "$now $reason"
        AppLogger.i("Capsule", "销毁胶囊：$reason")
    }
}
