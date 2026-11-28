package com.app.magiccapsule.service

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内置 Shizuku 桥接器（防递归与防主线程阻塞优化版）：
 * - 使用 AtomicBoolean 防止重复/递归触发 grantWriteSecureSettings
 * - refreshState 仅刷新状态，不再无限回调 onGrantedCallback
 */
object ShizukuBridge {

    private const val REQ_CODE_PERMISSION = 10099

    private val _binderAlive = MutableStateFlow(false)
    val binderAlive: StateFlow<Boolean> = _binderAlive.asStateFlow()

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    private val _statusText = MutableStateFlow("等待检测 Shizuku 服务...")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val initialized = AtomicBoolean(false)
    private val autoRequestedThisSession = AtomicBoolean(false)
    private val autoGrantedOnce = AtomicBoolean(false)
    private val grantInProgress = AtomicBoolean(false)
    private val refreshInProgress = AtomicBoolean(false)

    @Volatile
    private var onGrantedCallback: (() -> Unit)? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshState(autoRequest = true, triggerAutoGrantIfNeeded = true)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _binderAlive.value = false
        _permissionGranted.value = false
        _statusText.value = "Shizuku 服务已断开（请在 Shizuku 应用中启动）"
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQ_CODE_PERMISSION) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                _permissionGranted.value = granted
                if (granted) {
                    _statusText.value = "Shizuku 已授权，正在同步权限..."
                    autoGrantedOnce.set(true)
                    onGrantedCallback?.invoke()
                } else {
                    _statusText.value = "用户拒绝了 Shizuku 授权，可随时点击按钮重新申请"
                }
            }
        }

    fun init(onGranted: () -> Unit) {
        onGrantedCallback = onGranted
        if (initialized.compareAndSet(false, true)) {
            runCatching {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Shizuku.addRequestPermissionResultListener(permissionResultListener)
            }
        }
        refreshState(autoRequest = true, triggerAutoGrantIfNeeded = true)
    }

    fun refreshState(
        autoRequest: Boolean = false,
        triggerAutoGrantIfNeeded: Boolean = false
    ) {
        if (!refreshInProgress.compareAndSet(false, true)) return
        try {
            refreshStateInternal(autoRequest, triggerAutoGrantIfNeeded)
        } finally {
            refreshInProgress.set(false)
        }
    }

    private fun refreshStateInternal(
        autoRequest: Boolean,
        triggerAutoGrantIfNeeded: Boolean
    ) {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        _binderAlive.value = alive
        if (!alive) {
            _permissionGranted.value = false
            _statusText.value = "未检测到运行中的 Shizuku 服务"
            return
        }
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        _permissionGranted.value = granted
        if (granted) {
            _statusText.value = "Shizuku 服务已连接且已授权"
            if (triggerAutoGrantIfNeeded && autoGrantedOnce.compareAndSet(false, true)) {
                onGrantedCallback?.invoke()
            }
        } else {
            _statusText.value = "Shizuku 服务已运行，等待授权"
            if (autoRequest && autoRequestedThisSession.compareAndSet(false, true)) {
                requestPermission()
            }
        }
    }

    fun requestPermission(): Boolean {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        _binderAlive.value = alive
        if (!alive) {
            _statusText.value = "请先打开 Shizuku 并启动服务"
            return false
        }
        val already = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (already) {
            _permissionGranted.value = true
            _statusText.value = "Shizuku 已授权"
            onGrantedCallback?.invoke()
            return true
        }
        return runCatching {
            if (Shizuku.isPreV11()) {
                _statusText.value = "当前 Shizuku 版本过低，请升级至 v11+"
                false
            } else {
                Shizuku.requestPermission(REQ_CODE_PERMISSION)
                _statusText.value = "已向 Shizuku 发起授权申请，请在弹窗中允许"
                true
            }
        }.getOrElse { e ->
            _statusText.value = "申请 Shizuku 授权失败：${e.message}"
            false
        }
    }

    /**
     * 通过 Shizuku Shell 进程执行 pm grant，自动授予本应用 WRITE_SECURE_SETTINGS
     */
    fun grantWriteSecureSettings(context: Context): Pair<Boolean, String> {
        if (!_binderAlive.value || !_permissionGranted.value) {
            return false to "Shizuku 未连接或未授权"
        }
        if (SystemHeadsUpController.hasPermission(context)) {
            _statusText.value = "Shizuku 已授权 · WRITE_SECURE_SETTINGS 已就绪"
            return true to "已具备 WRITE_SECURE_SETTINGS 权限"
        }
        if (!grantInProgress.compareAndSet(false, true)) {
            return false to "正在通过 Shizuku 授权中..."
        }
        return try {
            runCatching {
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                ).apply { isAccessible = true }

                val cmd = arrayOf(
                    "pm",
                    "grant",
                    context.packageName,
                    "android.permission.WRITE_SECURE_SETTINGS"
                )
                val proc = method.invoke(null, cmd, null, null) as Process
                val err = proc.errorStream.bufferedReader().readText().trim()
                val code = proc.waitFor()
                val ok = code == 0 || SystemHeadsUpController.hasPermission(context)
                if (ok) {
                    _statusText.value = "已通过 Shizuku 自动授予 WRITE_SECURE_SETTINGS"
                    true to "已通过 Shizuku 自动授予 WRITE_SECURE_SETTINGS"
                } else {
                    val msg = "Shizuku 授权执行返回 $code${if (err.isNotBlank()) "：$err" else ""}"
                    _statusText.value = msg
                    false to msg
                }
            }.getOrElse { e ->
                val msg = "Shizuku 执行异常：${e.javaClass.simpleName}: ${e.message}"
                _statusText.value = msg
                false to msg
            }
        } finally {
            grantInProgress.set(false)
        }
    }

    /**
     * 通过已授权的 Shizuku 执行只读系统状态查询命令（例如 dumpsys / settings get）
     */
    fun runShellCommand(vararg cmd: String): Pair<Boolean, String> {
        if (!_binderAlive.value || !_permissionGranted.value) {
            return false to ""
        }
        return runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            val proc = method.invoke(null, cmd, null, null) as Process
            val out = proc.inputStream.bufferedReader().readText().trim()
            val err = proc.errorStream.bufferedReader().readText().trim()
            val code = proc.waitFor()
            val combined = if (err.isNotEmpty()) "$out | err=$err" else out
            (code == 0) to combined
        }.getOrDefault(false to "")
    }
}
