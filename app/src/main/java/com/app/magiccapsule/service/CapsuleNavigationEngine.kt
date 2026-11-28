package com.app.magiccapsule.service

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.content.res.Configuration
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import com.app.magiccapsule.capsule.CapsulePublisher
import com.app.magiccapsule.capsule.DeviceBrandRouter
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.data.CapsulePrefs
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 纯事件驱动 + Polyline 窗口高效匹配 + 速度动态预警 + 严格线程隔离 灵动胶囊导航引擎（v131）：
 * 1. 零定时器轮询：彻底移除 Handler.postDelayed 轮询循环，完全由系统底层 LocationManager 事件回调、
 *    硬件传感器事件（9轴旋转矢量/计步PDR）及亮屏事件按需驱动
 * 2. Polyline 高效滑动窗口匹配：解码四大地图 Polyline 轨迹点为紧凑 DoubleArray，仅在当前路段索引
 *    [idx-4 .. idx+25] 局部窗口内做平面点到线段投影，O(1) 定位当前路段并只读取后续 3 个转向点
 * 3. 基于 Location.getSpeed() 的速度动态预警：高速(>=80km/h)自动提前 500m 预警、快速路 300m、
 *    常规道路 150m、步行 50m，并结合实时车速精确计算到达下一转向点秒数与剩余 ETA
 * 4. 严格线程隔离：全部路径解析、Polyline 解码、距离投影、磁偏角与缓存读写均在 Dispatchers.IO 协程中
 *    原子合并（latest-wins）执行，主线程/UI 仅接收不可变 NavUiState（String/Int）最终结果，零卡顿
 */
object CapsuleNavigationEngine {

    const val NAV_KEY = "magic_capsule_web_nav"
    private const val ROUTE_CACHE_FILENAME = "nav_active_route.json"
    private const val MAX_CACHED_ROUTES = 3
    private const val COMPASS_PUSH_FAST_INTERVAL_MS = 45L
    private const val COMPASS_PUSH_FINE_INTERVAL_MS = 75L
    private const val ONLINE_REPLAN_COOLDOWN_MS = 20_000L

    enum class NavMachineState(val label: String) {
        IDLE("未导航"),
        PLANNING("路线规划中"),
        CRUISING("正常巡航"),
        APPROACHING_TURN("即将转向"),
        WAITING_TRAFFIC_LIGHT("等待信号灯"),
        OFF_ROUTE_REPLANNING("偏航重算中"),
        OFFLINE_RECOVERY("离线回补中"),
        ARRIVED("已到达终点")
    }

    enum class TurnAction(val label: String) {
        STRAIGHT("直行"),
        LEFT("左转"),
        RIGHT("右转"),
        SLIGHT_LEFT("左前方"),
        SLIGHT_RIGHT("右前方"),
        UTURN("调头"),
        ARRIVE("到达")
    }

    data class NavStep(
        val instruction: String,
        val action: TurnAction,
        val roadName: String,
        val distanceMeters: Int,
        val endLat: Double,
        val endLon: Double,
        val polyStartIdx: Int = 0,
        val polyEndIdx: Int = 0
    )

    data class NavRoute(
        val provider: String,
        val destinationName: String,
        val destLat: Double,
        val destLon: Double,
        val totalDistanceMeters: Int,
        val totalDurationSec: Int,
        val steps: List<NavStep>,
        val usesGcj02: Boolean,
        val routeLabel: String = "推荐路线",
        val polyLats: DoubleArray = DoubleArray(0),
        val polyLons: DoubleArray = DoubleArray(0)
    )

    @Immutable
    data class NavUiState(
        val running: Boolean = false,
        val loading: Boolean = false,
        val statusText: String = "未开始导航",
        val currentPillText: String = "",
        val currentDetailText: String = "",
        val routeCount: Int = 0,
        val activeRouteIndex: Int = 0,
        val sensorSummary: String = "",
        val machineState: NavMachineState = NavMachineState.IDLE,
        val originLabel: String = "",
        val destinationLabel: String = "",
        val destLat: Double = 0.0,
        val destLon: Double = 0.0,
        val routeStepsPreview: List<String> = emptyList()
    )

