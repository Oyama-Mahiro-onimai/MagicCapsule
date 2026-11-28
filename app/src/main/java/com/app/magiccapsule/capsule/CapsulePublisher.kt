package com.app.magiccapsule.capsule

import com.app.magiccapsule.capsule.huawei.HuaweiLiveCapsuleBridge

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.service.ActiveMirroredPayload
import com.app.magiccapsule.service.CapsuleNotificationListener
import com.app.magiccapsule.service.NotificationIconStore
import com.app.magiccapsule.service.SoCThreadPools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 灵动胶囊异步合并发布器（零主线程阻塞 + 帧合并防雪崩）：
 * - RemoteViews 构建、位图注入、framework-magic 反射与 NotificationManager.notify Binder IPC 全部下沉至独立后台线程，
 *   彻底释放主线程与 GPU RenderThread，消除前台掉帧与 SoC 大核升频
 * - 采用 latest-wins 原子合并（pendingJob）：高频触发时自动丢弃已被新状态覆盖的中间帧，杜绝线程队列堆积与软死锁
 */
object CapsulePublisher {

    private data class PublishJob(
        val ctx: Context,
        val baseConfig: CapsuleConfig,
        val effectiveConfig: CapsuleConfig,
        val payload: ActiveMirroredPayload?,
        val isPinned: Boolean
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val publisherExecutor get() = SoCThreadPools.publishExecutor
    private val refreshCounter = AtomicInteger(0)
    private val pendingJob = AtomicReference<PublishJob?>(null)
    private val drainScheduled = AtomicBoolean(false)

    @Volatile private var lastStatusSignature: String = ""
    // 系统状态变化短时插播窗口：在插播期间临时挂起后台媒体/歌词刷新，到期后自动恢复媒体/导航胶囊
    @Volatile private var sysEventPreemptUntilElapsedMs: Long = 0L
    // 手动自定义文案胶囊保护锁：防止关闭媒体拦截或收到普通通知时被自动镜像顶掉/到期销毁
    @Volatile private var manualCapsulePinned: Boolean = false

    fun setManualCapsulePinned(pinned: Boolean) {
        manualCapsulePinned = pinned
    }

    fun isManualCapsulePinned(): Boolean = manualCapsulePinned

    fun restoreManualCapsuleIfNeeded(context: Context): Boolean {
        if (!manualCapsulePinned) return false
        val ctx = context.applicationContext
        val cfg = runCatching { com.app.magiccapsule.data.CapsulePrefs(ctx).load() }.getOrElse { CapsuleConfig() }
        if (!cfg.enableCapsuleEngine) return false
        NotificationIconStore.currentActivePayload = null
        enqueueLatest(
            PublishJob(
                ctx = ctx,
                baseConfig = cfg,
                effectiveConfig = cfg,
                payload = null,
                isPinned = true
            )
        )
        return true
    }

    fun markSysEventPreemptWindow(durationMs: Long) {
        sysEventPreemptUntilElapsedMs = SystemClock.elapsedRealtime() + durationMs.coerceIn(2000L, 15_000L)
    }

    fun clearSysEventPreemptWindow() {
        sysEventPreemptUntilElapsedMs = 0L
    }

    fun isSysEventPreempting(): Boolean {
        return SystemClock.elapsedRealtime() < sysEventPreemptUntilElapsedMs
    }
    @Volatile private var appContext: Context? = null

    private val _lastCapsuleStatus = MutableStateFlow("尚未弹出胶囊")
    val lastCapsuleStatus: StateFlow<String> = _lastCapsuleStatus.asStateFlow()

    private val autoDismissTask = Runnable {
        val ctx = appContext ?: return@Runnable
        clearSysEventPreemptWindow()
        if (com.app.magiccapsule.service.CapsuleNavigationEngine.restoreActiveNavCapsuleIfNeeded(ctx)) {
            return@Runnable
        }
        if (com.app.magiccapsule.service.CapsuleNotificationListener.restoreActiveMediaCapsuleIfNeeded(ctx)) {
            return@Runnable
        }
        if (restoreManualCapsuleIfNeeded(ctx)) {
            return@Runnable
        }
        dismissInternal(ctx, "保持时间到，胶囊已自动销毁")
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
        // 统一总闸：关闭「灵动胶囊引擎总开关」时，任何入口（歌词/收藏/系统事件/导航）均禁止拉起状态栏胶囊
        if (!baseConfig.enableCapsuleEngine) return

        val useLiveLyricOnPill = baseConfig.showMediaMetadataLyricInCapsule &&
            payload.isMedia &&
            payload.liveLyricLine.isNotBlank()
        val pillMain = when {
            useLiveLyricOnPill -> payload.liveLyricLine
            payload.livePillText.isNotBlank() -> payload.livePillText
            else -> payload.title.ifBlank { baseConfig.mainText }
        }
        val effectiveConfig = baseConfig.copy(
            businessName = payload.appName.ifBlank { baseConfig.businessName },
            mainText = pillMain,
            subText = if (useLiveLyricOnPill) payload.title.ifBlank { payload.text } else payload.text.ifBlank { payload.title }
        )

        enqueueLatest(
            PublishJob(
                ctx = ctx,
                baseConfig = baseConfig,
                effectiveConfig = effectiveConfig,
                payload = payload,
                isPinned = isPinned
            )
        )
    }

    fun refreshCurrent(context: Context, baseConfig: CapsuleConfig) {
        if (!baseConfig.enableCapsuleEngine) return
        val ctx = context.applicationContext
        appContext = ctx
        val payload = NotificationIconStore.currentActivePayload
        if (payload != null) {
            val isPinned = CapsuleNotificationListener.pinnedKey.value == payload.key
            publishMirrored(ctx, baseConfig, payload, isPinned = isPinned)
        } else {
            enqueueLatest(
                PublishJob(
                    ctx = ctx,
                    baseConfig = baseConfig,
                    effectiveConfig = baseConfig,
                    payload = null,
                    isPinned = true
                )
            )
        }
    }

    private fun enqueueLatest(job: PublishJob) {
        pendingJob.set(job)
        if (drainScheduled.compareAndSet(false, true)) {
            publisherExecutor.execute {
                try {
                    while (true) {
                        val next = pendingJob.getAndSet(null) ?: break
                        executeJob(next)
                    }
                } finally {
                    drainScheduled.set(false)
                    if (pendingJob.get() != null && drainScheduled.compareAndSet(false, true)) {
                        publisherExecutor.execute {
                            try {
                                while (true) {
                                    val next = pendingJob.getAndSet(null) ?: break
                                    executeJob(next)
                                }
                            } finally {
                                drainScheduled.set(false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun executeJob(job: PublishJob) {
        mainHandler.removeCallbacks(autoDismissTask)
        runCatching {
            val count = refreshCounter.incrementAndGet()
            val notification = CapsuleNotificationFactory.create(
                context = job.ctx,
                config = job.effectiveConfig,
                refreshCount = count,
                mirroredPayload = job.payload
            )
            job.ctx.getSystemService(NotificationManager::class.java)
                .notify(CapsuleNotificationFactory.NOTIFY_ID, notification)

            val payload = job.payload
            val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (payload == null) {
                val statusSig = "manual|${job.effectiveConfig.mainText}"
                if (statusSig != lastStatusSignature) {
                    lastStatusSignature = statusSig
                    _lastCapsuleStatus.value = "$now 已刷新自定义胶囊"
                }
            } else if (!job.isPinned && (job.baseConfig.capsuleAutoDismiss || isSysEventPreempting())) {
                val isSysPreempt = payload.key == "sys_status_snapshot" && isSysEventPreempting()
                val holdSec = if (isSysPreempt) {
                    job.baseConfig.capsuleHoldDurationSec.coerceIn(3, 8)
                } else {
                    job.baseConfig.capsuleHoldDurationSec.coerceIn(2, 60)
                }
                mainHandler.postDelayed(autoDismissTask, holdSec * 1000L)
                _lastCapsuleStatus.value = if (isSysPreempt) {
                    "$now 系统状态插播：${payload.title}（${holdSec}s 后恢复媒体胶囊）"
                } else {
                    "$now 已弹出胶囊：${payload.appName}（保持 ${holdSec}s 后自动销毁）"
                }
                AppLogger.i("Capsule", "临时胶囊 app=${payload.appName} sysPreempt=$isSysPreempt hold=${holdSec}s mode=${job.baseConfig.capsuleDeliveryMode}")
            } else {
                val statusSig = "${payload.packageName}|${payload.title}|${job.isPinned}"
                if (statusSig != lastStatusSignature) {
                    lastStatusSignature = statusSig
                    _lastCapsuleStatus.value = "$now 已更新常驻胶囊：${payload.appName}"
                    AppLogger.i("Capsule", "更新常驻胶囊 app=${payload.appName} pkg=${payload.packageName} pinned=${job.isPinned} mode=${job.baseConfig.capsuleDeliveryMode}")
                }
            }
        }.onFailure { e ->
            _lastCapsuleStatus.value = "胶囊发布异常：${e.javaClass.simpleName}: ${e.message}"
            AppLogger.e("Capsule", "发布胶囊失败", e)
        }
    }

    fun dismiss(context: Context, reason: String = "胶囊已关闭") {
        val ctx = context.applicationContext
        appContext = ctx
        pendingJob.set(null)
        publisherExecutor.execute {
            dismissInternal(ctx, reason)
        }
    }

    private fun dismissInternal(ctx: Context, reason: String) {
        mainHandler.removeCallbacks(autoDismissTask)
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)
                .cancel(CapsuleNotificationFactory.NOTIFY_ID)
            CapsuleNotificationFactory.releaseMediaSession()
            HuaweiLiveCapsuleBridge.resetSession()
            val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            lastStatusSignature = ""
            _lastCapsuleStatus.value = "$now $reason"
            AppLogger.i("Capsule", "销毁胶囊：$reason")
        }.onFailure { e ->
            AppLogger.w("Capsule", "销毁胶囊异常：${e.message}")
        }
    }
}
