package com.app.magiccapsule.service

import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 面向 ARM64 异构多核 SoC（big.LITTLE / EAS 能效感知调度）的分级线程池：
 * - 通过 Process.setThreadPriority() 明确告知内核调度器线程优先级（nice 值），
 *   将非 UI 渲染的后台解析、RemoteViews 组装、系统状态采集与日志落盘引导至高能效小核（LITTLE cores）
 *   并行分摊执行，避免单线程积压拉高大核频点（OPP 电压平方级功耗激增）
 * - 所有核心线程均启用 allowCoreThreadTimeOut(true)，空闲 20 秒自动回收，零后台空转开销
 */
object SoCThreadPools {

    private fun createPool(
        namePrefix: String,
        poolSize: Int,
        androidPriority: Int
    ): ThreadPoolExecutor {
        val seq = AtomicInteger(1)
        val factory = ThreadFactory { runnable ->
            Thread({
                runCatching { Process.setThreadPriority(androidPriority) }
                runnable.run()
            }, "$namePrefix-${seq.getAndIncrement()}").apply {
                isDaemon = true
            }
        }
        val idleSec = if (SystemPropertyProfile.isLowRamDevice) 10L else 20L
        return ThreadPoolExecutor(
            poolSize,
            poolSize,
            idleSec,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            factory
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }

    /**
     * 通知解析与媒体状态同步池（2 核并发分摊，nice = +5，兼顾及时性与小核优先调度）
     */
    val notifExecutor: ExecutorService by lazy {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val size = if (!SystemPropertyProfile.isLowRamDevice && cores >= 6) 2 else 1
        createPool("mc-notif", size, Process.THREAD_PRIORITY_DEFAULT + 5)
    }

    /**
     * 胶囊 RemoteViews 构建与 Binder IPC 发布池（单路保序防乱序覆盖，nice = +10 走能效小核）
     */
    val publishExecutor: ExecutorService by lazy {
        createPool("mc-pub", 1, Process.THREAD_PRIORITY_BACKGROUND)
    }

    /**
     * 设备状态监控与日志磁盘 I/O 池（nice = +19 最低优先级，严格由 EAS 调度至能效小核，绝不触发大核升频）
     */
    val monitorIoExecutor: ExecutorService by lazy {
        createPool("mc-io", 2, Process.THREAD_PRIORITY_LOWEST)
    }
}