    // 严格线程隔离：所有轨迹匹配、距离投影、网络规划与文件缓存全部在 Dispatchers.IO 后台执行
    private val navIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + AppLogger.coroutineExceptionHandler("Nav"))
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(NavUiState())
    val state: StateFlow<NavUiState> = _state.asStateFlow()

    // latest-wins 后台位置计算合并队列，防止高频定位/步进堆积
    private val pendingEvalLocation = AtomicReference<Location?>(null)
    private val evalDrainRunning = AtomicBoolean(false)
    // 会话单调递增代数：销毁/停止导航时递增，彻底隔离在途协程与旧传感器残余回调，杜绝错误状态机切变
    private val navSessionGen = AtomicLong(0L)
    @Volatile private var navBannerInitialPopupDone: Boolean = false

    @Volatile private var appContext: Context? = null
    @Volatile private var cachedRoutes: List<NavRoute> = emptyList()
    @Volatile private var activeRouteIndex: Int = 0
    @Volatile private var currentStepIndex: Int = 0
    @Volatile private var currentPolyIndex: Int = 0
    @Volatile private var activeNavMode: String = "driving"
    @Volatile private var activeProvider: String = "amap"
    @Volatile private var activeApiKey: String = ""
    @Volatile private var activeDestinationQuery: String = ""

    @Volatile private var locationListener: LocationListener? = null
    @Volatile private var latestLocation: Location? = null
    @Volatile private var smoothedSpeedMps: Float = 0f
    @Volatile private var hasValidSpeed: Boolean = false
    @Volatile private var lastAcceptedLocation: Location? = null
    @Volatile private var lastAcceptedLocTimeMs: Long = 0L
    @Volatile private var sensorStationaryFlag: Boolean = false
    @Volatile private var lastSensorMotionElapsedMs: Long = 0L
    private const val MAX_VALID_ACCURACY_METERS = 50f
    private const val MAX_VALID_SPEED_ACCURACY_MPS = 5.0f
    private const val GNSS_LOW_AVG_SNR_DBHZ = 22.0f
    private const val IMU_XY_DISPARITY_REJECT_MPS2 = 6.0f
    private const val IMU_MAX_AXIS_SPIKE_MPS2 = 12.0f

    // GNSS 平均信噪比（C/N0 dB-Hz）监测 + 低信噪比自动切换 3 轴加速度融合测速状态
    @Volatile private var gnssStatusCallback: GnssStatus.Callback? = null
    @Volatile private var gnssAvgCn0DbHz: Float = -1f
    @Volatile private var gnssUsedSatCount: Int = 0
    @Volatile private var gnssStatusReceived: Boolean = false
    @Volatile private var usingImuSpeedFallback: Boolean = false

    // 3 轴线性加速度 -> 世界坐标 ENU 投影 + 运动方向确认 + 速度积分器
    private val latestRotMatrix = FloatArray(9)
    @Volatile private var hasRotMatrix: Boolean = false
    @Volatile private var imuFusedSpeedMps: Float = 0f
    @Volatile private var imuMotionHeadingDeg: Float = -1f
    @Volatile private var lastLinAccTimestampNs: Long = 0L
    @Volatile private var imuStillSinceElapsedMs: Long = 0L
    @Volatile private var pdrOffsetLatDeg: Double = 0.0
    @Volatile private var pdrOffsetLonDeg: Double = 0.0

    // 真北硬件指南针 + 磁偏角 + 残余硬铁偏置 + 全传感器融合状态
    @Volatile private var compassTrueAzimuthDeg: Float = -1f
    @Volatile private var magneticDeclinationDeg: Float = 0f
    @Volatile private var residualCompassBiasDeg: Float = 0f
    @Volatile private var magneticInterference: Boolean = false
    @Volatile private var sensorListener: SensorEventListener? = null
    @Volatile private var sigMotionTriggerListener: TriggerEventListener? = null
    @Volatile private var baselineAltitudeM: Float = Float.NaN
    @Volatile private var currentDeltaAltitudeM: Float = 0f
    @Volatile private var activeSensorSummary: String = ""

    private val lastAccel = FloatArray(3)
    private val lastMag = FloatArray(3)
    @Volatile private var hasAccel = false
    @Volatile private var hasMag = false

    @Volatile private var screenReceiver: BroadcastReceiver? = null
    @Volatile private var lastNavPushSignature: String = ""
    @Volatile private var lastTargetBearingDeg: Float = -1f
    @Volatile private var lastRenderedRelDeg: Int = Int.MIN_VALUE
    @Volatile private var lastCompassPushElapsedMs: Long = 0L
    @Volatile private var pendingFlushHeadingDeg: Float = -1f
    @Volatile private var cachedAction: TurnAction = TurnAction.STRAIGHT
    @Volatile private var cachedPillText: String = ""
    @Volatile private var cachedTitle: String = ""
    @Volatile private var cachedRemainEtaSuffix: String = ""

    // 状态机 + 连续 3 点 >45m 实时偏航检测 + 信号灯停车检测 + TTS 语音播报
    @Volatile private var currentMachineState: NavMachineState = NavMachineState.IDLE
    @Volatile private var consecutiveOffRouteCount: Int = 0
    @Volatile private var lowSpeedSinceElapsedMs: Long = 0L
    @Volatile private var lastOnlineReplanElapsedMs: Long = 0L
    private val onlineReplanInFlight = AtomicBoolean(false)
    @Volatile private var navTts: TextToSpeech? = null
    @Volatile private var navTtsReady: Boolean = false
    @Volatile private var lastTtsSpeakElapsedMs: Long = 0L
    private const val OFF_ROUTE_THRESHOLD_METERS = 45
    private const val OFF_ROUTE_CONSECUTIVE_FIXES = 3
    private const val TTS_OFF_ROUTE_COOLDOWN_MS = 15_000L

    // 仅用于转向停止时的单次尾沿补发（绝非循环定时器）
    private val pendingCompassFlushTask = Runnable {
        val ctx = appContext ?: return@Runnable
        val h = pendingFlushHeadingDeg
        if (h >= 0f && isRunning() && isScreenInteractive(ctx)) {
            pendingFlushHeadingDeg = -1f
            navIoScope.launch { pushCompassDotNow(ctx, h) }
        }
    }

    private fun currentActiveRoute(): NavRoute? = cachedRoutes.getOrNull(activeRouteIndex) ?: cachedRoutes.firstOrNull()

    private fun isScreenInteractive(ctx: Context): Boolean {
        return runCatching {
            ctx.getSystemService(PowerManager::class.java)?.isInteractive != false
        }.getOrDefault(true)
    }

    private fun isNetworkAvailable(ctx: Context): Boolean {
        return runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
    }

    private fun speakNavPrompt(ctx: Context, message: String, cooldownMs: Long = TTS_OFF_ROUTE_COOLDOWN_MS) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTtsSpeakElapsedMs < cooldownMs) return
        lastTtsSpeakElapsedMs = now
        mainHandler.post {
            runCatching {
                val existing = navTts
                if (existing != null && navTtsReady) {
                    existing.speak(message, TextToSpeech.QUEUE_FLUSH, null, "nav_prompt_$now")
                    return@runCatching
                }
                if (existing == null) {
                    var created: TextToSpeech? = null
                    created = TextToSpeech(ctx.applicationContext) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            navTtsReady = true
                            runCatching {
                                created?.language = Locale.SIMPLIFIED_CHINESE
                                created?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "nav_prompt_init")
                            }
                        }
                    }
                    navTts = created
                }
            }
        }
    }

    private fun shutdownNavTts() {
        val tts = navTts ?: return
        navTts = null
        navTtsReady = false
        mainHandler.post {
            runCatching {
                tts.stop()
                tts.shutdown()
            }
        }
    }

    /**
     * 后台协程 latest-wins 轨迹匹配派发器：所有位置变化均在此异步隔离计算，绝不阻塞主线程。
     */
    private fun enqueueBackgroundLocationEvaluation(ctx: Context, loc: Location) {
        pendingEvalLocation.set(loc)
        if (!evalDrainRunning.compareAndSet(false, true)) return
        val sessionGen = navSessionGen.get()
        navIoScope.launch {
            try {
                while (true) {
                    val nextLoc = pendingEvalLocation.getAndSet(null) ?: break
                    if (!isRunning() || sessionGen != navSessionGen.get()) break
                    if (!isScreenInteractive(ctx)) {
                        pauseHardwareForScreenOff(ctx)
                        break
                    }
                    if (cachedRoutes.isEmpty()) {
                        val restored = readMultiRouteCacheFile(ctx)
                        if (restored != null && restored.first.isNotEmpty()) {
                            cachedRoutes = restored.first
                            activeRouteIndex = restored.second.coerceIn(0, restored.first.lastIndex)
                        }
                    }
                    val route = currentActiveRoute() ?: break
                    evaluateAndPushFromCache(ctx, route, nextLoc)
                }
            } finally {
                evalDrainRunning.set(false)
                if (pendingEvalLocation.get() != null && isRunning()) {
                    pendingEvalLocation.getAndSet(null)?.let { enqueueBackgroundLocationEvaluation(ctx, it) }
                }
            }
        }
    }

    private fun pauseHardwareForScreenOff(ctx: Context) {
        mainHandler.removeCallbacks(pendingCompassFlushTask)
        pendingFlushHeadingDeg = -1f
        unregisterAllNavSensors(ctx)
        unregisterLocationUpdates(ctx)
        AppLogger.d("Capsule", "息屏按需释放全部导航传感器与 GPS 监听（零硬件中断、零定时器）")
    }

    private fun resumeHardwareForScreenOn(ctx: Context) {
        if (!isRunning()) return
        registerLocationUpdates(ctx)
        registerAllNavSensors(ctx)
        val loc = latestLocation ?: resolveQuickLastLocation(ctx)
        if (loc != null) {
            enqueueBackgroundLocationEvaluation(ctx, loc)
        }
        AppLogger.d("Capsule", "亮屏恢复事件驱动 GPS 与全传感器监听")
    }

    private fun registerScreenPowerReceiver(ctx: Context) {
        unregisterScreenPowerReceiver(ctx)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (!isRunning()) return
                when (action) {
                    Intent.ACTION_SCREEN_OFF -> pauseHardwareForScreenOff(ctx)
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> resumeHardwareForScreenOn(ctx)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        screenReceiver = receiver
        runCatching {
            ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { e ->
            AppLogger.w("Capsule", "注册导航息屏监听失败：${e.message}")
        }
    }

    private fun unregisterScreenPowerReceiver(ctx: Context) {
        val receiver = screenReceiver ?: return
        screenReceiver = null
        runCatching { ctx.unregisterReceiver(receiver) }
    }

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isRunning(): Boolean = _state.value.running

    fun restoreActiveNavCapsuleIfNeeded(context: Context): Boolean {
        if (!isRunning()) return false
        val payload = NotificationIconStore.getPayload(NAV_KEY) ?: return false
        val ctx = context.applicationContext
        val cfg = runCatching { CapsulePrefs(ctx).load() }.getOrElse { CapsuleConfig() }
        CapsulePublisher.publishMirrored(ctx, cfg, payload, isPinned = true)
        return true
    }

    /**
     * 一键切换至下一条已缓存备选路线（在 Dispatchers.IO 协程中完成索引重定位与推送，零主线程卡顿）
     */
    fun switchToNextAlternativeRoute(context: Context): Boolean {
        val ctx = context.applicationContext
        val routes = cachedRoutes
        if (!isRunning() || routes.size <= 1) return false
        val nextIdx = (activeRouteIndex + 1) % routes.size
        activeRouteIndex = nextIdx
        currentPolyIndex = 0
        currentStepIndex = 0
        lastNavPushSignature = ""
        navBannerInitialPopupDone = false
        navIoScope.launch {
            val targetRoute = routes[nextIdx]
            writeMultiRouteCacheFile(ctx, routes, nextIdx)
            val loc = latestLocation ?: resolveQuickLastLocation(ctx)
            AppLogger.i("Capsule", "用户一键绕行切换至备选路线 ${nextIdx + 1}/${routes.size} (${targetRoute.routeLabel})")
            if (loc != null) {
                evaluateAndPushFromCache(ctx, targetRoute, loc)
            }
        }
        return true
    }

    /**
     * 启动胶囊导航：全流程运行于 Dispatchers.IO，零定时器轮询，纯事件驱动。
     */
    fun startNavigation(
        context: Context,
        destination: String,
        config: CapsuleConfig
    ) {
        val ctx = context.applicationContext
        appContext = ctx
        val cleanDest = destination.trim()
        if (cleanDest.isBlank()) {
            _state.value = NavUiState(statusText = "请输入目的地名称或地址")
            return
        }
        if (!hasLocationPermission(ctx)) {
            _state.value = NavUiState(statusText = "请先授予位置权限（精确定位）")
            return
        }
        val provider = config.navProvider.lowercase()
        val apiKey = when (provider) {
            "amap" -> config.navAmapKey.trim()
            "tencent" -> config.navTencentKey.trim()
            "baidu" -> config.navBaiduKey.trim()
            else -> ""
        }
        if (provider != "osrm" && apiKey.isBlank()) {
            val name = providerLabel(provider)
            _state.value = NavUiState(statusText = "请先填写 $name Web 服务 API Key，或切换为免 Key(OSRM)")
            return
        }

        stopNavigation(ctx, silent = true)
        activeProvider = provider
        activeApiKey = apiKey
        activeNavMode = config.navMode.lowercase().ifBlank { "driving" }
        activeDestinationQuery = cleanDest
        currentMachineState = NavMachineState.PLANNING
        _state.value = NavUiState(loading = true, statusText = "正在定位并解码多条 Polyline 路线（${providerLabel(provider)}）...", machineState = NavMachineState.PLANNING)

        navIoScope.launch {
            runCatching {
                val loc = resolveBestLastLocation(ctx)
                if (loc == null) {
                    _state.value = NavUiState(statusText = "暂未获取到 GPS 定位，请打开系统定位开关后重试")
                    return@launch
                }
                latestLocation = loc
                lastAcceptedLocation = loc
                lastAcceptedLocTimeMs = SystemClock.elapsedRealtime()
                val initAccOk = !loc.hasAccuracy() || loc.accuracy <= MAX_VALID_ACCURACY_METERS
                hasValidSpeed = initAccOk && loc.hasSpeed() && loc.speed >= 0f
                smoothedSpeedMps = if (hasValidSpeed) loc.speed else 0f
                sensorStationaryFlag = false
                lastSensorMotionElapsedMs = SystemClock.elapsedRealtime()
                updateMagneticDeclination(loc)
                val routes = planMultiRoutes(
                    provider = provider,
                    apiKey = apiKey,
                    mode = activeNavMode,
                    originWgsLat = loc.latitude,
                    originWgsLon = loc.longitude,
                    destination = cleanDest
                )
                if (routes.isEmpty()) return@launch

                writeMultiRouteCacheFile(ctx, routes, 0)
                cachedRoutes = routes
                activeRouteIndex = 0
                currentStepIndex = 0
                currentPolyIndex = 0
                lastNavPushSignature = ""
                lastTargetBearingDeg = -1f
                lastRenderedRelDeg = Int.MIN_VALUE
                lastCompassPushElapsedMs = 0L
                consecutiveOffRouteCount = 0
                lowSpeedSinceElapsedMs = 0L
                currentMachineState = NavMachineState.CRUISING

                _state.value = NavUiState(
                    running = true,
                    loading = false,
                    statusText = "导航中 · 已缓存 ${routes.size} 条路线 · 前往 ${routes[0].destinationName}",
                    routeCount = routes.size,
                    activeRouteIndex = 0
                )
                registerScreenPowerReceiver(ctx)
                if (isScreenInteractive(ctx)) {
                    registerLocationUpdates(ctx)
                    registerAllNavSensors(ctx)
                }
                evaluateAndPushFromCache(ctx, routes[0], loc)
            }.onFailure { e ->
                AppLogger.e("Capsule", "胶囊导航启动异常", e)
                _state.value = NavUiState(statusText = "规划失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 彻底清空所有传感器历史向量、磁偏角、气压基准、PDR 步进与静止状态缓存，
     * 防止旧导航会话或息屏前的历史传感器数据污染新状态机。
     */
    private fun clearSensorDataCache() {
        mainHandler.removeCallbacks(pendingCompassFlushTask)
        lastAccel.fill(0f)
        lastMag.fill(0f)
        hasAccel = false
        hasMag = false
        compassTrueAzimuthDeg = -1f
        magneticDeclinationDeg = 0f
        residualCompassBiasDeg = 0f
        magneticInterference = false
        sensorStationaryFlag = false
        lastSensorMotionElapsedMs = 0L
        baselineAltitudeM = Float.NaN
        currentDeltaAltitudeM = 0f
        pdrOffsetLatDeg = 0.0
        pdrOffsetLonDeg = 0.0
        activeSensorSummary = ""
        pendingFlushHeadingDeg = -1f
        lastCompassPushElapsedMs = 0L
        lastRenderedRelDeg = Int.MIN_VALUE
        latestRotMatrix.fill(0f)
        hasRotMatrix = false
        imuFusedSpeedMps = 0f
        imuMotionHeadingDeg = -1f
        lastLinAccTimestampNs = 0L
        imuStillSinceElapsedMs = 0L
        gnssAvgCn0DbHz = -1f
        gnssUsedSatCount = 0
        gnssStatusReceived = false
        usingImuSpeedFallback = false
    }

    fun stopNavigation(context: Context, silent: Boolean = false) {
        val ctx = context.applicationContext
        navSessionGen.incrementAndGet()
        navBannerInitialPopupDone = false
        pendingEvalLocation.set(null)
        unregisterScreenPowerReceiver(ctx)
        unregisterLocationUpdates(ctx)
        unregisterAllNavSensors(ctx)
        clearSensorDataCache()
        cachedRoutes = emptyList()
        activeRouteIndex = 0
        latestLocation = null
        lastAcceptedLocation = null
        lastAcceptedLocTimeMs = 0L
        smoothedSpeedMps = 0f
        hasValidSpeed = false
        currentStepIndex = 0
        currentPolyIndex = 0
        lastNavPushSignature = ""
        lastTargetBearingDeg = -1f
        cachedAction = TurnAction.STRAIGHT
        cachedPillText = ""
        cachedTitle = ""
        cachedRemainEtaSuffix = ""
        consecutiveOffRouteCount = 0
        lowSpeedSinceElapsedMs = 0L
        lastOnlineReplanElapsedMs = 0L
        onlineReplanInFlight.set(false)
        lastTtsSpeakElapsedMs = 0L
        currentMachineState = NavMachineState.IDLE
        shutdownNavTts()
        UniversalBannerHost.dismissIfKey(NAV_KEY)
        CapsuleOverlayAccessibilityService.dismissBannerIfKey(NAV_KEY)
        navIoScope.launch { deleteRouteCacheFile(ctx) }
        if (!silent) {
            _state.value = NavUiState(running = false, loading = false, statusText = "导航已结束（已释放传感器并清理多路线缓存）")
            if (!CapsuleNotificationListener.restoreActiveMediaCapsuleIfNeeded(ctx)) {
                CapsulePublisher.dismiss(ctx, "导航已结束")
            }
            AppLogger.i("Capsule", "胶囊导航已结束，全传感器/GPS已释放且多路线缓存已删除")
        }
    }

    // ==================== 全传感器与低功耗 SensorHub 融合 + 真北磁偏角校准 ====================

    private fun updateMagneticDeclination(loc: Location) {
        runCatching {
            val alt = if (loc.hasAltitude()) loc.altitude.toFloat() else 0f
            val field = GeomagneticField(
                loc.latitude.toFloat(),
                loc.longitude.toFloat(),
                alt,
                System.currentTimeMillis()
            )
            magneticDeclinationDeg = field.declination
        }
    }

    private fun registerAllNavSensors(ctx: Context) {
        unregisterAllNavSensors(ctx)
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return
        hasAccel = false
        hasMag = false
        val enabledNames = ArrayList<String>(6)

        val listener = object : SensorEventListener {
            private val rotMatrix = FloatArray(9)
            private val remappedMatrix = FloatArray(9)
            private val orient = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR,
                    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                        System.arraycopy(rotMatrix, 0, latestRotMatrix, 0, 9)
                        hasRotMatrix = true
                        val trueHeading = extractTrueHeadingFromRotationMatrix(ctx, rotMatrix, remappedMatrix, orient)
                        applySmoothedTrueHeading(ctx, trueHeading)
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, lastAccel, 0, 3)
                        hasAccel = true
                        if (hasMag && SensorManager.getRotationMatrix(rotMatrix, null, lastAccel, lastMag)) {
                            val trueHeading = extractTrueHeadingFromRotationMatrix(ctx, rotMatrix, remappedMatrix, orient)
                            applySmoothedTrueHeading(ctx, trueHeading)
                        }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, lastMag, 0, 3)
                        hasMag = true
                        val magStrengthUt = sqrt(
                            event.values[0] * event.values[0] +
                                event.values[1] * event.values[1] +
                                event.values[2] * event.values[2]
                        )
                        magneticInterference = magStrengthUt < 20f || magStrengthUt > 72f
                    }
                    Sensor.TYPE_STEP_DETECTOR -> {
                        // 步行模式事件驱动 PDR 航位推算：每走一步推进 ~0.72m 并投递后台协程刷新
                        if (activeNavMode == "walking" && compassTrueAzimuthDeg >= 0f) {
                            val rad = Math.toRadians(compassTrueAzimuthDeg.toDouble())
                            val strideM = 0.72
                            pdrOffsetLatDeg += (strideM * cos(rad)) / 111_320.0
                            val curLoc = latestLocation
                            val curLat = curLoc?.latitude ?: 30.0
                            val lonScale = (111_320.0 * cos(Math.toRadians(curLat))).coerceAtLeast(10_000.0)
                            pdrOffsetLonDeg += (strideM * sin(rad)) / lonScale
                            sensorStationaryFlag = false
                            lastSensorMotionElapsedMs = SystemClock.elapsedRealtime()
                            if (curLoc != null) {
                                enqueueBackgroundLocationEvaluation(ctx, curLoc)
                            }
                        }
                    }
                    Sensor.TYPE_PRESSURE -> {
                        val hPa = event.values.getOrNull(0) ?: return
                        if (hPa in 300f..1100f) {
                            val altM = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hPa)
                            if (baselineAltitudeM.isNaN()) {
                                baselineAltitudeM = altM
                            } else {
                                currentDeltaAltitudeM = altM - baselineAltitudeM
                            }
                        }
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        val ax = event.values[0]
                        val ay = event.values[1]
                        val az = event.values[2]
                        val tsNs = event.timestamp
                        val prevTsNs = lastLinAccTimestampNs
                        lastLinAccTimestampNs = tsNs

                        // 1. 竖屏/横屏下 X/Y 轴差值异常与剧烈抖动过滤：差值过大直接丢弃该次不可信采样
                        val isLandNow = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        val xyDiff = abs(abs(ax) - abs(ay))
                        val primaryAxis = if (isLandNow) abs(ax) else abs(ay)
                        val crossAxis = if (isLandNow) abs(ay) else abs(ax)
                        if (xyDiff > IMU_XY_DISPARITY_REJECT_MPS2 ||
                            abs(ax) > IMU_MAX_AXIS_SPIKE_MPS2 ||
                            abs(ay) > IMU_MAX_AXIS_SPIKE_MPS2 ||
                            abs(az) > 15.0f ||
                            (crossAxis > 4.5f && crossAxis > primaryAxis * 2.4f)
                        ) {
                            return
                        }

                        val totalAcc3D = sqrt(ax * ax + ay * ay + az * az)
                        val nowMs = SystemClock.elapsedRealtime()
                        if (totalAcc3D < 0.22f) {
                            // 零速校正（ZUPT）：持续静止超过 1.5s 归零积分速度，杜绝加速度积分漂移
                            if (imuStillSinceElapsedMs == 0L) {
                                imuStillSinceElapsedMs = nowMs
                            } else if (nowMs - imuStillSinceElapsedMs >= 1500L) {
                                imuFusedSpeedMps = 0f
                                sensorStationaryFlag = true
                                if (usingImuSpeedFallback) {
                                    smoothedSpeedMps = 0f
                                    hasValidSpeed = true
                                }
                            }
                            return
                        }

                        imuStillSinceElapsedMs = 0L
                        if (totalAcc3D >= 0.35f) {
                            sensorStationaryFlag = false
                            lastSensorMotionElapsedMs = nowMs
                        }

                        if (prevTsNs <= 0L) return
                        val dtSec = ((tsNs - prevTsNs) / 1_000_000_000.0f).coerceIn(0.005f, 0.25f)

                        // 2. 将设备坐标系 3 轴加速度 (ax, ay, az) 通过旋转矩阵 R 投影至世界 ENU 坐标系，确认设备移动方向
                        val aFwd: Float = if (hasRotMatrix) {
                            val aE = latestRotMatrix[0] * ax + latestRotMatrix[1] * ay + latestRotMatrix[2] * az
                            val aN = latestRotMatrix[3] * ax + latestRotMatrix[4] * ay + latestRotMatrix[5] * az
                            val aHoriz = sqrt(aE * aE + aN * aN)
                            if (aHoriz >= 0.35f) {
                                imuMotionHeadingDeg = ((Math.toDegrees(atan2(aE.toDouble(), aN.toDouble())).toFloat() + 360f) % 360f)
                            }
                            val travelDeg = when {
                                compassTrueAzimuthDeg >= 0f -> compassTrueAzimuthDeg
                                imuMotionHeadingDeg >= 0f -> imuMotionHeadingDeg
                                else -> 0f
                            }
                            val rad = Math.toRadians(travelDeg.toDouble())
                            (aE * sin(rad) + aN * cos(rad)).toFloat()
                        } else {
                            if (isLandNow) ax else ay
                        }

                        // 3. 代入运动学速度积分方程 v(t) = clamp((v(t-1) + a_fwd * dt) * decay, 0, v_max)
                        val damping = if (abs(aFwd) < 0.28f) 0.985f else 0.996f
                        val integrated = ((imuFusedSpeedMps + aFwd * dtSec) * damping).coerceIn(0f, 45.0f)
                        imuFusedSpeedMps = integrated

                        val snrLow = (gnssAvgCn0DbHz > 0f && gnssAvgCn0DbHz < GNSS_LOW_AVG_SNR_DBHZ) ||
                            (gnssStatusReceived && gnssUsedSatCount < 4)
                        if (snrLow || usingImuSpeedFallback) {
                            usingImuSpeedFallback = true
                            hasValidSpeed = true
                            smoothedSpeedMps = if (smoothedSpeedMps <= 0.1f) integrated else (smoothedSpeedMps * 0.6f + integrated * 0.4f)
                        }
                    }
                    Sensor.TYPE_STATIONARY_DETECT -> {
                        sensorStationaryFlag = true
                        smoothedSpeedMps = 0f
                        hasValidSpeed = false
                    }
                    Sensor.TYPE_MOTION_DETECT -> {
                        sensorStationaryFlag = false
                        lastSensorMotionElapsedMs = SystemClock.elapsedRealtime()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD || sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                        magneticInterference = true
                    }
                }
            }
        }
        sensorListener = listener

        mainHandler.post {
            runCatching {
                val lowPowerBatchUs = 500_000
                val rotVec = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                val geoRotVec = sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
                when {
                    rotVec != null -> {
                        sm.registerListener(listener, rotVec, SensorManager.SENSOR_DELAY_GAME, 0)
                        enabledNames.add("9轴旋转矢量")
                    }
                    geoRotVec != null -> {
                        sm.registerListener(listener, geoRotVec, SensorManager.SENSOR_DELAY_UI, 0)
                        enabledNames.add("低功耗地磁矢量")
                    }
                    else -> {
                        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, 0)
                        }
                        enabledNames.add("加速度+磁力计")
                    }
                }
                sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                    sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, lowPowerBatchUs)
                }
                sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
                    sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, lowPowerBatchUs)
                    enabledNames.add("计步PDR")
                }
                sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
                    sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, 1_000_000)
                    enabledNames.add("气压高度")
                }
                sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
                    sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, 100_000)
                    enabledNames.add("3轴惯导测速")
                }
                sm.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT)?.let {
                    sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
                sm.getDefaultSensor(Sensor.TYPE_MOTION_DETECT)?.let {
                    sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
                sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)?.let { sigSensor ->
                    val trig = object : TriggerEventListener() {
                        override fun onTrigger(event: TriggerEvent?) {
                            val loc = latestLocation
                            if (loc != null && isRunning() && isScreenInteractive(ctx)) {
                                enqueueBackgroundLocationEvaluation(ctx, loc)
                                runCatching { sm.requestTriggerSensor(this, sigSensor) }
                            }
                        }
                    }
                    sigMotionTriggerListener = trig
                    sm.requestTriggerSensor(trig, sigSensor)
                    enabledNames.add("低功耗动静唤醒")
                }

                val decText = "%.1f°".format(magneticDeclinationDeg)
                activeSensorSummary = "纯事件驱动 · ${enabledNames.joinToString("+")} · 磁偏角$decText"
                _state.value = _state.value.copy(sensorSummary = activeSensorSummary)
                AppLogger.i("Capsule", "已启用导航全传感器事件融合：$activeSensorSummary")
            }.onFailure { e ->
                AppLogger.w("Capsule", "注册导航传感器失败：${e.message}")
            }
        }
    }

    private fun extractTrueHeadingFromRotationMatrix(
        ctx: Context,
        rotMatrix: FloatArray,
        remapped: FloatArray,
        outOrient: FloatArray
    ): Float {
        val rotation = runCatching {
            ctx.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }.getOrDefault(Surface.ROTATION_0)

        val m = when (rotation) {
            Surface.ROTATION_90 -> if (SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)) remapped else rotMatrix
            Surface.ROTATION_180 -> if (SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped)) remapped else rotMatrix
            Surface.ROTATION_270 -> if (SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)) remapped else rotMatrix
            else -> rotMatrix
        }

        val xHorizNorm = sqrt((m[0] * m[0] + m[3] * m[3]).toDouble())
        val magAzimuth = if (xHorizNorm > 0.25) {
            val forwardE = -m[3].toDouble()
            val forwardN = m[0].toDouble()
            ((Math.toDegrees(atan2(forwardE, forwardN)) + 360.0) % 360.0).toFloat()
        } else {
            SensorManager.getOrientation(m, outOrient)
            ((Math.toDegrees(outOrient[0].toDouble()) + 360.0) % 360.0).toFloat()
        }
        return ((magAzimuth + magneticDeclinationDeg + residualCompassBiasDeg) % 360f + 360f) % 360f
    }

    private fun applySmoothedTrueHeading(ctx: Context, rawTrueDeg: Float) {
        val prev = compassTrueAzimuthDeg
        val smoothed = if (prev < 0f) {
            rawTrueDeg
        } else {
            var diff = rawTrueDeg - prev
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            val absDiff = abs(diff)
            val alpha = when {
                magneticInterference -> if (absDiff >= 25f) 0.45f else 0.15f
                absDiff >= 15f -> 0.88f
                absDiff >= 5f -> 0.65f
                absDiff <= 0.8f -> 1.0f
                else -> 0.50f
            }
            ((prev + diff * alpha) + 360f) % 360f
        }
        compassTrueAzimuthDeg = smoothed
        maybeRefreshCompassDotOn1DegRotation(ctx, smoothed)
    }

    private fun unregisterAllNavSensors(ctx: Context) {
        val listener = sensorListener
        sensorListener = null
        val trig = sigMotionTriggerListener
        sigMotionTriggerListener = null
        clearSensorDataCache()
        mainHandler.post {
            runCatching {
                val sm = ctx.getSystemService(SensorManager::class.java) ?: return@runCatching
                if (listener != null) sm.unregisterListener(listener)
                if (trig != null) {
                    sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)?.let {
                        sm.cancelTriggerSensor(trig, it)
                    }
                }
            }
        }
    }

    private fun maybeRefreshCompassDotOn1DegRotation(ctx: Context, trueHeadingDeg: Float) {
        val targetBearing = lastTargetBearingDeg
        if (targetBearing < 0f || !isRunning() || cachedPillText.isBlank()) return
        val rel360 = ((((targetBearing - trueHeadingDeg) % 360f) + 360f).roundToInt()) % 360
        val prevRel = lastRenderedRelDeg
        val angDiff = if (prevRel != Int.MIN_VALUE) {
            val rawDiff = abs(rel360 - prevRel)
            minOf(rawDiff, 360 - rawDiff)
        } else 360
        if (angDiff < 1) return
        if (!isScreenInteractive(ctx)) return

        val now = SystemClock.elapsedRealtime()
        val minInterval = if (angDiff >= 12) COMPASS_PUSH_FAST_INTERVAL_MS else COMPASS_PUSH_FINE_INTERVAL_MS
        val elapsed = now - lastCompassPushElapsedMs
        if (elapsed < minInterval) {
            pendingFlushHeadingDeg = trueHeadingDeg
            mainHandler.removeCallbacks(pendingCompassFlushTask)
            mainHandler.postDelayed(pendingCompassFlushTask, (minInterval - elapsed).coerceAtLeast(16L))
            return
        }
        mainHandler.removeCallbacks(pendingCompassFlushTask)
        pendingFlushHeadingDeg = -1f
        navIoScope.launch { pushCompassDotNow(ctx, trueHeadingDeg) }
    }

    private fun pushCompassDotNow(ctx: Context, trueHeadingDeg: Float) {
        val targetBearing = lastTargetBearingDeg
        if (targetBearing < 0f || !isRunning() || cachedPillText.isBlank()) return
        val rawSignedRel = (((targetBearing - trueHeadingDeg) + 540f) % 360f) - 180f
        val rel360 = ((((targetBearing - trueHeadingDeg) % 360f) + 360f).roundToInt()) % 360
        lastRenderedRelDeg = rel360
        lastCompassPushElapsedMs = SystemClock.elapsedRealtime()
        val headingInt = (trueHeadingDeg.roundToInt() % 360 + 360) % 360
        val compassTag = "${compassCardinal(headingInt.toFloat())}${headingInt}°"
        val subtitle = if (cachedRemainEtaSuffix.isNotBlank()) {
            "真北 $compassTag · $cachedRemainEtaSuffix"
        } else {
            "真北 $compassTag"
        }
        pushNavCapsule(ctx, cachedAction, cachedPillText, cachedTitle, subtitle, rawSignedRel, allowPopup = false)
    }

    // ==================== Polyline 本地缓存读写与自动清理 ====================

    private fun routeCacheFile(ctx: Context): File = File(ctx.cacheDir, ROUTE_CACHE_FILENAME)

    private fun writeMultiRouteCacheFile(ctx: Context, routes: List<NavRoute>, activeIdx: Int) {
        runCatching {
            val routesArr = JSONArray()
            for (route in routes) {
                val stepsArr = JSONArray()
                for (s in route.steps) {
                    stepsArr.put(
                        JSONObject().apply {
                            put("instruction", s.instruction)
                            put("action", s.action.name)
                            put("roadName", s.roadName)
                            put("distanceMeters", s.distanceMeters)
                            put("endLat", s.endLat)
                            put("endLon", s.endLon)
                            put("polyStartIdx", s.polyStartIdx)
                            put("polyEndIdx", s.polyEndIdx)
                        }
                    )
                }
                val polySb = StringBuilder(route.polyLats.size * 18)
                for (i in route.polyLats.indices) {
                    if (i > 0) polySb.append(';')
                    polySb.append("%.5f,%.5f".format(route.polyLats[i], route.polyLons[i]))
                }
                routesArr.put(
                    JSONObject().apply {
                        put("provider", route.provider)
                        put("destinationName", route.destinationName)
                        put("destLat", route.destLat)
                        put("destLon", route.destLon)
                        put("totalDistanceMeters", route.totalDistanceMeters)
                        put("totalDurationSec", route.totalDurationSec)
                        put("usesGcj02", route.usesGcj02)
                        put("routeLabel", route.routeLabel)
                        put("poly", polySb.toString())
                        put("steps", stepsArr)
                    }
                )
            }
            val root = JSONObject().apply {
                put("activeRouteIndex", activeIdx)
                put("routes", routesArr)
            }
            routeCacheFile(ctx).writeText(root.toString(), Charsets.UTF_8)
            AppLogger.i("Capsule", "已将 ${routes.size} 条 Polyline 备选路线写入本地缓存 active=${activeIdx + 1}")
        }.onFailure { e ->
            AppLogger.w("Capsule", "写入多路线缓存失败：${e.message}")
        }
    }

    private fun readMultiRouteCacheFile(ctx: Context): Pair<List<NavRoute>, Int>? {
        return runCatching {
            val f = routeCacheFile(ctx)
            if (!f.exists() || f.length() < 16) return null
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val routesArr = root.optJSONArray("routes") ?: return null
            val list = ArrayList<NavRoute>(routesArr.length())
            for (rIdx in 0 until routesArr.length()) {
                val rObj = routesArr.getJSONObject(rIdx)
                val stepsArr = rObj.optJSONArray("steps") ?: continue
                val steps = ArrayList<NavStep>(stepsArr.length())
                for (i in 0 until stepsArr.length()) {
                    val s = stepsArr.getJSONObject(i)
                    val act = runCatching { TurnAction.valueOf(s.optString("action")) }.getOrDefault(TurnAction.STRAIGHT)
                    steps.add(
                        NavStep(
                            instruction = s.optString("instruction"),
                            action = act,
                            roadName = s.optString("roadName"),
                            distanceMeters = s.optInt("distanceMeters", 0),
                            endLat = s.optDouble("endLat"),
                            endLon = s.optDouble("endLon"),
                            polyStartIdx = s.optInt("polyStartIdx", 0),
                            polyEndIdx = s.optInt("polyEndIdx", 0)
                        )
                    )
                }
                val polyStr = rObj.optString("poly")
                val (pLats, pLons) = if (polyStr.isNotBlank()) {
                    val parts = polyStr.split(';')
                    val lats = DoubleArray(parts.size)
                    val lons = DoubleArray(parts.size)
                    var c = 0
                    for (pt in parts) {
                        val kv = pt.split(',')
                        val la = kv.getOrNull(0)?.toDoubleOrNull()
                        val lo = kv.getOrNull(1)?.toDoubleOrNull()
                        if (la != null && lo != null) {
                            lats[c] = la
                            lons[c] = lo
                            c++
                        }
                    }
                    lats.copyOf(c) to lons.copyOf(c)
                } else {
                    DoubleArray(0) to DoubleArray(0)
                }
                if (steps.isNotEmpty()) {
                    list.add(
                        NavRoute(
                            provider = rObj.optString("provider", "amap"),
                            destinationName = rObj.optString("destinationName"),
                            destLat = rObj.optDouble("destLat"),
                            destLon = rObj.optDouble("destLon"),
                            totalDistanceMeters = rObj.optInt("totalDistanceMeters", 0),
                            totalDurationSec = rObj.optInt("totalDurationSec", 60),
                            steps = steps,
                            usesGcj02 = rObj.optBoolean("usesGcj02", true),
                            routeLabel = rObj.optString("routeLabel", "方案${rIdx + 1}"),
                            polyLats = pLats,
                            polyLons = pLons
                        )
                    )
                }
            }
            if (list.isEmpty()) null else list to root.optInt("activeRouteIndex", 0)
        }.getOrNull()
    }

    private fun deleteRouteCacheFile(ctx: Context) {
        runCatching {
            val f = routeCacheFile(ctx)
            if (f.exists()) f.delete()
        }
    }

    // ==================== 纯事件驱动 LocationManager 回调（无任何定时器） ====================

    @Suppress("MissingPermission")
    private fun resolveQuickLastLocation(ctx: Context): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || l.time > best.time) best = l
        }
        return best
    }

    @Suppress("MissingPermission")
    private fun resolveBestLastLocation(ctx: Context): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val best = resolveQuickLastLocation(ctx)
        val isFreshAndAccurate = best != null &&
            (System.currentTimeMillis() - best.time) < 90_000L &&
            (!best.hasAccuracy() || best.accuracy <= 120f)
        if (isFreshAndAccurate) return best
        val liveProvider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        } ?: return best
        val ref = AtomicReference<Location?>(best)
        val latch = CountDownLatch(1)
        val cs = CancellationSignal()
        runCatching {
            lm.getCurrentLocation(liveProvider, cs, ctx.mainExecutor) { loc ->
                if (loc != null) ref.set(loc)
                latch.countDown()
            }
            if (!latch.await(5, TimeUnit.SECONDS)) {
                cs.cancel()
            }
        }
        return ref.get()
    }

    @Suppress("MissingPermission")
    private fun registerLocationUpdates(ctx: Context) {
        unregisterLocationUpdates(ctx)
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val nowMs = SystemClock.elapsedRealtime()
                val locElapsedMs = if (location.elapsedRealtimeNanos > 0L) {
                    location.elapsedRealtimeNanos / 1_000_000L
                } else nowMs
                val accM = if (location.hasAccuracy()) location.accuracy else 999f
                val speedAccMps = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else 0f

                // 防线 2：时间戳与物理位移防漂移（丢弃倒流时间戳或违背物理规律的瞬移跳点，如 2 秒位移 1 公里）
                val prevLoc = lastAcceptedLocation
                val prevTimeMs = lastAcceptedLocTimeMs
                var dispSincePrevM = 0
                if (prevLoc != null && prevTimeMs > 0L) {
                    if (locElapsedMs + 1000L < prevTimeMs) {
                        return
                    }
                    val dtSec = ((locElapsedMs - prevTimeMs) / 1000.0).coerceAtLeast(0.2)
                    dispSincePrevM = haversineMeters(prevLoc.latitude, prevLoc.longitude, location.latitude, location.longitude)
                    val impliedSpeedMps = dispSincePrevM / dtSec
                    val maxPhysicalSpeedMps = when (activeNavMode) {
                        "walking" -> 8.0
                        "bicycling" -> 22.0
                        else -> 55.0 // 约 198 km/h，超出视为 GNSS 瞬移漂移
                    }
                    if (dispSincePrevM > 120 && impliedSpeedMps > maxPhysicalSpeedMps) {
                        AppLogger.w("Capsule", "丢弃 GNSS 物理漂移点：位移 ${dispSincePrevM}m / ${"%.1f".format(dtSec)}s (${(impliedSpeedMps * 3.6).roundToInt()}km/h)")
                        return
                    }
                    // 室内极差基站跳点保护：已有稳定定位时，丢弃精度 > 80m 的劣质点防止误判偏航
                    if (accM > 80f && (nowMs - prevTimeMs) < 15_000L) {
                        hasValidSpeed = false
                        smoothedSpeedMps = 0f
                        return
                    }
                }

                lastAcceptedLocation = location
                lastAcceptedLocTimeMs = locElapsedMs
                latestLocation = location
                pdrOffsetLatDeg = 0.0
                pdrOffsetLonDeg = 0.0

                // 防线 1（精度 + GNSS 平均信噪比校验）+ 防线 3（传感器静止覆盖）+ 低信噪比自动切换 3 轴惯导测速：
                val poorAccuracy = accM > MAX_VALID_ACCURACY_METERS ||
                    (location.hasSpeedAccuracy() && speedAccMps > MAX_VALID_SPEED_ACCURACY_MPS)
                val lowGnssSnr = (gnssAvgCn0DbHz > 0f && gnssAvgCn0DbHz < GNSS_LOW_AVG_SNR_DBHZ) ||
                    (gnssStatusReceived && gnssUsedSatCount < 4)
                val isSensorStationary = sensorStationaryFlag ||
                    (lastSensorMotionElapsedMs > 0L && (nowMs - lastSensorMotionElapsedMs) > 6000L && dispSincePrevM < 12)

                // 计算位移/时间推算速度，兼容网络定位或无 hasSpeed() 的机型
                val dispDerivedMps: Float = if (prevLoc != null && prevTimeMs > 0L) {
                    val dtSec = ((locElapsedMs - prevTimeMs).coerceAtLeast(500L)) / 1000.0
                    if (dtSec in 0.5..20.0 && dispSincePrevM >= 2.0) (dispSincePrevM / dtSec).toFloat().coerceIn(0f, 55f) else -1f
                } else -1f

                val rawGnssSpeedMps: Float = when {
                    location.hasSpeed() && location.speed >= 0f -> location.speed
                    dispDerivedMps >= 0f -> dispDerivedMps
                    else -> -1f
                }

                when {
                    isSensorStationary && rawGnssSpeedMps < 1.5f -> {
                        usingImuSpeedFallback = false
                        hasValidSpeed = true
                        smoothedSpeedMps = 0f
                        imuFusedSpeedMps = 0f
                    }
                    lowGnssSnr || poorAccuracy -> {
                        // GNSS 平均信噪比过低 (<22 dB-Hz) 或精度差：自动切换至 3 轴线性加速度融合测速
                        usingImuSpeedFallback = true
                        val fallbackMps = if (imuFusedSpeedMps >= 0.25f) {
                            imuFusedSpeedMps
                        } else if (dispDerivedMps >= 0.3f && !isSensorStationary) {
                            dispDerivedMps
                        } else 0f
                        hasValidSpeed = true
                        smoothedSpeedMps = if (smoothedSpeedMps <= 0.1f) fallbackMps else (smoothedSpeedMps * 0.55f + fallbackMps * 0.45f)
                    }
                    rawGnssSpeedMps >= 0f -> {
                        // GNSS 信噪比良好：GNSS 速度与 3 轴加速度互补融合，并实时回锚惯导速度基准
                        usingImuSpeedFallback = false
                        hasValidSpeed = true
                        val fusedMps = if (imuFusedSpeedMps >= 0.3f) {
                            rawGnssSpeedMps * 0.75f + imuFusedSpeedMps * 0.25f
                        } else {
                            rawGnssSpeedMps
                        }
                        smoothedSpeedMps = if (smoothedSpeedMps <= 0.1f) {
                            fusedMps
                        } else {
                            smoothedSpeedMps * 0.60f + fusedMps * 0.40f
                        }
                        imuFusedSpeedMps = smoothedSpeedMps
                    }
                    else -> {
                        usingImuSpeedFallback = true
                        hasValidSpeed = true
                        smoothedSpeedMps = imuFusedSpeedMps
                    }
                }

                updateMagneticDeclination(location)
                if (!poorAccuracy && !isSensorStationary && location.hasBearing() && smoothedSpeedMps >= 2.0f &&
                    compassTrueAzimuthDeg >= 0f && !magneticInterference
                ) {
                    var err = location.bearing - compassTrueAzimuthDeg
                    if (err > 180f) err -= 360f
                    if (err < -180f) err += 360f
                    if (abs(err) <= 30f) {
                        residualCompassBiasDeg = (residualCompassBiasDeg + err * 0.06f).coerceIn(-15f, 15f)
                    }
                }
                enqueueBackgroundLocationEvaluation(ctx, location)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        locationListener = listener
        mainHandler.post {
            runCatching {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 3f, listener, Looper.getMainLooper())
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 8f, listener, Looper.getMainLooper())
                }
                val gnssCb = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        var usedSum = 0f
                        var usedCnt = 0
                        var allSum = 0f
                        var allCnt = 0
                        for (i in 0 until status.satelliteCount) {
                            val cn0 = status.getCn0DbHz(i)
                            if (cn0 > 0f) {
                                allSum += cn0
                                allCnt++
                                if (status.usedInFix(i)) {
                                    usedSum += cn0
                                    usedCnt++
                                }
                            }
                        }
                        gnssStatusReceived = true
                        gnssUsedSatCount = usedCnt
                        gnssAvgCn0DbHz = when {
                            usedCnt > 0 -> usedSum / usedCnt
                            allCnt > 0 -> allSum / allCnt
                            else -> 0f
                        }
                    }
                }
                gnssStatusCallback = gnssCb
                runCatching { lm.registerGnssStatusCallback(ctx.mainExecutor, gnssCb) }
            }.onFailure { e ->
                AppLogger.w("Capsule", "注册导航定位监听失败：${e.message}")
            }
        }
    }

    private fun unregisterLocationUpdates(ctx: Context) {
        val listener = locationListener
        locationListener = null
        val gnssCb = gnssStatusCallback
        gnssStatusCallback = null
        mainHandler.post {
            val lm = ctx.getSystemService(LocationManager::class.java) ?: return@post
            if (listener != null) runCatching { lm.removeUpdates(listener) }
            if (gnssCb != null) runCatching { lm.unregisterGnssStatusCallback(gnssCb) }
        }
    }

    // ==================== Polyline 局部滑动窗口高效匹配算法（后台 IO 线程执行） ====================

    /**
     * 平面等距近似点到线段最短距离（米）：比全量 Haversine 快百倍，精度在局部路段内误差 < 0.2m。
     */
    private fun pointToSegmentMeters(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Double {
        val cosLat = cos(Math.toRadians(pLat))
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cosLat
        val ax = (aLon - pLon) * mPerDegLon
        val ay = (aLat - pLat) * mPerDegLat
        val bx = (bLon - pLon) * mPerDegLon
        val by = (bLat - pLat) * mPerDegLat
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq > 1e-6) (-(ax * dx + ay * dy) / lenSq).coerceIn(0.0, 1.0) else 0.0
        val projX = ax + t * dx
        val projY = ay + t * dy
        return sqrt(projX * projX + projY * projY)
    }

    /**
     * 仅在当前轨迹索引附近 [start..end] 滑动窗口内匹配最近 Polyline 路段，
     * O(1) 映射到当前路段索引，绝不遍历整条路线。
     */
    private fun matchPolylineWindow(
        route: NavRoute,
        curLat: Double,
        curLon: Double,
        hintPolyIdx: Int,
        fullScan: Boolean = false
    ): Pair<Int, Int> {
        val n = route.polyLats.size
        if (n < 2) {
            return matchStepsWindow(route, curLat, curLon, currentStepIndex, fullScan)
        }
        val lo = if (fullScan) 0 else (hintPolyIdx - 4).coerceAtLeast(0)
        val hi = if (fullScan) n - 2 else (hintPolyIdx + 25).coerceAtMost(n - 2)
        var bestIdx = lo
        var bestDist = Double.MAX_VALUE
        for (i in lo..hi) {
            val d = pointToSegmentMeters(
                curLat, curLon,
                route.polyLats[i], route.polyLons[i],
                route.polyLats[i + 1], route.polyLons[i + 1]
            )
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx to bestDist.roundToInt()
    }

    private fun matchStepsWindow(
        route: NavRoute,
        curLat: Double,
        curLon: Double,
        hintStepIdx: Int,
        fullScan: Boolean
    ): Pair<Int, Int> {
        val lo = if (fullScan) 0 else (hintStepIdx - 1).coerceAtLeast(0)
        val hi = if (fullScan) route.steps.lastIndex else (hintStepIdx + 3).coerceAtMost(route.steps.lastIndex)
        var bestIdx = lo
        var bestDist = Int.MAX_VALUE
        for (i in lo..hi) {
            val d = haversineMeters(curLat, curLon, route.steps[i].endLat, route.steps[i].endLon)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx to bestDist
    }

    private fun mapPolyIndexToStepIndex(route: NavRoute, polyIdx: Int): Int {
        if (route.polyLats.size < 2) return polyIdx.coerceIn(0, route.steps.lastIndex)
        // 从当前 step 附近局部查找包含 polyIdx 的步骤（仅查相邻 4 步，不遍历全量）
        val startCheck = (currentStepIndex - 1).coerceAtLeast(0)
        val endCheck = (currentStepIndex + 4).coerceAtMost(route.steps.lastIndex)
        for (i in startCheck..endCheck) {
            val s = route.steps[i]
            if (polyIdx in s.polyStartIdx..s.polyEndIdx) return i
        }
        // 二分查找全局步骤边界
        var lo = 0
        var hi = route.steps.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = route.steps[mid]
            when {
                polyIdx < s.polyStartIdx -> hi = mid - 1
                polyIdx > s.polyEndIdx -> lo = mid + 1
                else -> return mid
            }
        }
        return lo.coerceIn(0, route.steps.lastIndex)
    }

    /**
     * 基于实时速度 Location.getSpeed() 计算动态预警提前距离（米）：
     * - 高速 (>=22m/s ≈ 80km/h)：提前 500m 预警
     * - 快速路 (14–22m/s ≈ 50–80km/h)：提前 300m 预警
     * - 城市常规/骑行 (5–14m/s ≈ 18–50km/h)：提前 150m 预警
     * - 步行/低速 (<5m/s)：提前 50m 预警
     */
    private fun computeSpeedAdaptiveWarningMeters(speedMps: Float): Int {
        return when {
            speedMps >= 22f -> 500
            speedMps >= 14f -> 300
            speedMps >= 5f -> 150
            else -> 50
        }
    }

    private fun triggerBackgroundMultiRouteReplan(ctx: Context, loc: Location) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOnlineReplanElapsedMs < ONLINE_REPLAN_COOLDOWN_MS) return
        if (!onlineReplanInFlight.compareAndSet(false, true)) return
        lastOnlineReplanElapsedMs = now
        val provider = activeProvider
        val key = activeApiKey
        val mode = activeNavMode
        val dest = activeDestinationQuery
        navIoScope.launch {
            try {
                val fresh = planMultiRoutes(provider, key, mode, loc.latitude, loc.longitude, dest)
                if (fresh.isNotEmpty() && isRunning()) {
                    cachedRoutes = fresh
                    activeRouteIndex = 0
                    currentStepIndex = 0
                    currentPolyIndex = 0
                    consecutiveOffRouteCount = 0
                    currentMachineState = NavMachineState.CRUISING
                    lastNavPushSignature = ""
                    writeMultiRouteCacheFile(ctx, fresh, 0)
                    AppLogger.i("Capsule", "偏离全部缓存路线，后台静默刷新 ${fresh.size} 条 Polyline 备选路线")
                    evaluateAndPushFromCache(ctx, fresh[0], loc)
                }
            } catch (_: Throwable) {
            } finally {
                onlineReplanInFlight.set(false)
            }
        }
    }

    private fun evaluateAndPushFromCache(ctx: Context, initialRoute: NavRoute, loc: Location) {
        val fusedWgsLat = loc.latitude + pdrOffsetLatDeg
        val fusedWgsLon = loc.longitude + pdrOffsetLonDeg
        var route = initialRoute
        val (curLat, curLon) = if (route.usesGcj02) {
            wgs84ToGcj02(fusedWgsLat, fusedWgsLon)
        } else {
            fusedWgsLat to fusedWgsLon
        }

        // 1. 到达终点（<= 30m）：切换 ARRIVED 状态，释放全部传感器、GPS、TTS 与缓存
        val distToDest = haversineMeters(curLat, curLon, route.destLat, route.destLon)
        if (distToDest <= 30) {
            currentMachineState = NavMachineState.ARRIVED
            mainHandler.removeCallbacks(pendingCompassFlushTask)
            unregisterScreenPowerReceiver(ctx)
            unregisterLocationUpdates(ctx)
            unregisterAllNavSensors(ctx)
            deleteRouteCacheFile(ctx)
            shutdownNavTts()
            lastNavPushSignature = "ARRIVE:${route.destinationName}"
            pushNavCapsule(
                ctx = ctx,
                action = TurnAction.ARRIVE,
                pillText = "已到达目的地",
                title = "已到达 ${route.destinationName}",
                subtitle = "本次导航已完成（传感器与多路线缓存已释放）",
                relativeBearingDeg = null
            )
            _state.value = NavUiState(
                running = false,
                statusText = "已到达 ${route.destinationName}",
                currentPillText = "已到达目的地",
                currentDetailText = "本次导航已完成（传感器与多路线缓存已释放）",
                routeCount = 0,
                activeRouteIndex = 0,
                sensorSummary = "",
                machineState = NavMachineState.ARRIVED
            )
            return
        }

        // 2. 实时垂直距离检测 + 动态全局索引回溯（允许 index 回溯）
        var (matchedPolyIdx, perpDistMeters) = matchPolylineWindow(route, curLat, curLon, currentPolyIndex, fullScan = false)
        val allRoutes = cachedRoutes
        if (perpDistMeters > OFF_ROUTE_THRESHOLD_METERS) {
            // 偏离局部窗口：全局搜索整条缓存轨迹（允许向前或向后回溯索引）
            val (globalPolyIdx, globalDist) = matchPolylineWindow(route, curLat, curLon, 0, fullScan = true)
            if (globalDist < perpDistMeters) {
                if (globalPolyIdx < currentPolyIndex) {
                    AppLogger.i("Capsule", "动态索引回溯：$currentPolyIndex -> $globalPolyIdx (dist=${globalDist}m)")
                }
                matchedPolyIdx = globalPolyIdx
                perpDistMeters = globalDist
            }
            // 若仍 >45m，在所有本地缓存备选路线中全局搜索最近点实现 <1ms 离线切线
            if (perpDistMeters > OFF_ROUTE_THRESHOLD_METERS && allRoutes.size > 1) {
                var bestAltIdx = activeRouteIndex
                var bestAltDist = perpDistMeters
                var bestAltPoly = matchedPolyIdx
                for (rIdx in allRoutes.indices) {
                    if (rIdx == activeRouteIndex) continue
                    val alt = allRoutes[rIdx]
                    val (altLat, altLon) = if (alt.usesGcj02) wgs84ToGcj02(fusedWgsLat, fusedWgsLon) else (fusedWgsLat to fusedWgsLon)
                    val (aPoly, aDist) = matchPolylineWindow(alt, altLat, altLon, 0, fullScan = true)
                    if (aDist + 25 < bestAltDist) {
                        bestAltDist = aDist
                        bestAltIdx = rIdx
                        bestAltPoly = aPoly
                    }
                }
                if (bestAltIdx != activeRouteIndex) {
                    activeRouteIndex = bestAltIdx
                    route = allRoutes[bestAltIdx]
                    matchedPolyIdx = bestAltPoly
                    perpDistMeters = bestAltDist
                    writeMultiRouteCacheFile(ctx, allRoutes, bestAltIdx)
                    AppLogger.i("Capsule", "偏航离线秒切备选路线 ${bestAltIdx + 1}/${allRoutes.size} (${route.routeLabel})")
                }
            }
        }

        // 3. 连续 3 个定位点垂直距离 > 45m 判定为偏航（防单点 GPS 跳变误报）
        val wasOffRoute = currentMachineState == NavMachineState.OFF_ROUTE_REPLANNING ||
            currentMachineState == NavMachineState.OFFLINE_RECOVERY
        if (perpDistMeters > OFF_ROUTE_THRESHOLD_METERS) {
            consecutiveOffRouteCount++
        } else {
            if (wasOffRoute) {
                AppLogger.i("Capsule", "已重新回归规划路线，恢复正常巡航")
                speakNavPrompt(ctx, "已回到规划路线", cooldownMs = 5000L)
            }
            consecutiveOffRouteCount = 0
        }

        val headingDeg: Float? = when {
            magneticInterference && loc.hasBearing() && loc.speed >= 1.5f -> loc.bearing
            compassTrueAzimuthDeg >= 0f -> compassTrueAzimuthDeg
            loc.hasBearing() && loc.speed >= 1.5f -> loc.bearing
            else -> null
        }

        // 4. 确认偏航（连续 >= 3 点 > 45m）：在线静默重算 + 弱网/断网本地回补路线兜底 + TTS/胶囊播报
        if (consecutiveOffRouteCount >= OFF_ROUTE_CONSECUTIVE_FIXES) {
            val online = isNetworkAvailable(ctx)
            currentMachineState = if (online) NavMachineState.OFF_ROUTE_REPLANNING else NavMachineState.OFFLINE_RECOVERY
            if (online) {
                triggerBackgroundMultiRouteReplan(ctx, loc)
            }
            // 计算本地回补路线：从当前位置直连全局最近的原路线缓存点
            val recoverLat = if (route.polyLats.isNotEmpty()) {
                route.polyLats[matchedPolyIdx.coerceIn(0, route.polyLats.lastIndex)]
            } else {
                route.steps[mapPolyIndexToStepIndex(route, matchedPolyIdx)].endLat
            }
            val recoverLon = if (route.polyLons.isNotEmpty()) {
                route.polyLons[matchedPolyIdx.coerceIn(0, route.polyLons.lastIndex)]
            } else {
                route.steps[mapPolyIndexToStepIndex(route, matchedPolyIdx)].endLon
            }
            val recoveryDistM = haversineMeters(curLat, curLon, recoverLat, recoverLon).coerceAtLeast(perpDistMeters)
            val recoveryBearing = bearingDegrees(curLat, curLon, recoverLat, recoverLon)
            val relRecoverBearing: Float? = if (headingDeg != null) {
                (((recoveryBearing - headingDeg) + 540f) % 360f) - 180f
            } else null

            lastTargetBearingDeg = recoveryBearing
            val recoverDirLabel = compassCardinal(recoveryBearing)
            val promptMsg = "您已偏航，请在前方合适位置调头"
            speakNavPrompt(ctx, promptMsg)

            val pillText = "偏航 调头回补"
            val title = promptMsg
            val modeLabel = if (online) "偏航重算中 · 本地回补" else "弱网离线回补"
            val remainEtaSuffix = "$modeLabel · 距原路线 ${formatMetersShort(recoveryDistM)}（向$recoverDirLabel）"
            val headingInt = if (headingDeg != null) ((headingDeg.roundToInt() % 360 + 360) % 360) else -1
            val compassTag = if (headingInt >= 0) "${compassCardinal(headingInt.toFloat())}${headingInt}°" else ""
            val subtitle = if (compassTag.isNotBlank()) "真北 $compassTag · $remainEtaSuffix" else remainEtaSuffix

            cachedAction = TurnAction.UTURN
            cachedPillText = pillText
            cachedTitle = title
            cachedRemainEtaSuffix = remainEtaSuffix

            val rel360 = if (relRecoverBearing != null) ((((relRecoverBearing % 360f) + 360f).roundToInt()) % 360) else 999
            val sig = "OFFROUTE|$currentMachineState|$pillText|$subtitle|$rel360"
            if (sig != lastNavPushSignature) {
                lastNavPushSignature = sig
                if (rel360 in 0..359) {
                    lastRenderedRelDeg = rel360
                    lastCompassPushElapsedMs = SystemClock.elapsedRealtime()
                }
                val nextUiState = NavUiState(
                    running = true,
                    statusText = "状态：${currentMachineState.label}（偏离 ${perpDistMeters}m）",
                    currentPillText = pillText,
                    currentDetailText = "$title\n$subtitle",
                    routeCount = allRoutes.size,
                    activeRouteIndex = activeRouteIndex,
                    sensorSummary = activeSensorSummary,
                    machineState = currentMachineState
                )
                if (_state.value != nextUiState) _state.value = nextUiState
                pushNavCapsule(ctx, TurnAction.UTURN, pillText, title, subtitle, relRecoverBearing)
            }
            return
        }

        // 5. 正常轨迹推进（支持回溯后的精确映射 + 最多前瞻 3 个转向点）
        currentPolyIndex = matchedPolyIdx
        var stepIdx = mapPolyIndexToStepIndex(route, matchedPolyIdx)
        val lookAheadEnd = minOf(route.steps.lastIndex, stepIdx + 3)
        while (stepIdx < lookAheadEnd) {
            val dEnd = haversineMeters(curLat, curLon, route.steps[stepIdx].endLat, route.steps[stepIdx].endLon)
            if (dEnd <= 25) stepIdx++ else break
        }
        currentStepIndex = stepIdx

        // 6. 基于 Location.getSpeed() 的动态预警与状态机切换（巡航 / 即将转向 / 等待信号灯）
        val step = route.steps[stepIdx]
        val nextStep = route.steps.getOrNull(stepIdx + 1)
        val distToTurn = haversineMeters(curLat, curLon, step.endLat, step.endLon)
        var remainTotal = distToTurn
        for (i in (stepIdx + 1) until route.steps.size) {
            remainTotal += route.steps[i].distanceMeters
        }

        val actualSpeedMps = if (hasValidSpeed) smoothedSpeedMps else 0f
        val nowMs = SystemClock.elapsedRealtime()
        if (activeNavMode != "walking" && actualSpeedMps < 0.8f && distToTurn <= 65) {
            if (lowSpeedSinceElapsedMs == 0L) lowSpeedSinceElapsedMs = nowMs
        } else {
            lowSpeedSinceElapsedMs = 0L
        }
        val waitingTrafficLight = lowSpeedSinceElapsedMs > 0L && (nowMs - lowSpeedSinceElapsedMs) >= 4000L

        val liveSpeedMps = when {
            hasValidSpeed && smoothedSpeedMps >= 0.8f -> smoothedSpeedMps
            else -> when (activeNavMode) {
                "walking" -> 1.4f
                "bicycling" -> 4.5f
                else -> if (route.totalDistanceMeters > 0) {
                    (route.totalDistanceMeters.toFloat() / route.totalDurationSec.coerceAtLeast(60)).coerceIn(3f, 25f)
                } else 9f
            }
        }
        val warningDistM = computeSpeedAdaptiveWarningMeters(liveSpeedMps)
        val secToTurn = (distToTurn / liveSpeedMps.coerceAtLeast(1.0f)).roundToInt().coerceAtLeast(1)
        val isAdvanceWarning = distToTurn <= warningDistM || secToTurn <= 15
        val etaMin = ((remainTotal / liveSpeedMps.coerceAtLeast(1.2f)) / 60f).toInt().coerceAtLeast(1)
        val speedKmh = if (hasValidSpeed) (smoothedSpeedMps * 3.6f).roundToInt() else -1

        currentMachineState = when {
            waitingTrafficLight -> NavMachineState.WAITING_TRAFFIC_LIGHT
            isAdvanceWarning -> NavMachineState.APPROACHING_TURN
            else -> NavMachineState.CRUISING
        }

        val distShort = formatMetersShort(distToTurn)
        val remainShort = formatMetersShort(remainTotal)

        // 目标方位指向当前 Polyline 前方第 3 个采样点（贴合弯道切线），无 Polyline 时指向路段终点
        val lookPolyIdx = (matchedPolyIdx + 3).coerceAtMost(route.polyLats.lastIndex)
        val targetBearing = if (route.polyLats.size >= 2 && lookPolyIdx > matchedPolyIdx) {
            bearingDegrees(curLat, curLon, route.polyLats[lookPolyIdx], route.polyLons[lookPolyIdx])
        } else {
            bearingDegrees(curLat, curLon, step.endLat, step.endLon)
        }
        val relBearing: Float? = if (headingDeg != null) {
            (((targetBearing - headingDeg) + 540f) % 360f) - 180f
        } else null

        val upcomingAction = nextStep?.action ?: step.action
        val displayAction = when {
            relBearing != null && abs(relBearing) >= 135f && distToTurn > 35 -> TurnAction.UTURN
            isAdvanceWarning -> upcomingAction
            else -> step.action
        }

        lastTargetBearingDeg = targetBearing
        val headingInt = if (headingDeg != null) ((headingDeg.roundToInt() % 360 + 360) % 360) else -1
        val compassTag = if (headingInt >= 0) "${compassCardinal(headingInt.toFloat())}${headingInt}°" else ""
        val altTag = if (abs(currentDeltaAltitudeM) >= 3.5f) {
            val sign = if (currentDeltaAltitudeM > 0f) "+" else ""
            " · 海拔${sign}${currentDeltaAltitudeM.roundToInt()}m"
        } else ""
        val routeTag = if (allRoutes.size > 1) "路线${activeRouteIndex + 1}/${allRoutes.size} · " else ""
        val stateBadge = when (currentMachineState) {
            NavMachineState.WAITING_TRAFFIC_LIGHT -> "等红灯 · "
            NavMachineState.APPROACHING_TURN -> if (speedKmh >= 50) "提前预警 · " else ""
            else -> ""
        }
        val pillText = if (currentMachineState == NavMachineState.WAITING_TRAFFIC_LIGHT) {
            "等红灯 $distShort"
        } else {
            "$distShort ${displayAction.label}"
        }
        val targetRoad = nextStep?.roadName?.ifBlank { step.roadName } ?: step.roadName
        val title = if (targetRoad.isNotBlank()) {
            "$stateBadge$distShort 后${displayAction.label}（约${secToTurn}秒）· $targetRoad"
        } else {
            "$stateBadge$distShort 后${displayAction.label}（约${secToTurn}秒）"
        }
        val snrSuffix = if (usingImuSpeedFallback) "(惯导)" else ""
        val speedTag = when {
            !hasValidSpeed && imuFusedSpeedMps < 0.2f -> "-- km/h · "
            speedKmh >= 1 -> "${speedKmh}km/h$snrSuffix · "
            else -> "0km/h$snrSuffix · "
        }
        val remainEtaSuffix = "${routeTag}${speedTag}剩余 $remainShort · 约 ${etaMin}分钟$altTag"
        val subtitle = buildString {
            if (compassTag.isNotBlank()) append("真北 ").append(compassTag).append(" · ")
            append(remainEtaSuffix)
        }
        cachedAction = displayAction
        cachedPillText = pillText
        cachedTitle = title
        cachedRemainEtaSuffix = remainEtaSuffix

        val rel360 = if (relBearing != null) ((((relBearing % 360f) + 360f).roundToInt()) % 360) else 999
        val sig = "$currentMachineState|$displayAction|$pillText|$title|$subtitle|$rel360"
        if (sig == lastNavPushSignature) return
        lastNavPushSignature = sig
        if (rel360 in 0..359) {
            lastRenderedRelDeg = rel360
            lastCompassPushElapsedMs = SystemClock.elapsedRealtime()
        }

        val originAcc = if (loc.hasAccuracy()) "±${loc.accuracy.toInt()}m" else "未知精度"
        val originStr = "%.5f, %.5f (%s %s)".format(Locale.US, loc.latitude, loc.longitude, loc.provider ?: "loc", originAcc)
        val destStr = "${route.destinationName} (%.5f, %.5f)".format(Locale.US, route.destLat, route.destLon)
        val stepList = route.steps.mapIndexed { idx, stp ->
            val rd = stp.roadName.ifBlank { stp.action.label }
            "${idx + 1}. ${stp.instruction.ifBlank { rd }} (${if (stp.distanceMeters >= 1000) "%.1fkm".format(Locale.US, stp.distanceMeters / 1000.0) else "${stp.distanceMeters}m"})"
        }
        val nextUiState = NavUiState(
            running = true,
            statusText = "${currentMachineState.label} · ${route.routeLabel}（${activeRouteIndex + 1}/${allRoutes.size.coerceAtLeast(1)} · 预警${warningDistM}m）",
            currentPillText = pillText,
            currentDetailText = "$title\n$subtitle",
            routeCount = allRoutes.size,
            activeRouteIndex = activeRouteIndex,
            sensorSummary = activeSensorSummary,
            machineState = currentMachineState,
            originLabel = originStr,
            destinationLabel = destStr,
            destLat = route.destLat,
            destLon = route.destLon,
            routeStepsPreview = stepList
        )
        if (_state.value != nextUiState) {
            _state.value = nextUiState
        }
        val shouldPop = !navBannerInitialPopupDone
        navBannerInitialPopupDone = true
        pushNavCapsule(ctx, displayAction, pillText, title, subtitle, relBearing, allowPopup = shouldPop)
    }

    private fun pushNavCapsule(
        ctx: Context,
        action: TurnAction,
        pillText: String,
        title: String,
        subtitle: String,
        relativeBearingDeg: Float?,
        allowPopup: Boolean = true
    ) {
        val stopPi = PendingIntent.getBroadcast(
            ctx,
            199,
            Intent(ctx, MediaControlReceiver::class.java).setAction(MediaControlReceiver.ACTION_STOP_NAVIGATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openAppPi = PendingIntent.getActivity(
            ctx,
            198,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val actions = ArrayList<BannerAction>(2)
        if (cachedRoutes.size > 1 && action != TurnAction.ARRIVE) {
            val switchPi = PendingIntent.getBroadcast(
                ctx,
                197,
                Intent(ctx, MediaControlReceiver::class.java).setAction(MediaControlReceiver.ACTION_SWITCH_NAV_ROUTE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            actions.add(BannerAction("绕行换线", switchPi))
        }
        actions.add(BannerAction("结束导航", stopPi, isDestructive = true))

        val bmp = buildTurnArrowBitmap(action, relativeBearingDeg)
        val payload = ActiveMirroredPayload(
            key = NAV_KEY,
            packageName = ctx.packageName,
            appName = "胶囊导航",
            title = title,
            text = subtitle,
            iconBitmap = bmp,
            contentIntent = openAppPi,
            actions = actions,
            livePillText = pillText
        )
        NotificationIconStore.putPayload(payload)
        val cfg = runCatching { CapsulePrefs(ctx).load() }.getOrElse { CapsuleConfig() }
        CapsulePublisher.clearSysEventPreemptWindow()
        CapsulePublisher.publishMirrored(ctx, cfg, payload, isPinned = true)
        if (!DeviceBrandRouter.isNativeCapsuleEngine() || cfg.enableUniversalBannerMode || (!cfg.enableCapsuleEngine && cfg.enableOverlayBanner)) {
            if (allowPopup) {
                UniversalBannerHost.showBanner(
                    context = ctx,
                    appName = "胶囊导航",
                    packageName = ctx.packageName,
                    title = title,
                    text = subtitle,
                    contentIntent = openAppPi,
                    iconBitmap = bmp,
                    actions = payload.actions,
                    config = cfg,
                    notificationKey = NAV_KEY,
                    isPersistent = true,
                    collapsibleToPill = true,
                    liveLyricLine = pillText
                )
            } else {
                UniversalBannerHost.updateNavBannerInPlace(
                    key = NAV_KEY,
                    title = title,
                    text = subtitle,
                    iconBitmap = bmp,
                    pillText = pillText
                )
            }
        }
    }

    // ==================== 四大地图 Web API 多路线 + Polyline 解码 ====================

    private fun planMultiRoutes(
        provider: String,
        apiKey: String,
        mode: String,
        originWgsLat: Double,
        originWgsLon: Double,
        destination: String
    ): List<NavRoute> {
        return when (provider) {
            "amap" -> planWithAmap(apiKey, mode, originWgsLat, originWgsLon, destination)
            "tencent" -> planWithTencent(apiKey, mode, originWgsLat, originWgsLon, destination)
            "baidu" -> planWithBaidu(apiKey, mode, originWgsLat, originWgsLon, destination)
            else -> planWithOsrm(mode, originWgsLat, originWgsLon, destination)
        }
    }

    private fun decodeEncodedPolyline(encoded: String): Pair<DoubleArray, DoubleArray> {
        if (encoded.isBlank()) return DoubleArray(0) to DoubleArray(0)
        val lats = ArrayList<Double>(64)
        val lons = ArrayList<Double>(64)
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat
            if (index >= len) break
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng
            lats.add(lat / 1e5)
            lons.add(lng / 1e5)
        }
        return lats.toDoubleArray() to lons.toDoubleArray()
    }

    private fun planWithAmap(
        key: String,
        mode: String,
        oWgsLat: Double,
        oWgsLon: Double,
        dest: String
    ): List<NavRoute> {
        val (oLat, oLon) = wgs84ToGcj02(oWgsLat, oWgsLon)
        val encDest = URLEncoder.encode(dest, "UTF-8")
        var destLoc = ""
        var destName = dest

        val amapLocParam = "%.6f,%.6f".format(Locale.US, oLon, oLat)
        val aroundBody = httpGet(
            "https://restapi.amap.com/v3/place/around?key=$key&location=$amapLocParam&keywords=$encDest&radius=50000&sortrule=distance&offset=1"
        )
        if (!aroundBody.isNullOrBlank()) {
            val aj = JSONObject(aroundBody)
            if (aj.optString("status") == "1") {
                val pois = aj.optJSONArray("pois")
                if (pois != null && pois.length() > 0) {
                    val p0 = pois.getJSONObject(0)
                    val pLoc = p0.optString("location")
                    if (pLoc.contains(',')) {
                        destLoc = pLoc
                        val city = p0.optString("cityname")
                        val pName = p0.optString("name").ifBlank { dest }
                        destName = if (city.isNotBlank() && !pName.contains(city)) "$city·$pName" else pName
                    }
                }
            }
        }
        val geoBody = if (destLoc.isBlank()) httpGet("https://restapi.amap.com/v3/geocode/geo?key=$key&address=$encDest") else null
        if (!geoBody.isNullOrBlank()) {
            val j = JSONObject(geoBody)
            if (j.optString("status") == "1") {
                val arr = j.optJSONArray("geocodes")
                if (arr != null && arr.length() > 0) {
                    destLoc = arr.getJSONObject(0).optString("location")
                }
            } else {
                val info = j.optString("info")
                if (info.contains("INVALID_USER_KEY") || info.contains("USERKEY")) {
                    _state.value = NavUiState(statusText = "高德 API Key 无效（$info），请确认申请的是「Web服务」Key")
                    return emptyList()
                }
            }
        }
        if (destLoc.isBlank()) {
            val poiBody = httpGet("https://restapi.amap.com/v3/place/text?key=$key&keywords=$encDest&offset=1&page=1")
            if (!poiBody.isNullOrBlank()) {
                val j = JSONObject(poiBody)
                if (j.optString("status") == "1") {
                    val pois = j.optJSONArray("pois")
                    if (pois != null && pois.length() > 0) {
                        val p0 = pois.getJSONObject(0)
                        destLoc = p0.optString("location")
                        destName = p0.optString("name").ifBlank { dest }
                    }
                }
            }
        }
        val dParts = destLoc.split(",")
        if (dParts.size != 2) {
            _state.value = NavUiState(statusText = "高德未找到目的地「$dest」")
            return emptyList()
        }
        val dLon = dParts[0].toDoubleOrNull() ?: return emptyList()
        val dLat = dParts[1].toDoubleOrNull() ?: return emptyList()

        val originStr = "%.6f,%.6f".format(oLon, oLat)
        val destStr = "%.6f,%.6f".format(dLon, dLat)
        val isBike = (mode == "bicycling")
        val routeUrl = when (mode) {
            "walking" -> "https://restapi.amap.com/v3/direction/walking?key=$key&origin=$originStr&destination=$destStr"
            "bicycling" -> "https://restapi.amap.com/v4/direction/bicycling?key=$key&origin=$originStr&destination=$destStr"
            else -> "https://restapi.amap.com/v3/direction/driving?key=$key&origin=$originStr&destination=$destStr&strategy=10"
        }
        val routeBody = httpGet(routeUrl) ?: run {
            _state.value = NavUiState(statusText = "高德路线请求超时，请检查网络")
            return emptyList()
        }
        val root = JSONObject(routeBody)
        val paths: JSONArray? = if (isBike) {
            if (root.optInt("errcode", -1) != 0) {
                _state.value = NavUiState(statusText = "高德骑行规划失败：${root.optString("errmsg")}")
                return emptyList()
            }
            root.optJSONObject("data")?.optJSONArray("paths")
        } else {
            if (root.optString("status") != "1") {
                _state.value = NavUiState(statusText = "高德规划失败：${root.optString("info")}")
                return emptyList()
            }
            root.optJSONObject("route")?.optJSONArray("paths")
        }
        if (paths == null || paths.length() == 0) {
            _state.value = NavUiState(statusText = "高德未返回可用路线")
            return emptyList()
        }
        val result = ArrayList<NavRoute>(minOf(paths.length(), MAX_CACHED_ROUTES))
        for (pIdx in 0 until minOf(paths.length(), MAX_CACHED_ROUTES)) {
            val pathObj = paths.getJSONObject(pIdx)
            val totalDist = pathObj.optString("distance").toIntOrNull() ?: pathObj.optInt("distance", 0)
            val totalDur = pathObj.optString("duration").toIntOrNull() ?: pathObj.optInt("duration", 60)
            val strategyLabel = pathObj.optString("strategy").ifBlank { if (pIdx == 0) "推荐路线" else "备选路线${pIdx + 1}" }
            val stepsArr = pathObj.optJSONArray("steps") ?: continue
            val steps = ArrayList<NavStep>(stepsArr.length())
            val allLats = ArrayList<Double>(256)
            val allLons = ArrayList<Double>(256)
            for (i in 0 until stepsArr.length()) {
                val s = stepsArr.getJSONObject(i)
                val inst = s.optString("instruction")
                val act = parseTurnAction(s.optString("action").ifBlank { inst })
                val road = s.optString("road")
                val dist = s.optString("distance").toIntOrNull() ?: s.optInt("distance", 0)
                val poly = s.optString("polyline")
                val pStart = allLats.size
                if (poly.isNotBlank()) {
                    for (pt in poly.split(';')) {
                        val kv = pt.split(',')
                        val lo = kv.getOrNull(0)?.toDoubleOrNull()
                        val la = kv.getOrNull(1)?.toDoubleOrNull()
                        if (la != null && lo != null) {
                            allLats.add(la)
                            allLons.add(lo)
                        }
                    }
                }
                val pEnd = (allLats.size - 1).coerceAtLeast(pStart)
                val eLat = allLats.lastOrNull() ?: dLat
                val eLon = allLons.lastOrNull() ?: dLon
                steps.add(NavStep(inst, act, road, dist, eLat, eLon, pStart, pEnd))
            }
            if (steps.isNotEmpty()) {
                result.add(
                    NavRoute(
                        "amap", destName, dLat, dLon, totalDist, totalDur, steps,
                        usesGcj02 = true, routeLabel = strategyLabel,
                        polyLats = allLats.toDoubleArray(), polyLons = allLons.toDoubleArray()
                    )
                )
            }
        }
        return result
    }

    private fun planWithTencent(
        key: String,
        mode: String,
        oWgsLat: Double,
        oWgsLon: Double,
        dest: String
    ): List<NavRoute> {
        val (oLat, oLon) = wgs84ToGcj02(oWgsLat, oWgsLon)
        val encDest = URLEncoder.encode(dest, "UTF-8")
        var dLat: Double? = null
        var dLon: Double? = null
        var destName = dest

        val qqLocParam = "%.6f,%.6f".format(Locale.US, oLat, oLon)
        val sugBody = httpGet("https://apis.map.qq.com/ws/place/v1/suggestion/?keyword=$encDest&location=$qqLocParam&key=$key")
        if (!sugBody.isNullOrBlank()) {
            val j = JSONObject(sugBody)
            if (j.optInt("status", -1) == 0) {
                val arr = j.optJSONArray("data")
                if (arr != null && arr.length() > 0) {
                    val d0 = arr.getJSONObject(0)
                    val loc = d0.optJSONObject("location")
                    if (loc != null) {
                        dLat = loc.optDouble("lat")
                        dLon = loc.optDouble("lng")
                        destName = d0.optString("title").ifBlank { dest }
                    }
                }
            } else if (j.optInt("status") in setOf(110, 111, 120, 121)) {
                _state.value = NavUiState(statusText = "腾讯 Key 无效或配额受限：${j.optString("message")}")
                return emptyList()
            }
        }
        if (dLat == null || dLon == null) {
            val geoBody = httpGet("https://apis.map.qq.com/ws/geocoder/v1/?address=$encDest&key=$key")
            if (!geoBody.isNullOrBlank()) {
                val j = JSONObject(geoBody)
                if (j.optInt("status", -1) == 0) {
                    val loc = j.optJSONObject("result")?.optJSONObject("location")
                    if (loc != null) {
                        dLat = loc.optDouble("lat")
                        dLon = loc.optDouble("lng")
                    }
                }
            }
        }
        if (dLat == null || dLon == null) {
            _state.value = NavUiState(statusText = "腾讯地图未找到目的地「$dest」")
            return emptyList()
        }

        val m = when (mode) {
            "walking" -> "walking"
            "bicycling" -> "bicycling"
            else -> "driving"
        }
        val mpParam = if (m == "driving") "&get_mp=1" else ""
        val url = "https://apis.map.qq.com/ws/direction/v1/$m/?from=%.6f,%.6f&to=%.6f,%.6f$mpParam&key=$key".format(oLat, oLon, dLat, dLon)
        val body = httpGet(url) ?: run {
            _state.value = NavUiState(statusText = "腾讯路线请求超时")
            return emptyList()
        }
        val root = JSONObject(body)
        if (root.optInt("status", -1) != 0) {
            _state.value = NavUiState(statusText = "腾讯规划失败：${root.optString("message")}")
            return emptyList()
        }
        val routes = root.optJSONObject("result")?.optJSONArray("routes") ?: return emptyList()
        val result = ArrayList<NavRoute>(minOf(routes.length(), MAX_CACHED_ROUTES))
        for (rIdx in 0 until minOf(routes.length(), MAX_CACHED_ROUTES)) {
            val rObj = routes.getJSONObject(rIdx)
            val totalDist = rObj.optInt("distance", 0)
            val totalDur = rObj.optInt("duration", 1) * 60
            val rawPoly = rObj.optJSONArray("polyline")
            val coors = DoubleArray(rawPoly?.length() ?: 0)
            if (rawPoly != null && rawPoly.length() >= 2) {
                coors[0] = rawPoly.optDouble(0)
                coors[1] = rawPoly.optDouble(1)
                for (i in 2 until rawPoly.length()) {
                    coors[i] = coors[i - 2] + rawPoly.optDouble(i) / 1_000_000.0
                }
            }
            val ptCount = coors.size / 2
            val pLats = DoubleArray(ptCount)
            val pLons = DoubleArray(ptCount)
            for (p in 0 until ptCount) {
                pLats[p] = coors[p * 2]
                pLons[p] = coors[p * 2 + 1]
            }
            val stepsArr = rObj.optJSONArray("steps") ?: continue
            val steps = ArrayList<NavStep>(stepsArr.length())
            for (i in 0 until stepsArr.length()) {
                val s = stepsArr.getJSONObject(i)
                val inst = s.optString("instruction")
                val actStr = s.optString("act_descr").ifBlank { s.optString("dir_desc").ifBlank { inst } }
                val road = s.optString("road_name")
                val dist = s.optInt("distance", 0)
                val idxArr = s.optJSONArray("polyline_idx")
                val startCoorIdx = idxArr?.optInt(0, 0) ?: 0
                val endCoorIdx = idxArr?.optInt(1, -1) ?: -1
                val pStart = (startCoorIdx / 2).coerceIn(0, (ptCount - 1).coerceAtLeast(0))
                val pEnd = if (endCoorIdx >= 0) (endCoorIdx / 2).coerceIn(pStart, (ptCount - 1).coerceAtLeast(0)) else pStart
                val eLat = if (endCoorIdx >= 0 && endCoorIdx + 1 < coors.size) coors[endCoorIdx] else dLat
                val eLon = if (endCoorIdx >= 0 && endCoorIdx + 1 < coors.size) coors[endCoorIdx + 1] else dLon
                steps.add(NavStep(inst, parseTurnAction(actStr), road, dist, eLat, eLon, pStart, pEnd))
            }
            if (steps.isNotEmpty()) {
                val lbl = if (rIdx == 0) "推荐路线" else "备选路线${rIdx + 1}"
                result.add(
                    NavRoute(
                        "tencent", destName, dLat, dLon, totalDist, totalDur, steps,
                        usesGcj02 = true, routeLabel = lbl, polyLats = pLats, polyLons = pLons
                    )
                )
            }
        }
        return result
    }

    private fun planWithBaidu(
        ak: String,
        mode: String,
        oWgsLat: Double,
        oWgsLon: Double,
        dest: String
    ): List<NavRoute> {
        val (oLat, oLon) = wgs84ToGcj02(oWgsLat, oWgsLon)
        val encDest = URLEncoder.encode(dest, "UTF-8")
        val encRegion = URLEncoder.encode("全国", "UTF-8")
        var dLat: Double? = null
        var dLon: Double? = null
        var destName = dest

        val bdLocParam = "%.6f,%.6f".format(Locale.US, oLat, oLon)
        val sugBody = httpGet("https://api.map.baidu.com/place/v2/suggestion?query=$encDest&region=$encRegion&location=$bdLocParam&output=json&coord_type=2&ret_coordtype=gcj02ll&ak=$ak")
        if (!sugBody.isNullOrBlank()) {
            val j = JSONObject(sugBody)
            if (j.optInt("status", -1) == 0) {
                val arr = j.optJSONArray("result")
                if (arr != null && arr.length() > 0) {
                    val p0 = arr.getJSONObject(0)
                    val loc = p0.optJSONObject("location")
                    if (loc != null) {
                        dLat = loc.optDouble("lat")
                        dLon = loc.optDouble("lng")
                        destName = p0.optString("name").ifBlank { dest }
                    }
                }
            } else if (j.optInt("status") in setOf(200, 201, 210, 240)) {
                _state.value = NavUiState(statusText = "百度 AK 无效或未开通服务端权限：${j.optString("message")}")
                return emptyList()
            }
        }
        if (dLat == null || dLon == null) {
            val geoBody = httpGet("https://api.map.baidu.com/geocoding/v3/?address=$encDest&output=json&ret_coordtype=gcj02ll&ak=$ak")
            if (!geoBody.isNullOrBlank()) {
                val j = JSONObject(geoBody)
                if (j.optInt("status", -1) == 0) {
                    val loc = j.optJSONObject("result")?.optJSONObject("location")
                    if (loc != null) {
                        dLat = loc.optDouble("lat")
                        dLon = loc.optDouble("lng")
                    }
                }
            }
        }
        if (dLat == null || dLon == null) {
            _state.value = NavUiState(statusText = "百度地图未找到目的地「$dest」")
            return emptyList()
        }

        val m = when (mode) {
            "walking" -> "walking"
            "bicycling" -> "riding"
            else -> "driving"
        }
        val altParam = if (m == "driving") "&alternatives=1" else ""
        val url = "https://api.map.baidu.com/directionlite/v1/$m?origin=%.6f,%.6f&destination=%.6f,%.6f&coord_type=gcj02&ret_coordtype=gcj02$altParam&ak=$ak"
            .format(oLat, oLon, dLat, dLon)
        val body = httpGet(url) ?: run {
            _state.value = NavUiState(statusText = "百度路线请求超时")
            return emptyList()
        }
        val root = JSONObject(body)
        if (root.optInt("status", -1) != 0) {
            _state.value = NavUiState(statusText = "百度规划失败：${root.optString("message")}")
            return emptyList()
        }
        val routes = root.optJSONObject("result")?.optJSONArray("routes") ?: return emptyList()
        val htmlTagRegex = Regex("<[^>]+>")
        val result = ArrayList<NavRoute>(minOf(routes.length(), MAX_CACHED_ROUTES))
        for (rIdx in 0 until minOf(routes.length(), MAX_CACHED_ROUTES)) {
            val rObj = routes.getJSONObject(rIdx)
            val totalDist = rObj.optInt("distance", 0)
            val totalDur = rObj.optInt("duration", 60)
            val stepsArr = rObj.optJSONArray("steps") ?: continue
            val steps = ArrayList<NavStep>(stepsArr.length())
            val allLats = ArrayList<Double>(256)
            val allLons = ArrayList<Double>(256)
            for (i in 0 until stepsArr.length()) {
                val s = stepsArr.getJSONObject(i)
                val inst = s.optString("instruction").replace(htmlTagRegex, "").trim()
                val road = s.optString("road_name")
                val dist = s.optInt("distance", 0)
                val endLoc = s.optJSONObject("end_location")
                val eLat = endLoc?.optDouble("lat", dLat) ?: dLat
                val eLon = endLoc?.optDouble("lng", dLon) ?: dLon
                val pStart = allLats.size
                val pathStr = s.optString("path")
                if (pathStr.isNotBlank()) {
                    for (pt in pathStr.split(';')) {
                        val kv = pt.split(',')
                        val lo = kv.getOrNull(0)?.toDoubleOrNull()
                        val la = kv.getOrNull(1)?.toDoubleOrNull()
                        if (la != null && lo != null) {
                            allLats.add(la)
                            allLons.add(lo)
                        }
                    }
                } else {
                    allLats.add(eLat)
                    allLons.add(eLon)
                }
                val pEnd = (allLats.size - 1).coerceAtLeast(pStart)
                steps.add(NavStep(inst, parseTurnAction(inst), road, dist, eLat, eLon, pStart, pEnd))
            }
            if (steps.isNotEmpty()) {
                val lbl = if (rIdx == 0) "推荐路线" else "备选路线${rIdx + 1}"
                result.add(
                    NavRoute(
                        "baidu", destName, dLat, dLon, totalDist, totalDur, steps,
                        usesGcj02 = true, routeLabel = lbl,
                        polyLats = allLats.toDoubleArray(), polyLons = allLons.toDoubleArray()
                    )
                )
            }
        }
        return result
    }

    private fun planWithOsrm(
        mode: String,
        oWgsLat: Double,
        oWgsLon: Double,
        dest: String
    ): List<NavRoute> {
        val encDest = URLEncoder.encode(dest, "UTF-8")
        val minLon = oWgsLon - 0.6
        val maxLon = oWgsLon + 0.6
        val minLat = oWgsLat - 0.6
        val maxLat = oWgsLat + 0.6
        val viewboxParam = "%.4f,%.4f,%.4f,%.4f".format(Locale.US, minLon, maxLat, maxLon, minLat)
        val geoBody = httpGet(
            "https://nominatim.openstreetmap.org/search?q=$encDest&format=json&limit=5&viewbox=$viewboxParam"
        ) ?: run {
            _state.value = NavUiState(statusText = "OSM 地理编码请求超时，请检查网络")
            return emptyList()
        }
        val geoArr = JSONArray(geoBody)
        if (geoArr.length() == 0) {
            _state.value = NavUiState(statusText = "OSM 未找到目的地「$dest」，可尝试更完整地名或切换高德/腾讯 Key")
            return emptyList()
        }
        val g0 = geoArr.getJSONObject(0)
        val dLat = g0.optString("lat").toDoubleOrNull() ?: return emptyList()
        val dLon = g0.optString("lon").toDoubleOrNull() ?: return emptyList()
        val destName = g0.optString("display_name").substringBefore(',').ifBlank { dest }

        val profile = when (mode) {
            "walking" -> "foot"
            "bicycling" -> "bike"
            else -> "driving"
        }
        val url = "https://router.project-osrm.org/route/v1/$profile/%.6f,%.6f;%.6f,%.6f?steps=true&geometries=polyline&overview=false&alternatives=$MAX_CACHED_ROUTES"
            .format(oWgsLon, oWgsLat, dLon, dLat)
        val body = httpGet(url) ?: run {
            _state.value = NavUiState(statusText = "OSRM 路线请求超时")
            return emptyList()
        }
        val root = JSONObject(body)
        if (root.optString("code") != "Ok") {
            _state.value = NavUiState(statusText = "OSRM 规划失败：${root.optString("message")}")
            return emptyList()
        }
        val routes = root.optJSONArray("routes") ?: return emptyList()
        val result = ArrayList<NavRoute>(minOf(routes.length(), MAX_CACHED_ROUTES))
        for (rIdx in 0 until minOf(routes.length(), MAX_CACHED_ROUTES)) {
            val rObj = routes.optJSONObject(rIdx) ?: continue
            val totalDist = rObj.optDouble("distance", 0.0).toInt()
            val totalDur = rObj.optDouble("duration", 60.0).toInt()
            val stepsArr = rObj.optJSONArray("legs")?.optJSONObject(0)?.optJSONArray("steps") ?: continue
            val steps = ArrayList<NavStep>(stepsArr.length())
            val allLats = ArrayList<Double>(256)
            val allLons = ArrayList<Double>(256)
            for (i in 0 until stepsArr.length()) {
                val s = stepsArr.getJSONObject(i)
                val man = s.optJSONObject("maneuver")
                val mType = man?.optString("type").orEmpty()
                val mMod = man?.optString("modifier").orEmpty()
                val locArr = man?.optJSONArray("location")
                val eLon = locArr?.optDouble(0, dLon) ?: dLon
                val eLat = locArr?.optDouble(1, dLat) ?: dLat
                val road = s.optString("name")
                val dist = s.optDouble("distance", 0.0).toInt()
                val act = parseOsrmAction(mType, mMod)
                val inst = if (road.isNotBlank()) "${act.label}进入 $road" else act.label
                val pStart = allLats.size
                val geom = s.optString("geometry")
                if (geom.isNotBlank()) {
                    val (dLats, dLons) = decodeEncodedPolyline(geom)
                    for (k in dLats.indices) {
                        allLats.add(dLats[k])
                        allLons.add(dLons[k])
                    }
                } else {
                    allLats.add(eLat)
                    allLons.add(eLon)
                }
                val pEnd = (allLats.size - 1).coerceAtLeast(pStart)
                val stepEndLat = allLats.lastOrNull() ?: eLat
                val stepEndLon = allLons.lastOrNull() ?: eLon
                steps.add(NavStep(inst, act, road, dist, stepEndLat, stepEndLon, pStart, pEnd))
            }
            if (steps.isNotEmpty()) {
                val lbl = if (rIdx == 0) "推荐路线" else "备选路线${rIdx + 1}"
                result.add(
                    NavRoute(
                        "osrm", destName, dLat, dLon, totalDist, totalDur, steps,
                        usesGcj02 = false, routeLabel = lbl,
                        polyLats = allLats.toDoubleArray(), polyLons = allLons.toDoubleArray()
                    )
                )
            }
        }
        return result
    }

    // ==================== 转向解析 / 360° 真北罗盘指示点图标 / 坐标计算 ====================

    private fun parseTurnAction(raw: String): TurnAction {
        val t = raw.lowercase()
        return when {
            t.contains("到达") || t.contains("终点") || t.contains("arrive") -> TurnAction.ARRIVE
            t.contains("调头") || t.contains("掉头") || t.contains("uturn") -> TurnAction.UTURN
            t.contains("左前") || t.contains("向左前") -> TurnAction.SLIGHT_LEFT
            t.contains("右前") || t.contains("向右前") -> TurnAction.SLIGHT_RIGHT
            t.contains("左") || t.contains("left") -> TurnAction.LEFT
            t.contains("右") || t.contains("right") -> TurnAction.RIGHT
            else -> TurnAction.STRAIGHT
        }
    }

    private fun parseOsrmAction(type: String, mod: String): TurnAction {
        if (type == "arrive") return TurnAction.ARRIVE
        return when {
            mod.contains("uturn") -> TurnAction.UTURN
            mod.contains("slight left") -> TurnAction.SLIGHT_LEFT
            mod.contains("slight right") -> TurnAction.SLIGHT_RIGHT
            mod.contains("left") -> TurnAction.LEFT
            mod.contains("right") -> TurnAction.RIGHT
            else -> TurnAction.STRAIGHT
        }
    }

    private fun buildTurnArrowBitmap(action: TurnAction, relativeBearingDeg: Float?): Bitmap {
        val size = 72
        val center = size / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (action == TurnAction.ARRIVE) Color.parseColor("#FF10B981") else Color.parseColor("#FF1A66FF")
            style = Paint.Style.FILL
        }
        c.drawCircle(center, center, center, bgPaint)

        if (relativeBearingDeg != null && action != TurnAction.ARRIVE) {
            val orbitRadius = size * 0.42f
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(70, 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = 1.8f
            }
            c.drawCircle(center, center, orbitRadius, ringPaint)

            val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 255, 255, 255)
                style = Paint.Style.FILL
            }
            val topY = center - orbitRadius
            val tickPath = Path().apply {
                moveTo(center, topY - 4.2f)
                lineTo(center - 2.8f, topY - 0.5f)
                lineTo(center + 2.8f, topY - 0.5f)
                close()
            }
            c.drawPath(tickPath, tickPaint)

            val rawNormDeg = ((relativeBearingDeg % 360f) + 360f) % 360f
            val absSigned = if (rawNormDeg > 180f) 360f - rawNormDeg else rawNormDeg
            val normDeg = if (absSigned <= 2.5f) 0f else rawNormDeg
            val rad = Math.toRadians((normDeg - 90f).toDouble())
            val dotX = center + orbitRadius * cos(rad).toFloat()
            val dotY = center + orbitRadius * sin(rad).toFloat()
            val dotColor = when {
                absSigned <= 10f -> Color.parseColor("#FF34D399")
                absSigned < 120f -> Color.parseColor("#FFFBBF24")
                else -> Color.parseColor("#FFF87171")
            }
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(95, Color.red(dotColor), Color.green(dotColor), Color.blue(dotColor))
                style = Paint.Style.FILL
            }
            c.drawCircle(dotX, dotY, 7.2f, glowPaint)
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            c.drawCircle(dotX, dotY, 5.2f, borderPaint)
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = dotColor
                style = Paint.Style.FILL
            }
            c.drawCircle(dotX, dotY, 3.8f, dotPaint)
        }

        val arrowScale = (size / 96f) * 0.76f
        val offset = (size - 96f * arrowScale) / 2f
        c.save()
        c.translate(offset, offset)
        c.scale(arrowScale, arrowScale)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        when (action) {
            TurnAction.LEFT -> {
                path.moveTo(60f, 74f); path.lineTo(60f, 40f); path.lineTo(28f, 40f)
                path.moveTo(40f, 28f); path.lineTo(26f, 40f); path.lineTo(40f, 52f)
            }
            TurnAction.RIGHT -> {
                path.moveTo(36f, 74f); path.lineTo(36f, 40f); path.lineTo(68f, 40f)
                path.moveTo(56f, 28f); path.lineTo(70f, 40f); path.lineTo(56f, 52f)
            }
            TurnAction.SLIGHT_LEFT -> {
                path.moveTo(56f, 74f); path.lineTo(56f, 48f); path.lineTo(32f, 26f)
                path.moveTo(48f, 26f); path.lineTo(32f, 26f); path.lineTo(32f, 42f)
            }
            TurnAction.SLIGHT_RIGHT -> {
                path.moveTo(40f, 74f); path.lineTo(40f, 48f); path.lineTo(64f, 26f)
                path.moveTo(48f, 26f); path.lineTo(64f, 26f); path.lineTo(64f, 42f)
            }
            TurnAction.UTURN -> {
                path.moveTo(64f, 74f); path.lineTo(64f, 36f)
                path.quadTo(64f, 20f, 48f, 20f); path.quadTo(32f, 20f, 32f, 36f); path.lineTo(32f, 64f)
                path.moveTo(20f, 52f); path.lineTo(32f, 66f); path.lineTo(44f, 52f)
            }
            TurnAction.ARRIVE -> {
                path.moveTo(28f, 50f); path.lineTo(44f, 66f); path.lineTo(70f, 32f)
            }
            TurnAction.STRAIGHT -> {
                path.moveTo(48f, 74f); path.lineTo(48f, 24f)
                path.moveTo(34f, 38f); path.lineTo(48f, 22f); path.lineTo(62f, 38f)
            }
        }
        c.drawPath(path, p)
        c.restore()
        bmp.prepareToDraw()
        return bmp
    }

    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0)).toFloat()
    }

    private fun compassCardinal(deg: Float): String {
        val dirs = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        val idx = (((deg + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)
        return dirs[idx]
    }

    private fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) {
            return lat to lon
        }
        val a = 6378245.0
        val ee = 0.00669342162296594323
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - ee * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (a / sqrtMagic * cos(radLat) * PI)
        return (lat + dLat) to (lon + dLon)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return (2.0 * r * atan2(sqrt(a), sqrt(1.0 - a))).toInt().coerceAtLeast(0)
    }

    fun formatMetersShort(meters: Int): String {
        return if (meters >= 1000) {
            "%.1fkm".format(meters / 1000.0)
        } else {
            "${meters}m"
        }
    }

    fun providerLabel(provider: String): String {
        return when (provider.lowercase()) {
            "amap" -> "高德地图"
            "tencent" -> "腾讯地图"
            "baidu" -> "百度地图"
            else -> "OSRM免Key"
        }
    }

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "MagicCapsule/1.0 (Android)")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
