package com.app.magiccapsule.crash

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 轻量级运行日志记录器（供真机测试排障使用）：
 * - 内存环形缓冲（保留最新 350 条）+ 后台单线程异步落盘至 getExternalFilesDir(null)/runtime_log.txt
 * - 主线程零阻塞、超 256KB 自动截断，不产生额外界面重组开销
 */
object AppLogger {

    private const val LOG_FILE_NAME = "runtime_log.txt"
    private const val MAX_MEMORY_LINES = 350
    private const val MAX_FILE_BYTES = 256 * 1024L

    private val lock = Any()
    private val memoryLines = ArrayDeque<String>(MAX_MEMORY_LINES)
    private val ioExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        i("App", "进程启动，运行日志系统已初始化")
    }

    fun getLogFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, LOG_FILE_NAME)
    }

    fun i(tag: String, msg: String) = log("I", tag, msg, null)
    fun w(tag: String, msg: String) = log("W", tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable? = null) = log("E", tag, msg, t)

    private fun log(level: String, tag: String, msg: String, t: Throwable?) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val errPart = if (t != null) " | ${t.javaClass.simpleName}: ${t.message}" else ""
        val line = "$time [$level/$tag] $msg$errPart"

        when (level) {
            "E" -> Log.e("MC_$tag", msg, t)
            "W" -> Log.w("MC_$tag", msg)
            else -> Log.i("MC_$tag", msg)
        }

        synchronized(lock) {
            if (memoryLines.size >= MAX_MEMORY_LINES) {
                memoryLines.removeFirst()
            }
            memoryLines.addLast(line)
        }

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
