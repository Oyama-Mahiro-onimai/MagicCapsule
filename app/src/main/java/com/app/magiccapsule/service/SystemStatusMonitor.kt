package com.app.magiccapsule.service

import android.app.ActivityManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.res.Configuration
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import com.app.magiccapsule.capsule.CapsulePublisher
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.data.CapsulePrefs
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.model.SystemStatusSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 低功耗、可自定义的 Shizuku 设备状态监控引擎：
 * 1. 自定义事件触发弹窗：充放电、低电量阈值、高温/温控阈值、响铃切换、网络切换、刷新率变化、高内存占用、Shizuku Doze 变化
 * 2. 常时监控模式：亮屏时按自定义间隔常驻刷新胶囊并展示用户勾选的指标，息屏自动休眠零耗电
 * 3. 零进程直读（BatteryManager / DisplayManager / PowerManager / ConnectivityManager）+ Shizuku 单次合并查询与 15s TTL 缓存
 */
object SystemStatusMonitor {

    private const val SHIZUKU_CACHE_TTL_MS = 60_000L
    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val worker get() = SoCThreadPools.monitorIoExecutor
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listenersRegistered = AtomicBoolean(false)
    private val shizukuBusy = AtomicBoolean(false)

    private val _snapshot = MutableStateFlow(SystemStatusSnapshot())
    val snapshot: StateFlow<SystemStatusSnapshot> = _snapshot.asStateFlow()

    private val _recentBroadcastEvents = MutableStateFlow<List<String>>(emptyList())
    val recentBroadcastEvents: StateFlow<List<String>> = _recentBroadcastEvents.asStateFlow()

    private fun recordBroadcastEvent(title: String) {
        val stamp = runCatching { LocalTime.now().format(timeFmt) }.getOrDefault("--:--:--")
        val entry = "$stamp · $title"
        val next = (listOf(entry) + _recentBroadcastEvents.value).take(10)
        _recentBroadcastEvents.value = next
    }

    @Volatile private var lastShizukuQueryMs: Long = 0L
    @Volatile private var cachedDoze: String = "--"
    @Volatile private var cachedCpuTemp: String = "--"
    @Volatile private var cachedThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
    @Volatile private var cachedRefreshHz: Float = 0f
    @Volatile private var cachedNetLabel: String = ""
    @Volatile private var cachedDownMbps: Int = 0

    // 防重复刷屏去重标记
    @Volatile private var lowBatteryAlertedThisCycle: Boolean = false
    @Volatile private var lastTempAlertMs: Long = 0L
    @Volatile private var lastHighRamAlertMs: Long = 0L
    @Volatile private var lastHzAlertValue: Int = -1
    @Volatile private var lastNetAlertLabel: String = ""
    @Volatile private var lastDozeAlertValue: String = ""
    @Volatile private var lastRingerMode: Int = -1
    @Volatile private var monitorStartedElapsedMs: Long = 0L
    @Volatile private var lastBatteryBroadcastMs: Long = 0L
    @Volatile private var lastBatteryPct: Int = -1
    @Volatile private var lastHzAlertMs: Long = 0L
    @Volatile private var lastNetAlertMs: Long = 0L
    @Volatile private var lastAlertedTempC: Float = 0f
    private var pendingHzDebounceTask: Runnable? = null
    private val lastBroadcastElapsedByAction = ConcurrentHashMap<String, Long>()
    @Volatile private var lastNightModeMask: Int = -1
    @Volatile private var lastDndFilter: Int = -1
    @Volatile private var lastHeadsetState: Int = -1
    @Volatile private var lastPowerSaveOn: Boolean? = null
    @Volatile private var lastLocationEnabled: Boolean? = null
    @Volatile private var packageReceiverRegistered: Boolean = false

    private var registeredContext: Context? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var continuousRunnable: Runnable? = null

