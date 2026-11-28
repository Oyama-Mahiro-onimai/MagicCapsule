package com.app.magiccapsule.crash

import android.content.Context
import android.os.Build
import android.util.Log
import com.app.magiccapsule.service.RenderPowerGovernor
import com.app.magiccapsule.service.SoCThreadPools
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/**
 * 高精度低耗电运行日志与异常捕获系统（v166 全量化增强版）：
 * - 日常 D/I/W 日志零堆栈开销、纯内存环形缓冲（1500 条），杜绝持续磁盘 I/O 耗电
 * - 发生错误 (E) 或携带 Throwable 的警告 (W) 时，全量记录：
 *   1) 线程名与线程 ID
 *   2) 完整异常因果链（最多 4 层 Caused by，直击根因不截断）
 *   3) 精准堆栈帧（优先高亮 com.app.magiccapsule.* 源码位置 + 抛出点，即使未传 Throwable 也自动回溯调用方文件与行号）
 *   4) 运行时上下文快照（版本号、API、堆内存占用、当前渲染 LOD）
 * - 提供全局 CoroutineExceptionHandler 防止后台协程异常静默吞没，并为崩溃报告提供最近 15 条运行轨迹
 */
object AppLogger {

    private const val LOG_FILE_NAME = "runtime_log.txt"
    private const val MAX_MEMORY_LINES = 1500
    private const val MAX_FILE_BYTES = 256 * 1024L
    private const val APP_PKG_PREFIX = "com.app.magiccapsule."

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val lock = Any()
    private val memoryLines = ArrayDeque<String>(MAX_MEMORY_LINES)
    private val ioExecutor get() = SoCThreadPools.monitorIoExecutor

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var cachedVersionName: String = ""

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        cachedVersionName = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        }.getOrDefault("")
        i("App", "进程启动，高精度低耗电日志系统已初始化 (v${cachedVersionName.ifBlank { "?" }} / API ${Build.VERSION.SDK_INT})")
    }

    fun getLogFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, LOG_FILE_NAME)
    }

    /**
     * 供后台 CoroutineScope 挂载的全局异常捕获器，防止未捕获协程异常静默消失。
     */
    fun coroutineExceptionHandler(tag: String): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            e(tag, "后台协程未捕获异常", throwable)
        }
    }

    fun d(tag: String, msg: String) = log("D", tag, msg, null, persistToDisk = false)
    fun i(tag: String, msg: String) = log("I", tag, msg, null, persistToDisk = false)
    fun w(tag: String, msg: String, t: Throwable? = null) = log("W", tag, msg, t, persistToDisk = t != null)
    fun e(tag: String, msg: String, t: Throwable? = null) = log("E", tag, msg, t, persistToDisk = true)

    private fun buildRuntimeSnapshot(): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
        val maxMb = (rt.maxMemory() / (1024L * 1024L)).coerceAtLeast(1L)
        val ver = cachedVersionName.ifBlank { "?" }
        val lod = runCatching { RenderPowerGovernor.formatCurrentLodSummary() }.getOrDefault("?")
        return "v$ver|API${Build.VERSION.SDK_INT}|Heap ${usedMb}/${maxMb}MB|LOD=$lod"
    }

    private fun formatThrowableDetail(t: Throwable): String {
        return buildString {
            // 1. 完整异常因果链（外层 -> 最深根因，最多 4 层）
            var cur: Throwable? = t
            var depth = 0
            var rootCause: Throwable = t
            while (cur != null && depth < 4) {
                rootCause = cur
                val prefix = if (depth == 0) " | " else "\n  ↳ Caused by: "
                append(prefix)
                append(cur.javaClass.name)
                if (!cur.message.isNullOrBlank()) {
                    append(": ").append(cur.message)
                }
                cur = cur.cause
                depth++
            }
            // 2. 精准提取根因堆栈帧（顶层抛出点 + 本应用关键帧，最多 6 帧）
            val frames = rootCause.stackTrace
            if (frames.isNotEmpty()) {
                val picked = ArrayList<StackTraceElement>(6)
                // 先收录前 2 帧（真实抛异常点）
                frames.take(2).forEach { picked.add(it) }
                // 再优先补充本应用源码帧
                for (f in frames.drop(2)) {
                    if (picked.size >= 6) break
                    if (f.className.startsWith(APP_PKG_PREFIX) || f.fileName?.endsWith(".kt") == true) {
                        picked.add(f)
                    }
                }
                // 若仍不足 4 帧，补充紧邻帧
                if (picked.size < 4) {
                    for (f in frames.drop(2)) {
                        if (picked.size >= 4) break
                        if (f !in picked) picked.add(f)
                    }
                }
                picked.forEach { el ->
                    append("\n    at ")
                    append(el.className.removePrefix(APP_PKG_PREFIX))
                    append('.').append(el.methodName)
                    append('(').append(el.fileName ?: "Unknown")
                    if (el.lineNumber > 0) append(':').append(el.lineNumber)
                    append(')')
                }
            }
        }
    }

    private fun captureCallerLocation(): String {
        val frames = Throwable().stackTrace
        for (el in frames) {
            if (el.className.contains("AppLogger")) continue
            val shortCls = el.className.removePrefix(APP_PKG_PREFIX)
            val linePart = if (el.lineNumber > 0) ":${el.lineNumber}" else ""
            return " @ $shortCls.${el.methodName}(${el.fileName ?: "?"}$linePart)"
        }
        return ""
    }

    private fun log(level: String, tag: String, msg: String, t: Throwable?, persistToDisk: Boolean) {
        val time = LocalTime.now().format(timeFmt)
        val line = if (level == "E" || t != null) {
            val thr = Thread.currentThread()
            val thrTag = "[T:${thr.name}#${thr.id}]"
            val detail = if (t != null) formatThrowableDetail(t) else captureCallerLocation()
            val ctxSnap = if (level == "E") "\n  [Ctx] ${buildRuntimeSnapshot()}" else ""
            "$time [$level/$tag] $thrTag $msg$detail$ctxSnap"
        } else {
            "$time [$level/$tag] $msg"
        }

        when (level) {
            "E" -> Log.e("MC_$tag", msg, t)
            "W" -> Log.w("MC_$tag", msg, t)
            "D" -> Log.d("MC_$tag", msg)
            else -> Log.i("MC_$tag", msg)
        }

        synchronized(lock) {
            if (memoryLines.size >= MAX_MEMORY_LINES) {
                memoryLines.removeFirst()
            }
            memoryLines.addLast(line)
        }

        if (!persistToDisk) return
        val ctx = appContext ?: return
        ioExecutor.execute {
            runCatching {
                val file = getLogFile(ctx)
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    val tail = file.readText(Charsets.UTF_8).takeLast(96 * 1024)
                    file.writeText(tail + "\n" + line + "\n", Charsets.UTF_8)
                } else {
                    file.appendText(line + "\n", Charsets.UTF_8)
                }
            }
        }
    }

    /**
     * 供 GlobalCrashHandler 在崩溃瞬间导出最近 N 条运行轨迹（纯内存零 I/O，安全不抛异常）
     */
    fun snapshotRecentLines(limit: Int = 15): List<String> {
        return synchronized(lock) {
            val all = memoryLines.toList()
            if (all.size <= limit) all else all.takeLast(limit)
        }
    }

    fun readLogs(context: Context): List<String> {
        val memCopy = synchronized(lock) { memoryLines.toList() }
        if (memCopy.isNotEmpty()) return memCopy.asReversed()
        val file = getLogFile(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).takeLast(MAX_MEMORY_LINES).asReversed()
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        synchronized(lock) { memoryLines.clear() }
        ioExecutor.execute {
            runCatching {
                val file = getLogFile(context)
                if (file.exists()) file.writeText("", Charsets.UTF_8)
            }
        }
    }
}
