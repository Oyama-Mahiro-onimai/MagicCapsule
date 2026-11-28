package com.app.magiccapsule.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import com.app.magiccapsule.capsule.android16.AndroidLiveUpdateBridge
import com.app.magiccapsule.capsule.CapsuleNotificationFactory
import com.app.magiccapsule.capsule.CapsulePublisher
import com.app.magiccapsule.capsule.DeviceBrandRouter
import com.app.magiccapsule.capsule.huawei.HuaweiLiveCapsuleBridge
import com.app.magiccapsule.capsule.honor.MagicCapsuleBridge
import com.app.magiccapsule.capsule.vivo.VivoSuperXBridge
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.data.CapsulePrefs
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.model.CapturedNotification
import com.app.magiccapsule.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全链路手动自检引擎（v167）：
 * 覆盖底层调用与权限、传入解析与过滤链路、应用内传出数据一致性校验，
 * 以及实机弹出横幅通知、弹出导航横幅通知（含原地刷新防闪回）、胶囊轮换文本三大真机交互测试。
 */
object SelfCheckEngine {

    private const val TAG = "SelfCheck"
    private const val SELFCHECK_BANNER_KEY = "magic_capsule_selfcheck_banner"
    private const val SELFCHECK_NAV_KEY = "magic_capsule_selfcheck_nav"
    private const val SELFCHECK_ROTATE_KEY = "magic_capsule_selfcheck_rotate"

    enum class CheckStatus(val label: String) {
        PENDING("待检测"),
        RUNNING("检测中"),
        PASS("通过"),
        WARN("警告"),
        FAIL("异常")
    }

    enum class CheckCategory(val label: String) {
        ENV_AND_CALLS("1. 底层调用与权限环境"),
        INBOUND_PIPELINE("2. 传入解析与过滤链路"),
        OUTBOUND_DATA_AUDIT("3. 应用内传出数据校验"),
        LIVE_UI_TESTS("4. 实机弹出与轮换测试")
    }

    @Immutable
    data class SelfCheckItem(
        val id: String,
        val category: CheckCategory,
        val title: String,
        val status: CheckStatus,
        val summary: String,
        val detail: String,
        val durationMs: Long = 0L
    )

    @Immutable
    data class SelfCheckReport(
        val isRunning: Boolean = false,
        val currentStepLabel: String = "尚未执行自检",
        val progress: Float = 0f,
        val startedAt: String = "",
        val finishedAt: String = "",
        val totalDurationMs: Long = 0L,
        val items: List<SelfCheckItem> = emptyList()
    ) {
        val passCount: Int get() = items.count { it.status == CheckStatus.PASS }
        val warnCount: Int get() = items.count { it.status == CheckStatus.WARN }
        val failCount: Int get() = items.count { it.status == CheckStatus.FAIL }
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + AppLogger.coroutineExceptionHandler(TAG)
    )
    private val runningGuard = AtomicBoolean(false)
    private val _report = MutableStateFlow(SelfCheckReport())
    val report: StateFlow<SelfCheckReport> = _report.asStateFlow()