    private fun shouldThrottleBroadcast(action: String, minGapMs: Long = 1500L): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - monitorStartedElapsedMs < 2500L) return true
        val prev = lastBroadcastElapsedByAction[action] ?: 0L
        if (now - prev < minGapMs) return true
        lastBroadcastElapsedByAction[action] = now
        return false
    }

    private val packageEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            val cfg = CapsulePrefs(context).load()
            if (!cfg.enableSystemStatusMonitor || !cfg.sysTriggerAllBroadcasts || !cfg.sysTriggerAppStorage) return
            val pkg = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
            if (pkg == context.packageName) return
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (action == Intent.ACTION_PACKAGE_REMOVED && replacing) return
            if (shouldThrottleBroadcast("$action:$pkg", 2000L)) return
            worker.execute {
                val snap = collectSnapshot(context, forceShizuku = false)
                val appLabel = runCatching {
                    val pm = context.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                val title = when (action) {
                    Intent.ACTION_PACKAGE_ADDED -> if (replacing) "应用已更新：$appLabel" else "已安装新应用：$appLabel"
                    Intent.ACTION_PACKAGE_REPLACED -> "应用已更新：$appLabel"
                    Intent.ACTION_PACKAGE_REMOVED -> "已卸载应用：$appLabel"
                    else -> return@execute
                }
                triggerEventCapsule(context, cfg, title, snap)
            }
        }
    }

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            // 忽略注册广播时系统立即补发的粘性广播（防止刚开开关就误报）
            if (isInitialStickyBroadcast) { AppLogger.d("Notif", "忽略粘性系统广播：$action"); return }
            val cfg = CapsulePrefs(context).load()
            if (!cfg.enableSystemStatusMonitor) return
            worker.execute {
                val snap = collectSnapshot(context, forceShizuku = false)
                when (action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        lowBatteryAlertedThisCycle = false
                        if (cfg.sysTriggerPowerChange) {
                            val title = "已接入电源 · ${snap.batteryPercent}% (${snap.chargeType})"
                            triggerEventCapsule(context, cfg, title, snap)
                        }
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        if (cfg.sysTriggerPowerChange) {
                            val title = "已断开电源 · 剩余 ${snap.batteryPercent}%"
                            triggerEventCapsule(context, cfg, title, snap)
                        }
                    }
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                        val curMode = runCatching {
                            context.getSystemService(AudioManager::class.java)?.ringerMode ?: -1
                        }.getOrDefault(-1)
                        val changed = lastRingerMode != -1 && curMode != -1 && curMode != lastRingerMode
                        if (curMode != -1) lastRingerMode = curMode
                        if (cfg.sysTriggerRingerChange && changed) {
                            val title = "响铃模式切换：${snap.ringerLabel}"
                            triggerEventCapsule(context, cfg, title, snap)
                        }
                    }
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val nowEl = SystemClock.elapsedRealtime()
                        if (snap.batteryPercent != lastBatteryPct || nowEl - lastBatteryBroadcastMs >= 15_000L) {
                            lastBatteryPct = snap.batteryPercent
                            lastBatteryBroadcastMs = nowEl
                            evaluateThresholdTriggers(context, cfg, snap)
                        }
                    }
                    else -> {
                        if (!cfg.sysTriggerAllBroadcasts) return@execute
                        handleExtendedSystemBroadcast(context, cfg, intent, action, snap)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun handleExtendedSystemBroadcast(
        context: Context,
        cfg: CapsuleConfig,
        intent: Intent,
        action: String,
        snap: SystemStatusSnapshot
    ) {
        val title: String? = when (action) {
            Intent.ACTION_BATTERY_LOW -> if (cfg.sysTriggerPowerSave && !shouldThrottleBroadcast(action, 30_000L)) "系统低电量警告 · ${snap.batteryPercent}%" else null
            Intent.ACTION_BATTERY_OKAY -> if (cfg.sysTriggerPowerSave && !shouldThrottleBroadcast(action, 30_000L)) "电量已恢复正常 · ${snap.batteryPercent}%" else null
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                val on = runCatching { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }.getOrDefault(false)
                val changed = lastPowerSaveOn != null && lastPowerSaveOn != on
                lastPowerSaveOn = on
                if (cfg.sysTriggerPowerSave && changed && !shouldThrottleBroadcast(action)) {
                    if (on) "省电模式已开启" else "省电模式已关闭"
                } else null
            }
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                val idle = runCatching { context.getSystemService(PowerManager::class.java)?.isDeviceIdleMode == true }.getOrDefault(false)
                if (cfg.sysTriggerDozeChange && !shouldThrottleBroadcast(action, 5000L)) {
                    if (idle) "系统已进入 Doze 休眠" else "系统已退出 Doze 休眠"
                } else null
            }
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                val on = intent.getBooleanExtra("state", false)
                if (cfg.sysTriggerWifiAirplane && !shouldThrottleBroadcast(action)) {
                    if (on) "飞行模式已开启" else "飞行模式已关闭"
                } else null
            }
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                val st = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                when (st) {
                    WifiManager.WIFI_STATE_ENABLED -> if (cfg.sysTriggerWifiAirplane && !shouldThrottleBroadcast("$action:on")) "Wi-Fi 已开启" else null
                    WifiManager.WIFI_STATE_DISABLED -> if (cfg.sysTriggerWifiAirplane && !shouldThrottleBroadcast("$action:off")) "Wi-Fi 已关闭" else null
                    else -> null
                }
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (st) {
                    BluetoothAdapter.STATE_ON -> if (cfg.sysTriggerBluetooth && !shouldThrottleBroadcast("$action:on")) "蓝牙已开启" else null
                    BluetoothAdapter.STATE_OFF -> if (cfg.sysTriggerBluetooth && !shouldThrottleBroadcast("$action:off")) "蓝牙已关闭" else null
                    else -> null
                }
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val devName = runCatching {
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.name
                }.getOrNull().orEmpty().ifBlank { "蓝牙设备" }
                if (cfg.sysTriggerBluetooth && !shouldThrottleBroadcast("$action:$devName", 3000L)) "蓝牙已连接：$devName" else null
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val devName = runCatching {
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.name
                }.getOrNull().orEmpty().ifBlank { "蓝牙设备" }
                if (cfg.sysTriggerBluetooth && !shouldThrottleBroadcast("$action:$devName", 3000L)) "蓝牙已断开：$devName" else null
            }
            NfcAdapter.ACTION_ADAPTER_STATE_CHANGED -> {
                val st = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF)
                when (st) {
                    NfcAdapter.STATE_ON -> if (cfg.sysTriggerSystemToggles && !shouldThrottleBroadcast("$action:on")) "NFC 已开启" else null
                    NfcAdapter.STATE_OFF -> if (cfg.sysTriggerSystemToggles && !shouldThrottleBroadcast("$action:off")) "NFC 已关闭" else null
                    else -> null
                }
            }
            LocationManager.MODE_CHANGED_ACTION -> {
                val enabled = runCatching {
                    context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
                }.getOrDefault(false)
                val changed = lastLocationEnabled != null && lastLocationEnabled != enabled
                lastLocationEnabled = enabled
                if (cfg.sysTriggerSystemToggles && changed && !shouldThrottleBroadcast(action)) {
                    if (enabled) "系统定位服务已开启" else "系统定位服务已关闭"
                } else null
            }
            AudioManager.ACTION_HEADSET_PLUG -> {
                val state = intent.getIntExtra("state", -1)
                val changed = lastHeadsetState != -1 && state != -1 && state != lastHeadsetState
                if (state != -1) lastHeadsetState = state
                if (cfg.sysTriggerHeadsetAudio && changed && !shouldThrottleBroadcast(action)) {
                    val name = intent.getStringExtra("name")?.takeIf { it.isNotBlank() } ?: "有线耳机"
                    if (state == 1) "$name 已插入" else "$name 已拔出"
                } else null
            }
            AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                if (cfg.sysTriggerHeadsetAudio && !shouldThrottleBroadcast(action, 3000L)) "音频输出已切换至扬声器" else null
            }
            NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                val filter = runCatching {
                    context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ?: -1
                }.getOrDefault(-1)
                val changed = lastDndFilter != -1 && filter != -1 && filter != lastDndFilter
                if (filter != -1) lastDndFilter = filter
                if (cfg.sysTriggerDnd && changed && !shouldThrottleBroadcast(action)) {
                    when (filter) {
                        NotificationManager.INTERRUPTION_FILTER_ALL -> "勿扰模式已关闭"
                        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "勿扰模式已开启（仅优先）"
                        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "勿扰模式已开启（仅闹钟）"
                        NotificationManager.INTERRUPTION_FILTER_NONE -> "勿扰模式已开启（完全静音）"
                        else -> "勿扰状态已变更"
                    }
                } else null
            }
            Intent.ACTION_DOCK_EVENT -> {
                val dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_UNDOCKED)
                if (cfg.sysTriggerAppStorage && !shouldThrottleBroadcast(action)) {
                    if (dockState == Intent.EXTRA_DOCK_STATE_UNDOCKED) "已断开底座连接" else "已连接外接底座"
                } else null
            }
            Intent.ACTION_DEVICE_STORAGE_LOW -> if (cfg.sysTriggerAppStorage && !shouldThrottleBroadcast(action, 60_000L)) "设备存储空间不足" else null
            Intent.ACTION_DEVICE_STORAGE_OK -> if (cfg.sysTriggerAppStorage && !shouldThrottleBroadcast(action, 60_000L)) "设备存储空间已恢复" else null
            Intent.ACTION_TIMEZONE_CHANGED -> if (cfg.sysTriggerSystemToggles && !shouldThrottleBroadcast(action, 5000L)) "系统时区已更新" else null
            Intent.ACTION_LOCALE_CHANGED -> if (cfg.sysTriggerSystemToggles && !shouldThrottleBroadcast(action, 5000L)) "系统语言区域已变更" else null
            Intent.ACTION_CONFIGURATION_CHANGED -> {
                val curNight = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                val changed = lastNightModeMask != -1 && curNight != lastNightModeMask
                lastNightModeMask = curNight
                if (cfg.sysTriggerSystemToggles && changed && !shouldThrottleBroadcast("$action:night", 2000L)) {
                    if (curNight == Configuration.UI_MODE_NIGHT_YES) "已切换至深色模式" else "已切换至浅色模式"
                } else null
            }
            else -> null
        }
        if (!title.isNullOrBlank()) {
            triggerEventCapsule(context, cfg, title, snap)
        }
    }

    fun syncMonitorRegistration(context: Context, config: CapsuleConfig) {
        val appCtx = context.applicationContext
        val needActive = config.enableSystemStatusMonitor || config.sysContinuousMonitor
        if (needActive) {
            if (listenersRegistered.compareAndSet(false, true)) {
                registeredContext = appCtx
                monitorStartedElapsedMs = SystemClock.elapsedRealtime()
                lastRingerMode = runCatching {
                    appCtx.getSystemService(AudioManager::class.java)?.ringerMode ?: -1
                }.getOrDefault(-1)
                lastNightModeMask = appCtx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                lastDndFilter = runCatching {
                    appCtx.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ?: -1
                }.getOrDefault(-1)
                lastPowerSaveOn = runCatching {
                    appCtx.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
                }.getOrNull()
                lastLocationEnabled = runCatching {
                    appCtx.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
                }.getOrNull()
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                    addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction(Intent.ACTION_BATTERY_LOW)
                    addAction(Intent.ACTION_BATTERY_OKAY)
                    addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                    addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
                    addAction(LocationManager.MODE_CHANGED_ACTION)
                    addAction(AudioManager.ACTION_HEADSET_PLUG)
                    addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                    addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                    addAction(Intent.ACTION_DOCK_EVENT)
                    addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
                    addAction(Intent.ACTION_DEVICE_STORAGE_OK)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_LOCALE_CHANGED)
                    addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                }
                runCatching {
                    ContextCompat.registerReceiver(appCtx, systemEventReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
                }
                val pkgFilter = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addDataScheme("package")
                }
                runCatching {
                    ContextCompat.registerReceiver(appCtx, packageEventReceiver, pkgFilter, ContextCompat.RECEIVER_EXPORTED)
                    packageReceiverRegistered = true
                }

                runCatching {
                    val pm = appCtx.getSystemService(PowerManager::class.java)
                    cachedThermalStatus = pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                    val tl = PowerManager.OnThermalStatusChangedListener { status ->
                        cachedThermalStatus = status
                        worker.execute {
                            val cfg = CapsulePrefs(appCtx).load()
                            if (cfg.enableSystemStatusMonitor && cfg.sysTriggerThermalAlert &&
                                status >= PowerManager.THERMAL_STATUS_MODERATE
                            ) {
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastTempAlertMs >= 300_000L) {
                                    lastTempAlertMs = now
                                    val snap = collectSnapshot(appCtx, forceShizuku = false)
                                    triggerEventCapsule(
                                        appCtx, cfg,
                                        "温控预警：${snap.thermalLabel} (${"%.1f".format(snap.batteryTempC)}°C)",
                                        snap
                                    )
                                }
                            }
                        }
                    }
                    pm?.addThermalStatusListener(worker, tl)
                    thermalListener = tl
                }

                runCatching {
                    val dm = appCtx.getSystemService(DisplayManager::class.java)
                    cachedRefreshHz = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f
                    lastHzAlertValue = cachedRefreshHz.roundToInt()
                    val dl = object : DisplayManager.DisplayListener {
                        override fun onDisplayAdded(displayId: Int) = Unit
                        override fun onDisplayRemoved(displayId: Int) = Unit
                        override fun onDisplayChanged(displayId: Int) {
                            if (displayId == Display.DEFAULT_DISPLAY) {
                                val hz = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: cachedRefreshHz
                                cachedRefreshHz = hz
                                val hzInt = hz.roundToInt()
                                // LTPO 屏幕触控会在 90Hz/120Hz 间毫秒级跳变：防抖 1.5s + 15s 冷却，彻底杜绝「发胶囊->拉高120Hz->再发胶囊」正反馈死循环
                                pendingHzDebounceTask?.let { mainHandler.removeCallbacks(it) }
                                val task = Runnable {
                                    pendingHzDebounceTask = null
                                    val settledHz = (dm?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: cachedRefreshHz).roundToInt()
                                    val now = SystemClock.elapsedRealtime()
                                    if (settledHz > 1 && settledHz != lastHzAlertValue && now - lastHzAlertMs >= 15_000L) {
                                        lastHzAlertValue = settledHz
                                        lastHzAlertMs = now
                                        worker.execute {
                                            val cfg = CapsulePrefs(appCtx).load()
                                            if (cfg.enableSystemStatusMonitor && cfg.sysTriggerRefreshRateChange) {
                                                // 仅在导航进行中避让；媒体播放期间允许短时插播并自动恢复
                                                if (CapsuleNavigationEngine.isRunning()) return@execute
                                                val snap = collectSnapshot(appCtx, forceShizuku = false)
                                                triggerEventCapsule(appCtx, cfg, "刷新率切换：${settledHz}Hz", snap)
                                            }
                                        }
                                    }
                                }
                                pendingHzDebounceTask = task
                                mainHandler.postDelayed(task, 1500L)
                            }
                        }
                    }
                    dm?.registerDisplayListener(dl, mainHandler)
                    displayListener = dl
                }

                runCatching {
                    val cm = appCtx.getSystemService(ConnectivityManager::class.java)
                    val nc = object : ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                            val label = resolveNetworkLabel(caps)
                            cachedNetLabel = label
                            cachedDownMbps = (caps.linkDownstreamBandwidthKbps / 1000).coerceAtLeast(0)
                            val nowNet = SystemClock.elapsedRealtime()
                            if (lastNetAlertLabel.isNotBlank() && label != lastNetAlertLabel && nowNet - lastNetAlertMs >= 10_000L) {
                                lastNetAlertMs = nowNet
                                worker.execute {
                                    val cfg = CapsulePrefs(appCtx).load()
                                    if (cfg.enableSystemStatusMonitor && cfg.sysTriggerNetworkChange) {
                                        val snap = collectSnapshot(appCtx, forceShizuku = false)
                                        triggerEventCapsule(appCtx, cfg, "网络切换：$label", snap)
                                    }
                                }
                            }
                            lastNetAlertLabel = label
                        }

                        override fun onLost(network: Network) {
                            cachedNetLabel = "离线"
                            cachedDownMbps = 0
                            if (lastNetAlertLabel.isNotBlank() && lastNetAlertLabel != "离线") {
                                worker.execute {
                                    val cfg = CapsulePrefs(appCtx).load()
                                    if (cfg.enableSystemStatusMonitor && cfg.sysTriggerNetworkChange) {
                                        val snap = collectSnapshot(appCtx, forceShizuku = false)
                                        triggerEventCapsule(appCtx, cfg, "网络已断开", snap)
                                    }
                                }
                            }
                            lastNetAlertLabel = "离线"
                        }
                    }
                    cm?.registerDefaultNetworkCallback(nc)
                    networkCallback = nc
                }

                AppLogger.i("Notif", "已开启自定义系统状态事件监听")
            }
        } else {
            if (listenersRegistered.compareAndSet(true, false)) {
                val ctx = registeredContext ?: appCtx
                runCatching { ctx.unregisterReceiver(systemEventReceiver) }
                if (packageReceiverRegistered) {
                    runCatching { ctx.unregisterReceiver(packageEventReceiver) }
                    packageReceiverRegistered = false
                }
                thermalListener?.let { tl ->
                    runCatching { ctx.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(tl) }
                }
                pendingHzDebounceTask?.let { mainHandler.removeCallbacks(it) }
                pendingHzDebounceTask = null
                displayListener?.let { dl ->
                    runCatching { ctx.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(dl) }
                }
                networkCallback?.let { nc ->
                    runCatching { ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(nc) }
                }
                thermalListener = null
                displayListener = null
                networkCallback = null
                registeredContext = null
                AppLogger.i("Notif", "已关闭系统状态事件监听")
            }
        }

        // 同步常时监控定时刷新器（仅亮屏运行，息屏自动休眠）
        syncContinuousLoop(appCtx, config)
    }

    private fun syncContinuousLoop(appCtx: Context, config: CapsuleConfig) {
        continuousRunnable?.let { mainHandler.removeCallbacks(it) }
        continuousRunnable = null
        if (!config.sysContinuousMonitor) return

        val task = object : Runnable {
            override fun run() {
                val cfgNow = CapsulePrefs(appCtx).load()
                if (!cfgNow.sysContinuousMonitor) return
                val isScreenOn = runCatching {
                    appCtx.getSystemService(PowerManager::class.java)?.isInteractive == true
                }.getOrDefault(false)
                if (isScreenOn) {
                    worker.execute {
                        val snap = collectSnapshot(appCtx, forceShizuku = false)
                        if (cfgNow.enableSystemStatusMonitor) {
                            evaluateThresholdTriggers(appCtx, cfgNow, snap)
                        }
                        // 若用户当前未手动锁定其他通知（如音乐），则常驻刷新系统状态胶囊
                        if (!CapsuleNotificationListener.isMediaOrRecorderActive()) {
                            val title = buildCustomTitle(snap, cfgNow)
                            val detail = buildCustomSummaryLine(snap, cfgNow)
                            pushStatusTextToCapsule(
                                context = appCtx,
                                config = cfgNow,
                                biz = cfgNow.sysCapsuleTitlePrefix.ifBlank { "设备监控" },
                                title = title,
                                detail = detail,
                                pinned = true
                            )
                        }
                    }
                }
                val intervalMs = cfgNow.sysMonitorIntervalSec.coerceIn(3, 60) * 1000L
                mainHandler.postDelayed(this, intervalMs)
            }
        }
        continuousRunnable = task
        mainHandler.post(task)
    }

    private fun evaluateThresholdTriggers(context: Context, cfg: CapsuleConfig, snap: SystemStatusSnapshot) {
        val now = SystemClock.elapsedRealtime()
        // 开启监听后前 3 秒静默建立基线，避免刚打开开关就连续刷屏弹窗
        if (now - monitorStartedElapsedMs < 3_000L) return
        // 1. 低电量自定义阈值
        if (cfg.sysTriggerLowBattery && !snap.isCharging && snap.batteryPercent in 1..cfg.sysLowBatteryThreshold) {
            if (!lowBatteryAlertedThisCycle) {
                lowBatteryAlertedThisCycle = true
                triggerEventCapsule(context, cfg, "低电量提醒：${snap.batteryPercent}%（≤${cfg.sysLowBatteryThreshold}%）", snap)
                return
            }
        } else if (snap.batteryPercent > cfg.sysLowBatteryThreshold + 3) {
            lowBatteryAlertedThisCycle = false
        }

        // 2. 高温自定义阈值（60 秒冷却）
        if (cfg.sysTriggerThermalAlert && snap.batteryTempC >= cfg.sysThermalTempThresholdC.toFloat()) {
            val jumpedHotter = snap.batteryTempC - lastAlertedTempC >= 2.0f
            if (now - lastTempAlertMs >= 300_000L || (jumpedHotter && now - lastTempAlertMs >= 60_000L)) {
                lastTempAlertMs = now
                lastAlertedTempC = snap.batteryTempC
                triggerEventCapsule(
                    context, cfg,
                    "温度提醒：${"%.1f".format(snap.batteryTempC)}°C（≥${cfg.sysThermalTempThresholdC}°C）",
                    snap
                )
                return
            }
        }

        // 3. 高内存自定义阈值（90 秒冷却）
        if (cfg.sysTriggerHighRam && snap.ramTotalGb > 0.1f) {
            val ramPct = ((snap.ramUsedGb / snap.ramTotalGb) * 100f).roundToInt()
            if (ramPct >= cfg.sysHighRamThresholdPercent && now - lastHighRamAlertMs >= 90_000L) {
                lastHighRamAlertMs = now
                triggerEventCapsule(context, cfg, "内存占用偏高：${ramPct}%（≥${cfg.sysHighRamThresholdPercent}%）", snap)
                return
            }
        }

        // 4. Shizuku Doze 状态变化
        if (cfg.sysTriggerDozeChange && snap.shizukuEnhanced && snap.dozeState != "--" && snap.dozeState != "需 Shizuku") {
            if (lastDozeAlertValue.isNotBlank() && snap.dozeState != lastDozeAlertValue) {
                triggerEventCapsule(context, cfg, "Doze 状态变更：${snap.dozeState}", snap)
            }
            lastDozeAlertValue = snap.dozeState
        }
    }

    private fun triggerEventCapsule(context: Context, cfg: CapsuleConfig, title: String, snap: SystemStatusSnapshot) {
        recordBroadcastEvent(title)
        if (CapsuleNavigationEngine.isRunning()) {
            AppLogger.d("Notif", "设备监控事件避让当前导航胶囊：$title")
            return
        }
        val mediaActive = CapsuleNotificationListener.isMediaOrRecorderActive()
        val detail = buildCustomSummaryLine(snap, cfg)
        if (mediaActive) {
            val holdSec = cfg.capsuleHoldDurationSec.coerceIn(3, 8)
            CapsulePublisher.markSysEventPreemptWindow(holdSec * 1000L)
            AppLogger.i("Notif", "设备状态事件短时插播媒体胶囊(${holdSec}s 后自动恢复)：$title | $detail")
        } else {
            AppLogger.i("Notif", "设备状态事件触发胶囊：$title | $detail")
        }
        pushStatusTextToCapsule(
            context = context,
            config = cfg,
            biz = cfg.sysCapsuleTitlePrefix.ifBlank { "设备监控" },
            title = title,
            detail = detail,
            pinned = if (mediaActive) false else cfg.sysContinuousMonitor
        )
    }

    fun refreshAsync(context: Context, forceShizuku: Boolean = true, onDone: ((SystemStatusSnapshot) -> Unit)? = null) {
        val appCtx = context.applicationContext
        worker.execute {
            val snap = collectSnapshot(appCtx, forceShizuku = forceShizuku)
            onDone?.invoke(snap)
        }
    }

    fun pushCurrentStatusToCapsule(context: Context, config: CapsuleConfig, pinned: Boolean = config.sysContinuousMonitor) {
        val appCtx = context.applicationContext
        worker.execute {
            val snap = collectSnapshot(appCtx, forceShizuku = true)
            val title = buildCustomTitle(snap, config)
            val detail = buildCustomSummaryLine(snap, config)
            pushStatusTextToCapsule(
                context = appCtx,
                config = config,
                biz = config.sysCapsuleTitlePrefix.ifBlank { "设备监控" },
                title = title,
                detail = detail,
                pinned = pinned
            )
        }
    }

    private fun pushStatusTextToCapsule(
        context: Context,
        config: CapsuleConfig,
        biz: String,
        title: String,
        detail: String,
        pinned: Boolean
    ) {
        val effectiveCfg = if (config.sysMonitorDualTextCarousel) {
            config.copy(template = CapsuleConfig.TEMPLATE_IMAGE_TEXT_TEXT)
        } else {
            config
        }
        val payload = ActiveMirroredPayload(
            key = "sys_status_snapshot",
            packageName = context.packageName,
            appName = biz,
            title = title,
            text = detail,
            iconBitmap = NotificationIconStore.resolveManualIconBitmap(context, effectiveCfg),
            contentIntent = null,
            actions = emptyList()
        )
        if (effectiveCfg.enableCapsuleEngine) {
            CapsulePublisher.publishMirrored(
                context = context,
                baseConfig = effectiveCfg,
                payload = payload,
                isPinned = pinned
            )
        }
        // 设备监控状态与变化事件同步复用至通用悬浮横幅；若当前正在播放媒体则走中断服务，展示结束后从断点恢复歌词/读秒/进度条
        val canUseUniversalBanner = (effectiveCfg.enableOverlayBanner || effectiveCfg.enableUniversalBannerMode) &&
            UniversalBannerHost.canDrawOverlay(context)
        if (canUseUniversalBanner) {
            val mediaActive = CapsuleNotificationListener.isMediaOrRecorderActive() ||
                UniversalBannerHost.isMediaBannerActive()
            if (mediaActive || !pinned) {
                val holdSec = effectiveCfg.capsuleHoldDurationSec.coerceIn(3, 8)
                UniversalBannerHost.showInterruptBanner(
                    context = context,
                    appName = biz,
                    title = title,
                    text = detail,
                    iconBitmap = payload.iconBitmap,
                    config = effectiveCfg,
                    holdDurationMs = holdSec * 1000L,
                    collapsibleToPill = pinned
                )
            } else {
                UniversalBannerHost.showBanner(
                    context = context,
                    appName = biz,
                    packageName = context.packageName,
                    title = title,
                    text = detail,
                    contentIntent = null,
                    iconBitmap = payload.iconBitmap,
                    actions = emptyList(),
                    config = effectiveCfg,
                    notificationKey = "sys_status_snapshot",
                    isPersistent = true,
                    collapsibleToPill = true,
                    isMedia = false
                )
            }
        }
    }

    fun buildCustomTitle(s: SystemStatusSnapshot, cfg: CapsuleConfig): String {
        if (cfg.sysMonitorDualTextCarousel) {
            // 双文案轮播第一段（主文案）：紧凑展示电量 + 实时电流（如 23% -837mA）
            val mainParts = mutableListOf<String>()
            if (cfg.sysShowBatteryCurrent) mainParts.add("${s.batteryPercent}% ${s.batteryCurrentMa}")
            else mainParts.add("${s.batteryPercent}%")
            if (!cfg.sysShowTempThermal && !cfg.sysShowRefreshRate && cfg.sysShowRam && s.ramTotalGb > 0.1f) {
                val pct = ((s.ramUsedGb / s.ramTotalGb) * 100f).roundToInt()
                mainParts.add("RAM $pct%")
            }
            return mainParts.joinToString(" ")
        }
        val parts = mutableListOf<String>()
        if (cfg.sysShowBatteryCurrent) parts.add("${s.batteryPercent}% ${s.batteryCurrentMa}")
        if (cfg.sysShowTempThermal) parts.add("${"%.1f".format(s.batteryTempC)}°C")
        if (cfg.sysShowRefreshRate && s.refreshRateLabel != "--") parts.add(s.refreshRateLabel)
        return if (parts.isEmpty()) "${s.batteryPercent}% · ${s.chargeType}" else parts.joinToString(" · ")
    }

    fun buildCustomSummaryLine(s: SystemStatusSnapshot, cfg: CapsuleConfig): String {
        val validDoze = s.dozeState.trim().takeIf {
            it.isNotBlank() && it != "--" && it != "需 Shizuku"
        }
        if (cfg.sysMonitorDualTextCarousel) {
            // 双文案轮播第二段（副文案）：紧凑展示温度 + 刷新率（如 32.1°C 120Hz）+ 可选内存/网络
            val subParts = mutableListOf<String>()
            if (cfg.sysShowTempThermal) subParts.add("${"%.1f".format(s.batteryTempC)}°C")
            if (cfg.sysShowRefreshRate && s.refreshRateLabel != "--") subParts.add(s.refreshRateLabel)
            if (cfg.sysShowRam && s.ramTotalGb > 0.1f) {
                val pct = ((s.ramUsedGb / s.ramTotalGb) * 100f).roundToInt()
                subParts.add("RAM$pct%")
            }
            if (cfg.sysShowNetwork) subParts.add(s.networkLabel)
            if (cfg.sysShowDoze && s.shizukuEnhanced && validDoze != null) subParts.add(validDoze)
            return if (subParts.isEmpty()) "${s.chargeType} ${s.networkLabel}" else subParts.joinToString(" ")
        }
        val parts = mutableListOf<String>()
        if (cfg.sysShowRam && s.ramTotalGb > 0.1f) {
            val pct = ((s.ramUsedGb / s.ramTotalGb) * 100f).roundToInt()
            parts.add("RAM $pct%(${"%.1f".format(s.ramUsedGb)}G)")
        }
        if (cfg.sysShowTempThermal) {
            val cpuPart = if (s.shizukuEnhanced && s.cpuTempLabel != "--" && s.cpuTempLabel != "需 Shizuku") {
                " CPU${s.cpuTempLabel}"
            } else {
                ""
            }
            parts.add("${s.thermalLabel}$cpuPart")
        }
        if (cfg.sysShowNetwork) {
            val bw = if (s.linkSpeedMbps > 0) " ${s.linkSpeedMbps}M" else ""
            parts.add("${s.networkLabel}$bw")
        }
        if (cfg.sysShowDoze && s.shizukuEnhanced && validDoze != null) {
            parts.add("Doze:$validDoze")
        }
        return if (parts.isEmpty()) {
            "${s.chargeType} · ${s.ringerLabel} · 音量${s.mediaVolumePercent}%"
        } else {
            parts.joinToString(" · ")
        }
    }

    private fun resolveNetworkLabel(caps: NetworkCapabilities?): String {
        if (caps == null) return "离线"
        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val base = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线网络"
            isVpn -> "VPN"
            else -> "已联网"
        }
        return if (isVpn && base != "VPN") "$base·VPN" else base
    }

    private fun thermalStatusToLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "正常"
        PowerManager.THERMAL_STATUS_LIGHT -> "微热"
        PowerManager.THERMAL_STATUS_MODERATE -> "偏热"
        PowerManager.THERMAL_STATUS_SEVERE -> "过热"
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "高温预警"
        else -> "正常"
    }

    fun collectSnapshot(context: Context, forceShizuku: Boolean = false): SystemStatusSnapshot {
        val battIntent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
        val status = battIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = battIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val chargeType = when {
            !isCharging -> "未充电"
            plugged == BatteryManager.BATTERY_PLUGGED_AC -> "交流快充"
            plugged == BatteryManager.BATTERY_PLUGGED_USB -> "USB 充电"
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
            else -> "充电中"
        }
        val tempC = (battIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val voltageMv = battIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val bm = context.getSystemService(BatteryManager::class.java)
        val rawCurrent = runCatching { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE }
            .getOrDefault(Int.MIN_VALUE)
        val currentMa = if (rawCurrent != Int.MIN_VALUE && rawCurrent != 0) {
            val ma = if (abs(rawCurrent) > 10_000) rawCurrent / 1000 else rawCurrent
            "${ma}mA"
        } else {
            "${voltageMv}mV"
        }

        val dm = context.getSystemService(DisplayManager::class.java)
        val hz = runCatching { dm?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: cachedRefreshHz }
            .getOrDefault(cachedRefreshHz)
        cachedRefreshHz = hz
        val refreshLabel = if (hz > 1f) "${hz.roundToInt()}Hz" else "--"

        val pm = context.getSystemService(PowerManager::class.java)
        val thermalStatus = runCatching { pm?.currentThermalStatus ?: cachedThermalStatus }.getOrDefault(cachedThermalStatus)
        cachedThermalStatus = thermalStatus
        val thermalLabel = thermalStatusToLabel(thermalStatus)

        val am = context.getSystemService(ActivityManager::class.java)
        val memInfo = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memInfo) }
        val totalGb = memInfo.totalMem / (1024f * 1024f * 1024f)
        val usedGb = ((memInfo.totalMem - memInfo.availMem).coerceAtLeast(0L)) / (1024f * 1024f * 1024f)

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val netLabel = resolveNetworkLabel(caps).ifBlank { cachedNetLabel.ifBlank { "离线" } }
        val downMbps = if (caps != null) (caps.linkDownstreamBandwidthKbps / 1000).coerceAtLeast(0) else cachedDownMbps
        cachedNetLabel = netLabel
        cachedDownMbps = downMbps

        val audio = context.getSystemService(AudioManager::class.java)
        val ringerLabel = when (audio?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "静音"
            AudioManager.RINGER_MODE_VIBRATE -> "震动"
            else -> "响铃"
        }
        val curVol = runCatching { audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0 }.getOrDefault(0)
        val maxVol = runCatching { audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1 }.getOrDefault(1).coerceAtLeast(1)
        val volPct = (curVol * 100 / maxVol).coerceIn(0, 100)

        val shizukuReady = ShizukuBridge.binderAlive.value && ShizukuBridge.permissionGranted.value
        val cfgNow = runCatching { CapsulePrefs(context).load() }.getOrNull()
        val needShizukuShell = forceShizuku || cfgNow == null || cfgNow.sysShowDoze || cfgNow.sysTriggerDozeChange
        val nowElapsed = SystemClock.elapsedRealtime()
        if (shizukuReady && needShizukuShell &&
            (forceShizuku || nowElapsed - lastShizukuQueryMs >= SHIZUKU_CACHE_TTL_MS) &&
            shizukuBusy.compareAndSet(false, true)
        ) {
            try {
            lastShizukuQueryMs = nowElapsed
            val (ok, out) = ShizukuBridge.runShellCommand(
                "sh", "-c",
                "echo $(dumpsys deviceidle get deep 2>/dev/null | head -n 1)|$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null | head -n 1)"
            )
            if (ok && out.isNotBlank()) {
                val parts = out.split("|", limit = 2)
                val doze = parts.getOrNull(0)?.trim().orEmpty()
                if (doze.isNotBlank()) cachedDoze = doze
                val rawTemp = parts.getOrNull(1)?.trim()?.toFloatOrNull()
                if (rawTemp != null && rawTemp > 0f) {
                    val c = if (rawTemp > 1000f) rawTemp / 1000f else rawTemp
                    if (c in 10f..110f) cachedCpuTemp = "${"%.1f".format(c)}°C"
                }
            }
            } finally {
                shizukuBusy.set(false)
            }
        }

        val snap = SystemStatusSnapshot(
            batteryPercent = pct,
            isCharging = isCharging,
            chargeType = chargeType,
            batteryTempC = tempC,
            batteryVoltageMv = voltageMv,
            batteryCurrentMa = currentMa,
            thermalLabel = thermalLabel,
            refreshRateLabel = refreshLabel,
            ramUsedGb = usedGb,
            ramTotalGb = totalGb,
            networkLabel = netLabel,
            linkSpeedMbps = downMbps,
            ringerLabel = ringerLabel,
            mediaVolumePercent = volPct,
            shizukuEnhanced = shizukuReady,
            dozeState = if (shizukuReady) cachedDoze else "需 Shizuku",
            cpuTempLabel = if (shizukuReady) cachedCpuTemp else "需 Shizuku",
            updatedAt = LocalTime.now().format(timeFmt)
        )
        val prev = _snapshot.value
        val meaningfulChange = prev.batteryPercent != snap.batteryPercent ||
            prev.isCharging != snap.isCharging ||
            prev.chargeType != snap.chargeType ||
            kotlin.math.abs(prev.batteryTempC - snap.batteryTempC) >= 0.4f ||
            prev.thermalLabel != snap.thermalLabel ||
            prev.refreshRateLabel != snap.refreshRateLabel ||
            kotlin.math.abs(prev.ramUsedGb - snap.ramUsedGb) >= 0.1f ||
            prev.networkLabel != snap.networkLabel ||
            prev.dozeState != snap.dozeState ||
            prev.shizukuEnhanced != snap.shizukuEnhanced ||
            prev.batteryCurrentMa != snap.batteryCurrentMa
        if (meaningfulChange) {
            _snapshot.value = snap
        }
        return snap
    }
}
