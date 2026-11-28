package com.app.magiccapsule.crash

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import com.app.magiccapsule.capsule.CapsuleNotificationFactory
import com.app.magiccapsule.capsule.CapsulePublisher
import com.app.magiccapsule.service.CapsuleNotificationListener
import com.app.magiccapsule.service.CapsuleOverlayAccessibilityService
import com.app.magiccapsule.service.NotificationIconStore
import com.app.magiccapsule.service.SystemHeadsUpController
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局未捕获异常处理器（Global Crash Handler）：
 * 1. 拦截全局未捕获异常并收集完整堆栈、设备信息与胶囊/横幅实时状态
 * 2. 写入 Context.getExternalFilesDir(null)/crash_log.txt（自动限制最大体积防膨胀）
 * 3. 在独立 Looper 线程弹出 Toast 提示用户，并清理悬浮横幅/恢复系统横幅
 * 4. 强制调用 Process.killProcess + System.exit(0) 安全退出，防止损坏的后台服务死循环重启
 */
object GlobalCrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "MagicCapsuleCrash"
    private const val LOG_FILE_NAME = "crash_log.txt"
    private const val MAX_LOG_BYTES = 512 * 1024L

    private val installed = AtomicBoolean(false)
    private val handlingCrash = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        if (installed.compareAndSet(false, true)) {
            Thread.setDefaultUncaughtExceptionHandler(this)
        }
    }

    fun getLogFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, LOG_FILE_NAME)
    }

    fun readCrashLog(context: Context): String {
        val file = getLogFile(context)
        if (!file.exists()) return ""
        return runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
    }

    fun clearCrashLog(context: Context): Boolean = runCatching {
        val file = getLogFile(context)
        if (file.exists()) file.writeText("", Charsets.UTF_8)
        true
    }.getOrDefault(false)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (!handlingCrash.compareAndSet(false, true)) {
            Process.killProcess(Process.myPid())
            System.exit(0)
            return
        }

        val ctx = appContext
        try {
            val report = buildCrashReport(thread, throwable)
            Log.e(TAG, report)
            if (ctx != null) {
                writeCrashReport(ctx, report)
                safeCleanupBeforeExit(ctx)
                showCrashToast(ctx)
            }
        } catch (_: Throwable) {
            // 崩溃兜底阶段绝不再向外抛异常
        } finally {
            Process.killProcess(Process.myPid())
            System.exit(0)
        }
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        val payload = NotificationIconStore.currentActivePayload
        val hasCapsuleData = payload != null
        val isPinned = CapsuleNotificationListener.pinnedKey.value != null
        val bannerShowing = CapsuleOverlayAccessibilityService.isBannerVisible()
        val capsuleDisplayState = when {
            bannerShowing -> "顶层横幅展开显示中"
            hasCapsuleData && isPinned -> "胶囊有数据（手动锁定常驻）"
            hasCapsuleData -> "胶囊有数据（自动镜像中）"
            else -> "折叠 / 无活跃数据"
        }

        return buildString {
            appendLine("==================================================")
            appendLine("Time         : $time")
            appendLine("Thread       : ${thread.name} (id=${thread.id})")
            appendLine("Device       : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android OS   : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Capsule Data : ${if (hasCapsuleData) "有数据 (${payload?.appName} / ${payload?.packageName})" else "无数据"}")
            appendLine("Capsule State: $capsuleDisplayState")
            appendLine("Last Status  : ${CapsulePublisher.lastCapsuleStatus.value}")
            appendLine("--------------------------------------------------")
            appendLine(stackTrace.trimEnd())
            val recentTrail = AppLogger.snapshotRecentLines(15)
            if (recentTrail.isNotEmpty()) {
                appendLine("---------- Recent Runtime Trail (${recentTrail.size}) ----------")
                recentTrail.forEach { appendLine(it) }
            }
            appendLine()
        }
    }

    private fun writeCrashReport(context: Context, report: String) {
        runCatching {
            val file = getLogFile(context)
            if (file.exists() && file.length() > MAX_LOG_BYTES) {
                // 超过 512KB 时截断保留最新一段，避免日志文件无限膨胀
                val tail = file.readText(Charsets.UTF_8).takeLast(128 * 1024)
                file.writeText(tail + "\n" + report, Charsets.UTF_8)
            } else {
                file.appendText(report, Charsets.UTF_8)
            }
        }
    }

    private fun safeCleanupBeforeExit(context: Context) {
        runCatching { CapsuleOverlayAccessibilityService.dismissBanner() }
        runCatching { SystemHeadsUpController.restoreDefault(context) }
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(CapsuleNotificationFactory.NOTIFY_ID)
        }
    }

    private fun showCrashToast(context: Context) {
        val toastThread = Thread {
            runCatching {
                Looper.prepare()
                Toast.makeText(
                    context,
                    "灵动胶囊服务遇到致命错误，已安全退出，日志已保存。",
                    Toast.LENGTH_LONG
                ).show()
                Looper.loop()
            }
        }
        toastThread.start()
        runCatching { Thread.sleep(1800L) }
    }
}