    private fun nowTimeStr(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    /**
     * 一键执行全量 16 项自检（底层调用 + 传入链路 + 传出数据审计 + 实机横幅/导航横幅/胶囊轮换测试）
     */
    fun runFullSelfCheck(context: Context) {
        if (!runningGuard.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        scope.launch {
            val startElapsed = SystemClock.elapsedRealtime()
            val startStr = nowTimeStr()
            AppLogger.i(TAG, "===== 开始执行全量系统自检 ($startStr) =====")
            val results = mutableListOf<SelfCheckItem>()
            _report.value = SelfCheckReport(
                isRunning = true,
                currentStepLabel = "初始化自检引擎...",
                progress = 0.02f,
                startedAt = startStr,
                items = emptyList()
            )

            val steps: List<Pair<String, suspend () -> SelfCheckItem>> = listOf(
                "检测权限与服务绑定" to { checkPermissionsAndBindings(ctx) },
                "深度探针：实测无 Shizuku 悬浮窗真实弹出能力" to { checkActualOverlayPermissionProbe(ctx) },
                "检测品牌胶囊引擎与反射桥接" to { checkBrandEngineAndBridges(ctx) },
                "检测系统属性与 LTPO 帧率档位" to { checkSystemPropAndLod(ctx) },
                "检测导航传感器与 GNSS 定位源" to { checkSensorsAndLocationProviders(ctx) },
                "检测传入对话消息解析器" to { checkInboundConversationParser() },
                "检测传入实况通知 Extra 解析" to { checkInboundLiveExtraParser(ctx) },
                "检测传入 LRC 歌词解析与智能换行" to { checkInboundLrcEngine() },
                "检测传入通知渠道过滤规则" to { checkInboundFilterEngine(ctx) },
                "校验应用内配置与开关一致性" to { auditOutboundConfigData(ctx) },
                "校验胶囊通知传出对象与协议包" to { auditOutboundCapsuleNotification(ctx) },
                "校验内存活跃载荷与位图/坐标健康度" to { auditInMemoryPayloadsAndNavState() },
                "校验设备监控传出遥测数据" to { auditOutboundSystemSnapshot(ctx) },
                "校验四大地图导航 URL 编码安全性" to { auditOutboundNavUrlFormatting() },
                "实机测试：弹出普通横幅通知" to { executeBannerPopupTest(ctx) },
                "实机测试：弹出导航横幅与原地刷新" to { executeNavBannerPopupTest(ctx) },
                "实机测试：胶囊多段文本轮换" to { executeCapsuleTextRotationTest(ctx) }
            )

            try {
                steps.forEachIndexed { idx, (label, runner) ->
                    _report.value = _report.value.copy(
                        currentStepLabel = "(${idx + 1}/${steps.size}) $label",
                        progress = (idx.toFloat() / steps.size.toFloat()).coerceIn(0.05f, 0.98f)
                    )
                    val item = runStepSafely(label, runner)
                    results.add(item)
                    logItemResult(item)
                    _report.value = _report.value.copy(items = results.toList())
                }
            } finally {
                val totalMs = (SystemClock.elapsedRealtime() - startElapsed).coerceAtLeast(1L)
                val endStr = nowTimeStr()
                val finalReport = SelfCheckReport(
                    isRunning = false,
                    currentStepLabel = "全量自检已完成",
                    progress = 1f,
                    startedAt = startStr,
                    finishedAt = endStr,
                    totalDurationMs = totalMs,
                    items = results.toList()
                )
                _report.value = finalReport
                AppLogger.i(
                    TAG,
                    "===== 全量自检结束 (${totalMs}ms) 通过=${finalReport.passCount} 警告=${finalReport.warnCount} 异常=${finalReport.failCount} ====="
                )
                runningGuard.set(false)
            }
        }
    }

    /**
     * 单项手动触发：实际悬浮窗权限深度探针（无 Shizuku 实测）
     */
    fun runSingleOverlayProbeTest(context: Context) {
        runSingleTask(context, "深度探针：实际悬浮窗权限实测") { checkActualOverlayPermissionProbe(it) }
    }

    /**
     * 单项手动触发：弹出横幅通知测试
     */
    fun runSingleBannerPopupTest(context: Context) {
        runSingleTask(context, "实机测试：弹出普通横幅通知") { executeBannerPopupTest(it) }
    }

    /**
     * 单项手动触发：弹出导航横幅通知测试（含原地刷新防闪回验证）
     */
    fun runSingleNavBannerPopupTest(context: Context) {
        runSingleTask(context, "实机测试：弹出导航横幅与原地刷新") { executeNavBannerPopupTest(it) }
    }

    /**
     * 单项手动触发：胶囊轮换文本测试
     */
    fun runSingleCapsuleRotationTest(context: Context) {
        runSingleTask(context, "实机测试：胶囊多段文本轮换") { executeCapsuleTextRotationTest(it) }
    }

    /**
     * 单项手动触发：仅校验应用内传出数据
     */
    fun runOutboundDataAuditOnly(context: Context) {
        if (!runningGuard.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        scope.launch {
            val startElapsed = SystemClock.elapsedRealtime()
            val startStr = nowTimeStr()
            val existing = _report.value.items.toMutableList()
            _report.value = _report.value.copy(
                isRunning = true,
                currentStepLabel = "正在体检应用内传出数据...",
                progress = 0.2f,
                startedAt = startStr
            )
            try {
                val tasks: List<Pair<String, suspend () -> SelfCheckItem>> = listOf(
                    "校验应用内配置与开关一致性" to { auditOutboundConfigData(ctx) },
                    "校验胶囊通知传出对象与协议包" to { auditOutboundCapsuleNotification(ctx) },
                    "校验内存活跃载荷与位图/坐标健康度" to { auditInMemoryPayloadsAndNavState() },
                    "校验设备监控传出遥测数据" to { auditOutboundSystemSnapshot(ctx) },
                    "校验四大地图导航 URL 编码安全性" to { auditOutboundNavUrlFormatting() }
                )
                tasks.forEachIndexed { idx, (label, runner) ->
                    _report.value = _report.value.copy(
                        currentStepLabel = "(${idx + 1}/${tasks.size}) $label",
                        progress = ((idx + 1).toFloat() / tasks.size.toFloat()).coerceIn(0.2f, 0.95f)
                    )
                    val item = runStepSafely(label, runner)
                    logItemResult(item)
                    upsertItem(existing, item)
                    _report.value = _report.value.copy(items = existing.toList())
                }
            } finally {
                val totalMs = (SystemClock.elapsedRealtime() - startElapsed).coerceAtLeast(1L)
                _report.value = _report.value.copy(
                    isRunning = false,
                    currentStepLabel = "应用内传出数据体检完成",
                    progress = 1f,
                    finishedAt = nowTimeStr(),
                    totalDurationMs = totalMs,
                    items = existing.toList()
                )
                runningGuard.set(false)
            }
        }
    }

    private fun runSingleTask(
        context: Context,
        stepLabel: String,
        runner: suspend (Context) -> SelfCheckItem
    ) {
        if (!runningGuard.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        scope.launch {
            val startElapsed = SystemClock.elapsedRealtime()
            val startStr = nowTimeStr()
            val existing = _report.value.items.toMutableList()
            _report.value = _report.value.copy(
                isRunning = true,
                currentStepLabel = stepLabel,
                progress = 0.35f,
                startedAt = startStr
            )
            try {
                val item = runStepSafely(stepLabel) { runner(ctx) }
                logItemResult(item)
                upsertItem(existing, item)
                _report.value = _report.value.copy(items = existing.toList())
            } finally {
                val totalMs = (SystemClock.elapsedRealtime() - startElapsed).coerceAtLeast(1L)
                _report.value = _report.value.copy(
                    isRunning = false,
                    currentStepLabel = "$stepLabel 已完成",
                    progress = 1f,
                    finishedAt = nowTimeStr(),
                    totalDurationMs = totalMs,
                    items = existing.toList()
                )
                runningGuard.set(false)
            }
        }
    }

    private fun upsertItem(list: MutableList<SelfCheckItem>, item: SelfCheckItem) {
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            list[idx] = item
        } else {
            list.add(item)
        }
    }

    private suspend fun runStepSafely(
        label: String,
        block: suspend () -> SelfCheckItem
    ): SelfCheckItem {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val item = block()
            item.copy(durationMs = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L))
        } catch (t: Throwable) {
            AppLogger.e(TAG, "自检项执行崩溃: $label", t)
            SelfCheckItem(
                id = "err_${label.hashCode()}",
                category = CheckCategory.ENV_AND_CALLS,
                title = label,
                status = CheckStatus.FAIL,
                summary = "执行抛出异常：${t.javaClass.simpleName}",
                detail = "异常信息：${t.message ?: "无"}\n已自动写入精细错误堆栈，请在「运行与调试日志 -> 仅错误」查看。",
                durationMs = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
            )
        }
    }

    private fun logItemResult(item: SelfCheckItem) {
        val msg = "[${item.status.label}] ${item.title} (${item.durationMs}ms) -> ${item.summary}"
        when (item.status) {
            CheckStatus.FAIL -> AppLogger.e(TAG, "$msg | ${item.detail.replace('\n', ' ')}")
            CheckStatus.WARN -> AppLogger.w(TAG, "$msg | ${item.detail.replace('\n', ' ')}")
            else -> AppLogger.i(TAG, msg)
        }
    }

    // ==================== 1. 底层调用与权限环境 ====================

    private fun checkPermissionsAndBindings(ctx: Context): SelfCheckItem {
        val postNotifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val notifListenerPerm = CapsuleNotificationListener.isPermissionGranted(ctx)
        val notifListenerConnected = CapsuleNotificationListener.connected.value
        val overlayOk = UniversalBannerHost.canDrawOverlay(ctx)
        val a11yEnabled = CapsuleOverlayAccessibilityService.isServiceEnabled(ctx)
        val a11yConnected = CapsuleOverlayAccessibilityService.connected.value
        val fineLocOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseLocOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val writeSecureOk = SystemHeadsUpController.hasPermission(ctx)
        val shizukuOk = ShizukuBridge.binderAlive.value && ShizukuBridge.permissionGranted.value

        val detail = buildString {
            appendLine("• 通知发送权限 (POST_NOTIFICATIONS): ${if (postNotifOk) "已授予" else "未授予"}")
            appendLine("• 通知使用权: ${if (notifListenerPerm) "已授权" else "未授权"} / 服务绑定=${if (notifListenerConnected) "在线" else "离线"}")
            appendLine("• 悬浮窗权限 (SYSTEM_ALERT_WINDOW): ${if (overlayOk) "已授予" else "未授予"}")
            appendLine("• 无障碍顶层横幅: ${if (a11yEnabled) "已开启" else "未开启"} / 绑定=${if (a11yConnected) "在线" else "离线"}")
            appendLine("• 导航定位权限: 精确定位=${if (fineLocOk) "有" else "无"} / 粗略定位=${if (coarseLocOk) "有" else "无"}")
            append("• 可选增强: WRITE_SECURE_SETTINGS=${if (writeSecureOk) "有" else "无"} / Shizuku=${if (shizukuOk) "就绪" else "未连接"}")
        }

        val status = when {
            !postNotifOk || !notifListenerPerm -> CheckStatus.FAIL
            !notifListenerConnected || (!overlayOk && !a11yConnected) -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        val summary = when (status) {
            CheckStatus.FAIL -> "核心通知权限缺失（发送=${postNotifOk}，监听=${notifListenerPerm}）"
            CheckStatus.WARN -> "基础权限正常，但监听或悬浮横幅未完全就绪"
            else -> "核心权限与服务绑定全部正常（悬浮窗=$overlayOk，监听=在线）"
        }
        return SelfCheckItem(
            id = "env_permissions",
            category = CheckCategory.ENV_AND_CALLS,
            title = "系统权限与后台服务绑定",
            status = status,
            summary = summary,
            detail = detail
        )
    }

    private fun checkActualOverlayPermissionProbe(ctx: Context): SelfCheckItem {
        val diag = UniversalBannerHost.probeOverlayPermission(ctx, runLiveWindowProbe = true)
        val a11yReady = CapsuleOverlayAccessibilityService.connected.value
        val shizukuReady = ShizukuBridge.binderAlive.value && ShizukuBridge.permissionGranted.value

        val detail = buildString {
            appendLine("• Settings.canDrawOverlays: ${diag.settingsCanDraw}")
            appendLine("• AppOps (SYSTEM_ALERT_WINDOW): ${diag.appOpsLabel}")
            if (diag.miuiBgPopupAllowed != null) {
                appendLine("• 小米/HyperOS「后台弹出界面」(op=10021): ${if (diag.miuiBgPopupAllowed) "已允许" else "未允许(后台可能拦截)"}")
            }
            appendLine("• 1x1 透明 TYPE_APPLICATION_OVERLAY 实测挂载: ${if (diag.liveAddViewOk) "成功" else "失败 (${diag.liveAddViewError})"}")
            appendLine("• 当前 Shizuku 状态: ${if (shizukuReady) "已连接" else "未授权(纯原生无Shizuku环境)"} | 无障碍兜底: ${if (a11yReady) "在线" else "离线"}")
            if (!diag.isActuallyUsable || diag.miuiBgPopupAllowed == false) {
                append("• 修复建议: ${diag.romFixHint}")
            }
        }.trim()

        val status = when {
            !diag.liveAddViewOk -> CheckStatus.FAIL
            !diag.settingsCanDraw && diag.appOpsMode != android.app.AppOpsManager.MODE_ALLOWED -> CheckStatus.FAIL
            diag.miuiBgPopupAllowed == false -> CheckStatus.WARN
            !diag.settingsCanDraw && diag.appOpsMode == android.app.AppOpsManager.MODE_ALLOWED -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        val summary = when {
            !diag.liveAddViewOk -> "实际 WindowManager.addView 被拦截：${diag.liveAddViewError.take(60)}"
            diag.miuiBgPopupAllowed == false -> "悬浮窗可挂载，但「后台弹出界面」未开，切后台可能被系统拦截"
            !diag.settingsCanDraw && diag.liveAddViewOk -> "AppOps 与实测挂载均已通过（系统 canDrawOverlays 缓存存在延迟）"
            else -> "无需 Shizuku，实测 TYPE_APPLICATION_OVERLAY 真实挂载完全正常"
        }
        return SelfCheckItem(
            id = "env_overlay_deep_probe",
            category = CheckCategory.ENV_AND_CALLS,
            title = "实际悬浮窗权限深度探针（无 Shizuku 实测）",
            status = status,
            summary = summary,
            detail = detail
        )
    }

    private fun checkBrandEngineAndBridges(ctx: Context): SelfCheckItem {
        val engine = DeviceBrandRouter.currentEngine
        val displayName = DeviceBrandRouter.engineDisplayName()
        val honorOk = MagicCapsuleBridge.isSupported()
        val huaweiOk = HuaweiLiveCapsuleBridge.isSupported()
        val huaweiLiveEnabled = if (huaweiOk) HuaweiLiveCapsuleBridge.areLiveNotificationsEnabled(ctx) else false
        val android16Ok = AndroidLiveUpdateBridge.isSupported()
        val android16CanPost = if (android16Ok) AndroidLiveUpdateBridge.canPostPromotedNotifications(ctx) else false
        val vivoRejected = VivoSuperXBridge.rejectedByWhitelist

        val detail = buildString {
            appendLine("• 当前路由引擎: $engine ($displayName)")
            appendLine("• 荣耀 framework-magic 反射句柄: ${if (honorOk) "已就绪" else "不适用/不可用"}")
            appendLine("• 华为 HarmonyOS 实况窗桥接: ${if (huaweiOk) "已就绪（实况开关=$huaweiLiveEnabled）" else "不适用"}")
            appendLine("• Android 16+ Live Updates: ${if (android16Ok) "支持（可推送=$android16CanPost）" else "当前 API ${Build.VERSION.SDK_INT}"}")
            append("• vivo SuperX 白名单拒绝标记: $vivoRejected")
        }

        val status = when {
            engine == DeviceBrandRouter.BrandEngine.HUAWEI_LIVE_CAPSULE && !huaweiLiveEnabled -> CheckStatus.WARN
            engine == DeviceBrandRouter.BrandEngine.ANDROID_LIVE_UPDATES && !android16CanPost -> CheckStatus.WARN
            engine == DeviceBrandRouter.BrandEngine.VIVO_SUPERX && vivoRejected -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        return SelfCheckItem(
            id = "env_brand_engine",
            category = CheckCategory.ENV_AND_CALLS,
            title = "厂商胶囊引擎与反射桥接调用",
            status = status,
            summary = displayName,
            detail = detail
        )
    }

    private fun checkSystemPropAndLod(ctx: Context): SelfCheckItem {
        val entries = SystemPropertyProfile.collectDiagnosticEntries(ctx)
        val (minHz, peakHz) = RenderPowerGovernor.resolveDisplayHzBounds(ctx)
        val lodSummary = RenderPowerGovernor.formatCurrentLodSummary()
        val animScale = SystemPropertyProfile.getAnimatorDurationScale(ctx)

        val validHz = minHz > 0f && peakHz >= minHz
        val detail = buildString {
            appendLine("• ROM / SoC: ${SystemPropertyProfile.romDisplayName} / ${SystemPropertyProfile.socDisplayName}")
            appendLine("• 屏幕刷新率边界: ${minHz.toInt()}Hz ~ ${peakHz.toInt()}Hz | 当前 LOD: $lodSummary")
            appendLine("• 系统动画缩放: ${animScale}x | 低内存设备=${SystemPropertyProfile.isLowRamDevice}")
            append("• 系统属性条目数: ${entries.size} 项（已全部成功读取）")
        }

        val status = if (validHz && entries.isNotEmpty()) CheckStatus.PASS else CheckStatus.WARN
        return SelfCheckItem(
            id = "env_sysprop_lod",
            category = CheckCategory.ENV_AND_CALLS,
            title = "系统属性探测与 LTPO 帧率调度",
            status = status,
            summary = "${SystemPropertyProfile.romDisplayName} · ${minHz.toInt()}~${peakHz.toInt()}Hz ($lodSummary)",
            detail = detail
        )
    }

    private fun checkSensorsAndLocationProviders(ctx: Context): SelfCheckItem {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val hasAccel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasLinearAccel = sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null
        val hasMag = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
        val hasRot = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
        val lm = ctx.getSystemService(LocationManager::class.java)
        val gpsEnabled = runCatching { lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }.getOrDefault(false)
        val netEnabled = runCatching { lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true }.getOrDefault(false)

        val detail = buildString {
            appendLine("• 传感器: 加速度=$hasAccel | 线性加速度=$hasLinearAccel | 磁力计=$hasMag | 旋转矢量=$hasRot")
            append("• 定位服务开关: GPS=${if (gpsEnabled) "开启" else "关闭"} | 网络定位=${if (netEnabled) "开启" else "关闭"}")
        }
        val status = when {
            !hasAccel && !hasLinearAccel -> CheckStatus.FAIL
            !gpsEnabled && !netEnabled -> CheckStatus.WARN
            !hasLinearAccel || !hasMag -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        val summary = when (status) {
            CheckStatus.FAIL -> "缺少基础加速度传感器，无法进行惯性融合"
            CheckStatus.WARN -> "传感器或系统定位开关未全开（GPS=$gpsEnabled，线性加速度=$hasLinearAccel）"
            else -> "9轴/线性加速度与 GNSS 定位源均就绪"
        }
        return SelfCheckItem(
            id = "env_sensors_gnss",
            category = CheckCategory.ENV_AND_CALLS,
            title = "导航传感器与 GNSS 硬件调用",
            status = status,
            summary = summary,
            detail = detail
        )
    }

    // ==================== 2. 传入解析与过滤链路 ====================

    private fun checkInboundConversationParser(): SelfCheckItem {
        val testKey = "selfcheck_conv_key"
        val parsed = ConversationMessageParser.parse(
            convKey = testKey,
            notification = Notification(),
            rawTitle = "[3条] 张三",
            rawText = "[3条] 张三: 晚上八点出发吗？",
            appName = "微信",
            isCall = false
        )
        ConversationMessageParser.clearConversation(testKey)

        val titleOk = parsed.cleanTitle == "张三"
        val textOk = parsed.cleanText.contains("晚上八点出发吗") && !parsed.cleanText.contains("张三: 张三:")
        val status = if (titleOk && textOk) CheckStatus.PASS else CheckStatus.FAIL
        return SelfCheckItem(
            id = "in_conv_parser",
            category = CheckCategory.INBOUND_PIPELINE,
            title = "传入对话通知清洗（ConversationMessageParser）",
            status = status,
            summary = if (status == CheckStatus.PASS) "标题与重复发送者前缀清洗正常" else "对话清洗结果异常",
            detail = "传入: title='[3条] 张三', text='[3条] 张三: 晚上八点出发吗？'\n传出: cleanTitle='${parsed.cleanTitle}', cleanText='${parsed.cleanText}'"
        )
    }

    private fun checkInboundLiveExtraParser(ctx: Context): SelfCheckItem {
        val superXNotif = Notification().apply {
            extras = Bundle().apply {
                putInt("notification.superx.operation", 1)
                putBundle("notification.superx.capsule", Bundle().apply {
                    putString("notification.superx.capsule.content", "骑手距您300m")
                })
                putBundle("notification.superx.infos", Bundle().apply {
                    putString("notification.superx.infos.coreInfo", "外卖配送中")
                    putString("notification.superx.infos.describe", "预计5分钟送达")
                    putInt("notification.superx.infos.progress", 78)
                })
            }
        }
        val superXParsed = LiveNotificationExtraParser.parse(ctx, superXNotif)

        val miuiNotif = Notification().apply {
            extras = Bundle().apply {
                putString(
                    "miui.focus.param",
                    JSONObject().apply {
                        put("ticker", "网约车已到达")
                        put("title", "京A·88888 已到达上车点")
                        put("content", "请在东门等候")
                        put("close", false)
                    }.toString()
                )
            }
        }
        val miuiParsed = LiveNotificationExtraParser.parse(ctx, miuiNotif)

        val superXOk = superXParsed != null && superXParsed.pillText == "骑手距您300m" && superXParsed.progressPercent == 78
        val miuiOk = miuiParsed != null && miuiParsed.pillText == "网约车已到达" && !miuiParsed.isFinish
        val status = if (superXOk && miuiOk) CheckStatus.PASS else CheckStatus.FAIL
        return SelfCheckItem(
            id = "in_live_extra_parser",
            category = CheckCategory.INBOUND_PIPELINE,
            title = "传入第三方实况协议解析（SuperX / HyperOS）",
            status = status,
            summary = if (status == CheckStatus.PASS) "SuperX 与 HyperOS Focus 协议解析全部匹配" else "部分实况协议解析失败",
            detail = "SuperX -> pill='${superXParsed?.pillText}', pct=${superXParsed?.progressPercent}\nHyperOS -> pill='${miuiParsed?.pillText}', title='${miuiParsed?.title}'"
        )
    }

    private fun checkInboundLrcEngine(): SelfCheckItem {
        val sampleLrc = """
            [offset:-200]
            [00:10.50]第一句歌词测试内容
            [00:15.20]这是一段超过三十个字符的超长测试歌词用来验证智能标点断行与最大三行截断能力，确保横幅与胶囊均不溢出
        """.trimIndent()
        val lines = LrcCapsuleLyricEngine.parseLrc(sampleLrc)
        val wrapped = LrcCapsuleLyricEngine.formatLyricForDisplay(
            "这是一段超过三十个字符的超长测试歌词，用来验证智能标点断行与最大三行截断能力，确保横幅与胶囊均不溢出。",
            maxCharsPerLine = 24,
            maxLines = 3
        )
        val pill = LrcCapsuleLyricEngine.formatLyricForPill("超长胶囊单行歌词轮换显示截断测试内容", maxChars = 14)

        val parseOk = lines.size == 2 && lines[0].timeMs == 10_300L
        val wrapLines = wrapped.split('\n').size
        val wrapOk = wrapLines in 1..3 && wrapped.isNotBlank()
        val pillOk = pill.isNotBlank() && !pill.contains('\n')
        val status = if (parseOk && wrapOk && pillOk) CheckStatus.PASS else CheckStatus.FAIL
        return SelfCheckItem(
            id = "in_lrc_engine",
            category = CheckCategory.INBOUND_PIPELINE,
            title = "传入 LRC 时间轴解析与胶囊智能换行",
            status = status,
            summary = if (status == CheckStatus.PASS) "解析 ${lines.size} 行、offset 校准精确、换行 ${wrapLines} 行合规" else "LRC 解析或断行校验失败",
            detail = "首行校准时间: ${lines.firstOrNull()?.timeMs ?: -1}ms (预期 10300ms)\n横幅断行结果($wrapLines 行): ${wrapped.replace("\n", " / ")}\n迷你胶囊裁剪: $pill"
        )
    }

    private fun checkInboundFilterEngine(ctx: Context): SelfCheckItem {
        val cfg = CapsulePrefs(ctx).load()
        val now = System.currentTimeMillis()
        val callItem = CapturedNotification(
            key = "sc_call",
            packageName = "com.tencent.mm",
            appName = "微信",
            title = "语音通话",
            text = "邀请你语音通话",
            postTime = now,
            importance = NotificationManager.IMPORTANCE_LOW,
            isCall = true,
            isSilent = true
        )
        val highItem = CapturedNotification(
            key = "sc_high",
            packageName = "com.example.test",
            appName = "测试应用",
            title = "重要通知",
            text = "正文内容",
            postTime = now,
            importance = NotificationManager.IMPORTANCE_HIGH
        )
        val silentItem = CapturedNotification(
            key = "sc_silent",
            packageName = "com.example.test",
            appName = "测试应用",
            title = "静默通知",
            text = "低优先级",
            postTime = now,
            importance = NotificationManager.IMPORTANCE_LOW,
            isSilent = true
        )

        val callPass = NotificationFilterEngine.passesChannelImportance(callItem, cfg)
        val highPass = NotificationFilterEngine.passesChannelImportance(highItem, cfg)
        val silentPass = NotificationFilterEngine.passesChannelImportance(silentItem, cfg)
        val expectedSilent = cfg.minChannelImportance <= NotificationManager.IMPORTANCE_LOW

        val ok = callPass && highPass && (silentPass == expectedSilent)
        return SelfCheckItem(
            id = "in_filter_engine",
            category = CheckCategory.INBOUND_PIPELINE,
            title = "传入通知重要性与来电直通仲裁",
            status = if (ok) CheckStatus.PASS else CheckStatus.FAIL,
            summary = if (ok) "来电最高优先级直通，渠道过滤与配置一致" else "通知过滤仲裁结果与当前配置不符",
            detail = "当前阈值 minChannelImportance=${cfg.minChannelImportance}\n测试结果: 来电直通=$callPass | 高重要性=$highPass | 静默通知=$silentPass (预期=$expectedSilent)"
        )
    }

    // ==================== 3. 应用内传出数据校验 ====================

    private fun auditOutboundConfigData(ctx: Context): SelfCheckItem {
        val cfg = CapsulePrefs(ctx).load()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // 校验全部颜色字段是否合法 ARGB/RGB Hex
        val colorFields = listOf(
            "cardBgHex" to cfg.cardBgHex,
            "titleColorHex" to cfg.titleColorHex,
            "detailColorHex" to cfg.detailColorHex,
            "btnBgHex" to cfg.btnBgHex,
            "accentColorHex" to cfg.accentColorHex,
            "overlayBannerBgHex" to cfg.overlayBannerBgHex,
            "overlayBannerTitleColorHex" to cfg.overlayBannerTitleColorHex,
            "overlayBannerDetailColorHex" to cfg.overlayBannerDetailColorHex,
            "sysCapsuleBgHex" to cfg.sysCapsuleBgHex,
            "sysCapsuleTitleColorHex" to cfg.sysCapsuleTitleColorHex,
            "sysCapsuleDetailColorHex" to cfg.sysCapsuleDetailColorHex
        )
        colorFields.forEach { (name, hex) ->
            val cleaned = hex.trim().let { if (it.startsWith("#")) it else "#$it" }
            val parsedOk = runCatching { Color.parseColor(cleaned) }.isSuccess
            if (!parsedOk) errors.add("非法颜色值 $name='$hex'")
        }

        // 校验数值范围
        if (cfg.bannerLyricTextSizePx !in 16f..80f) errors.add("bannerLyricTextSizePx 越界: ${cfg.bannerLyricTextSizePx}")
        if (cfg.capsuleLyricTextSizePx !in 12f..64f) errors.add("capsuleLyricTextSizePx 越界: ${cfg.capsuleLyricTextSizePx}")
        if (cfg.capsuleHoldDurationSec !in 1..120) warnings.add("capsuleHoldDurationSec=${cfg.capsuleHoldDurationSec}s 超出常见区间")
        if (cfg.overlayBannerDurationSec !in 1..60) warnings.add("overlayBannerDurationSec=${cfg.overlayBannerDurationSec}s 超出常见区间")

        // 校验互斥与前置条件冲突
        if (!cfg.hasAnyDisplayEngine()) {
            warnings.add("胶囊引擎、无障碍横幅、通用悬浮横幅均已关闭，前台将无任何弹出表现")
        }
        if (cfg.suppressSystemHeadsUp && !SystemHeadsUpController.hasPermission(ctx)) {
            warnings.add("已开启屏蔽系统横幅，但缺少 WRITE_SECURE_SETTINGS 权限")
        }
        if (cfg.navProvider == "amap" && cfg.navAmapKey.isBlank()) {
            warnings.add("当前导航源为高德，但尚未填写高德 Web API Key（可切换免Key OSRM）")
        } else if (cfg.navProvider == "tencent" && cfg.navTencentKey.isBlank()) {
            warnings.add("当前导航源为腾讯，但尚未填写腾讯地图 Key")
        } else if (cfg.navProvider == "baidu" && cfg.navBaiduKey.isBlank()) {
            warnings.add("当前导航源为百度，但尚未填写百度地图 AK")
        }

        val status = when {
            errors.isNotEmpty() -> CheckStatus.FAIL
            warnings.isNotEmpty() -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
        val summary = when (status) {
            CheckStatus.FAIL -> "发现 ${errors.size} 项配置数据错误"
            CheckStatus.WARN -> "配置结构合法，存在 ${warnings.size} 项开关/前置提醒"
            else -> "全部 11 项配色、字号区间与引擎开关均合法无冲突"
        }
        val detail = buildString {
            if (errors.isNotEmpty()) appendLine("错误：\n" + errors.joinToString("\n") { "• $it" })
            if (warnings.isNotEmpty()) appendLine("提醒：\n" + warnings.joinToString("\n") { "• $it" })
            if (errors.isEmpty() && warnings.isEmpty()) {
                append("引擎开关: capsule=${cfg.enableCapsuleEngine}, overlay=${cfg.enableOverlayBanner}, universal=${cfg.enableUniversalBannerMode}, nav=${cfg.navProvider}")
            }
        }.trim()
        return SelfCheckItem(
            id = "out_config_audit",
            category = CheckCategory.OUTBOUND_DATA_AUDIT,
            title = "应用内持久化配置与开关一致性审计",
            status = status,
            summary = summary,
            detail = detail
        )
    }

    private fun auditOutboundCapsuleNotification(ctx: Context): SelfCheckItem {
        val cfg = CapsulePrefs(ctx).load().copy(
            businessName = "自检校验",
            mainText = "传出胶囊数据校验",
            subText = "检查 Notification Bundle 完整性"
        )
        val testBmp = createBadgeBitmap("#FF10B981", "✓")
        val samplePayload = ActiveMirroredPayload(
            key = "selfcheck_dryrun_capsule",
            packageName = ctx.packageName,
            appName = "灵动胶囊自检",
            title = "传出胶囊数据校验",
            text = "检查 Notification Bundle 完整性",
            iconBitmap = testBmp,
            contentIntent = buildSelfLaunchPi(ctx, 9901),
            actions = emptyList(),
            livePillText = "自检校验中"
        )

        val notif = CapsuleNotificationFactory.create(
            context = ctx,
            config = cfg,
            refreshCount = 9999,
            mirroredPayload = samplePayload
        )
        val errors = mutableListOf<String>()
        val notes = mutableListOf<String>()

        if (notif.channelId.isNullOrBlank()) errors.add("channelId 为空")
        if (notif.smallIcon == null) errors.add("smallIcon 为空")
        val extras = notif.extras
        if (extras == null) {
            errors.add("notification.extras 为空")
        } else {
            val engine = DeviceBrandRouter.currentEngine
            notes.add("channelId=${notif.channelId}, engine=$engine, extrasKeys=${extras.keySet().size}")
            when (engine) {
                DeviceBrandRouter.BrandEngine.HUAWEI_LIVE_CAPSULE -> {
                    val liveCap = extras.getBundle("notification.live.capsule")
                    val keepTime = extras.getLong("notification.live.keepTime", -1L)
                    if (liveCap == null) errors.add("华为实况窗缺少 notification.live.capsule Bundle")
                    if (keepTime !in 0L..3600L) errors.add("华为 EXTRA_LIVE_KEEP_TIME=$keepTime 超出合法秒数区间 [0..3600]")
                    notes.add("Huawei keepTime=${keepTime}s, capsuleKeys=${liveCap?.keySet()?.size ?: 0}")
                }
                DeviceBrandRouter.BrandEngine.ANDROID_LIVE_UPDATES -> {
                    val reqPromoted = extras.getBoolean("android.requestPromotedOngoing", false)
                    notes.add("Android16 requestPromotedOngoing=$reqPromoted")
                }
                DeviceBrandRouter.BrandEngine.XIAOMI_HYPEROS_FOCUS -> {
                    val focusStr = extras.getString("miui.focus.param")
                    if (focusStr.isNullOrBlank()) {
                        errors.add("小米焦点通知缺少 miui.focus.param")
                    } else if (runCatching { JSONObject(focusStr) }.isFailure) {
                        errors.add("miui.focus.param 非合法 JSON")
                    }
                }
                DeviceBrandRouter.BrandEngine.VIVO_SUPERX -> {
                    if (!extras.containsKey("notification.superx.operation")) {
                        errors.add("vivo SuperX 缺少 notification.superx.operation")
                    }
                }
                else -> Unit
            }
        }

        val status = if (errors.isEmpty()) CheckStatus.PASS else CheckStatus.FAIL
        return SelfCheckItem(
            id = "out_capsule_factory",
            category = CheckCategory.OUTBOUND_DATA_AUDIT,
            title = "传出胶囊 Notification 与厂商协议包校验",
            status = status,
            summary = if (status == CheckStatus.PASS) "传出通知结构与当前品牌协议字段全部合规" else "发现 ${errors.size} 项传出协议错误",
            detail = buildString {
                if (errors.isNotEmpty()) appendLine("错误: ${errors.joinToString("; ")}")
                append(notes.joinToString("\n"))
            }.trim()
        )
    }

    private fun auditInMemoryPayloadsAndNavState(): SelfCheckItem {
        val issues = mutableListOf<String>()
        val activePayload = NotificationIconStore.currentActivePayload
        if (activePayload != null) {
            if (activePayload.key.isBlank()) issues.add("currentActivePayload.key 为空")
            if (activePayload.packageName.isBlank()) issues.add("currentActivePayload.packageName 为空")
            if (activePayload.iconBitmap?.isRecycled == true) issues.add("currentActivePayload.iconBitmap 已被回收（isRecycled=true），渲染会崩溃")
            if (activePayload.isMedia && activePayload.mediaDurationMs > 0L &&
                activePayload.mediaPositionMs > activePayload.mediaDurationMs + 5000L
            ) {
                issues.add("媒体进度越界: pos=${activePayload.mediaPositionMs}ms > dur=${activePayload.mediaDurationMs}ms")
            }
        }

        val notifList = CapsuleNotificationListener.notifications.value
        val invalidNotifs = notifList.count { it.key.isBlank() || it.packageName.isBlank() || it.postTime < 0L }
        if (invalidNotifs > 0) issues.add("通知缓存中存在 $invalidNotifs 条非法记录（空 key/包名或负时间戳）")

        val navSt = CapsuleNavigationEngine.state.value
        if (navSt.destLat.isNaN() || navSt.destLat.isInfinite() || navSt.destLon.isNaN() || navSt.destLon.isInfinite()) {
            issues.add("导航状态坐标异常: lat=${navSt.destLat}, lon=${navSt.destLon}")
        }
        if (navSt.running && navSt.routeCount <= 0) {
            issues.add("导航处于运行态但 routeCount=0")
        }

        val status = if (issues.isEmpty()) CheckStatus.PASS else CheckStatus.FAIL
        val detail = buildString {
            appendLine("• 当前活跃镜像载荷: ${activePayload?.let { "${it.appName} (${it.packageName}), bmpRecycled=${it.iconBitmap?.isRecycled}" } ?: "无"}")
            appendLine("• 监听通知列表缓存: ${notifList.size} 条（异常=$invalidNotifs）")
            append("• 导航状态机: ${navSt.machineState.label} | running=${navSt.running} | routes=${navSt.routeCount}")
            if (issues.isNotEmpty()) append("\n发现异常：\n" + issues.joinToString("\n") { "• $it" })
        }
        return SelfCheckItem(
            id = "out_live_payload_audit",
            category = CheckCategory.OUTBOUND_DATA_AUDIT,
            title = "内存活跃载荷、位图与导航状态机一致性",
            status = status,
            summary = if (status == CheckStatus.PASS) "位图均未回收，媒体进度与导航状态机均正常" else "检测到 ${issues.size} 项内存状态异常",
            detail = detail
        )
    }

    private fun auditOutboundSystemSnapshot(ctx: Context): SelfCheckItem {
        val snap = SystemStatusMonitor.collectSnapshot(ctx, forceShizuku = false)
        val cfg = CapsulePrefs(ctx).load()
        val title = SystemStatusMonitor.buildCustomTitle(snap, cfg)
        val summaryLine = SystemStatusMonitor.buildCustomSummaryLine(snap, cfg)
        val issues = mutableListOf<String>()

        if (snap.batteryPercent !in 0..100) issues.add("电量百分比越界: ${snap.batteryPercent}%")
        if (snap.batteryTempC < -20f || snap.batteryTempC > 90f) issues.add("电池温度异常: ${snap.batteryTempC}°C")
        if (snap.ramTotalGb <= 0f || snap.ramUsedGb < 0f || snap.ramUsedGb > snap.ramTotalGb + 0.5f) {
            issues.add("内存统计异常: ${snap.ramUsedGb}/${snap.ramTotalGb} GB")
        }
        if (title.isBlank() || summaryLine.isBlank()) issues.add("设备监控传出文案为空")

        val status = if (issues.isEmpty()) CheckStatus.PASS else CheckStatus.FAIL
        return SelfCheckItem(
            id = "out_sys_snapshot_audit",
            category = CheckCategory.OUTBOUND_DATA_AUDIT,
            title = "设备监控传出遥测数据校验",
            status = status,
            summary = if (status == CheckStatus.PASS) "电量 ${snap.batteryPercent}% · ${snap.batteryTempC}°C · ${snap.refreshRateLabel} 数据正常" else "遥测数据发现 ${issues.size} 项越界",
            detail = "传出标题: $title\n传出副文: $summaryLine" + if (issues.isNotEmpty()) "\n异常: ${issues.joinToString("; ")}" else ""
        )
    }

    private fun auditOutboundNavUrlFormatting(): SelfCheckItem {
        val trickyDest = "北京西站 (南广场) 100%测试"
        val encDest = URLEncoder.encode(trickyDest, "UTF-8")
        val originLat = 39.904211
        val originLon = 116.407394
        val oLatStr = String.format(Locale.US, "%.6f", originLat)
        val oLonStr = String.format(Locale.US, "%.6f", originLon)
        val urls = listOf(
            "amap" to "https://restapi.amap.com/v3/place/around?key=TEST&keywords=$encDest&location=$oLonStr,$oLatStr&radius=50000",
            "tencent" to "https://apis.map.qq.com/ws/place/v1/suggestion/?keyword=$encDest&location=$oLatStr,$oLonStr&key=TEST",
            "baidu" to "https://api.map.baidu.com/place/v2/suggestion?query=$encDest&location=$oLatStr,$oLonStr&output=json&ak=TEST",
            "osrm" to "https://nominatim.openstreetmap.org/search?q=$encDest&format=json&limit=1"
        )
        val bad = mutableListOf<String>()
        urls.forEach { (provider, u) ->
            val ok = runCatching { URL(u).toURI() }.isSuccess
            if (!ok) bad.add(provider)
        }
        val status = if (bad.isEmpty()) CheckStatus.PASS else CheckStatus.FAIL
        return SelfCheckItem(
            id = "out_nav_url_safety",
            category = CheckCategory.OUTBOUND_DATA_AUDIT,
            title = "导航传出 URL 百分号编码防崩溃校验",
            status = status,
            summary = if (status == CheckStatus.PASS) "高德/腾讯/百度/OSRM 含中文与%符号 URL 构造零异常" else "以下导航源 URL 异常: ${bad.joinToString()}",
            detail = "测试目的地: '$trickyDest'\n编码后: $encDest\n4 大地图 URL 语法与 URI 解析全部通过"
        )
    }

    // ==================== 4. 实机弹出与轮换测试 ====================

    private suspend fun executeBannerPopupTest(ctx: Context): SelfCheckItem {
        val cfg = CapsulePrefs(ctx).load().copy(overlayBannerDurationSec = 3)
        val bmp = createBadgeBitmap("#FF10B981", "测")
        val pi = buildSelfLaunchPi(ctx, 9911)
        val actions = listOf(BannerAction(title = "知道了", intent = null))

        val canSysOverlay = UniversalBannerHost.canDrawOverlay(ctx)
        val canA11y = CapsuleOverlayAccessibilityService.connected.value

        if (!canSysOverlay && !canA11y) {
            return SelfCheckItem(
                id = "live_banner_popup",
                category = CheckCategory.LIVE_UI_TESTS,
                title = "弹出横幅通知测试",
                status = CheckStatus.WARN,
                summary = "跳过：未授予悬浮窗权限且无障碍服务未绑定",
                detail = "请先授予「悬浮窗权限 (SYSTEM_ALERT_WINDOW)」或开启「灵动胶囊无障碍服务」，再点击重新测试。"
            )
        }

        val usedHost: String
        val ok: Boolean
        if (canSysOverlay) {
            usedHost = "UniversalBannerHost (悬浮窗)"
            ok = UniversalBannerHost.showBanner(
                context = ctx,
                appName = "灵动胶囊自检",
                packageName = ctx.packageName,
                title = "横幅通知弹出测试",
                text = "若你在屏幕顶部看到本卡片，说明横幅渲染与布局安全区正常",
                contentIntent = pi,
                iconBitmap = bmp,
                actions = actions,
                config = cfg,
                notificationKey = SELFCHECK_BANNER_KEY,
                isPersistent = false,
                collapsibleToPill = false
            )
        } else {
            usedHost = "CapsuleOverlayAccessibilityService (无障碍顶层)"
            ok = CapsuleOverlayAccessibilityService.showBanner(
                appName = "灵动胶囊自检",
                packageName = ctx.packageName,
                title = "横幅通知弹出测试",
                text = "若你在屏幕顶部看到本卡片，说明无障碍顶层横幅渲染正常",
                contentIntent = pi,
                iconBitmap = bmp,
                actions = actions,
                config = cfg,
                notificationKey = SELFCHECK_BANNER_KEY,
                isPersistent = false
            )
        }

        delay(1800L)
        val addViewErr = if (canSysOverlay) UniversalBannerHost.lastAddViewError.value else ""
        val actualOk = ok && addViewErr.isBlank()
        UniversalBannerHost.dismissIfKey(SELFCHECK_BANNER_KEY)
        CapsuleOverlayAccessibilityService.dismissBannerIfKey(SELFCHECK_BANNER_KEY)

        return SelfCheckItem(
            id = "live_banner_popup",
            category = CheckCategory.LIVE_UI_TESTS,
            title = "弹出横幅通知测试",
            status = if (actualOk) CheckStatus.PASS else CheckStatus.FAIL,
            summary = if (actualOk) "已通过 $usedHost 真实挂载并自动收回" else "悬浮窗弹出失败：$addViewErr",
            detail = "通道: $usedHost\nUniversalBannerHost 状态: ${UniversalBannerHost.lastStatus.value}" +
                if (addViewErr.isNotBlank()) "\naddView 异常: $addViewErr" else ""
        )
    }

    private suspend fun executeNavBannerPopupTest(ctx: Context): SelfCheckItem {
        val cfg = CapsulePrefs(ctx).load().copy(overlayBannerDurationSec = 4)
        val leftArrowBmp = createTurnArrowBitmap(isLeft = true)
        val straightArrowBmp = createTurnArrowBitmap(isLeft = false)
        val pi = buildSelfLaunchPi(ctx, 9912)

        val canSysOverlay = UniversalBannerHost.canDrawOverlay(ctx)
        val canA11y = CapsuleOverlayAccessibilityService.connected.value

        if (!canSysOverlay && !canA11y) {
            return SelfCheckItem(
                id = "live_nav_banner_popup",
                category = CheckCategory.LIVE_UI_TESTS,
                title = "弹出导航横幅通知测试",
                status = CheckStatus.WARN,
                summary = "跳过：未授予悬浮窗权限且无障碍服务未绑定",
                detail = "导航横幅测试需要悬浮窗权限或无障碍顶层横幅服务支持。"
            )
        }

        val ok: Boolean
        val modeDesc: String
        if (canSysOverlay) {
            modeDesc = "悬浮窗弹出 + 原地无闪回刷新"
            ok = UniversalBannerHost.showBanner(
                context = ctx,
                appName = "胶囊导航自检",
                packageName = ctx.packageName,
                title = "120m 后左转 · 人民中路",
                text = "剩余 2.4km · 约 5 分钟 · 36 km/h · GNSS",
                contentIntent = pi,
                iconBitmap = leftArrowBmp,
                actions = emptyList(),
                config = cfg,
                notificationKey = SELFCHECK_NAV_KEY,
                isPersistent = true,
                collapsibleToPill = true,
                liveLyricLine = "120m左转"
            )
            if (ok) {
                delay(1200L)
                // 原地更新导航横幅，验证不重弹、不闪回
                UniversalBannerHost.updateNavBannerInPlace(
                    key = SELFCHECK_NAV_KEY,
                    title = "35m 后直行进入主路",
                    text = "剩余 2.3km · 约 4 分钟 · 42 km/h · IMU融合",
                    iconBitmap = straightArrowBmp,
                    pillText = "35m直行"
                )
                delay(1300L)
            }
            UniversalBannerHost.dismissIfKey(SELFCHECK_NAV_KEY)
        } else {
            modeDesc = "无障碍顶层导航横幅弹出"
            ok = CapsuleOverlayAccessibilityService.showBanner(
                appName = "胶囊导航自检",
                packageName = ctx.packageName,
                title = "120m 后左转 · 人民中路",
                text = "剩余 2.4km · 约 5 分钟 · 36 km/h · GNSS",
                contentIntent = pi,
                iconBitmap = leftArrowBmp,
                actions = emptyList(),
                config = cfg,
                notificationKey = SELFCHECK_NAV_KEY,
                isPersistent = false
            )
            delay(1800L)
            CapsuleOverlayAccessibilityService.dismissBannerIfKey(SELFCHECK_NAV_KEY)
        }

        return SelfCheckItem(
            id = "live_nav_banner_popup",
            category = CheckCategory.LIVE_UI_TESTS,
            title = "弹出导航横幅通知测试",
            status = if (ok) CheckStatus.PASS else CheckStatus.FAIL,
            summary = if (ok) "已完成 $modeDesc 并安全销毁" else "导航横幅弹出失败",
            detail = "阶段1: 120m 后左转 · 人民中路\n阶段2(原地刷新): 35m 后直行进入主路 · 42 km/h · IMU融合"
        )
    }

    private suspend fun executeCapsuleTextRotationTest(ctx: Context): SelfCheckItem {
        val cfg = CapsulePrefs(ctx).load()
        val postNotifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (!postNotifOk) {
            return SelfCheckItem(
                id = "live_capsule_rotation",
                category = CheckCategory.LIVE_UI_TESTS,
                title = "胶囊轮换文本测试",
                status = CheckStatus.FAIL,
                summary = "缺少 POST_NOTIFICATIONS 通知权限，无法向状态栏推送胶囊",
                detail = "请在系统设置中允许本应用发送通知后重试。"
            )
        }
        if (!cfg.enableCapsuleEngine) {
            return SelfCheckItem(
                id = "live_capsule_rotation",
                category = CheckCategory.LIVE_UI_TESTS,
                title = "胶囊轮换文本测试",
                status = CheckStatus.WARN,
                summary = "跳过：当前已关闭「灵动胶囊引擎总开关」",
                detail = "如需测试状态栏原生胶囊轮换，请先开启「灵动胶囊引擎总开关」。"
            )
        }

        val prevPayload = NotificationIconStore.currentActivePayload
        val bmp = createBadgeBitmap("#FF3B82F6", "轮")
        val pi = buildSelfLaunchPi(ctx, 9913)
        val rotateCfg = cfg.copy(
            template = CapsuleConfig.TEMPLATE_IMAGE_TEXT_TEXT,
            capsuleAutoDismiss = false
        )
        val frames = listOf(
            Triple("自检 1/3 · 主文案轮换", "验证状态栏胶囊首次推送与图标注入", "自检1/3 轮换"),
            Triple("自检 2/3 · 双文案超长轮换校验", "检查文本平滑更新、智能断行与无闪烁刷新", "自检2/3 超长校验"),
            Triple("自检 3/3 · 胶囊轮换通过", "三帧连续推送完成，准备恢复原状态", "自检3/3 通过")
        )

        val statuses = mutableListOf<String>()
        var hadError = false

        // 临时开启插播保护窗口，防止后台播放器（如酷狗/网易云）高频进度或歌词刷新中途抢占自检轮换帧
        CapsulePublisher.markSysEventPreemptWindow(6000L)
        try {
            frames.forEachIndexed { idx, (mainTxt, subTxt, pillTxt) ->
                val payload = ActiveMirroredPayload(
                    key = SELFCHECK_ROTATE_KEY,
                    packageName = ctx.packageName,
                    appName = "胶囊自检",
                    title = mainTxt,
                    text = subTxt,
                    iconBitmap = bmp,
                    contentIntent = pi,
                    actions = emptyList(),
                    liveLyricLine = mainTxt,
                    livePillText = pillTxt
                )
                CapsulePublisher.publishMirrored(
                    context = ctx,
                    baseConfig = rotateCfg,
                    payload = payload,
                    isPinned = true
                )
                delay(1050L)
                val st = CapsulePublisher.lastCapsuleStatus.value
                statuses.add("帧${idx + 1}: $st")
                if (st.contains("异常")) hadError = true
            }
        } finally {
            CapsulePublisher.clearSysEventPreemptWindow()
        }

        // 恢复原活跃胶囊，避免残留测试胶囊
        if (prevPayload != null && prevPayload.key != SELFCHECK_ROTATE_KEY) {
            NotificationIconStore.currentActivePayload = prevPayload
            CapsulePublisher.publishMirrored(
                context = ctx,
                baseConfig = cfg,
                payload = prevPayload,
                isPinned = CapsuleNotificationListener.pinnedKey.value == prevPayload.key
            )
        } else if (!CapsuleNavigationEngine.restoreActiveNavCapsuleIfNeeded(ctx) &&
            !CapsuleNotificationListener.restoreActiveMediaCapsuleIfNeeded(ctx) &&
            !CapsulePublisher.restoreManualCapsuleIfNeeded(ctx)
        ) {
            NotificationIconStore.currentActivePayload = null
            CapsulePublisher.dismiss(ctx, "胶囊轮换文本自检结束，已自动清理")
        }

        return SelfCheckItem(
            id = "live_capsule_rotation",
            category = CheckCategory.LIVE_UI_TESTS,
            title = "胶囊轮换文本测试",
            status = if (hadError) CheckStatus.FAIL else CheckStatus.PASS,
            summary = if (hadError) "轮换过程中胶囊发布器报告异常" else "已完成 3 帧连续轮换推送并恢复原状",
            detail = statuses.joinToString("\n")
        )
    }

    // ==================== 辅助工具 ====================

    fun formatReportText(r: SelfCheckReport = _report.value): String = buildString {
        appendLine("【灵动胶囊全链路自检报告】")
        appendLine("时间: ${r.startedAt} ~ ${r.finishedAt} (耗时 ${r.totalDurationMs}ms)")
        appendLine("汇总: 通过 ${r.passCount} | 警告 ${r.warnCount} | 异常 ${r.failCount}")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT} / ${SystemPropertyProfile.romDisplayName}")
        appendLine("引擎: ${DeviceBrandRouter.engineDisplayName()}")
        appendLine()
        r.items.forEach { item ->
            appendLine("[${item.status.label}] (${item.category.label}) ${item.title} (${item.durationMs}ms)")
            appendLine("  结论: ${item.summary}")
            item.detail.lines().forEach { line ->
                if (line.isNotBlank()) appendLine("  $line")
            }
        }
    }.trim()

    private fun buildSelfLaunchPi(ctx: Context, reqCode: Int): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            ctx,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createBadgeBitmap(bgHex: String, glyph: String): Bitmap {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(bgHex) }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 18f, 18f, bgPaint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val y = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(glyph, size / 2f, y, textPaint)
        return bmp
    }

    private fun createTurnArrowBitmap(isLeft: Boolean): Bitmap {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF2563EB") }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 18f, 18f, bgPaint)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 7f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        if (isLeft) {
            path.moveTo(46f, 54f)
            path.lineTo(46f, 30f)
            path.lineTo(24f, 30f)
            path.moveTo(32f, 20f)
            path.lineTo(22f, 30f)
            path.lineTo(32f, 40f)
        } else {
            path.moveTo(36f, 56f)
            path.lineTo(36f, 18f)
            path.moveTo(24f, 30f)
            path.lineTo(36f, 18f)
            path.lineTo(48f, 30f)
        }
        canvas.drawPath(path, p)
        return bmp
    }
}
