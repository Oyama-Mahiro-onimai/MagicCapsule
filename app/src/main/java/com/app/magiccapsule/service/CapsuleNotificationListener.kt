package com.app.magiccapsule.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.app.magiccapsule.capsule.CapsulePublisher
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.data.CapsulePrefs
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.model.CapturedNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通知监听服务（省电 + 胶囊/横幅统一图标与交互逻辑版）：
 * - 后台息屏或应用界面不可见时跳过 activeNotifications 列表全量扫描，显著降低 Binder IPC 与耗电
 * - 统一提取通知大图标/发起应用图标（96px 圆角 Bitmap LRU 缓存）与 Notification.actions，
 *   同时供荣耀原生灵动胶囊（状态栏胶囊图标 + 展开卡片）与顶层横幅共享，避免重复解码
 */
class CapsuleNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        private var instance: CapsuleNotificationListener? = null

        private val worker = Executors.newSingleThreadExecutor()
        private val appNameCache = ConcurrentHashMap<String, String>()
        private val reloadScheduled = AtomicBoolean(false)
        private val uiVisible = AtomicBoolean(false)

        @Volatile
        private var lastReloadMs: Long = 0L

        private val _notifications = MutableStateFlow<List<CapturedNotification>>(emptyList())
        val notifications: StateFlow<List<CapturedNotification>> = _notifications.asStateFlow()

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _pinnedKey = MutableStateFlow<String?>(null)
        val pinnedKey: StateFlow<String?> = _pinnedKey.asStateFlow()

        private val _pinnedPackage = MutableStateFlow<String?>(null)

        private val _activeSourcePackage = MutableStateFlow<String?>(null)
        val activeSourcePackage: StateFlow<String?> = _activeSourcePackage.asStateFlow()

        private val _activeSourceKey = MutableStateFlow<String?>(null)

        private val _lastInterceptedSummary = MutableStateFlow("尚未捕获到新通知")
        val lastInterceptedSummary: StateFlow<String> = _lastInterceptedSummary.asStateFlow()

        @Volatile
        private var lastNonWhitelistAutoPushMs: Long = 0L

        @Volatile
        private var lastPushedSignature: String? = null

        @Volatile
        private var lastBannerSignature: String? = null

        @Volatile
        private var lastBannerTimeMs: Long = 0L

        @Volatile
        private var lastDirectCapsuleSignature: String? = null

        @Volatile
        private var lastDirectCapsuleTimeMs: Long = 0L

        private val suppressedKeys = ConcurrentHashMap.newKeySet<String>()

        fun setUiVisible(visible: Boolean) {
            uiVisible.set(visible)
            if (visible) {
                refreshSnapshot(force = true)
            }
        }

        fun pinNotification(key: String?, packageName: String? = null) {
            if (key != null) suppressedKeys.remove(key)
            _pinnedKey.value = key
            _pinnedPackage.value = if (key != null) packageName else null
            lastPushedSignature = null
            if (key != null && packageName != null) {
                _activeSourceKey.value = key
                _activeSourcePackage.value = packageName
            }
        }

        fun unpinAndDismiss(context: Context) {
            _pinnedKey.value?.let { suppressedKeys.add(it) }
            _activeSourceKey.value?.let { suppressedKeys.add(it) }
            _pinnedKey.value = null
            _pinnedPackage.value = null
            CapsulePublisher.dismiss(context, reason = "已取消锁定并关闭胶囊")
            runCatching {
                context.stopService(Intent(context, CapsuleKeepAliveService::class.java))
            }
        }

        fun clearActiveSource() {
            _activeSourceKey.value = null
            _activeSourcePackage.value = null
            lastPushedSignature = null
        }

        fun isPermissionGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val target = ComponentName(context, CapsuleNotificationListener::class.java)
            return enabled.contains(target.flattenToString()) ||
                enabled.contains(target.flattenToShortString()) ||
                enabled.contains(context.packageName)
        }

        fun refreshSnapshot(force: Boolean = false) {
            instance?.scheduleReloadActiveNotifications(force)
        }

        fun triggerActionFromList(context: Context, notificationKey: String, actionIndex: Int): Boolean {
            val payload = NotificationIconStore.getPayload(notificationKey) ?: return false
            val act = payload.actions.getOrNull(actionIndex) ?: return false
            if (!act.hasRemoteInput && act.intent != null) {
                val ok = runCatching {
                    act.intent.send()
                    true
                }.getOrDefault(false)
                if (ok) return true
            }
            val fallback = payload.fullScreenIntent ?: payload.contentIntent
            if (fallback != null) {
                return runCatching {
                    fallback.send()
                    true
                }.getOrDefault(false)
            }
            return runCatching {
                val launch = context.packageManager.getLaunchIntentForPackage(payload.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    true
                } else false
            }.getOrDefault(false)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        _connected.value = true
        AppLogger.i("Notif", "NotificationListenerService 已连接")
        scheduleReloadActiveNotifications(force = true)
    }

    override fun onListenerDisconnected() {
        _connected.value = false
        AppLogger.w("Notif", "NotificationListenerService 已断开")
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val opPkg = runCatching { sbn.opPkg }.getOrNull().orEmpty()
        if (sbn.packageName == packageName || opPkg == packageName) return

        worker.execute {
            runCatching { handleNotificationPostedAsync(sbn) }
        }
    }

    private fun handleNotificationPostedAsync(sbn: StatusBarNotification) {
        // 省电：仅在应用界面处于前台可见时才同步通知选择器快照
        if (uiVisible.get()) {
            scheduleReloadActiveNotifications(force = false)
        }

        val item = toCaptured(sbn) ?: return
        val prefs = CapsulePrefs(this).load()
        val isWhitelisted = item.packageName in prefs.autoWhitelistPackages
        val sig = "${item.packageName}|${item.key}|${item.title}|${item.text}"

        val nowStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _lastInterceptedSummary.value = "$nowStr · [${item.importanceLabel()}] ${item.appName} (${item.packageName})"
        AppLogger.i(
            "Notif",
            "收到通知 app=${item.appName} pkg=${item.packageName} tier=${item.importanceLabel()}(imp=${item.importance}) call=${item.isCall} ongoing=${item.isOngoing} ch=${item.channelName}"
        )

        // 统一解析图标（带 96px LRU 缓存）与通知交互按钮（含 CallStyle 接听/挂断），供胶囊和横幅共同复用
        val iconBitmap = NotificationIconStore.resolveNotificationBitmap(this, sbn)
        val bannerActions = extractNotificationActions(sbn, isCall = item.isCall)
        val fullScreenPi = sbn.notification?.fullScreenIntent
        val clickPi = if (item.isCall) (fullScreenPi ?: sbn.notification?.contentIntent) else sbn.notification?.contentIntent
        val payload = ActiveMirroredPayload(
            key = item.key,
            packageName = item.packageName,
            appName = item.appName,
            title = item.title,
            text = item.text,
            iconBitmap = iconBitmap,
            contentIntent = clickPi,
            actions = bannerActions,
            fullScreenIntent = fullScreenPi,
            isCall = item.isCall,
            importance = item.importance,
            channelName = item.channelName
        )
        NotificationIconStore.putPayload(payload)

        val isScreenOn = runCatching { getSystemService(PowerManager::class.java)?.isInteractive == true }
            .getOrDefault(true)

        // 来电完全透传保护（参考 wechat-notify-evo CALL_OR_ONGOING_PASSTHROUGH）：
        // - 未开启屏蔽系统横幅时：100% 透传，不弹覆盖横幅也不弹胶囊，完全不遮挡微信/QQ 原生来电接听浮窗
        // - 开启了屏蔽系统横幅时：立即临时恢复系统横幅，并用 Android 14+ BAL 合规方式尝试拉起通话界面；不叠加胶囊防抢焦点
        if (item.isCall) {
            AppLogger.i("Notif", "CALL_PASSTHROUGH app=${item.appName} suppress=${prefs.suppressSystemHeadsUp} actions=${bannerActions.size} hasFullScreen=${fullScreenPi != null}")
            if (!prefs.suppressSystemHeadsUp) {
                // 纯透传：直接交给系统与微信/QQ 原生来电界面，绝不遮挡接听按钮
                return
            }
            SystemHeadsUpController.suspendSuppressionForCall(this, item.key)
            var launchedCallUi = false
            if (prefs.autoLaunchCallFullScreen && fullScreenPi != null) {
                launchedCallUi = sendPendingIntentFromService(fullScreenPi)
                AppLogger.i("Notif", "尝试拉起来电通话界面 app=${item.appName} ok=$launchedCallUi")
            }
            // 若全屏来电界面未能直接拉起，则弹出带「接听/进入通话」按钮的救援横幅（不叠发胶囊防止遮挡）
            if (!launchedCallUi && isScreenOn && prefs.enableOverlayBanner) {
                dispatchOverlayBanner(payload, prefs)
            }
            return
        }

        // 统一链路：优先处理手动锁定通知（保持常驻直到用户取消或原通知移除）
        val currentPinned = _pinnedKey.value
        val currentPinnedPkg = _pinnedPackage.value
        if (currentPinned != null && item.key == currentPinned &&
            (currentPinnedPkg == null || item.packageName == currentPinnedPkg)
        ) {
            if (sig != lastPushedSignature) {
                lastPushedSignature = sig
                pushToCapsule(payload, prefs = prefs, isPinned = true)
            }
            return
        }

        val acquireMode = prefs.capsuleAcquireMode
        val passesUnifiedAlertFilter = shouldTriggerUnifiedAlert(item, prefs, isWhitelisted, sig)

        // 1. 顶层覆盖横幅（亮屏且通过统一过滤时弹出）
        if (passesUnifiedAlertFilter && isScreenOn) {
            dispatchOverlayBanner(payload, prefs)
        }

        // 2. 荣耀灵动胶囊：按用户选择的「通知获取方式」决定是否触发
        if (currentPinned == null && item.key !in suppressedKeys) {
            val shouldTriggerCapsule = when (acquireMode) {
                CapsuleConfig.ACQUIRE_MANUAL_PIN_ONLY -> false
                CapsuleConfig.ACQUIRE_DIRECT_LISTENER ->
                    prefs.autoMirrorNotifications && shouldTriggerDirectCapsule(item, prefs, isWhitelisted, sig)
                else -> // ACQUIRE_UNIFIED_BANNER
                    prefs.autoMirrorNotifications && passesUnifiedAlertFilter
            }
            if (shouldTriggerCapsule && (isScreenOn || !prefs.capsuleAutoDismiss)) {
                lastPushedSignature = sig
                pushToCapsule(payload, prefs = prefs, isPinned = false)
            }
        }
    }

    private fun isPositiveCallLabel(label: String): Boolean {
        val lower = label.lowercase()
        return lower.contains("接听") || lower.contains("接受") || lower.contains("同意") ||
            lower.contains("answer") || lower.contains("accept")
    }

    private fun isDestructiveCallLabel(label: String): Boolean {
        val lower = label.lowercase()
        return lower.contains("挂断") || lower.contains("拒绝") || lower.contains("拒接") ||
            lower.contains("decline") || lower.contains("hang up") || lower.contains("reject")
    }

    @Suppress("DEPRECATION")
    private fun extractNotificationActions(sbn: StatusBarNotification, isCall: Boolean = false): List<BannerAction> {
        val n = sbn.notification ?: return emptyList()
        val result = ArrayList<BannerAction>()
        val seenTitles = HashSet<String>()

        // Android 12+ CallStyle 专用接听 / 拒绝 / 挂断 PendingIntent
        val extras = n.extras
        if (extras != null) {
            val answerPi = extras.getParcelable<PendingIntent>("android.answerIntent")
            if (answerPi != null && seenTitles.add("接听")) {
                result.add(BannerAction("接听", answerPi, hasRemoteInput = false, isPositive = true))
            }
            val declinePi = extras.getParcelable<PendingIntent>("android.declineIntent")
            if (declinePi != null && seenTitles.add("挂断")) {
                result.add(BannerAction("挂断", declinePi, hasRemoteInput = false, isDestructive = true))
            }
            val hangUpPi = extras.getParcelable<PendingIntent>("android.hangUpIntent")
            if (hangUpPi != null && seenTitles.add("挂断")) {
                result.add(BannerAction("挂断", hangUpPi, hasRemoteInput = false, isDestructive = true))
            }
        }

        // 标准 Notification.actions（如标记已读、回复、复制验证码、接听/挂断等）
        n.actions?.forEach { act ->
            val label = act.title?.toString()?.trim().orEmpty()
            if (label.isNotBlank() && seenTitles.add(label)) {
                val hasRemote = !act.remoteInputs.isNullOrEmpty()
                result.add(
                    BannerAction(
                        title = label,
                        intent = act.actionIntent,
                        hasRemoteInput = hasRemote,
                        isPositive = isPositiveCallLabel(label),
                        isDestructive = isDestructiveCallLabel(label)
                    )
                )
            }
        }

        // 桥接 CarExtender 的原生「标为已读」入口（参考 wechat-notify-evo captureBridgeActions）
        if (!isCall) {
            val unread = runCatching { Notification.CarExtender(n).unreadConversation }.getOrNull()
            val readPi = unread?.readPendingIntent
            if (readPi != null && seenTitles.add("标为已读")) {
                result.add(BannerAction("标为已读", readPi, hasRemoteInput = false))
            }
        }

        // 若识别为来电但第三方应用（如旧版微信/QQ自定义界面）未暴露 Action，则提供直达通话界面按钮
        if (isCall && result.isEmpty()) {
            val callPi = n.fullScreenIntent ?: n.contentIntent
            result.add(BannerAction("接听 / 进入通话", callPi, hasRemoteInput = false, isPositive = true))
        }

        return result.take(3)
    }

    /**
     * 复用横幅通知的统一监听过滤链路：
     * - 白名单应用完全免限频、免常驻过滤
     * - 非白名单应用按 whitelistOnly / ignoreOngoing / autoThrottleIntervalSec 过滤，并对 2 秒内相同内容去重
     */
    private fun shouldTriggerUnifiedAlert(
        item: CapturedNotification,
        prefs: CapsuleConfig,
        isWhitelisted: Boolean,
        sig: String
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (sig == lastBannerSignature && now - lastBannerTimeMs < 2000L) {
            AppLogger.i("Notif", "过滤跳过(2s内重复)：${item.appName}")
            return false
        }

        if (!isWhitelisted && !item.isCall) {
            if (prefs.whitelistOnlyInAutoMode) {
                AppLogger.i("Notif", "过滤跳过(仅同步白名单)：${item.packageName}")
                return false
            }
            if (!passesChannelImportance(item, prefs)) {
                AppLogger.i("Notif", "过滤跳过(渠道重要性=${item.importanceLabel()} imp=${item.importance} < ${prefs.minChannelImportance})：${item.packageName}")
                return false
            }
            if (prefs.ignoreOngoingInAutoMode && item.isOngoing) {
                AppLogger.i("Notif", "过滤跳过(常驻通知)：${item.packageName}")
                return false
            }
            val minIntervalMs = prefs.autoThrottleIntervalSec.coerceIn(1, 60) * 1000L
            if (now - lastNonWhitelistAutoPushMs < minIntervalMs) {
                AppLogger.i("Notif", "过滤跳过(频率冷却 ${prefs.autoThrottleIntervalSec}s)：${item.packageName}")
                return false
            }
            lastNonWhitelistAutoPushMs = now
        }
        lastBannerSignature = sig
        lastBannerTimeMs = now
        return true
    }

    private fun dispatchOverlayBanner(
        payload: ActiveMirroredPayload,
        prefs: CapsuleConfig
    ) {
        if (!prefs.enableOverlayBanner) return
        if (!CapsuleOverlayAccessibilityService.connected.value) return
        CapsuleOverlayAccessibilityService.showBanner(
            appName = payload.appName,
            packageName = payload.packageName,
            title = payload.title,
            text = payload.text,
            contentIntent = payload.contentIntent,
            iconBitmap = payload.iconBitmap,
            actions = payload.actions,
            config = prefs,
            notificationKey = payload.key,
            isPersistent = payload.isCall
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null) {
            suppressedKeys.remove(sbn.key)
            NotificationIconStore.removePayload(sbn.key)
            ConversationMessageParser.clearConversation(sbn.key)
            CapsuleOverlayAccessibilityService.dismissBannerIfKey(sbn.key)
            val cfgNow = CapsulePrefs(this).load()
            val shouldReSuppress = cfgNow.enableOverlayBanner &&
                cfgNow.suppressSystemHeadsUp &&
                CapsuleOverlayAccessibilityService.connected.value
            SystemHeadsUpController.resumeSuppressionAfterCall(this, sbn.key, shouldReSuppress)
            val wasPinned = sbn.key == _pinnedKey.value &&
                (_pinnedPackage.value == null || sbn.packageName == _pinnedPackage.value)
            val wasActive = sbn.key == _activeSourceKey.value &&
                sbn.packageName == _activeSourcePackage.value
            if (wasPinned) {
                _pinnedKey.value = null
                _pinnedPackage.value = null
            }
            if (wasActive) {
                clearActiveSource()
            }
            if (wasPinned || wasActive) {
                CapsulePublisher.dismiss(this, reason = "来源通知已清除，胶囊同步销毁")
                runCatching { stopService(Intent(this, CapsuleKeepAliveService::class.java)) }
            }
        }
        if (uiVisible.get()) {
            scheduleReloadActiveNotifications(force = false)
        }
    }

    private fun shouldTriggerDirectCapsule(
        item: CapturedNotification,
        prefs: CapsuleConfig,
        isWhitelisted: Boolean,
        sig: String
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (sig == lastDirectCapsuleSignature && now - lastDirectCapsuleTimeMs < 1000L) {
            return false
        }
        if (!isWhitelisted && !item.isCall) {
            if (prefs.whitelistOnlyInAutoMode) {
                AppLogger.i("Notif", "独立监听跳过(仅白名单)：${item.packageName}")
                return false
            }
            if (!passesChannelImportance(item, prefs)) {
                AppLogger.i("Notif", "独立监听跳过(渠道重要性=${item.importanceLabel()})：${item.packageName}")
                return false
            }
            if (prefs.ignoreOngoingInAutoMode && item.isOngoing) {
                AppLogger.i("Notif", "独立监听跳过(常驻通知)：${item.packageName}")
                return false
            }
        }
        lastDirectCapsuleSignature = sig
        lastDirectCapsuleTimeMs = now
        return true
    }

    private fun pushToCapsule(payload: ActiveMirroredPayload, prefs: CapsuleConfig, isPinned: Boolean) {
        _activeSourceKey.value = payload.key
        _activeSourcePackage.value = payload.packageName
        if (prefs.capsuleDeliveryMode == CapsuleConfig.DELIVERY_FOREGROUND_SERVICE) {
            NotificationIconStore.currentActivePayload = payload
            val intent = Intent(this, CapsuleKeepAliveService::class.java)
                .setAction(CapsuleKeepAliveService.ACTION_MIRROR_NOTIFICATION)
                .putExtra(CapsuleKeepAliveService.EXTRA_PACKAGE_NAME, payload.packageName)
                .putExtra(CapsuleKeepAliveService.EXTRA_APP_NAME, payload.appName)
                .putExtra(CapsuleKeepAliveService.EXTRA_TITLE, payload.title)
                .putExtra(CapsuleKeepAliveService.EXTRA_TEXT, payload.text)
                .putExtra(CapsuleKeepAliveService.EXTRA_IS_PINNED, isPinned)
            val fgsOk = runCatching {
                ContextCompat.startForegroundService(this, intent)
            }.onFailure { e ->
                AppLogger.w("Capsule", "前台服务替换后台启动受限(${e.javaClass.simpleName})，自动回退直接推送")
            }.isSuccess
            if (fgsOk) {
                AppLogger.i("Capsule", "前台服务替换胶囊 app=${payload.appName} pkg=${payload.packageName} pinned=$isPinned")
                return
            }
        }
        CapsulePublisher.publishMirrored(
            context = this,
            baseConfig = prefs,
            payload = payload,
            isPinned = isPinned
        )
    }

    private fun scheduleReloadActiveNotifications(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReloadMs < 600L) return
        if (!reloadScheduled.compareAndSet(false, true)) return
        worker.execute {
            try {
                lastReloadMs = SystemClock.elapsedRealtime()
                val list = runCatching { activeNotifications }.getOrNull() ?: return@execute
                val parsed = list.mapNotNull { sbn ->
                    val cap = toCaptured(sbn) ?: return@mapNotNull null
                    // 同步缓存已有通知的图标与按钮，确保用户点选锁定时立刻带有真实图标
                    val bmp = NotificationIconStore.getPayload(cap.key)?.iconBitmap
                        ?: NotificationIconStore.resolveNotificationBitmap(this, sbn)
                    val acts = extractNotificationActions(sbn, isCall = cap.isCall)
                    val fullPi = sbn.notification?.fullScreenIntent
                    NotificationIconStore.putPayload(
                        ActiveMirroredPayload(
                            key = cap.key,
                            packageName = cap.packageName,
                            appName = cap.appName,
                            title = cap.title,
                            text = cap.text,
                            iconBitmap = bmp,
                            contentIntent = if (cap.isCall) (fullPi ?: sbn.notification?.contentIntent) else sbn.notification?.contentIntent,
                            actions = acts,
                            fullScreenIntent = fullPi,
                            isCall = cap.isCall,
                            importance = cap.importance,
                            channelName = cap.channelName
                        )
                    )
                    cap
                }
                    .sortedWith(compareByDescending<CapturedNotification> { it.importanceRank() }.thenByDescending { it.postTime })
                    .take(25)
                if (parsed != _notifications.value) {
                    _notifications.value = parsed
                }
            } finally {
                reloadScheduled.set(false)
            }
        }
    }

    private fun resolveAppNameCached(pkg: String): String {
        appNameCache[pkg]?.let { return it }
        val resolved = runCatching {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrElse { pkg.substringAfterLast('.') }
        appNameCache[pkg] = resolved
        return resolved
    }

    private fun passesChannelImportance(item: CapturedNotification, prefs: CapsuleConfig): Boolean {
        val minLevel = prefs.minChannelImportance.coerceIn(
            NotificationManager.IMPORTANCE_LOW,
            NotificationManager.IMPORTANCE_HIGH
        )
        if (minLevel <= NotificationManager.IMPORTANCE_LOW) return true
        if (item.isSilent) return false
        return item.importance >= minLevel
    }

    private fun sendPendingIntentFromService(pi: PendingIntent): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                pi.send(this, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
            true
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun detectIsCall(sbn: StatusBarNotification, title: String, text: String, isOngoing: Boolean): Boolean {
        val n = sbn.notification ?: return false
        if (n.category == Notification.CATEGORY_CALL) return true
        val extras = n.extras
        if (extras != null) {
            if (extras.containsKey("android.answerIntent") ||
                extras.containsKey("android.declineIntent") ||
                extras.containsKey("android.hangUpIntent") ||
                extras.containsKey("android.callPerson")
            ) return true
            val tpl = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
            if (tpl.contains("CallStyle", ignoreCase = true)) return true
        }
        val actHasCall = n.actions?.any { act ->
            val t = act.title?.toString()?.trim().orEmpty()
            isPositiveCallLabel(t) || isDestructiveCallLabel(t)
        } == true
        if (actHasCall) return true

        if (n.fullScreenIntent != null) return true
        val channelIdLower = n.channelId.orEmpty().lowercase()
        if (channelIdLower.contains("voip") || channelIdLower.contains("call") || channelIdLower.contains("ring")) {
            return true
        }
        val combined = "$title $text"
        val looksLikeRinging = combined.contains("邀请你语音通话") ||
            combined.contains("邀请你视频通话") ||
            combined.contains("来电") ||
            combined.contains("正在呼叫") ||
            combined.contains("等待对方接听") ||
            (isOngoing && (combined.contains("语音通话") || combined.contains("视频通话") || combined.contains("通话中")))
        if (looksLikeRinging) return true
        return false
    }

    @Suppress("DEPRECATION")
    private fun toCaptured(sbn: StatusBarNotification): CapturedNotification? {
        val opPkg = runCatching { sbn.opPkg }.getOrNull().orEmpty().ifBlank { sbn.packageName }
        if (sbn.packageName == packageName || opPkg == packageName) return null
        val n = sbn.notification ?: return null
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null

        val extras = n.extras
        val callerName = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras?.getParcelable("android.callPerson", Person::class.java)?.name?.toString()
            } else {
                extras?.getParcelable<Person>("android.callPerson")?.name?.toString()
            }
        }.getOrNull()?.trim().orEmpty()

        val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            .ifBlank { callerName }
        val rawText = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()

        val isOngoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        val isCall = detectIsCall(sbn, rawTitle, rawText, isOngoing)

        if (!isCall && rawTitle.isBlank() && rawText.isBlank()) return null

        val ranking = Ranking()
        val hasRanking = runCatching { currentRanking?.getRanking(sbn.key, ranking) == true }.getOrDefault(false)
        val importance = if (hasRanking && ranking.importance != NotificationManager.IMPORTANCE_UNSPECIFIED) {
            ranking.importance
        } else {
            if (isCall) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
        }
        val channelName = if (hasRanking) {
            ranking.channel?.name?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        val isSilent = !isCall && (
            importance <= NotificationManager.IMPORTANCE_LOW ||
                (hasRanking && ranking.isAmbient)
        )

        val appName = resolveAppNameCached(sbn.packageName)
        val parsed = ConversationMessageParser.parse(
            convKey = sbn.key,
            notification = n,
            rawTitle = rawTitle,
            rawText = rawText,
            appName = appName,
            isCall = isCall
        )
        val finalTitle = parsed.cleanTitle
        val finalText = parsed.cleanText
        val actionTitles = extractNotificationActions(sbn, isCall = isCall).map { it.title }

        return CapturedNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            opPkg = opPkg,
            notificationId = sbn.id,
            appName = appName,
            title = finalTitle,
            text = finalText,
            postTime = sbn.postTime,
            isOngoing = isOngoing,
            importance = importance,
            channelName = channelName,
            isCall = isCall,
            isSilent = isSilent,
            actionTitles = actionTitles
        )
    }
}
