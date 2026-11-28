package com.app.magiccapsule.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.app.magiccapsule.capsule.CapsulePublisher
import com.app.magiccapsule.capsule.DeviceBrandRouter
import com.app.magiccapsule.capsule.honor.MagicCapsuleBridge
import com.app.magiccapsule.capsule.vivo.VivoSuperXBridge
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

        private val worker get() = SoCThreadPools.notifExecutor
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

        // 自动拦截的媒体播放 / 录音机通知（不覆盖用户手动锁定，移除或停止时自动收回胶囊）
        @Volatile
        private var autoMediaKey: String? = null
        @Volatile
        private var autoMediaPackage: String? = null
        private var activeSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
        // 监听所有活跃 MediaSession 的播放状态变更（修复切回酷狗等已有会话时 onActiveSessionsChanged 不回调的问题）
        private val watchedSessionCallbacks = ConcurrentHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

        // 流氓媒体假通知防伪：5 秒时间窗口重复无变化更新计数器 + 30 秒临时黑名单
        private data class MediaSpamState(
            val stateSig: String,
            val timestamps: ArrayDeque<Long>
        )
        private val mediaSpamTracker = ConcurrentHashMap<String, MediaSpamState>()
        private val tempMediaBlacklistUntilMs = ConcurrentHashMap<String, Long>()
        private const val MEDIA_SPAM_WINDOW_MS = 5_000L
        private const val MEDIA_SPAM_MAX_UNCHANGED_HITS = 2
        private const val MEDIA_TEMP_BLACKLIST_MS = 30_000L

        // 切歌防延迟/防旧图错乱控制：单调递增切歌代数 + 150ms 元数据防抖合并 + 曲目标识校验
        private val trackSwitchGeneration = AtomicLong(0L)
        @Volatile
        private var pendingMetadataCommitTask: Runnable? = null
        @Volatile
        private var lastCommittedTrackIdentity: String = ""
        private const val METADATA_DEBOUNCE_MS = 150L

        private fun extractMetadataTrackIdentity(pkg: String, metadata: MediaMetadata?): String {
            if (metadata == null) return ""
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
            val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim().orEmpty()
            val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?.trim()
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)?.trim()
                ?: ""
            val artBmp = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            val artHash = if (artBmp != null && !artBmp.isRecycled) {
                NotificationIconStore.bitmapFingerprint(artBmp)
            } else 0
            return "$pkg|$mediaId|$title|$artUri|$artHash"
        }

        /**
         * 切歌瞬间（按钮点击或检测到新歌标题/ID变化）：
         * 1. 强制清空 NotificationIconStore.currentActivePayload，杜绝读取上一首缓存
         * 2. 递增切歌代数并取消旧防抖任务，废弃上一首尚未返回的异步封面加载
         * 3. 立即将横幅封面置为默认占位图、文本置为「加载中...」并绑定当前曲目 View Tag
         */
        private fun triggerImmediateTrackSwitchPlaceholder(
            context: Context,
            notifKey: String?,
            pkg: String,
            appName: String,
            newTitle: String = ""
        ): Long {
            val gen = trackSwitchGeneration.incrementAndGet()
            pendingMetadataCommitTask?.let { mediaProgressHandler.removeCallbacks(it) }
            pendingMetadataCommitTask = null
            NotificationIconStore.currentActivePayload = null
            lastPushedSignature = ""
            lastPushedIconHash = 0
            lastQuantizedStep = -1
            lastPushedSecond = -1L
            val trackToken = "trk_$gen"
            val resolvedKey = notifKey ?: pinnedMediaNotifKey ?: autoMediaKey ?: _activeSourceKey.value
            val resolvedApp = appName.ifBlank { pkg }
            UniversalBannerHost.onTrackSwitchingPlaceholder(
                context = context,
                key = resolvedKey,
                packageName = pkg,
                appName = resolvedApp,
                newTitle = newTitle,
                trackToken = trackToken
            )
            CapsuleOverlayAccessibilityService.onTrackSwitchingPlaceholder(
                context = context,
                key = resolvedKey,
                packageName = pkg,
                appName = resolvedApp,
                newTitle = newTitle,
                trackToken = trackToken
            )
            return gen
        }

        private fun isTrackedMediaKey(notifKey: String): Boolean {
            return _pinnedKey.value == notifKey || autoMediaKey == notifKey
        }

        /**
         * 判断当前是否有活跃的手动锁定、媒体播放或录音机胶囊正在展示（用于让设备监控自动避让，不抢占媒体胶囊）
         */
        /**
         * 临时普通通知胶囊到期后，若当前仍有活跃的媒体播放/录音任务，自动恢复媒体胶囊而不直接销毁。
         */
        fun restoreActiveMediaCapsuleIfNeeded(context: Context): Boolean {
            val svc = instance ?: return false
            val mKey = autoMediaKey ?: return false
            val prefs = runCatching { CapsulePrefs(context).load() }.getOrNull() ?: return false
            if (!prefs.enableCapsuleEngine || !prefs.enableMediaRecorderCapsule) return false
            val cached = NotificationIconStore.getPayload(mKey)
            val isStillPlaying = cached != null && (cached.isRecorder || (cached.isMedia && cached.mediaPlaying))
            if (!isStillPlaying || cached == null) return false
            SoCThreadPools.notifExecutor.execute {
                val latest = NotificationIconStore.getPayload(mKey) ?: cached
                AppLogger.i("Capsule", "临时通知胶囊到期，自动恢复媒体/录音胶囊 app=${latest.appName} pkg=${latest.packageName}")
                svc.pushToCapsule(latest, prefs = prefs, isPinned = true)
            }
            return true
        }

        fun isMediaOrRecorderActive(): Boolean {
            if (CapsuleNavigationEngine.isRunning()) return true
            if (_pinnedKey.value != null) return true
            val amKey = autoMediaKey ?: return false
            return _activeSourceKey.value == amKey || MediaProgressSyncEngine.isActivelyPlaying()
        }

        // 切歌保护期与防抖延迟销毁：防止上/下一曲瞬间出现短暂无媒体活动或通知重发导致胶囊闪退销毁
        @Volatile
        private var lastTrackSkipElapsedMs: Long = 0L
        @Volatile
        private var pendingMediaDismissTask: Runnable? = null

        private fun isActiveOrTransitioningState(state: Int?): Boolean {
            return state == PlaybackState.STATE_PLAYING ||
                state == PlaybackState.STATE_BUFFERING ||
                state == PlaybackState.STATE_CONNECTING ||
                state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
                state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS ||
                state == PlaybackState.STATE_FAST_FORWARDING ||
                state == PlaybackState.STATE_REWINDING
        }

        private fun cancelPendingMediaDismiss() {
            pendingMediaDismissTask?.let { mediaProgressHandler.removeCallbacks(it) }
            pendingMediaDismissTask = null
        }

        private val _activeSourcePackage = MutableStateFlow<String?>(null)
        val activeSourcePackage: StateFlow<String?> = _activeSourcePackage.asStateFlow()

        private val _activeSourceKey = MutableStateFlow<String?>(null)
        val activeSourceKey: StateFlow<String?> = _activeSourceKey.asStateFlow()

        private val _lastInterceptedSummary = MutableStateFlow("尚未捕获到新通知")
        val lastInterceptedSummary: StateFlow<String> = _lastInterceptedSummary.asStateFlow()

        @Volatile
        private var lastNonWhitelistAutoPushMs: Long = 0L

        @Volatile
        private var lastPushedSignature: String? = null

        @Volatile
        private var lastPushedIconHash: Int = 0

        @Volatile
        private var pinnedMediaController: MediaController? = null

        @Volatile
        private var pinnedMediaCallback: MediaController.Callback? = null

        @Volatile
        private var pinnedMediaToken: MediaSession.Token? = null

        fun getPinnedMediaToken(): MediaSession.Token? = pinnedMediaToken
        private var pinnedMediaNotifKey: String? = null

        private val mediaProgressHandler = Handler(Looper.getMainLooper())
        private var mediaProgressTicker: Runnable? = null

        // 本地 PlaybackState 锚点：避免定时轮询 MediaController Binder IPC，本地纯数学插值计算当前进度
        @Volatile private var anchorPosMs: Long = 0L
        @Volatile private var anchorElapsedMs: Long = 0L
        @Volatile private var anchorDurationMs: Long = 0L
        @Volatile private var anchorSpeed: Float = 1f
        @Volatile private var anchorPlaying: Boolean = false
        @Volatile private var lastQuantizedStep: Int = -1
        private var lastPushedSecond: Long = -1L
        private var lastRawStatePosMs: Long = -1L
        private var lastRawStateUpdateTimeMs: Long = -1L
        private var pendingResyncTask1: Runnable? = null
        private var pendingResyncTask2: Runnable? = null
        @Volatile private var optimisticSeekPosMs: Long = -1L
        @Volatile private var optimisticSeekElapsedMs: Long = 0L

        fun stopMediaProgressTicker() {
            mediaProgressTicker?.let { mediaProgressHandler.removeCallbacks(it) }
            mediaProgressTicker = null
            anchorPlaying = false
            lastQuantizedStep = -1
            lastPushedSecond = -1L
        }

        fun detachPinnedMediaObserver() {
            stopMediaProgressTicker()
            LrcCapsuleLyricEngine.stop()
            BannerLrcLyricEngine.stop()
            runCatching {
                val ctrl = pinnedMediaController
                val cb = pinnedMediaCallback
                if (ctrl != null && cb != null) {
                    ctrl.unregisterCallback(cb)
                }
            }
            pinnedMediaController = null
            pinnedMediaCallback = null
            pinnedMediaToken = null
            pinnedMediaNotifKey = null
            pendingResyncTask1?.let { mediaProgressHandler.removeCallbacks(it) }
            pendingResyncTask2?.let { mediaProgressHandler.removeCallbacks(it) }
            pendingResyncTask1 = null
            pendingResyncTask2 = null
            MediaProgressSyncEngine.resetAll()
            optimisticSeekPosMs = -1L
            optimisticSeekElapsedMs = 0L
            lastQuantizedStep = -1
            lastPushedSecond = -1L
        }

        /**
         * 关闭媒体拦截总开关或启动手动自定义文案胶囊时调用：
         * 立即清除残留的自动媒体拦截 key、MediaController 监听、进度定时器与 LRC 歌词调度，
         * 防止残留回调继续覆盖手动文案胶囊。
         */
        fun clearAutoMediaIntercept(context: Context? = null) {
            cancelPendingMediaDismiss()
            val oldAutoKey = autoMediaKey
            autoMediaKey = null
            autoMediaPackage = null
            if (_pinnedKey.value == null) {
                detachPinnedMediaObserver()
            }
            if (oldAutoKey != null && _activeSourceKey.value == oldAutoKey) {
                _activeSourceKey.value = null
                _activeSourcePackage.value = null
                NotificationIconStore.currentActivePayload = null
            }
            AppLogger.i("Capsule", "已清理自动媒体拦截状态与歌词/进度回调")
        }

        private fun resolveLiveMediaController(context: Context): MediaController? {
            pinnedMediaController?.let { return it }
            val targetPkg = _pinnedPackage.value ?: _activeSourcePackage.value
            return runCatching {
                val msm = context.getSystemService(MediaSessionManager::class.java) ?: return null
                val cn = ComponentName(context, CapsuleNotificationListener::class.java)
                val sessions = msm.getActiveSessions(cn)
                val matched = if (targetPkg != null) {
                    sessions.firstOrNull { it.packageName == targetPkg } ?: sessions.firstOrNull()
                } else {
                    sessions.firstOrNull()
                }
                matched
            }.getOrNull()
        }

        /**
         * 连点停止后的“延迟拉平”防抖校准（600ms + 1500ms）：
         * 每次连点/切歌都先取消上一次的延迟任务重新计时，停止操作后第 600ms 与 1500ms
         * 主动读取一次 MediaController.playbackState 强制覆盖本地基准值，拉平缓冲误差。
         */
        fun scheduleImmediateMediaResync() {
            val svc = instance ?: return
            val key = _pinnedKey.value ?: autoMediaKey ?: _activeSourceKey.value ?: return
            val ctrl = pinnedMediaController ?: resolveLiveMediaController(svc) ?: return
            pendingResyncTask1?.let { mediaProgressHandler.removeCallbacks(it) }
            pendingResyncTask2?.let { mediaProgressHandler.removeCallbacks(it) }
            val t1 = Runnable {
                pendingResyncTask1 = null
                optimisticSeekPosMs = -1L
                if (_pinnedKey.value == key || autoMediaKey == key || _activeSourceKey.value == key) {
                    AppLogger.d("Capsule", "执行 600ms 防抖拉平校准(强制同步进度条与读秒) key=$key")
                    svc.syncPinnedMediaProgress(ctrl, key, pollController = true, forceOverwrite = true, forcePush = true)
                }
            }
            val t2 = Runnable {
                pendingResyncTask2 = null
                optimisticSeekPosMs = -1L
                if (_pinnedKey.value == key || autoMediaKey == key || _activeSourceKey.value == key) {
                    AppLogger.d("Capsule", "执行 1500ms 防抖拉平校准(强制同步进度条与读秒) key=$key")
                    svc.syncPinnedMediaProgress(ctrl, key, pollController = true, forceOverwrite = true, forcePush = true)
                }
            }
            pendingResyncTask1 = t1
            pendingResyncTask2 = t2
            mediaProgressHandler.postDelayed(t1, 600L)
            mediaProgressHandler.postDelayed(t2, 1500L)
        }

        /**
         * 执行「歌词」等原通知自定义动作（绝不错误回退成跳转打开 App）：
         * 1. 优先从活跃 MediaController 的 PlaybackState.CustomAction 中匹配并调用 sendCustomAction（如酷狗/QQ音乐的桌面歌词开关）
         * 2. 其次实时从 NotificationListenerService.activeNotifications 中提取最新未过期的原通知 actionIntent 直接发送
         */
        fun triggerFreshNotificationAction(
            context: Context,
            notifKey: String?,
            packageName: String?,
            actionLabel: String
        ): Boolean {
            val isLyric = actionLabel.contains("词") || actionLabel.contains("lyric", ignoreCase = true)
            // 1. 优先尝试 MediaSession CustomAction（安卓 13–15 音乐播放器桌面歌词的标准通道）
            val ctrl = resolveLiveMediaController(context)
            if (ctrl != null) {
                val customActs = runCatching { ctrl.playbackState?.customActions }.getOrNull().orEmpty()
                val matchedCustom = customActs.firstOrNull { ca ->
                    val name = ca.name?.toString().orEmpty()
                    val actId = ca.action.orEmpty()
                    val locName = NotificationActionExtractor.localizeLabel(name)
                    val locId = NotificationActionExtractor.localizeLabel(actId)
                    locName == actionLabel || locId == actionLabel ||
                        (isLyric && (name.contains("词") || name.contains("lyric", true) || actId.contains("lyric", true) || actId.contains("lrc", true)))
                }
                if (matchedCustom != null) {
                    val sent = runCatching {
                        ctrl.transportControls.sendCustomAction(matchedCustom, matchedCustom.extras)
                        true
                    }.getOrDefault(false)
                    AppLogger.i("Capsule", "执行 MediaSession CustomAction[${matchedCustom.action}] ok=$sent")
                    if (sent) return true
                }
            }

            // 2. 实时从 activeNotifications 读取最新鲜、未被 cancel 的原通知 actionIntent
            val svc = instance
            val freshSbn = runCatching {
                val list = svc?.activeNotifications ?: return@runCatching null
                list.firstOrNull { !notifKey.isNullOrBlank() && it.key == notifKey }
                    ?: list.firstOrNull { !packageName.isNullOrBlank() && it.packageName == packageName }
            }.getOrNull()

            if (freshSbn != null) {
                val freshActs = NotificationActionExtractor.extractActions(freshSbn, isCall = false)
                val matchedAct = freshActs.firstOrNull { it.title == actionLabel }
                    ?: if (isLyric) freshActs.firstOrNull { it.title.contains("词") } else null
                val pi = matchedAct?.intent
                if (pi != null) {
                    val ok = runCatching {
                        pi.send()
                        true
                    }.onFailure { e ->
                        AppLogger.w("Capsule", "发送实时动作[$actionLabel]失败：${e.javaClass.simpleName}: ${e.message}")
                    }.getOrDefault(false)
                    AppLogger.i("Capsule", "发送实时通知动作[$actionLabel] isAct=${pi.isActivity} isBcast=${pi.isBroadcast} isSvc=${pi.isService} ok=$ok")
                    if (ok) return true
                }
            }
            AppLogger.w("Capsule", "未能匹配到可执行的动作[$actionLabel] pkg=$packageName")
            return false
        }

        fun dispatchPinnedMediaCommand(context: Context, action: String): Boolean {
            val ctrl = resolveLiveMediaController(context) ?: return false
            return runCatching {
                when (action) {
                    CapsuleKeepAliveService.ACTION_MEDIA_PREV -> {
                        lastTrackSkipElapsedMs = SystemClock.elapsedRealtime()
                        cancelPendingMediaDismiss()
                        val pkg = ctrl.packageName.orEmpty()
                        val appLabel = appNameCache[pkg] ?: pkg
                        triggerImmediateTrackSwitchPlaceholder(context, pinnedMediaNotifKey ?: autoMediaKey, pkg, appLabel)
                        ctrl.transportControls.skipToPrevious()
                        AppLogger.i("Capsule", "媒体控制：上一曲 (pkg=$pkg，已清空旧载荷并置入占位图)")
                    }
                    CapsuleKeepAliveService.ACTION_MEDIA_NEXT -> {
                        lastTrackSkipElapsedMs = SystemClock.elapsedRealtime()
                        cancelPendingMediaDismiss()
                        val pkg = ctrl.packageName.orEmpty()
                        val appLabel = appNameCache[pkg] ?: pkg
                        triggerImmediateTrackSwitchPlaceholder(context, pinnedMediaNotifKey ?: autoMediaKey, pkg, appLabel)
                        ctrl.transportControls.skipToNext()
                        AppLogger.i("Capsule", "媒体控制：下一曲 (pkg=$pkg，已清空旧载荷并置入占位图)")
                    }
                    CapsuleKeepAliveService.ACTION_MEDIA_PLAY_PAUSE -> {
                        val isPlaying = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
                        if (isPlaying) {
                            ctrl.transportControls.pause()
                            AppLogger.i("Capsule", "媒体控制：暂停播放 (pkg=${ctrl.packageName})")
                        } else {
                            ctrl.transportControls.play()
                            AppLogger.i("Capsule", "媒体控制：继续播放 (pkg=${ctrl.packageName})")
                        }
                    }
                    CapsuleKeepAliveService.ACTION_MEDIA_REWIND -> {
                        val st = ctrl.playbackState
                        val acts = st?.actions ?: 0L
                        val canSeek = (acts and PlaybackState.ACTION_SEEK_TO) != 0L
                        val canRew = (acts and PlaybackState.ACTION_REWIND) != 0L
                        if (!canSeek && !canRew) {
                            AppLogger.w("Capsule", "该应用不支持快退(actions=$acts, pkg=${ctrl.packageName})，已忽略以防读秒卡死")
                            return@runCatching false
                        }
                        val nowMs = SystemClock.elapsedRealtime()
                        val curPos = if (optimisticSeekPosMs >= 0L && (nowMs - optimisticSeekElapsedMs) < 1200L) {
                            optimisticSeekPosMs
                        } else {
                            val basePos = st?.position?.coerceAtLeast(0L) ?: 0L
                            val elapsed = if (st?.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0L) {
                                val spd = if (st.playbackSpeed > 0f) st.playbackSpeed else 1f
                                ((nowMs - st.lastPositionUpdateTime) * spd).toLong().coerceAtLeast(0L)
                            } else 0L
                            basePos + elapsed
                        }
                        if (canSeek) {
                            val target = (curPos - 10_000L).coerceAtLeast(0L)
                            optimisticSeekPosMs = target
                            optimisticSeekElapsedMs = nowMs
                            ctrl.transportControls.seekTo(target)
                            MediaProgressSyncEngine.applyOptimisticSeek(target)
                            val activeKey = _pinnedKey.value ?: autoMediaKey ?: _activeSourceKey.value
                            if (activeKey != null) {
                                instance?.syncPinnedMediaProgress(ctrl, activeKey, pollController = false, forcePush = true)
                            }
                            AppLogger.i("Capsule", "媒体控制：快退 10s (${curPos}ms -> ${target}ms, pkg=${ctrl.packageName})")
                        } else {
                            ctrl.transportControls.rewind()
                            AppLogger.i("Capsule", "媒体控制：快退 rewind() (pkg=${ctrl.packageName})")
                        }
                    }
                    CapsuleKeepAliveService.ACTION_MEDIA_FAST_FORWARD -> {
                        val st = ctrl.playbackState
                        val acts = st?.actions ?: 0L
                        val canSeek = (acts and PlaybackState.ACTION_SEEK_TO) != 0L
                        val canFf = (acts and PlaybackState.ACTION_FAST_FORWARD) != 0L
                        if (!canSeek && !canFf) {
                            AppLogger.w("Capsule", "该应用不支持快进(actions=$acts, pkg=${ctrl.packageName})，已忽略以防读秒卡死")
                            return@runCatching false
                        }
                        val dur = ctrl.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
                        val nowMs = SystemClock.elapsedRealtime()
                        val curPos = if (optimisticSeekPosMs >= 0L && (nowMs - optimisticSeekElapsedMs) < 1200L) {
                            optimisticSeekPosMs
                        } else {
                            val basePos = st?.position?.coerceAtLeast(0L) ?: 0L
                            val elapsed = if (st?.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0L) {
                                val spd = if (st.playbackSpeed > 0f) st.playbackSpeed else 1f
                                ((nowMs - st.lastPositionUpdateTime) * spd).toLong().coerceAtLeast(0L)
                            } else 0L
                            basePos + elapsed
                        }
                        if (canSeek) {
                            val target = if (dur > 0L) (curPos + 10_000L).coerceAtMost(dur) else (curPos + 10_000L)
                            optimisticSeekPosMs = target
                            optimisticSeekElapsedMs = nowMs
                            ctrl.transportControls.seekTo(target)
                            MediaProgressSyncEngine.applyOptimisticSeek(target)
                            val activeKey = _pinnedKey.value ?: autoMediaKey ?: _activeSourceKey.value
                            if (activeKey != null) {
                                instance?.syncPinnedMediaProgress(ctrl, activeKey, pollController = false, forcePush = true)
                            }
                            AppLogger.i("Capsule", "媒体控制：快进 10s (${curPos}ms -> ${target}ms, pkg=${ctrl.packageName})")
                        } else {
                            ctrl.transportControls.fastForward()
                            AppLogger.i("Capsule", "媒体控制：快进 fastForward() (pkg=${ctrl.packageName})")
                        }
                    }
                    else -> return@runCatching false
                }
                true
            }.onFailure { e ->
                AppLogger.w("Capsule", "执行媒体控制失败：$action | ${e.message}")
            }.getOrDefault(false)
        }

        @Volatile
        private var lastBannerSignature: String? = null

        @Volatile
        private var lastBannerTimeMs: Long = 0L

        @Volatile
        private var lastDirectCapsuleSignature: String? = null

        @Volatile
        private var lastDirectCapsuleTimeMs: Long = 0L

        private val suppressedKeys = ConcurrentHashMap.newKeySet<String>()
        // 歌词三态循环状态机（按包名记忆）：0=未显示(点一次显示歌词), 1=已显示(再点锁定歌词), 2=已锁定(再点取消歌词)
        private val lyricStateByPkg = ConcurrentHashMap<String, Int>()
        // 缓存各音乐应用曾出现过的歌词动作，防止手动锁定歌词后原通知隐藏/改名按钮导致胶囊中「歌词」按钮消失
        private val cachedLyricActionByPkg = ConcurrentHashMap<String, BannerAction>()
        private val cachedLyricCustomActionIdByPkg = ConcurrentHashMap<String, String>()
        // 荣耀 SystemUI（HnMediaDataManagerImpl / HnLyricStateListenerImpl）原生歌词双向协议常量
        private const val EVENT_COMMAND_QUERY_LYRIC = NativeMediaCardBridge.EVENT_COMMAND_QUERY_LYRIC
        private const val EVENT_COMMAND_SHOW_LYRIC = NativeMediaCardBridge.EVENT_COMMAND_SHOW_LYRIC
        private const val EVENT_COMMAND_HIDE_LYRIC = NativeMediaCardBridge.EVENT_COMMAND_HIDE_LYRIC
        private val nativeLyricSupportedPkgs = ConcurrentHashMap.newKeySet<String>()
        // 原生收藏/评分状态缓存（对齐 HnMediaDataManagerImpl.getRatingAction）
        private val favoriteStateByPkg = ConcurrentHashMap<String, Boolean>()
        // 记忆各音乐包名是否支持快进/快退，防止切歌缓冲瞬间 actions=0 导致「快退/快进」按钮消失
        private val seekCapableByPkg = ConcurrentHashMap<String, Boolean>()

        private fun handleSessionLyricEvent(context: Context, pkg: String, event: String, extras: android.os.Bundle?) {
            val ev = NativeMediaCardBridge.parseLyricEventFull(event, extras) ?: return
            nativeLyricSupportedPkgs.add(pkg)
            val prev = lyricStateByPkg[pkg] ?: 0
            val next = when {
                ev.isLocked == true -> 2
                !ev.isShown -> 0
                ev.isLocked == false -> 1
                else -> if (prev == 2) 2 else 1
            }
            lyricStateByPkg[pkg] = next
            AppLogger.i("Capsule", "收到原生歌词状态回执 pkg=$pkg shown=${ev.isShown} locked=${ev.isLocked} state=$prev->$next")
            val label = getLyricButtonLabel(pkg)
            UniversalBannerHost.refreshLyricLabel(pkg, label)
            CapsuleOverlayAccessibilityService.refreshLyricLabel(pkg, label)
            val cfgNow = runCatching { CapsulePrefs(context).load() }.getOrNull()
            if (cfgNow != null && cfgNow.enableCapsuleEngine && prev != next) {
                CapsulePublisher.refreshCurrent(context, cfgNow)
            }
        }

        fun recordLyricCapability(packageName: String, actions: List<BannerAction>) {
            if (packageName.isBlank()) return
            val hasUnlock = actions.any { a ->
                val t = a.title.lowercase()
                t.contains("解锁") || t.contains("unlock")
            }
            if (hasUnlock) {
                // 检测到播放器通知变为「解锁歌词」，自动同步状态为 2（已锁定），下次点击直接解锁
                lyricStateByPkg[packageName] = 2
            } else if (lyricStateByPkg[packageName] == 2) {
                // 通知已恢复正常（不再包含「解锁」），立即清除旧的锁定态，防止按钮永久卡死在「解锁词」
                lyricStateByPkg[packageName] = 0
            }
            val lyricAct = actions.firstOrNull { a ->
                val t = a.title.lowercase()
                (t.contains("词") || t.contains("lyric") || t.contains("lrc")) &&
                    !t.contains("解锁") && !t.contains("unlock") && !t.contains("锁") && !t.contains("lock")
            }
            if (lyricAct != null) {
                cachedLyricActionByPkg[packageName] = lyricAct
            }
        }

        fun shouldShowLyricButton(packageName: String?, actions: List<BannerAction>): Boolean {
            if (actions.any { a ->
                    val t = a.title.lowercase()
                    t.contains("词") || t.contains("lyric") || t.contains("lrc") || t.contains("解锁")
                }
            ) return true
            return !packageName.isNullOrBlank() &&
                (cachedLyricActionByPkg.containsKey(packageName) || packageName in nativeLyricSupportedPkgs)
        }

        fun getLyricButtonLabel(packageName: String?): String {
            val st = if (packageName.isNullOrBlank()) 0 else (lyricStateByPkg[packageName] ?: 0)
            return when (st) {
                1 -> "锁词"
                2 -> "关词"
                else -> "歌词"
            }
        }

        fun shouldShowFavoriteButton(context: Context, packageName: String?, actions: List<BannerAction>): Boolean {
            if (actions.any { it.title == "喜欢" || it.title == "取消喜欢" || it.title == "已赞" }) return true
            val ctrl = resolveLiveMediaController(context)
            if (ctrl != null && (packageName.isNullOrBlank() || ctrl.packageName == packageName)) {
                if (NativeMediaCardBridge.isNativeRatingSupported(ctrl)) {
                    if (!packageName.isNullOrBlank()) {
                        favoriteStateByPkg[packageName] = NativeMediaCardBridge.isCurrentlyFavorited(ctrl)
                    }
                    return true
                }
            }
            return false
        }

        fun getFavoriteButtonLabel(packageName: String?): String {
            val fav = if (packageName.isNullOrBlank()) false else (favoriteStateByPkg[packageName] == true)
            return if (fav) "已赞" else "喜欢"
        }

        fun triggerNativeFavoriteAction(context: Context, notifKey: String?, packageName: String?): Boolean {
            val pkg = packageName ?: _activeSourcePackage.value ?: _pinnedPackage.value ?: return false
            val ctrl = resolveLiveMediaController(context)
            val (handled, newFav) = NativeMediaCardBridge.toggleNativeFavorite(ctrl)
            if (handled) {
                favoriteStateByPkg[pkg] = newFav
                val cfgNow = CapsulePrefs(context).load()
                if (cfgNow.enableCapsuleEngine) CapsulePublisher.refreshCurrent(context, cfgNow)
                scheduleImmediateMediaResync()
                return true
            }
            // 兜底：尝试 CustomAction / 通知原始「喜欢」「取消喜欢」按钮
            return triggerFreshNotificationAction(context, notifKey, pkg, "喜欢") ||
                triggerFreshNotificationAction(context, notifKey, pkg, "取消喜欢")
        }

        /**
         * 歌词三态循环点击逻辑：
         * - 0 -> 1：点击一次显示歌词
         * - 1 -> 2：再点击一次锁定歌词（优先调用播放器锁词 Action，不误关歌词）
         * - 2 -> 0：再点击一次取消歌词（若处于锁定态先触发解锁，再关闭桌面歌词）
         */
        fun triggerLyricCycleAction(context: Context, notifKey: String?, packageName: String?): Boolean {
            val pkg = packageName ?: _activeSourcePackage.value ?: _pinnedPackage.value ?: return false
            val curState = lyricStateByPkg[pkg] ?: 0
            val svc = instance
            val ctrl = resolveLiveMediaController(context)
            val customActs = runCatching { ctrl?.playbackState?.customActions }.getOrNull().orEmpty()
            val freshSbn = runCatching {
                val list = svc?.activeNotifications ?: return@runCatching null
                list.firstOrNull { !notifKey.isNullOrBlank() && it.key == notifKey }
                    ?: list.firstOrNull { it.packageName == pkg }
            }.getOrNull()
            val freshActs = if (freshSbn != null) NotificationActionExtractor.extractActions(freshSbn, isCall = false) else emptyList()

            AppLogger.d(
                "Capsule",
                "歌词点击 pkg=$pkg state=$curState customActs=${customActs.map { "${it.name}(${it.action})" }} notifActs=${freshActs.map { it.title }}"
            )

            fun fireCustom(predicate: (String, String) -> Boolean): Boolean {
                if (ctrl == null) return false
                val matched = customActs.firstOrNull { ca ->
                    predicate(ca.name?.toString().orEmpty().lowercase(), ca.action.orEmpty().lowercase())
                } ?: return false
                return runCatching {
                    cachedLyricCustomActionIdByPkg[pkg] = matched.action
                    ctrl.transportControls.sendCustomAction(matched, matched.extras)
                    AppLogger.i("Capsule", "歌词执行 CustomAction[${matched.name}/${matched.action}]")
                    true
                }.getOrDefault(false)
            }

            fun fireCachedCustomId(): Boolean {
                if (ctrl == null) return false
                val savedId = cachedLyricCustomActionIdByPkg[pkg] ?: return false
                val matched = customActs.firstOrNull { it.action == savedId } ?: return false
                return runCatching {
                    ctrl.transportControls.sendCustomAction(matched, matched.extras)
                    AppLogger.i("Capsule", "歌词按缓存ID执行 CustomAction[${matched.action}]")
                    true
                }.getOrDefault(false)
            }

            fun fireNotifAction(predicate: (BannerAction) -> Boolean): Boolean {
                val act = freshActs.firstOrNull(predicate) ?: return false
                val pi = act.intent ?: return false
                return runCatching {
                    pi.send()
                    AppLogger.i("Capsule", "歌词执行通知动作[${act.title}]")
                    true
                }.getOrDefault(false)
            }

            fun fireCachedLyric(): Boolean {
                val pi = cachedLyricActionByPkg[pkg]?.intent ?: return false
                return runCatching {
                    pi.send()
                    AppLogger.i("Capsule", "歌词执行缓存歌词动作")
                    true
                }.getOrDefault(false)
            }

            fun firePrimaryLyricAction(): Boolean {
                return fireCustom { n, a -> n.contains("词") || n.contains("lyric") || a.contains("lyric") || a.contains("lrc") } ||
                    fireCachedCustomId() ||
                    fireNotifAction { a -> val t = a.title.lowercase(); t.contains("词") || t.contains("lyric") || t.contains("lrc") || t.contains("解锁") } ||
                    fireCachedLyric()
            }

            fun sendHonorNativeLyricCommand(show: Boolean): Boolean {
                if (ctrl == null) return false
                val cmd = if (show) EVENT_COMMAND_SHOW_LYRIC else EVENT_COMMAND_HIDE_LYRIC
                return runCatching {
                    ctrl.sendCommand(cmd, android.os.Bundle(), android.os.ResultReceiver(mediaProgressHandler))
                    AppLogger.i("Capsule", "已下发荣耀播控中心同款指令 $cmd pkg=$pkg")
                    true
                }.getOrDefault(false)
            }

            val useNativeLyric = pkg in nativeLyricSupportedPkgs || NativeMediaCardBridge.isInLyricWhitelist(pkg)
            val isKugou = NativeMediaCardBridge.isKugouPackage(pkg)
            when (curState) {
                0 -> {
                    // 第 1 次点击（0 -> 1）：显示歌词
                    val ok = if (useNativeLyric) {
                        sendHonorNativeLyricCommand(show = true) || firePrimaryLyricAction()
                    } else {
                        firePrimaryLyricAction() || sendHonorNativeLyricCommand(show = true)
                    }
                    lyricStateByPkg[pkg] = 1
                    AppLogger.i("Capsule", "歌词三态[0->1 显示歌词] pkg=$pkg native=$useNativeLyric ok=$ok")
                }
                1 -> {
                    // 第 2 次点击（1 -> 2）：锁定歌词（酷狗走实测可用的 com.kugou.android.minilyric.islock 广播 + Shizuku 双保险）
                    val lockOk = if (isKugou) {
                        NativeMediaCardBridge.sendKugouLyricLockBroadcast(context, pkg, lock = true) {
                            mediaProgressHandler.postDelayed({
                                NativeMediaCardBridge.queryLyricState(ctrl, mediaProgressHandler)
                            }, 350L)
                        }
                    } else {
                        fireCustom { n, a -> (n.contains("锁") || a.contains("lock")) && !n.contains("解锁") && !a.contains("unlock") } ||
                            fireNotifAction { a -> val t = a.title.lowercase(); (t.contains("锁") || t.contains("lock")) && !t.contains("解锁") && !t.contains("unlock") }
                    }
                    if (lockOk) {
                        lyricStateByPkg[pkg] = 2
                        AppLogger.i("Capsule", "歌词三态[1->2 锁定歌词] pkg=$pkg kugou=$isKugou ok=true")
                    } else {
                        // 非酷狗且未提供锁词接口的播放器：直接关闭歌词防止空点
                        val hideOk = if (useNativeLyric) {
                            sendHonorNativeLyricCommand(show = false) || firePrimaryLyricAction()
                        } else {
                            firePrimaryLyricAction() || sendHonorNativeLyricCommand(show = false)
                        }
                        lyricStateByPkg[pkg] = 0
                        AppLogger.i("Capsule", "歌词三态[1->0 无锁词接口改关闭] pkg=$pkg ok=$hideOk")
                    }
                }
                else -> {
                    // 第 3 次点击（2 -> 0）：先原生解锁，再自动关闭桌面歌词
                    val unlockOk = NativeMediaCardBridge.sendUnlockLyricCommand(ctrl, mediaProgressHandler) ||
                        (isKugou && NativeMediaCardBridge.sendKugouLyricLockBroadcast(context, pkg, lock = false)) ||
                        fireCustom { n, a -> n.contains("解锁") || a.contains("unlock") } ||
                        fireNotifAction { a -> val t = a.title.lowercase(); t.contains("解锁") || t.contains("unlock") }
                    lyricStateByPkg[pkg] = 0
                    mediaProgressHandler.postDelayed({
                        val hideOk = sendHonorNativeLyricCommand(show = false) || firePrimaryLyricAction()
                        AppLogger.i("Capsule", "歌词三态[2->0 解锁后自动关闭歌词] pkg=$pkg unlockOk=$unlockOk hideOk=$hideOk")
                    }, 180L)
                }
            }

            val cfgNow = CapsulePrefs(context).load()
            if (cfgNow.enableCapsuleEngine) CapsulePublisher.refreshCurrent(context, cfgNow)
            val newLabel = getLyricButtonLabel(pkg)
            val payload = NotificationIconStore.currentActivePayload
            if (payload != null && payload.packageName == pkg) {
                UniversalBannerHost.refreshLyricLabel(pkg, newLabel)
                CapsuleOverlayAccessibilityService.refreshLyricLabel(pkg, newLabel)
            }
            return true
        }

        fun setUiVisible(visible: Boolean) {
            uiVisible.set(visible)
            if (visible) {
                refreshSnapshot(force = true)
            }
        }

        fun trimMemoryWhenHidden() {
            if (!uiVisible.get() && _pinnedKey.value == null) {
                _notifications.value = emptyList()
            }
        }

        fun pinNotification(key: String?, packageName: String? = null) {
            if (key != _pinnedKey.value) {
                detachPinnedMediaObserver()
            }
            if (key != null) suppressedKeys.remove(key)
            _pinnedKey.value = key
            _pinnedPackage.value = if (key != null) packageName else null
            lastPushedSignature = null
            lastPushedIconHash = 0
            if (key != null && packageName != null) {
                _activeSourceKey.value = key
                _activeSourcePackage.value = packageName
                instance?.attachMediaObserverForActiveKey(key)
            }
        }

        fun unpinAndDismiss(context: Context) {
            cancelPendingMediaDismiss()
            detachPinnedMediaObserver()
            _pinnedKey.value?.let { suppressedKeys.add(it) }
            _activeSourceKey.value?.let { suppressedKeys.add(it) }
            _pinnedKey.value = null
            _pinnedPackage.value = null
            autoMediaKey = null
            autoMediaPackage = null
            UniversalBannerHost.dismiss()
            VivoSuperXBridge.resetSession()
            CapsulePublisher.dismiss(context, reason = "已取消锁定并关闭胶囊")
            runCatching {
                context.stopService(Intent(context, CapsuleKeepAliveService::class.java))
            }
        }

        /**
         * 仅关闭状态栏灵动胶囊引擎（不销毁 MediaController、LrcCapsuleLyricEngine 与顶层横幅），
         * 确保关闭「灵动胶囊引擎总开关」后，顶层横幅依然可以独立接收媒体进度与实时滚动歌词。
         */
        fun dismissCapsuleOnly(context: Context) {
            CapsulePublisher.setManualCapsulePinned(false)
            VivoSuperXBridge.resetSession()
            CapsulePublisher.dismiss(context, reason = "已关闭灵动胶囊引擎（横幅独立运行）")
            runCatching {
                context.stopService(Intent(context, CapsuleKeepAliveService::class.java))
            }
        }

        /**
         * 当用户将「灵动胶囊引擎总开关」或「顶层横幅引擎总开关」关闭后再重新打开时，
         * 立即清除旧去重签名并主动重扫活跃通知与 MediaSession，自动拉起当前正在播放的媒体胶囊/横幅与歌词。
         */
        fun rescanAndRestoreActiveMedia(context: Context) {
            val appCtx = context.applicationContext
            lastPushedSignature = null
            lastPushedIconHash = 0
            suppressedKeys.clear()
            if (CapsuleNavigationEngine.restoreActiveNavCapsuleIfNeeded(appCtx)) {
                return
            }
            val svc = instance ?: return
            worker.execute {
                runCatching {
                    val prefs = CapsulePrefs(appCtx).load()
                    if (!prefs.hasAnyDisplayEngine()) return@runCatching
                    var restoredFromNotif = false
                    if (prefs.enableMediaRecorderCapsule) {
                        val activeList = runCatching { svc.activeNotifications }.getOrNull()
                        if (activeList != null) {
                            for (sbn in activeList) {
                                if (sbn.packageName == appCtx.packageName) continue
                                val n = sbn.notification ?: continue
                                if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
                                val extras = n.extras
                                val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                                val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                                val acts = NotificationActionExtractor.extractActions(sbn, isCall = false).map { it.title }
                                val kind = MediaRecorderInterceptor.classify(sbn, title, text, acts, "")
                                if (MediaRecorderInterceptor.shouldAutoIntercept(kind, prefs)) {
                                    svc.onNotificationPosted(sbn)
                                    restoredFromNotif = true
                                    break
                                }
                            }
                        }
                        if (!restoredFromNotif) {
                            val msm = appCtx.getSystemService(MediaSessionManager::class.java)
                            val cn = ComponentName(appCtx, CapsuleNotificationListener::class.java)
                            val sessions = runCatching { msm?.getActiveSessions(cn).orEmpty() }.getOrDefault(emptyList())
                            if (sessions.isNotEmpty()) {
                                svc.handleActiveMediaSessions(sessions)
                            }
                        }
                    }
                    LrcCapsuleLyricEngine.resyncTimeline()
                    BannerLrcLyricEngine.resyncTimeline()
                    AppLogger.i("Capsule", "总开关重新开启，已主动重扫并恢复活跃媒体拦截(fromNotif=$restoredFromNotif)")
                }
            }
        }

        fun clearActiveSource() {
            _activeSourceKey.value = null
            _activeSourcePackage.value = null
            lastPushedSignature = null
            lastPushedIconHash = 0
        }

        fun requestRebindNow(context: Context): Boolean {
            if (!isPermissionGranted(context)) return false
            return runCatching {
                requestRebind(ComponentName(context.applicationContext, CapsuleNotificationListener::class.java))
                AppLogger.i("Notif", "已向系统发起 NotificationListenerService.requestRebind 自愈请求")
                true
            }.onFailure { e ->
                AppLogger.w("Notif", "requestRebind 失败：${e.message}")
            }.getOrDefault(false)
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

    @Volatile private var btLyricReceiverRegistered: Boolean = false
    @Volatile private var lastBtLyricPushElapsedMs: Long = 0L
    // 前台媒体广播感知状态（用于多会话并发时判断音乐 vs 视频归属与最新活跃来源）
    @Volatile private var lastMediaBroadcastPkg: String? = null
    @Volatile private var lastMediaBroadcastType: MediaContentType? = null
    @Volatile private var lastMediaBroadcastPlaying: Boolean = false
    @Volatile private var lastMediaBroadcastElapsedMs: Long = 0L

    private fun inferBroadcastSourcePackage(action: String, intent: Intent): String? {
        val explicit = intent.getStringExtra("package")
            ?: intent.getStringExtra("pkg")
            ?: intent.getStringExtra("app")
            ?: intent.getStringExtra("packageName")
        if (!explicit.isNullOrBlank()) return explicit.trim()
        return when {
            action.startsWith("com.netease.cloudmusic.") -> "com.netease.cloudmusic"
            action.startsWith("com.tencent.qqmusic.") -> "com.tencent.qqmusic"
            action.startsWith("com.kugou.android.") -> "com.kugou.android"
            action.startsWith("com.spotify.music.") -> "com.spotify.music"
            else -> null
        }
    }

    /**
     * 统一监听前台/第三方媒体广播（音乐/视频播放状态广播 + 蓝牙实时歌词广播）：
     * 1. 识别前台应用发出的播放状态/切歌广播，辅助判断当前为 VIDEO 还是 MUSIC 媒体并即时重仲裁胶囊；
     * 2. 兼容酷狗/QQ音乐/网易云蓝牙歌词广播 com.android.music.metachanged。
     */
    private val btMetaLyricReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            val now = SystemClock.elapsedRealtime()

            if (action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                worker.execute {
                    runCatching {
                        val msm = getSystemService(MediaSessionManager::class.java) ?: return@runCatching
                        val cn = ComponentName(this@CapsuleNotificationListener, CapsuleNotificationListener::class.java)
                        handleActiveMediaSessions(msm.getActiveSessions(cn))
                    }
                }
                return
            }

            val srcPkg = inferBroadcastSourcePackage(action, intent)
            val hasPlayingExtra = intent.hasExtra("playing") || intent.hasExtra("isPlaying")
            val playingFlag = if (intent.hasExtra("playing")) {
                intent.getBooleanExtra("playing", true)
            } else {
                intent.getBooleanExtra("isPlaying", !action.endsWith("playbackcomplete"))
            }
            val hasAlbumOrArtist = !intent.getStringExtra("artist").isNullOrBlank() ||
                !intent.getStringExtra("album").isNullOrBlank()
            val inferredType = when {
                !srcPkg.isNullOrBlank() -> MediaRecorderInterceptor.detectMediaContentType(srcPkg, "", isRecorder = false)
                hasAlbumOrArtist || action.contains("music") -> MediaContentType.MUSIC
                else -> null
            }
            if (srcPkg != null || inferredType != null || hasPlayingExtra) {
                lastMediaBroadcastPkg = srcPkg ?: lastMediaBroadcastPkg
                if (inferredType != null) lastMediaBroadcastType = inferredType
                lastMediaBroadcastPlaying = playingFlag
                lastMediaBroadcastElapsedMs = now
                AppLogger.d("Notif", "捕获媒体广播 action=$action pkg=$srcPkg type=$inferredType playing=$playingFlag")
            }

            // 播放状态变更广播：立即触发一次多会话仲裁，避免音乐与视频并发时胶囊停留在错误类型
            if (action.endsWith("playstatechanged") || action.endsWith("playbackstatechanged") || action.endsWith("playbackcomplete")) {
                worker.execute {
                    runCatching {
                        val msm = getSystemService(MediaSessionManager::class.java) ?: return@runCatching
                        val cn = ComponentName(this@CapsuleNotificationListener, CapsuleNotificationListener::class.java)
                        handleActiveMediaSessions(msm.getActiveSessions(cn))
                    }
                }
                return
            }

            // 蓝牙实时歌词提取（仅对 MUSIC 胶囊生效，绝不把歌词贴到 VIDEO 视频胶囊上）
            if (action != "com.android.music.metachanged") return
            val rawTrack = intent.getStringExtra("track")?.replace('\n', ' ')?.trim().orEmpty()
            if (rawTrack.isBlank()) return
            if (now - lastBtLyricPushElapsedMs < 250L) return
            worker.execute {
                runCatching {
                    val prefs = CapsulePrefs(this@CapsuleNotificationListener).load()
                    if (!prefs.hasAnyDisplayEngine() || !prefs.showMediaMetadataLyricInCapsule) return@runCatching
                    val activeKey = _pinnedKey.value ?: autoMediaKey ?: _activeSourceKey.value ?: return@runCatching
                    if (!prefs.enableMediaRecorderCapsule && _pinnedKey.value != activeKey) return@runCatching
                    if (CapsulePublisher.isManualCapsulePinned() && !prefs.capsuleFollowBannerStyle) return@runCatching
                    val base = NotificationIconStore.getPayload(activeKey)
                        ?: NotificationIconStore.currentActivePayload
                        ?: return@runCatching
                    if (!base.isMedia || base.mediaContentType != MediaContentType.MUSIC) return@runCatching
                    if (rawTrack == base.title || rawTrack == base.liveLyricLine) return@runCatching
                    LrcCapsuleLyricEngine.publishExternalLyric(base.key, rawTrack)
                    BannerLrcLyricEngine.markNativeLyricActive()
                    lastBtLyricPushElapsedMs = SystemClock.elapsedRealtime()
                    val updated = base.copy(liveLyricLine = rawTrack, bannerNativeLyric = rawTrack)
                    NotificationIconStore.putPayload(updated)
                    UniversalBannerHost.updateLiveLyric(updated.key, rawTrack)
                    CapsuleOverlayAccessibilityService.updateLiveLyric(updated.key, rawTrack)
                    if (prefs.enableCapsuleEngine) {
                        pushToCapsule(updated, prefs = prefs, isPinned = true)
                    }
                    AppLogger.d("Capsule", "蓝牙广播实时歌词更新 pkg=${base.packageName} lyric=${rawTrack.take(24)}")
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        _connected.value = true
        AppLogger.i("Notif", "NotificationListenerService 已连接，正在恢复监控与会话监听")
        if (!btLyricReceiverRegistered) {
            runCatching {
                val mediaFilter = IntentFilter().apply {
                    addAction("com.android.music.metachanged")
                    addAction("com.android.music.playstatechanged")
                    addAction("com.android.music.playbackcomplete")
                    addAction("com.netease.cloudmusic.metachanged")
                    addAction("com.netease.cloudmusic.playstatechanged")
                    addAction("com.tencent.qqmusic.metachanged")
                    addAction("com.tencent.qqmusic.playstatechanged")
                    addAction("com.kugou.android.music.metachanged")
                    addAction("com.kugou.android.music.playstatechanged")
                    addAction("com.spotify.music.metadatachanged")
                    addAction("com.spotify.music.playbackstatechanged")
                    addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                }
                ContextCompat.registerReceiver(
                    this,
                    btMetaLyricReceiver,
                    mediaFilter,
                    ContextCompat.RECEIVER_EXPORTED
                )
                btLyricReceiverRegistered = true
            }
        }
        val cfg = runCatching { CapsulePrefs(this).load() }.getOrNull()
        runCatching { ShizukuBridge.init {} }
        if (cfg != null) {
            runCatching { SystemStatusMonitor.syncMonitorRegistration(this, cfg) }
            runCatching { ResidentKeepAliveService.sync(this, cfg) }
        }
        registerActiveSessionsWatcher()
        scheduleReloadActiveNotifications(force = true)
    }

    override fun onListenerDisconnected() {
        if (btLyricReceiverRegistered) {
            runCatching { unregisterReceiver(btMetaLyricReceiver) }
            btLyricReceiverRegistered = false
        }
        unregisterActiveSessionsWatcher()
        detachPinnedMediaObserver()
        _connected.value = false
        AppLogger.w("Notif", "NotificationListenerService 已断开，准备 1.5s 后自动重绑自愈")
        instance = null
        if (isPermissionGranted(this)) {
            val appCtx = applicationContext
            mediaProgressHandler.postDelayed({
                if (!_connected.value && isPermissionGranted(appCtx)) {
                    requestRebindNow(appCtx)
                }
            }, 1500L)
        }
        super.onListenerDisconnected()
        ResidentKeepAliveService.healNotificationListenerIfStuck(this, "onListenerDisconnected")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val cfg = runCatching { CapsulePrefs(this).load() }.getOrNull()
        if (cfg?.enableBackgroundKeepAlive == true) {
            ResidentKeepAliveService.start(this)
        }
        if (!_connected.value && isPermissionGranted(this)) {
            requestRebindNow(this)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        // 超省电前置零分配拦截：后台息屏/未打开本应用时，高频常驻通知（如 FlClash 网速、下载进度等）
        // 若既未锁定、不在白名单、也非来电，直接在此丢弃——零线程唤醒、零 Bitmap 解码、零日志分配
        val prefsFast = CapsulePrefs(this).load()
        if (!uiVisible.get() && !prefsFast.hasAnyDisplayEngine() && sbn.key != _pinnedKey.value) {
            return
        }
        val isOngoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        // 系统自身悬浮窗提示（android）高频刷屏，直接零分配丢弃
        if (pkg == "android" && isOngoing && n.category != Notification.CATEGORY_CALL) {
            return
        }
        // VPN/代理/后台服务类常驻通知（CATEGORY_SERVICE，如 FlClash 每秒网速）若未锁定且不在白名单，直接零分配丢弃，杜绝每小时 3600 次无效唤醒
        if (isOngoing && n.category == Notification.CATEGORY_SERVICE &&
            sbn.key != _pinnedKey.value && sbn.key != autoMediaKey &&
            pkg !in prefsFast.autoWhitelistPackages &&
            MediaRecorderInterceptor.classify(sbn) == MediaRecorderInterceptor.SpecialOngoingKind.NONE
        ) {
            return
        }
        // 高频常驻通知（如 FlClash 每秒网速）在前台也执行零分配前置拦截，防止每秒唤醒工作线程、解码位图并触发 Compose 全树重组导致高耗电
        if (isOngoing && sbn.key != _pinnedKey.value && sbn.key != autoMediaKey) {
            val maybeCall = n.category == Notification.CATEGORY_CALL || n.fullScreenIntent != null
            val specialKindFast = MediaRecorderInterceptor.classify(sbn)
            val allowSpecialOngoing = MediaRecorderInterceptor.shouldAutoIntercept(specialKindFast, prefsFast)
            val bothIgnoreOngoing = (!prefsFast.enableCapsuleEngine || prefsFast.ignoreOngoingInAutoMode) &&
                (!prefsFast.enableOverlayBanner || prefsFast.bannerIgnoreOngoing)
            if (!maybeCall && !allowSpecialOngoing && bothIgnoreOngoing && pkg !in prefsFast.autoWhitelistPackages) {
                return
            }
        }

        val opPkg = runCatching { sbn.opPkg }.getOrNull().orEmpty()
        if (opPkg == packageName) return

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
        val nExtras = sbn.notification?.extras
        val hasMediaTok = nExtras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        val hasChrono = nExtras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) == true
        val nTpl = nExtras?.getString(Notification.EXTRA_TEMPLATE)?.substringAfterLast('.') ?: "none"
        val nCat = sbn.notification?.category ?: "none"
        AppLogger.i(
            "Notif",
            "收到通知 app=${item.appName} pkg=${item.packageName} tier=${item.importanceLabel()}(imp=${item.importance}) " +
                "call=${item.isCall} ongoing=${item.isOngoing} ch=${item.channelName} cat=$nCat tpl=$nTpl " +
                "mediaTok=$hasMediaTok chrono=$hasChrono title=${item.title.take(20)}"
        )

        val bannerActions = NotificationActionExtractor.extractActions(sbn, isCall = item.isCall)
        recordLyricCapability(item.packageName, bannerActions)
        val liveExtra = LiveNotificationExtraParser.parse(this, sbn.notification)
        if (liveExtra != null && liveExtra.isFinish) {
            if (_activeSourceKey.value == item.key) {
                if (!CapsuleNavigationEngine.restoreActiveNavCapsuleIfNeeded(this) &&
                    !restoreActiveMediaCapsuleIfNeeded(this)
                ) {
                    CapsulePublisher.dismiss(this, "实况通知已结束")
                }
            }
            return
        }
        val fullScreenPi = sbn.notification?.fullScreenIntent
        val clickPi = if (item.isCall) (fullScreenPi ?: sbn.notification?.contentIntent) else sbn.notification?.contentIntent
        var cachedPayload: ActiveMirroredPayload? = null
        fun ensurePayloadWithIcon(): ActiveMirroredPayload {
            cachedPayload?.let { return it }
            val bmp = liveExtra?.iconBitmap ?: NotificationIconStore.resolveNotificationBitmap(this, sbn)
            val rawMediaInfo = NotificationIconStore.extractMediaPlaybackInfo(this, sbn.notification)
            val actTitles = bannerActions.map { it.title }
            val specialKind = MediaRecorderInterceptor.classify(sbn, item.title, item.text, actTitles, item.channelName)
            val isRec = specialKind == MediaRecorderInterceptor.SpecialOngoingKind.RECORDER
            val mediaInfo = if (specialKind == MediaRecorderInterceptor.SpecialOngoingKind.MEDIA_PLAYBACK && !rawMediaInfo.isMedia) {
                val matchedCtrl = runCatching {
                    val msm = getSystemService(MediaSessionManager::class.java)
                    val cn = ComponentName(this, CapsuleNotificationListener::class.java)
                    msm?.getActiveSessions(cn)?.firstOrNull { it.packageName == item.packageName }
                }.getOrNull()
                if (matchedCtrl != null) {
                    val dur = runCatching { matchedCtrl.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L }.getOrDefault(0L)
                    val ps = runCatching { matchedCtrl.playbackState }.getOrNull()
                    val playing = ps?.state == PlaybackState.STATE_PLAYING
                    val pos = ps?.position?.coerceAtLeast(0L) ?: 0L
                    val acts = ps?.actions ?: 0L
                    val canSeek = (acts and (
                        PlaybackState.ACTION_SEEK_TO or
                            PlaybackState.ACTION_FAST_FORWARD or
                            PlaybackState.ACTION_REWIND
                        )) != 0L
                    NotificationIconStore.MediaPlaybackInfo(
                        isMedia = true,
                        durationMs = dur,
                        positionMs = pos,
                        isPlaying = playing,
                        supportsSeek = canSeek,
                        sessionToken = matchedCtrl.sessionToken
                    )
                } else {
                    rawMediaInfo.copy(isMedia = true, isPlaying = true)
                }
            } else {
                rawMediaInfo
            }
            val chronoBase = if (isRec) MediaRecorderInterceptor.extractRecorderChronometerBaseMs(sbn.notification) else 0L
            val chronoPaused = if (isRec) MediaRecorderInterceptor.isRecorderPaused(item.title, item.text, actTitles) else false
            // 提取状态栏歌词通道（酷狗/QQ音乐/网易云/酷我：Notification.tickerText + FLAG_ALWAYS_SHOW_TICKER / FLAG_ONLY_UPDATE_TICKER）
            val rawTicker = sbn.notification?.tickerText?.toString()?.replace('\n', ' ')?.trim().orEmpty()
            val nFlags = sbn.notification?.flags ?: 0
            val hasTickerLyricFlag = (nFlags and 0x01000000) != 0 || (nFlags and 0x02000000) != 0
            val tickerLyric = if (rawTicker.isNotBlank() &&
                (hasTickerLyricFlag || (mediaInfo.isMedia && rawTicker != item.title && rawTicker != item.text))
            ) rawTicker else ""
            // 套用实况通知关联字段（vivo SuperX capsule.content / 小米焦点通知 ticker）作为媒体实时歌词第二嗅探通道
            val extraLyric = if (mediaInfo.isMedia && liveExtra != null &&
                liveExtra.pillText.isNotBlank() &&
                liveExtra.pillText != item.title &&
                liveExtra.pillText != item.text
            ) liveExtra.pillText else ""
            val combinedNotifLyric = tickerLyric.ifBlank { extraLyric }
            if (combinedNotifLyric.isNotBlank()) {
                LrcCapsuleLyricEngine.markNativeLyricActive()
                BannerLrcLyricEngine.markNativeLyricActive()
            }
            return ActiveMirroredPayload(
                key = item.key,
                packageName = item.packageName,
                appName = item.appName,
                title = liveExtra?.title?.ifBlank { item.title } ?: item.title,
                text = liveExtra?.subtitle?.ifBlank { item.text } ?: item.text,
                iconBitmap = bmp,
                contentIntent = clickPi,
                actions = bannerActions,
                fullScreenIntent = fullScreenPi,
                isCall = item.isCall,
                importance = item.importance,
                channelName = item.channelName,
                isMedia = mediaInfo.isMedia,
                mediaDurationMs = mediaInfo.durationMs,
                mediaPositionMs = mediaInfo.positionMs,
                mediaPlaying = mediaInfo.isPlaying,
                isRecorder = isRec,
                chronometerBaseMs = chronoBase,
                chronometerPaused = chronoPaused,
                mediaContentType = MediaRecorderInterceptor.detectMediaContentType(item.packageName, item.appName, isRec),
                mediaSupportsSeek = mediaInfo.supportsSeek || (seekCapableByPkg[item.packageName] == true && mediaInfo.durationMs > 0L),
                mediaSessionToken = mediaInfo.sessionToken,
                liveLyricLine = combinedNotifLyric,
                bannerNativeLyric = combinedNotifLyric,
                livePillText = if (mediaInfo.isMedia) "" else liveExtra?.pillText.orEmpty()
            ).also {
                cachedPayload = it
                NotificationIconStore.putPayload(it)
            }
        }

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
                dispatchOverlayBanner(ensurePayloadWithIcon(), prefs)
            }
            return
        }

        // 统一链路：优先处理手动锁定通知（保持常驻直到用户取消或原通知移除）
        // 针对音乐类应用上/下一曲异步加载封面的场景：同时比对文案签名与封面位图指纹，并挂载 MediaController 实时监听封面变更
        val currentPinned = _pinnedKey.value
        val currentPinnedPkg = _pinnedPackage.value
        if (prefs.enableCapsuleEngine && currentPinned != null && item.key == currentPinned &&
            (currentPinnedPkg == null || item.packageName == currentPinnedPkg)
        ) {
            attachPinnedMediaObserverIfNeeded(sbn, item.key)
            val freshPayload = ensurePayloadWithIcon()
            val iconHash = NotificationIconStore.bitmapFingerprint(freshPayload.iconBitmap)
            val titleChanged = sig != lastPushedSignature
            if (freshPayload.isMedia) {
                pinnedMediaController?.let { ctrl ->
                    updateMediaAnchorFromController(ctrl, fallbackDur = freshPayload.mediaDurationMs)
                    if (titleChanged) {
                        lastQuantizedStep = -1
            lastPushedSecond = -1L
                        schedulePostSwitchMediaResync(ctrl, item.key)
                    }
                }
            }
            if (titleChanged || iconHash != lastPushedIconHash) {
                lastPushedSignature = sig
                lastPushedIconHash = iconHash
                pushToCapsule(freshPayload, prefs = prefs, isPinned = true)
            }
            if (freshPayload.isMedia) {
                if (freshPayload.mediaContentType == MediaContentType.MUSIC && prefs.showMediaMetadataLyricInCapsule) {
                    startOrSyncLrcCapsuleEngine(freshPayload)
                    startOrSyncBannerLrcEngine(freshPayload, prefs)
                } else if (freshPayload.mediaContentType != MediaContentType.MUSIC) {
                    LrcCapsuleLyricEngine.stop()
            BannerLrcLyricEngine.stop()
                }
            }
            return
        }

        // 自动拦截媒体播放与录音机（免受高频常驻过滤；不覆盖用户手动锁定）
        val actNames = bannerActions.map { it.title }
        val specialKind = MediaRecorderInterceptor.classify(sbn, item.title, item.text, actNames, item.channelName)
        val allowSpecial = MediaRecorderInterceptor.shouldAutoIntercept(specialKind, prefs)
        if (specialKind != MediaRecorderInterceptor.SpecialOngoingKind.NONE) {
            AppLogger.d(
                "Notif",
                "特殊常驻判定 kind=$specialKind allow=$allowSpecial pinned=${currentPinned != null} " +
                    "suppressed=${item.key in suppressedKeys} acts=$actNames"
            )
        }
        // 若属于媒体/录音通知但用户已关闭媒体拦截总开关，禁止回落到普通通知镜像通道顶掉手动胶囊
        if (specialKind != MediaRecorderInterceptor.SpecialOngoingKind.NONE && !allowSpecial) {
            if (autoMediaKey == item.key) {
                clearAutoMediaIntercept(this)
            }
            AppLogger.d("Notif", "已关闭媒体/录音拦截开关，跳过上胶囊防止顶掉当前胶囊 app=${item.appName} kind=$specialKind")
            return
        }
        val manualProtected = CapsulePublisher.isManualCapsulePinned() && !prefs.capsuleFollowBannerStyle
        if (currentPinned == null && !manualProtected && item.key !in suppressedKeys && allowSpecial) {
            val freshPayload = ensurePayloadWithIcon()
            if (specialKind == MediaRecorderInterceptor.SpecialOngoingKind.MEDIA_PLAYBACK &&
                prefs.mediaCapsuleAutoHideOnPause && freshPayload.isMedia && !freshPayload.mediaPlaying
            ) {
                if (autoMediaKey == item.key) {
                    scheduleDelayedMediaDismiss("媒体已暂停，自动收回胶囊", item.packageName, baseDelayMs = 2500L)
                }
                return
            }
            // 三重流氓媒体假通知防伪墙（严格 PlaybackState 校验 + AudioManager 音频焦点仲裁 + 5s 无变化频发临时黑名单）
            if (specialKind == MediaRecorderInterceptor.SpecialOngoingKind.MEDIA_PLAYBACK) {
                val rejectReason = evaluateMediaSpoofRejection(sbn, item.packageName, freshPayload)
                if (rejectReason != null) {
                    AppLogger.w("Notif", "拦截流氓/假媒体通知抢占 pkg=${item.packageName} app=${item.appName} 原因=$rejectReason")
                    return
                }
                if (shouldYieldToActiveHigherPriorityMedia(item.packageName, freshPayload.mediaContentType, freshPayload.mediaPlaying)) {
                    AppLogger.d("Notif", "当前前台/视频媒体活跃中，后台媒体通知让位防冲突 app=${item.appName} type=${freshPayload.mediaContentType}")
                    return
                }
            }
            cancelPendingMediaDismiss()
            autoMediaKey = item.key
            autoMediaPackage = item.packageName
            if (specialKind == MediaRecorderInterceptor.SpecialOngoingKind.MEDIA_PLAYBACK) {
                attachPinnedMediaObserverIfNeeded(sbn, item.key)
            }
            val iconHash = NotificationIconStore.bitmapFingerprint(freshPayload.iconBitmap)
            val effectiveSig = if (prefs.showMediaMetadataLyricInCapsule && freshPayload.liveLyricLine.isNotBlank()) {
                "$sig|lrc=${freshPayload.liveLyricLine}"
            } else sig
            val titleChanged = sig != lastPushedSignature?.substringBefore("|lrc=")
            val contentChanged = effectiveSig != lastPushedSignature
            if (freshPayload.isMedia) {
                pinnedMediaController?.let { ctrl ->
                    updateMediaAnchorFromController(ctrl, fallbackDur = freshPayload.mediaDurationMs)
                    if (titleChanged) {
                        lastQuantizedStep = -1
                        lastPushedSecond = -1L
                        schedulePostSwitchMediaResync(ctrl, item.key)
                    }
                }
            }
            if (contentChanged || iconHash != lastPushedIconHash || _activeSourceKey.value != item.key) {
                lastPushedSignature = effectiveSig
                lastPushedIconHash = iconHash
                if (titleChanged || _activeSourceKey.value != item.key) {
                    AppLogger.i("Notif", "自动拦截${if (specialKind == MediaRecorderInterceptor.SpecialOngoingKind.RECORDER) "录音/录制" else "媒体播放"}到胶囊 app=${item.appName} pkg=${item.packageName}")
                }
                pushToCapsule(freshPayload, prefs = prefs, isPinned = true)
            }
            if (freshPayload.isMedia) {
                if (freshPayload.mediaContentType == MediaContentType.MUSIC && prefs.showMediaMetadataLyricInCapsule) {
                    startOrSyncLrcCapsuleEngine(freshPayload)
                    startOrSyncBannerLrcEngine(freshPayload, prefs)
                } else if (freshPayload.mediaContentType != MediaContentType.MUSIC) {
                    LrcCapsuleLyricEngine.stop()
            BannerLrcLyricEngine.stop()
                }
            }
            return
        }

        // 第三方实况通知（高德导航/打车、美团/饿了么等含 superx / miui.focus 关联字段）直接复用现有替换机制上胶囊
        if (liveExtra != null && prefs.enableCapsuleEngine && currentPinned == null && !manualProtected && item.key !in suppressedKeys) {
            val livePayload = ensurePayloadWithIcon()
            val liveSig = "${item.packageName}|${item.key}|${liveExtra.pillText}|${liveExtra.title}|${liveExtra.subtitle}"
            val liveHash = NotificationIconStore.bitmapFingerprint(livePayload.iconBitmap)
            if (liveSig != lastPushedSignature || liveHash != lastPushedIconHash || _activeSourceKey.value != item.key) {
                lastPushedSignature = liveSig
                lastPushedIconHash = liveHash
                AppLogger.i("Notif", "提取实况关联字段替换上胶囊 src=${liveExtra.source} app=${item.appName} pill=${liveExtra.pillText.take(20)}")
                pushToCapsule(livePayload, prefs = prefs, isPinned = true)
            }
            return
        }

        // 1. 顶层覆盖横幅（完全独立开关与过滤）
        val nativeCapsuleReadyNow = DeviceBrandRouter.isNativeCapsuleReady(this)
        val bannerPassed = NotificationFilterEngine.shouldTriggerBanner(
            item = item,
            prefs = prefs,
            isWhitelisted = isWhitelisted,
            sig = sig,
            forceUniversalFallback = !nativeCapsuleReadyNow
        )
        if (bannerPassed && isScreenOn) {
            dispatchOverlayBanner(ensurePayloadWithIcon(), prefs)
        }

        // 2. 荣耀灵动胶囊（完全独立开关与过滤，与横幅彻底解耦）
        if (prefs.enableCapsuleEngine && currentPinned == null && !manualProtected && item.key !in suppressedKeys) {
            // 正在播放媒体/录音时，禁止静默/低优先级通知（如 Play 商店静默推广、系统管家服务）抢占胶囊位
            val mediaActiveNow = isMediaOrRecorderActive()
            val isLowOrSilent = item.importance <= android.app.NotificationManager.IMPORTANCE_LOW
            if (mediaActiveNow && isLowOrSilent && !isWhitelisted) {
                AppLogger.d("Notif", "媒体/录音胶囊活跃中，拦截静默/低优通知抢占 app=${item.appName} imp=${item.importance}")
            } else {
                val capsulePassed = NotificationFilterEngine.shouldTriggerCapsule(
                    item = item,
                    prefs = prefs,
                    isWhitelisted = isWhitelisted,
                    sig = sig,
                    bannerPassed = bannerPassed
                )
                if (capsulePassed && (isScreenOn || !prefs.capsuleAutoDismiss)) {
                    lastPushedSignature = sig
                    pushToCapsule(ensurePayloadWithIcon(), prefs = prefs, isPinned = false)
                }
            }
        }
    }

    private fun startOrSyncLrcCapsuleEngine(payload: ActiveMirroredPayload) {
        if (!payload.isMedia || payload.title.isBlank()) return
        LrcCapsuleLyricEngine.onTrackActive(
            context = this,
            notifKey = payload.key,
            title = payload.title,
            artist = payload.text,
            durationMs = payload.mediaDurationMs
        ) { targetKey, lyricLine ->
            worker.execute {
                runCatching {
                    val prefs = CapsulePrefs(this@CapsuleNotificationListener).load()
                    if (!prefs.hasAnyDisplayEngine() || !prefs.showMediaMetadataLyricInCapsule) return@runCatching
                    if (!prefs.enableMediaRecorderCapsule && _pinnedKey.value != targetKey) return@runCatching
                    if (CapsulePublisher.isManualCapsulePinned() && !prefs.capsuleFollowBannerStyle) return@runCatching
                    if (!isTrackedMediaKey(targetKey)) return@runCatching
                    val base = NotificationIconStore.getPayload(targetKey)
                        ?: NotificationIconStore.currentActivePayload
                        ?: return@runCatching
                    if (!base.isMedia || base.mediaContentType != MediaContentType.MUSIC || base.liveLyricLine == lyricLine) return@runCatching
                    // 胶囊联网 LRC 渠道与横幅通知严格隔离：仅更新状态栏胶囊 liveLyricLine，绝不推送到横幅通知
                    val updated = base.copy(liveLyricLine = lyricLine)
                    NotificationIconStore.putPayload(updated)
                    if (prefs.enableCapsuleEngine && !CapsuleNavigationEngine.isRunning() && !CapsulePublisher.isSysEventPreempting()) {
                        CapsulePublisher.publishMirrored(
                            context = this@CapsuleNotificationListener,
                            baseConfig = prefs,
                            payload = updated,
                            isPinned = true
                        )
                    }
                }
            }
        }
    }

    /**
     * 横幅专属独立联网 LRC 通道：与胶囊 LrcCapsuleLyricEngine 完全隔离，
     * 走独立的 LRCLIB 优先联网源与独立缓存目录，仅推送到横幅宿主。
     */
    private fun startOrSyncBannerLrcEngine(payload: ActiveMirroredPayload, prefs: CapsuleConfig) {
        if (!payload.isMedia || payload.mediaContentType != MediaContentType.MUSIC || payload.title.isBlank()) return
        val bannerEnabled = prefs.enableOverlayBanner ||
            prefs.enableUniversalBannerMode ||
            !DeviceBrandRouter.isNativeCapsuleEngine()
        if (!bannerEnabled) return
        BannerLrcLyricEngine.onTrackActive(
            context = this,
            notifKey = payload.key,
            title = payload.title,
            artist = payload.text,
            durationMs = payload.mediaDurationMs
        ) { targetKey, lyricLine ->
            worker.execute {
                runCatching {
                    val prefsNow = CapsulePrefs(this@CapsuleNotificationListener).load()
                    if (!prefsNow.showMediaMetadataLyricInCapsule) return@runCatching
                    val activeBanner = prefsNow.enableOverlayBanner ||
                        prefsNow.enableUniversalBannerMode ||
                        !DeviceBrandRouter.isNativeCapsuleEngine()
                    if (!activeBanner) return@runCatching
                    if (!isTrackedMediaKey(targetKey)) return@runCatching
                    val base = NotificationIconStore.getPayload(targetKey)
                        ?: NotificationIconStore.currentActivePayload
                        ?: return@runCatching
                    if (!base.isMedia || base.mediaContentType != MediaContentType.MUSIC || base.bannerNativeLyric == lyricLine) return@runCatching
                    val updated = base.copy(bannerNativeLyric = lyricLine)
                    NotificationIconStore.putPayload(updated)
                    UniversalBannerHost.updateLiveLyric(updated.key, lyricLine)
                    CapsuleOverlayAccessibilityService.updateLiveLyric(updated.key, lyricLine)
                }
            }
        }
    }

    private fun attachMediaObserverForActiveKey(notifKey: String) {
        worker.execute {
            val sbn = runCatching { activeNotifications?.firstOrNull { it.key == notifKey } }.getOrNull() ?: return@execute
            attachPinnedMediaObserverIfNeeded(sbn, notifKey)
            pinnedMediaController?.let { ctrl ->
                syncPinnedMediaProgress(ctrl, notifKey, forcePush = true)
            }
        }
    }

    private fun schedulePostSwitchMediaResync(ctrl: MediaController, notifKey: String) {
        scheduleImmediateMediaResync()
    }

    @Suppress("DEPRECATION")
    private fun attachPinnedMediaObserverIfNeeded(sbn: StatusBarNotification, notifKey: String) {
        val extras = sbn.notification?.extras ?: return
        val extraToken = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            }
        }.getOrNull()
        val token = extraToken ?: runCatching {
            val msm = getSystemService(MediaSessionManager::class.java)
            val cn = ComponentName(this, CapsuleNotificationListener::class.java)
            msm?.getActiveSessions(cn)?.firstOrNull { it.packageName == sbn.packageName }?.sessionToken
        }.getOrNull()
        if (token == null) {
            AppLogger.d("Capsule", "未找到可绑定的 MediaSession token pkg=${sbn.packageName}")
            return
        }
        AppLogger.d("Capsule", "匹配到 MediaSession token pkg=${sbn.packageName} source=${if (extraToken != null) "EXTRA" else "MSM_FALLBACK"}")

        if (pinnedMediaToken == token && pinnedMediaNotifKey == notifKey && pinnedMediaController != null && pinnedMediaCallback != null) return
        detachPinnedMediaObserver()

        runCatching {
            val ctrl = MediaController(applicationContext, token)
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (state == null || !isTrackedMediaKey(notifKey)) return
                    syncPinnedMediaProgress(ctrl, notifKey, pollController = true, explicitState = state, forcePush = false)
                    LrcCapsuleLyricEngine.resyncTimeline()
                    BannerLrcLyricEngine.resyncTimeline()
                }

                override fun onSessionEvent(event: String, extras: android.os.Bundle?) {
                    if (!isTrackedMediaKey(notifKey)) return
                    handleSessionLyricEvent(this@CapsuleNotificationListener, ctrl.packageName.orEmpty(), event, extras)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    if (metadata == null || !isTrackedMediaKey(notifKey)) return
                    val pkgName = ctrl.packageName.orEmpty().ifBlank { sbn.packageName }
                    val metaIdNow = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim().orEmpty()
                    val metaTitleNow = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
                    val metaDurNow = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
                    val existingBase = NotificationIconStore.getPayload(notifKey)

                    @Suppress("DEPRECATION")
                    val isBtA2dpOnNow = runCatching {
                        getSystemService(AudioManager::class.java)?.isBluetoothA2dpOn == true
                    }.getOrDefault(false)
                    val sameDurationNow = metaDurNow <= 0L || (existingBase?.mediaDurationMs ?: 0L) <= 0L ||
                        kotlin.math.abs(metaDurNow - (existingBase?.mediaDurationMs ?: 0L)) <= 1500L
                    val curPosNow1 = ctrl.playbackState?.position ?: 0L
                    val isPlayingNow1 = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
                    val matchesLiveLyric1 = metaTitleNow.isNotBlank() && (
                        metaTitleNow == LrcCapsuleLyricEngine.liveLyric.value.line ||
                            metaTitleNow == existingBase?.liveLyricLine
                        )
                    val isBtLyricNow = sameDurationNow &&
                        metaTitleNow.isNotBlank() &&
                        !existingBase?.title.isNullOrBlank() &&
                        metaTitleNow != existingBase?.title &&
                        (isBtA2dpOnNow || matchesLiveLyric1 || (isPlayingNow1 && curPosNow1 > 2500L))

                    val prevTitle = existingBase?.title.orEmpty()
                    val newTrackIdentity = extractMetadataTrackIdentity(pkgName, metadata)
                    val isTitleChanged = !isBtLyricNow && metaTitleNow.isNotBlank() && prevTitle.isNotBlank() && metaTitleNow != prevTitle
                    val isIdentityChanged = !isBtLyricNow && newTrackIdentity.isNotBlank() &&
                        lastCommittedTrackIdentity.isNotBlank() && newTrackIdentity != lastCommittedTrackIdentity
                    val detectedNewTrack = isTitleChanged || (isIdentityChanged && metaTitleNow.isNotBlank())

                    val activeGen = if (detectedNewTrack) {
                        val appLabel = existingBase?.appName?.ifBlank { appNameCache[pkgName] ?: pkgName }
                            ?: (appNameCache[pkgName] ?: pkgName)
                        triggerImmediateTrackSwitchPlaceholder(
                            context = this@CapsuleNotificationListener,
                            notifKey = notifKey,
                            pkg = pkgName,
                            appName = appLabel,
                            newTitle = metaTitleNow
                        )
                    } else {
                        trackSwitchGeneration.get()
                    }

                    pendingMetadataCommitTask?.let { mediaProgressHandler.removeCallbacks(it) }
                    val commitTask = Runnable {
                        worker.execute {
                            runCatching {
                                if (activeGen != trackSwitchGeneration.get()) return@runCatching
                                // 防抖落地后直接从 MediaController 拉取最新元数据，避免读取陈旧回调快照或旧 currentActivePayload
                                val freshMeta = ctrl.metadata ?: metadata
                                val metaId = freshMeta.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim().orEmpty()
                                val metaTitle = freshMeta.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
                                val metaArtist = freshMeta.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
                                val metaDur = freshMeta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)

                                val art = freshMeta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                                    ?: freshMeta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                                    ?: freshMeta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                                val roundedArt = if (art != null && !art.isRecycled) {
                                    NotificationIconStore.roundedFromBitmap(art)
                                } else null
                                // 异步解码封面后二次校验代数，若期间用户再次切歌则直接废弃旧图
                                if (activeGen != trackSwitchGeneration.get()) return@runCatching

                                val base = NotificationIconStore.getPayload(notifKey)
                                    ?: MediaRecorderInterceptor.buildPayloadFromMediaController(this@CapsuleNotificationListener, ctrl)
                                    ?: return@runCatching

                                val sameDuration = metaDur <= 0L || base.mediaDurationMs <= 0L ||
                                    kotlin.math.abs(metaDur - base.mediaDurationMs) <= 1500L
                                val isBtLyricInTitle = isBtA2dpOnNow &&
                                    metaTitle.isNotBlank() &&
                                    base.title.isNotBlank() &&
                                    metaTitle != base.title &&
                                    sameDuration

                                val trackChanged = if (isBtLyricInTitle) {
                                    false
                                } else {
                                    MediaProgressSyncEngine.onTrackMetadataCheck(
                                        mediaId = metaId,
                                        title = metaTitle.ifBlank { base.title },
                                        durationMs = metaDur
                                    ) || detectedNewTrack
                                }
                                val snap = if (trackChanged) {
                                    stopMediaProgressTicker()
                                    lastQuantizedStep = -1
                                    lastPushedSecond = -1L
                                    MediaProgressSyncEngine.currentSnapshot()
                                } else {
                                    updateMediaAnchorFromController(ctrl, fallbackDur = if (metaDur > 0L) metaDur else base.mediaDurationMs)
                                }

                                val pkgKey = base.packageName.ifBlank { pkgName }
                                if (pkgKey.isNotBlank() && NativeMediaCardBridge.isNativeRatingSupported(ctrl)) {
                                    favoriteStateByPkg[pkgKey] = NativeMediaCardBridge.isCurrentlyFavorited(ctrl)
                                }
                                val metaLyricField = NativeMediaCardBridge.extractLiveLyricLine(freshMeta)
                                if (isBtLyricInTitle || metaLyricField.isNotBlank()) {
                                    LrcCapsuleLyricEngine.markNativeLyricActive()
                BannerLrcLyricEngine.markNativeLyricActive()
                                }
                                val nativeBannerLyric = when {
                                    isBtLyricInTitle -> metaTitle
                                    metaLyricField.isNotBlank() -> metaLyricField
                                    trackChanged -> ""
                                    else -> base.bannerNativeLyric
                                }
                                val liveLyric = when {
                                    isBtLyricInTitle -> metaTitle
                                    metaLyricField.isNotBlank() -> metaLyricField
                                    trackChanged -> ""
                                    else -> base.liveLyricLine
                                }
                                val updatedTitle = if (isBtLyricInTitle) base.title else metaTitle.ifBlank { if (trackChanged) "加载中..." else base.title }
                                // 切歌时严禁沿用上一首的歌手/封面旧缓存
                                val updatedText = if (trackChanged) metaArtist else metaArtist.ifBlank { base.text }
                                val updatedBmp = if (trackChanged) {
                                    roundedArt ?: NotificationIconStore.resolveAppIconBitmap(this@CapsuleNotificationListener, pkgKey)
                                } else {
                                    roundedArt ?: base.iconBitmap
                                }
                                val trackToken = "trk_$activeGen"
                                if (roundedArt != null) {
                                    UniversalBannerHost.applyTaggedCoverBitmap(notifKey, trackToken, roundedArt)
                                    CapsuleOverlayAccessibilityService.applyTaggedCoverBitmap(notifKey, trackToken, roundedArt)
                                }
                                val newSig = "${base.packageName}|${base.key}|$updatedTitle|$updatedText"
                                val newHash = NotificationIconStore.bitmapFingerprint(updatedBmp)
                                lastPushedSignature = newSig
                                lastPushedIconHash = newHash
                                if (!isBtLyricInTitle) {
                                    lastCommittedTrackIdentity = extractMetadataTrackIdentity(pkgKey, freshMeta)
                                }
                                val updatedPayload = base.copy(
                                    title = updatedTitle,
                                    text = updatedText,
                                    iconBitmap = updatedBmp,
                                    isMedia = true,
                                    mediaDurationMs = snap.durationMs,
                                    mediaPositionMs = snap.positionMs,
                                    mediaPlaying = snap.isActivelyPlaying,
                                    liveLyricLine = liveLyric,
                                    bannerNativeLyric = nativeBannerLyric
                                )
                                NotificationIconStore.putPayload(updatedPayload)
                                CapsuleOverlayAccessibilityService.updateBannerMediaProgress(
                                    notifKey, snap.positionMs, snap.durationMs, snap.isActivelyPlaying, snap.playbackSpeed
                                )
                                UniversalBannerHost.updateMediaProgress(
                                    notifKey, snap.positionMs, snap.durationMs, snap.isActivelyPlaying, snap.playbackSpeed
                                )
                                val prefsNow = CapsulePrefs(this@CapsuleNotificationListener).load()
                                AppLogger.i("Capsule", "MediaSession 元数据防抖同步 changed=$trackChanged gen=$activeGen title=$updatedTitle dur=${snap.durationMs}ms pos=${snap.positionMs}ms playing=${snap.isActivelyPlaying}")
                                pushToCapsule(updatedPayload, prefs = prefsNow, isPinned = true)
                                if (trackChanged) {
                                    startMediaProgressTicker(ctrl, notifKey, pollInitial = false)
                                    scheduleImmediateMediaResync()
                                }
                            }
                        }
                    }
                    pendingMetadataCommitTask = commitTask
                    val debounceDelay = if (isBtLyricNow) 0L else METADATA_DEBOUNCE_MS
                    mediaProgressHandler.postDelayed(commitTask, debounceDelay)
                }
            }
            ctrl.registerCallback(cb, mediaProgressHandler)
            runCatching { ctrl.sendCommand(EVENT_COMMAND_QUERY_LYRIC, android.os.Bundle(), android.os.ResultReceiver(mediaProgressHandler)) }
            pinnedMediaController = ctrl
            pinnedMediaCallback = cb
            pinnedMediaToken = token
            pinnedMediaNotifKey = notifKey
            AppLogger.i("Capsule", "已挂载锁定音乐 MediaSession 封面实时监听 pkg=${sbn.packageName}")
            startMediaProgressTicker(ctrl, notifKey)
        }.onFailure { e ->
            AppLogger.w("Capsule", "挂载 MediaSession 监听失败：${e.message}")
        }
    }

    private fun localizeActionLabel(raw: String): String {
        if (raw.isBlank()) return ""
        val norm = raw.trim().uppercase().replace(" ", "").replace("_", "").replace("-", "")
        return when (norm) {
            "PREV", "PRE", "PREVIOUS", "SKIPTOPREVIOUS", "SKIPPREVIOUS", "LAST" -> "上一曲"
            "NEXT", "SKIPTONEXT", "SKIPNEXT" -> "下一曲"
            "PLAY" -> "播放"
            "PAUSE" -> "暂停"
            "PLAY/PAUSE", "PLAYPAUSE", "PLAYORPAUSE", "TOGGLEPLAY", "TOGGLE" -> "播放/暂停"
            "MINILYRIC", "LYRIC", "LYRICS", "DESKTOPLYRIC", "LRC" -> "歌词"
            "CLOSE", "DISMISS", "STOP", "EXIT" -> "关闭"
            "LIKE", "FAVORITE", "FAV", "LOVE" -> "喜欢"
            "UNLIKE", "UNFAVORITE", "UNFAV" -> "取消喜欢"
            "REPLY", "QUICKREPLY" -> "回复"
            "MARKASREAD", "READ", "MARKREAD" -> "标为已读"
            "ANSWER", "ACCEPT" -> "接听"
            "DECLINE", "REJECT", "HANGUP" -> "挂断"
            "OPEN", "VIEW" -> "打开"
            "COPY" -> "复制"
            "DELETE", "REMOVE" -> "删除"
            "CANCEL" -> "取消"
            "MUTE" -> "静音"
            else -> raw
        }
    }

    private fun updateMediaAnchorFromController(
        ctrl: MediaController,
        fallbackDur: Long = 0L,
        explicitState: PlaybackState? = null,
        forceOverwrite: Boolean = false
    ): MediaProgressSyncEngine.SyncSnapshot {
        val meta = runCatching { ctrl.metadata }.getOrNull()
        val ps = explicitState ?: runCatching { ctrl.playbackState }.getOrNull()
        val rawDur = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
        val durHint = if (rawDur > 0L) rawDur else fallbackDur.coerceAtLeast(0L)
        val snap = MediaProgressSyncEngine.applySystemPlaybackState(
            ps = ps,
            durationHintMs = durHint,
            forceOverwrite = forceOverwrite
        )
        if (snap.snappedBigJump) {
            AppLogger.d("Capsule", "检测到 >1.5s 进度跳变，已直接对齐系统回调 pos=${snap.positionMs}ms state=${snap.state}")
        }
        return snap
    }

    private fun computeInterpolatedPositionMs(): Long = MediaProgressSyncEngine.computeClampedPositionMs()

    private fun syncPinnedMediaProgress(
        ctrl: MediaController,
        notifKey: String,
        pollController: Boolean = false,
        explicitState: PlaybackState? = null,
        forceOverwrite: Boolean = false,
        forcePush: Boolean = true
    ) {
        worker.execute {
            runCatching {
                if (!isTrackedMediaKey(notifKey)) return@runCatching
                val base = NotificationIconStore.getPayload(notifKey)
                    ?: NotificationIconStore.currentActivePayload
                    ?: return@runCatching
                val snap = if (pollController || explicitState != null || forceOverwrite) {
                    updateMediaAnchorFromController(
                        ctrl = ctrl,
                        fallbackDur = base.mediaDurationMs,
                        explicitState = explicitState,
                        forceOverwrite = forceOverwrite
                    )
                } else {
                    MediaProgressSyncEngine.currentSnapshot()
                }
                val dur = snap.durationMs
                val pos = snap.positionMs
                val acts = (explicitState ?: if (pollController) runCatching { ctrl.playbackState }.getOrNull() else null)?.actions
                val rawSeek = if (acts != null) {
                    (acts and (
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_FAST_FORWARD or
                        PlaybackState.ACTION_REWIND
                    )) != 0L
                } else {
                    base.mediaSupportsSeek
                }
                if (rawSeek) {
                    seekCapableByPkg[base.packageName] = true
                }
                val isTransitioning = snap.state == PlaybackState.STATE_BUFFERING ||
                    snap.state == PlaybackState.STATE_CONNECTING ||
                    snap.state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
                    snap.state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                val supportsSeek = rawSeek || (isTransitioning && seekCapableByPkg[base.packageName] == true) ||
                    (acts == 0L && seekCapableByPkg[base.packageName] == true)

                val posSec = pos / 1000L
                val stepPct = if (dur > 0L) ((pos * 100L) / dur).toInt().coerceIn(0, 100) else -1
                val secDrift = kotlin.math.abs(posSec - lastPushedSecond)
                if (!forcePush && !forceOverwrite && !snap.snappedBigJump &&
                    stepPct == lastQuantizedStep && secDrift < 2L &&
                    snap.isActivelyPlaying == base.mediaPlaying &&
                    dur == base.mediaDurationMs &&
                    supportsSeek == base.mediaSupportsSeek
                ) {
                    CapsuleOverlayAccessibilityService.updateBannerMediaProgress(
                        notifKey, pos, dur, snap.isActivelyPlaying, snap.playbackSpeed
                    )
                    UniversalBannerHost.updateMediaProgress(
                        notifKey, pos, dur, snap.isActivelyPlaying, snap.playbackSpeed
                    )
                    return@runCatching
                }
                lastPushedSecond = posSec
                lastQuantizedStep = stepPct

                val updated = base.copy(
                    isMedia = true,
                    mediaDurationMs = dur,
                    mediaPositionMs = pos,
                    mediaPlaying = snap.isActivelyPlaying,
                    mediaSupportsSeek = supportsSeek
                )
                NotificationIconStore.putPayload(updated)
                CapsuleOverlayAccessibilityService.updateBannerMediaProgress(
                    key = notifKey,
                    positionMs = pos,
                    durationMs = dur,
                    playing = snap.isActivelyPlaying,
                    speed = snap.playbackSpeed
                )
                UniversalBannerHost.updateMediaProgress(
                    key = notifKey,
                    positionMs = pos,
                    durationMs = dur,
                    playing = snap.isActivelyPlaying,
                    speed = snap.playbackSpeed
                )
                val prefsNow = CapsulePrefs(this@CapsuleNotificationListener).load()
                pushToCapsule(updated, prefs = prefsNow, isPinned = true)
            }
        }
    }

    private fun startMediaProgressTicker(
        ctrl: MediaController,
        notifKey: String,
        pollInitial: Boolean = true
    ) {
        stopMediaProgressTicker()
        if (pollInitial) {
            updateMediaAnchorFromController(ctrl)
        }
        // 荣耀机型展开卡片为 RemoteViews，需要保持低频自适应进度同步以防止 Chronometer 越界或切歌后进度残留
        val ticker = object : Runnable {
            override fun run() {
                if (!isTrackedMediaKey(notifKey) || pinnedMediaController != ctrl) {
                    AppLogger.d("Capsule", "媒体进度定时器停止 key=$notifKey tracked=${isTrackedMediaKey(notifKey)} sameCtrl=${pinnedMediaController == ctrl}")
                    stopMediaProgressTicker()
                    return
                }
                val isScreenOn = runCatching { getSystemService(PowerManager::class.java)?.isInteractive == true }
                    .getOrDefault(false)
                val activePlaying = MediaProgressSyncEngine.isActivelyPlaying()
                val dur = MediaProgressSyncEngine.currentDurationMs()
                // 日常播放零 Binder 轮询：严格仅在亮屏且 STATE_PLAYING && speed > 0 时按纯本地推算步进 1%
                if (isScreenOn && activePlaying && dur > 0L) {
                    syncPinnedMediaProgress(ctrl, notifKey, pollController = false, forcePush = false)
                }
                val adaptiveDelayMs = when {
                    !isScreenOn -> 15_000L
                    activePlaying && dur > 0L -> (dur / 50L).coerceIn(4000L, 8000L)
                    else -> 6000L
                }
                mediaProgressHandler.postDelayed(this, adaptiveDelayMs)
            }
        }
        mediaProgressTicker = ticker
        val curDur = MediaProgressSyncEngine.currentDurationMs()
        val curPlay = MediaProgressSyncEngine.isActivelyPlaying()
        AppLogger.d("Capsule", "启动媒体进度定时器(降频节拍+插值补间) key=$notifKey pkg=${ctrl.packageName} dur=${curDur}ms play=$curPlay")
        val firstDelay = if (curDur > 0L && curPlay) (curDur / 50L).coerceIn(4000L, 6000L) else 4000L
        mediaProgressHandler.postDelayed(ticker, firstDelay)
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

        // 标准 Notification.actions（自动汉化常见英文媒体/交互标签，最多采集 5 个避免丢失上一曲/暂停/下一曲）
        n.actions?.forEach { act ->
            val rawLabel = act.title?.toString()?.trim().orEmpty()
            val label = localizeActionLabel(rawLabel)
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

        return result.take(5)
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
        prefs: CapsuleConfig,
        collapsibleToPill: Boolean = false
    ) {
        val hasNativeCapsule = DeviceBrandRouter.isNativeCapsuleReady(this)
        val isUniversal = !hasNativeCapsule || prefs.enableUniversalBannerMode
        if (!prefs.enableOverlayBanner && !isUniversal) return
        val canSysOverlay = UniversalBannerHost.canDrawOverlay(this)
        val canA11yOverlay = CapsuleOverlayAccessibilityService.connected.value

        // 非原生胶囊机型或开启通用模式时优先使用 TYPE_APPLICATION_OVERLAY；原生机型优先使用无障碍置顶层，未开启时回退至 TYPE_APPLICATION_OVERLAY
        val preferUniversalHost = canSysOverlay && (isUniversal || !canA11yOverlay)
        // 横幅仅消费播放器/系统原生直推歌词（bannerNativeLyric），绝不走胶囊联网 LRC 渠道
        val useNativeBannerLyric = payload.isMedia &&
            payload.mediaContentType == MediaContentType.MUSIC &&
            payload.bannerNativeLyric.isNotBlank()
        val bannerDetailText = if (useNativeBannerLyric) payload.bannerNativeLyric else payload.text
        val bannerLyricLine = if (useNativeBannerLyric) payload.bannerNativeLyric else ""

        if (preferUniversalHost) {
            // 双悬浮层严格互斥：弹出通用悬浮横幅前先清理无障碍层，杜绝双层叠影
            if (CapsuleOverlayAccessibilityService.isBannerVisible()) {
                CapsuleOverlayAccessibilityService.dismissBanner()
            }
            UniversalBannerHost.showBanner(
                context = this,
                appName = payload.appName,
                packageName = payload.packageName,
                title = payload.title,
                text = bannerDetailText,
                contentIntent = payload.contentIntent,
                iconBitmap = payload.iconBitmap,
                actions = payload.actions,
                config = prefs,
                notificationKey = payload.key,
                isPersistent = payload.isCall,
                collapsibleToPill = collapsibleToPill,
                isMedia = payload.isMedia,
                mediaDurationMs = payload.mediaDurationMs,
                mediaPositionMs = payload.mediaPositionMs,
                mediaPlaying = payload.mediaPlaying,
                mediaContentType = payload.mediaContentType,
                mediaSupportsSeek = payload.mediaSupportsSeek,
                liveLyricLine = bannerLyricLine
            )
            return
        }
        if (canA11yOverlay) {
            // 双悬浮层严格互斥：弹出无障碍横幅前先清理通用悬浮层
            UniversalBannerHost.dismiss()
            CapsuleOverlayAccessibilityService.showBanner(
                appName = payload.appName,
                packageName = payload.packageName,
                title = payload.title,
                text = bannerDetailText,
                contentIntent = payload.contentIntent,
                iconBitmap = payload.iconBitmap,
                actions = payload.actions,
                config = prefs,
                notificationKey = payload.key,
                isPersistent = payload.isCall,
                isMedia = payload.isMedia,
                mediaDurationMs = payload.mediaDurationMs,
                mediaPositionMs = payload.mediaPositionMs,
                mediaPlaying = payload.mediaPlaying,
                mediaContentType = payload.mediaContentType,
                mediaSupportsSeek = payload.mediaSupportsSeek
            )
        } else {
            AppLogger.w("Banner", "无法弹出横幅：既未授予悬浮窗权限(SYSTEM_ALERT_WINDOW)，也未开启无障碍服务")
        }
    }

    private fun registerActiveSessionsWatcher() {
        unregisterActiveSessionsWatcher()
        runCatching {
            val msm = getSystemService(MediaSessionManager::class.java) ?: return
            val cn = ComponentName(this, CapsuleNotificationListener::class.java)
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                worker.execute {
                    runCatching { handleActiveMediaSessions(controllers.orEmpty()) }
                }
            }
            msm.addOnActiveSessionsChangedListener(listener, cn, mediaProgressHandler)
            activeSessionsListener = listener
            val initial = runCatching { msm.getActiveSessions(cn) }.getOrDefault(emptyList())
            if (initial.isNotEmpty()) {
                worker.execute { runCatching { handleActiveMediaSessions(initial) } }
            }
        }.onFailure { e ->
            AppLogger.w("Notif", "注册 MediaSession 监听失败：${e.message}")
        }
    }

    private fun unregisterActiveSessionsWatcher() {
        watchedSessionCallbacks.values.forEach { (ctrl, cb) ->
            runCatching { ctrl.unregisterCallback(cb) }
        }
        watchedSessionCallbacks.clear()
        val listener = activeSessionsListener ?: return
        runCatching {
            val msm = getSystemService(MediaSessionManager::class.java)
            msm?.removeOnActiveSessionsChangedListener(listener)
        }
        activeSessionsListener = null
    }

    private fun syncWatchedSessionCallbacks(controllers: List<MediaController>) {
        val activeTokens = controllers.map { it.sessionToken }.toSet()
        // 清理已消失的会话回调
        val staleTokens = watchedSessionCallbacks.keys.filter { it !in activeTokens }
        staleTokens.forEach { tok ->
            watchedSessionCallbacks.remove(tok)?.let { (ctrl, cb) ->
                runCatching { ctrl.unregisterCallback(cb) }
            }
        }
        // 为所有外部活跃会话挂轻量级状态监听：一旦某个已有会话从暂停切回播放，立即触发接管
        controllers.forEach { ctrl ->
            if (ctrl.packageName == packageName) return@forEach
            val tok = ctrl.sessionToken
            if (watchedSessionCallbacks.containsKey(tok)) return@forEach
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    AppLogger.d("Notif", "会话状态变化 pkg=${ctrl.packageName} state=${state?.state} pos=${state?.position}")
                    if (state?.state == PlaybackState.STATE_PLAYING) {
                        worker.execute {
                            runCatching {
                                val msm = getSystemService(MediaSessionManager::class.java) ?: return@runCatching
                                val cn = ComponentName(this@CapsuleNotificationListener, CapsuleNotificationListener::class.java)
                                handleActiveMediaSessions(msm.getActiveSessions(cn))
                            }
                        }
                    }
                }
            }
            runCatching {
                ctrl.registerCallback(cb, mediaProgressHandler)
            runCatching { ctrl.sendCommand(EVENT_COMMAND_QUERY_LYRIC, android.os.Bundle(), android.os.ResultReceiver(mediaProgressHandler)) }
                watchedSessionCallbacks[tok] = ctrl to cb
            }
        }
    }

    private fun scheduleDelayedMediaDismiss(reason: String, targetPkg: String?, baseDelayMs: Long = 2500L) {
        cancelPendingMediaDismiss()
        val sinceSkip = SystemClock.elapsedRealtime() - lastTrackSkipElapsedMs
        val delayMs = if (sinceSkip in 0L..4000L) maxOf(baseDelayMs, 3200L) else baseDelayMs
        AppLogger.d("Notif", "媒体短暂中断，延迟 ${delayMs}ms 复核后再决定是否销毁胶囊：$reason (pkg=$targetPkg)")
        val task = Runnable {
            pendingMediaDismissTask = null
            worker.execute {
                runCatching {
                    if (_pinnedKey.value != null) {
                        AppLogger.d("Notif", "延迟销毁取消：用户已手动锁定胶囊")
                        return@runCatching
                    }
                    val prefsNow = CapsulePrefs(this@CapsuleNotificationListener).load()
                    // 复核 1：检查是否已有活跃或正在缓冲/切歌中的 MediaSession
                    val msm = getSystemService(MediaSessionManager::class.java)
                    val cn = ComponentName(this@CapsuleNotificationListener, CapsuleNotificationListener::class.java)
                    val sessions = runCatching { msm?.getActiveSessions(cn) }.getOrNull().orEmpty()
                    val activeCtrl = sessions.firstOrNull { c ->
                        c.packageName != packageName && isActiveOrTransitioningState(c.playbackState?.state)
                    }
                    if (activeCtrl != null) {
                        AppLogger.i("Notif", "切歌/缓冲已恢复播放(pkg=${activeCtrl.packageName})，取消胶囊销毁并同步新曲目")
                        handleActiveMediaSessions(sessions)
                        return@runCatching
                    }
                    // 复核 2：如果未开启「暂停自动收回」，且目标应用仍存在暂停态会话或活跃通知，保留胶囊
                    if (!prefsNow.mediaCapsuleAutoHideOnPause && !targetPkg.isNullOrBlank()) {
                        val hasPausedSession = sessions.any { it.packageName == targetPkg && it.playbackState?.state == PlaybackState.STATE_PAUSED }
                        val hasLiveNotif = runCatching { activeNotifications?.any { it.packageName == targetPkg } == true }.getOrDefault(false)
                        if (hasPausedSession || hasLiveNotif) {
                            AppLogger.d("Notif", "目标媒体仍处于暂停/通知存活状态(pkg=$targetPkg)，保留胶囊不销毁")
                            return@runCatching
                        }
                    }
                    // 确认媒体已真正停止，执行销毁
                    AppLogger.i("Notif", "复核确认无媒体活动，销毁胶囊：$reason (pkg=$targetPkg)")
                    detachPinnedMediaObserver()
                    autoMediaKey = null
                    autoMediaPackage = null
                    clearActiveSource()
                    if (CapsuleNavigationEngine.restoreActiveNavCapsuleIfNeeded(this@CapsuleNotificationListener)) {
                        AppLogger.i("Capsule", "媒体结束但导航仍在运行，已保留导航胶囊")
                    } else {
                        UniversalBannerHost.dismiss()
                        CapsulePublisher.dismiss(this@CapsuleNotificationListener, reason = reason)
                        runCatching { stopService(Intent(this@CapsuleNotificationListener, CapsuleKeepAliveService::class.java)) }
                    }
                }
            }
        }
        pendingMediaDismissTask = task
        mediaProgressHandler.postDelayed(task, delayMs)
    }

    private fun handleActiveMediaSessions(controllers: List<MediaController>) {
        syncWatchedSessionCallbacks(controllers)
        val summary = controllers.joinToString(", ") { c ->
            val st = c.playbackState
            val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val canSeek = ((st?.actions ?: 0L) and (PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND)) != 0L
            "${c.packageName}:st=${st?.state ?: -1}(dur=${dur}ms,seek=$canSeek)"
        }
        AppLogger.d("Notif", "MediaSession 扫描 count=${controllers.size} [$summary] autoKey=$autoMediaKey pinned=${_pinnedKey.value}")
        val prefs = CapsulePrefs(this).load()
        if (!prefs.hasAnyDisplayEngine() || !prefs.enableMediaRecorderCapsule || !prefs.interceptMediaPlayback) return
        if (_pinnedKey.value != null) return

        val playingCtrl = choosePreferredMediaController(controllers)
        if (playingCtrl == null) {
            val curAuto = autoMediaKey
            if (curAuto != null && curAuto.startsWith("media_session:")) {
                val stillAlive = controllers.any { "media_session:${it.packageName}" == curAuto }
                if (!stillAlive || prefs.mediaCapsuleAutoHideOnPause) {
                    scheduleDelayedMediaDismiss("媒体会话已停止，自动收回胶囊", autoMediaPackage, baseDelayMs = 2500L)
                }
            }
            return
        }
        cancelPendingMediaDismiss()

        if (autoMediaPackage == playingCtrl.packageName && autoMediaKey?.startsWith("media_session:") == false) {
            return
        }

        val rawPayload = MediaRecorderInterceptor.buildPayloadFromMediaController(this, playingCtrl) ?: return
        val arbitratedType = resolveArbitratedMediaType(playingCtrl, rawPayload.mediaContentType)
        val payload = if (arbitratedType != rawPayload.mediaContentType) {
            rawPayload.copy(mediaContentType = arbitratedType)
        } else rawPayload
        autoMediaKey = payload.key
        autoMediaPackage = payload.packageName
        NotificationIconStore.putPayload(payload)
        attachControllerObserverDirect(playingCtrl, payload.key)
        updateMediaAnchorFromController(playingCtrl, fallbackDur = payload.mediaDurationMs)
        if (payload.mediaContentType == MediaContentType.VIDEO) {
            LrcCapsuleLyricEngine.stop()
            BannerLrcLyricEngine.stop()
        } else if (payload.mediaContentType == MediaContentType.MUSIC && prefs.showMediaMetadataLyricInCapsule) {
            startOrSyncLrcCapsuleEngine(payload)
            startOrSyncBannerLrcEngine(payload, prefs)
        }
        val sig = "${payload.packageName}|${payload.key}|${payload.mediaContentType}|${payload.title}|${payload.text}"
        if (sig != lastPushedSignature || _activeSourceKey.value != payload.key) {
            lastPushedSignature = sig
            lastPushedIconHash = NotificationIconStore.bitmapFingerprint(payload.iconBitmap)
            AppLogger.i("Notif", "多会话仲裁展示${if (payload.mediaContentType == MediaContentType.VIDEO) "视频" else "音乐"}胶囊 app=${payload.appName} title=${payload.title}")
            pushToCapsule(payload, prefs = prefs, isPinned = true)
        }
    }

    /**
     * 读取系统当前活跃音频流属性（Android 8+ 公开 API，零额外权限），判断是否正存在视频(MOVIE)音频流。
     */
    /**
     * 尝试通过 AudioManager.getActivePlaybackConfigurations() 解析当前真正发声的包名集合（含反射安全获取 clientUid，无权时优雅降级）。
     */
    private fun resolveActiveSoundingPackages(): Set<String> {
        val result = HashSet<String>()
        runCatching {
            val am = getSystemService(AudioManager::class.java) ?: return emptySet()
            val configs = am.activePlaybackConfigurations
            if (configs.isEmpty()) return emptySet()
            val pm = packageManager
            for (cfg in configs) {
                val uid = runCatching {
                    cfg.javaClass.getMethod("getClientUid").invoke(cfg) as? Int
                }.getOrNull() ?: continue
                if (uid > 1000) {
                    pm.getPackagesForUid(uid)?.forEach { result.add(it) }
                }
            }
        }
        return result
    }

    /**
     * 三重流氓/挂起假媒体通知防伪拦截器（返回 null 表示放行，返回非空字符串表示拦截原因）：
     * 1. 时间窗口防抖与临时黑名单：同一包名 5 秒内状态未变连续推送 >2 次，立即拉入 30 秒临时黑名单屏蔽；
     * 2. 严格校验 PlaybackState：非当前已追踪媒体（试图新抢占胶囊）时，必须 STATE_PLAYING 且具备 ACTION_PLAY_PAUSE 能力，否则直接丢弃；
     * 3. AudioManager 音频焦点/活跃发声流仲裁：若当前有其他真实发声包或系统完全静音，且来者包名不在发声列表且非强制播放中，直接丢弃。
     */
    @Suppress("DEPRECATION")
    private fun evaluateMediaSpoofRejection(
        sbn: StatusBarNotification,
        pkg: String,
        payload: ActiveMirroredPayload
    ): String? {
        val now = SystemClock.elapsedRealtime()
        val isAlreadyTracked = (autoMediaKey == payload.key && autoMediaPackage == pkg)

        // 解析通知携带的真实 MediaSession -> PlaybackState
        val token = payload.mediaSessionToken
            ?: sbn.notification?.extras?.getParcelable(Notification.EXTRA_MEDIA_SESSION)
        val ctrl = if (token != null) runCatching { MediaController(this, token) }.getOrNull() else null
        val ps = ctrl?.playbackState
        val state = ps?.state ?: PlaybackState.STATE_NONE
        val actions = ps?.actions ?: 0L
        val hasPlayPauseAction = (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L
        val hasAnyToggleAction = hasPlayPauseAction || (actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)) != 0L
        val isTrulyPlaying = state == PlaybackState.STATE_PLAYING && (ps?.playbackSpeed ?: 0f) > 0f

        // 1. 临时黑名单检查（若状态真正切换为 STATE_PLAYING 且系统正在发声，允许提前解封）
        val blacklistUntil = tempMediaBlacklistUntilMs[pkg] ?: 0L
        if (now < blacklistUntil) {
            val am = getSystemService(AudioManager::class.java)
            val audioActiveNow = am?.isMusicActive == true
            if (!(isTrulyPlaying && hasPlayPauseAction && audioActiveNow)) {
                return "临时黑名单屏蔽中(剩余${((blacklistUntil - now) / 1000L).coerceAtLeast(1)}s)"
            }
            tempMediaBlacklistUntilMs.remove(pkg)
        }

        // 2. 5 秒时间窗口无变化频发防抖（针对 B站等后台挂起反复刷同一假通知）
        val stateSig = "$state|${payload.mediaPlaying}|${payload.title}|${payload.text}"
        val prevSpam = mediaSpamTracker[pkg]
        if (prevSpam != null && prevSpam.stateSig == stateSig) {
            synchronized(prevSpam.timestamps) {
                while (prevSpam.timestamps.isNotEmpty() && now - prevSpam.timestamps.first() > MEDIA_SPAM_WINDOW_MS) {
                    prevSpam.timestamps.removeFirst()
                }
                prevSpam.timestamps.addLast(now)
                if (!isAlreadyTracked && prevSpam.timestamps.size > MEDIA_SPAM_MAX_UNCHANGED_HITS) {
                    tempMediaBlacklistUntilMs[pkg] = now + MEDIA_TEMP_BLACKLIST_MS
                    return "5秒内状态未变重复推送${prevSpam.timestamps.size}次，已触发30秒临时黑名单"
                }
            }
        } else {
            val dq = ArrayDeque<Long>()
            dq.addLast(now)
            mediaSpamTracker[pkg] = MediaSpamState(stateSig, dq)
        }

        // 若是当前已经在胶囊上播放的同一个 App 自身状态更新（如正常暂停/切歌），跳过抢占校验
        if (isAlreadyTracked) return null

        // 3. 严格校验 PlaybackState：试图抢占胶囊的新媒体通知必须处于 STATE_PLAYING 且具备 ACTION_PLAY_PAUSE（或暂停/播放）能力
        if (ps == null) {
            return "缺少有效 MediaSession PlaybackState"
        }
        if (state != PlaybackState.STATE_PLAYING) {
            return "PlaybackState 非 STATE_PLAYING(state=$state)，禁止暂停/挂起态假通知抢占"
        }
        if (!hasPlayPauseAction && !hasAnyToggleAction) {
            return "缺少 ACTION_PLAY_PAUSE 播控能力(actions=$actions)，判定为僵尸 MediaSession"
        }

        // 4. AudioManager 音频焦点 / 活跃发声包仲裁
        val am = getSystemService(AudioManager::class.java)
        val isMusicAudioActive = am?.isMusicActive == true
        val activeAudioConfigs = runCatching { am?.activePlaybackConfigurations.orEmpty() }.getOrDefault(emptyList())
        val soundingPkgs = resolveActiveSoundingPackages()

        // 情况 A：能直接解析出正在发声的包名集合，且来者包名不在发声集合中
        if (soundingPkgs.isNotEmpty() && pkg !in soundingPkgs) {
            val curAuto = autoMediaPackage
            if (curAuto != null && curAuto in soundingPkgs) {
                return "音频焦点仍归属 $curAuto，拦截未发声包 $pkg 抢占"
            }
            if (!isTrulyPlaying) {
                return "未命中系统发声包($soundingPkgs)且非强制播放中"
            }
        }

        // 情况 B：系统当前完全无任何音频流发声（静音假通知）
        if (!isMusicAudioActive && activeAudioConfigs.isEmpty()) {
            return "系统当前无活跃音频输出(isMusicActive=false)，拦截后台静音假播放"
        }

        // 情况 C：已有真实音乐在播放，而视频 App 发来通知但系统中并无 MOVIE 音频流且该包未在发声列表
        val curAutoKey = autoMediaKey
        val curAutoPayload = if (curAutoKey != null) NotificationIconStore.getPayload(curAutoKey) else null
        if (curAutoPayload != null && curAutoPayload.isMedia && curAutoPayload.mediaPlaying &&
            payload.mediaContentType == MediaContentType.VIDEO &&
            !hasActiveMovieAudioStream() &&
            (soundingPkgs.isNotEmpty() && pkg !in soundingPkgs)
        ) {
            return "视频应用 $pkg 无活跃视频音频流，禁止挤掉正在播放的 ${curAutoPayload.appName}"
        }

        return null
    }

    private fun hasActiveMovieAudioStream(): Boolean {
        return runCatching {
            val am = getSystemService(AudioManager::class.java) ?: return false
            am.activePlaybackConfigurations.any { cfg ->
                cfg.audioAttributes?.contentType == AudioAttributes.CONTENT_TYPE_MOVIE
            }
        }.getOrDefault(false)
    }

    /**
     * 结合包名、MediaMetadata、前台媒体广播与系统音频流属性，精准判定该会话是 VIDEO 还是 MUSIC。
     */
    private fun resolveArbitratedMediaType(ctrl: MediaController, baseType: MediaContentType): MediaContentType {
        if (baseType == MediaContentType.RECORDER) return MediaContentType.RECORDER
        val pkg = ctrl.packageName.orEmpty()
        val now = SystemClock.elapsedRealtime()
        val recentBc = (now - lastMediaBroadcastElapsedMs) <= 4000L
        if (recentBc && lastMediaBroadcastPlaying && lastMediaBroadcastPkg != null && pkg.startsWith(lastMediaBroadcastPkg!!)) {
            lastMediaBroadcastType?.let { return it }
        }
        if (baseType == MediaContentType.VIDEO) return MediaContentType.VIDEO
        val meta = ctrl.metadata
        val hasArtist = !meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).isNullOrBlank()
        val hasAlbum = !meta?.getString(MediaMetadata.METADATA_KEY_ALBUM).isNullOrBlank()
        if (!hasArtist && !hasAlbum && hasActiveMovieAudioStream()) {
            return MediaContentType.VIDEO
        }
        return baseType
    }

    /**
     * 多媒体并发（音乐 + 视频同时播放）仲裁：
     * 1. 若仅 1 个活跃会话，直接返回；
     * 2. 若多个会话同时处于播放态：优先前台/最新焦点；当视频与音乐并发播放时，优先匹配前台广播来源或正在播放的视频胶囊（避免后台音乐抢占视频）；
     *    一旦视频暂停/停止，自动无缝切回仍在播放的音乐胶囊。
     */
    private fun isZombieMediaController(ctrl: MediaController): Boolean {
        val meta = runCatching { ctrl.metadata }.getOrNull()
        val dur = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
        val title = (meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)).orEmpty().trim()
        val artist = (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).orEmpty().trim()
        val acts = ctrl.playbackState?.actions ?: 0L
        val canSeek = (acts and (
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        )) != 0L
        return dur <= 0L && !canSeek && title.isBlank() && artist.isBlank()
    }

    private fun choosePreferredMediaController(controllers: List<MediaController>): MediaController? {
        val nowChoose = SystemClock.elapsedRealtime()
        val activeList = controllers.filter { ctrl ->
            val p = ctrl.packageName.orEmpty()
            val ps = ctrl.playbackState
            val hasPlayPauseCap = ((ps?.actions ?: 0L) and (
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY
            )) != 0L
            val blacklisted = (tempMediaBlacklistUntilMs[p] ?: 0L) > nowChoose && ps?.state != PlaybackState.STATE_PLAYING
            p != packageName &&
                !blacklisted &&
                ps?.state == PlaybackState.STATE_PLAYING &&
                hasPlayPauseCap &&
                !isZombieMediaController(ctrl)
        }
        if (activeList.isEmpty()) return null
        if (activeList.size == 1) return activeList[0]

        val now = SystemClock.elapsedRealtime()
        val recentBc = (now - lastMediaBroadcastElapsedMs) <= 4000L && lastMediaBroadcastPlaying
        val movieAudioActive = hasActiveMovieAudioStream()
        val soundingPkgs = resolveActiveSoundingPackages()

        var bestCtrl = activeList[0]
        var bestScore = Int.MIN_VALUE
        activeList.forEachIndexed { index, ctrl ->
            val pkg = ctrl.packageName.orEmpty()
            val meta = runCatching { ctrl.metadata }.getOrNull()
            val dur = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
            val metaTitle = (meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)).orEmpty().trim()
            val acts = ctrl.playbackState?.actions ?: 0L
            val canSeek = (acts and (
                PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
            )) != 0L
            val baseType = MediaRecorderInterceptor.detectMediaContentType(pkg, "", isRecorder = false)
            val resolvedType = resolveArbitratedMediaType(ctrl, baseType)

            var score = (activeList.size - index) * 35
            if (index == 0) score += 25
            if (dur > 0L) score += 35
            if (canSeek) score += 20
            if (metaTitle.isNotBlank()) score += 25
            if (soundingPkgs.isNotEmpty() && pkg in soundingPkgs) score += 80
            if (recentBc && lastMediaBroadcastPkg != null && pkg.startsWith(lastMediaBroadcastPkg!!)) {
                score += 60
            }
            // 仅当系统确实存在视频音频流(MOVIE)且该视频会话非空壳时才给视频加权
            if (resolvedType == MediaContentType.VIDEO && movieAudioActive && (dur > 0L || metaTitle.isNotBlank())) {
                score += 30
            }
            if (score > bestScore) {
                bestScore = score
                bestCtrl = ctrl
            }
        }
        return bestCtrl
    }

    /**
     * 当某应用通知推送过来时，判断是否应让位于当前更高优先级的活跃媒体（如：正在播视频时，后台音乐通知不应抢占视频胶囊）。
     */
    private fun shouldYieldToActiveHigherPriorityMedia(
        incomingPkg: String,
        incomingType: MediaContentType,
        incomingPlaying: Boolean
    ): Boolean {
        val curKey = autoMediaKey ?: return false
        val curPkg = autoMediaPackage ?: return false
        if (curPkg == incomingPkg) return false
        val curPayload = NotificationIconStore.getPayload(curKey) ?: return false
        if (!curPayload.isMedia || !curPayload.mediaPlaying) return false

        // 若当前占位的是空壳僵尸视频（无时长、无进度拖拽、标题仅应用名或“正在播放”），绝不阻拦真实音乐接管！
        val curIsZombie = curPayload.mediaDurationMs <= 0L &&
            !curPayload.mediaSupportsSeek &&
            (curPayload.title == curPayload.appName || curPayload.text == "正在播放")
        if (curIsZombie) {
            return false
        }

        // 场景 A：当前正在播放真实视频胶囊，而新来的是后台音乐通知 -> 仅当视频确实在发声或仍居系统焦点首位时让位
        if (curPayload.mediaContentType == MediaContentType.VIDEO && incomingType == MediaContentType.MUSIC) {
            val now = SystemClock.elapsedRealtime()
            val musicJustBroadcasted = (now - lastMediaBroadcastElapsedMs) <= 3000L &&
                lastMediaBroadcastPlaying &&
                lastMediaBroadcastPkg != null &&
                incomingPkg.startsWith(lastMediaBroadcastPkg!!)
            if (musicJustBroadcasted) return false
            val movieAudioActive = hasActiveMovieAudioStream()
            val soundingPkgs = resolveActiveSoundingPackages()
            if (soundingPkgs.isNotEmpty() && incomingPkg in soundingPkgs && curPkg !in soundingPkgs) {
                return false
            }
            if (!movieAudioActive) {
                val topPkg = runCatching {
                    val msm = getSystemService(MediaSessionManager::class.java)
                    val cn = ComponentName(this, CapsuleNotificationListener::class.java)
                    msm?.getActiveSessions(cn)?.firstOrNull { it.packageName != packageName }?.packageName
                }.getOrNull()
                if (topPkg == incomingPkg) return false
            }
            return true
        }
        // 场景 B：当前正在播放媒体，而新来的另一个媒体通知处于暂停态 -> 暂停态绝不抢占正在播放的胶囊
        if (!incomingPlaying && curPayload.mediaPlaying) {
            return true
        }
        return false
    }

    private fun attachControllerObserverDirect(ctrl: MediaController, notifKey: String) {
        val token = ctrl.sessionToken
        if (pinnedMediaToken == token && pinnedMediaNotifKey == notifKey && pinnedMediaController != null && pinnedMediaCallback != null) return
        detachPinnedMediaObserver()
        runCatching {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (state == null || !isTrackedMediaKey(notifKey)) return
                    val prefsNow = CapsulePrefs(this@CapsuleNotificationListener).load()
                    if (isActiveOrTransitioningState(state.state)) {
                        cancelPendingMediaDismiss()
                        syncPinnedMediaProgress(ctrl, notifKey)
                        return
                    }
                    val isStopped = state.state == PlaybackState.STATE_STOPPED || state.state == PlaybackState.STATE_NONE
                    val isPaused = state.state == PlaybackState.STATE_PAUSED
                    if (notifKey.startsWith("media_session:") && (isStopped || (isPaused && prefsNow.mediaCapsuleAutoHideOnPause))) {
                        scheduleDelayedMediaDismiss("媒体会话已暂停/停止", ctrl.packageName, baseDelayMs = 2500L)
                        return
                    }
                    syncPinnedMediaProgress(ctrl, notifKey, pollController = true, explicitState = state, forcePush = false)
                }

                override fun onSessionEvent(event: String, extras: android.os.Bundle?) {
                    if (!isTrackedMediaKey(notifKey)) return
                    handleSessionLyricEvent(this@CapsuleNotificationListener, ctrl.packageName.orEmpty(), event, extras)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    if (metadata == null || !isTrackedMediaKey(notifKey)) return
                    val pkgName = ctrl.packageName.orEmpty()
                    val metaTitleNow = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
                    val metaDurNow = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
                    val existingPayload = NotificationIconStore.getPayload(notifKey)
                    val existingTitle = existingPayload?.title.orEmpty()
                    val existingDur = existingPayload?.mediaDurationMs ?: 0L

                    // 防误判“歌词塞进 TITLE = 切歌”：同曲总时长未变（误差<=1.5s），且当前播放进度已在曲中(>2.5s)或已命中蓝牙/LRC歌词流
                    @Suppress("DEPRECATION")
                    val isBtA2dpOn = runCatching {
                        getSystemService(AudioManager::class.java)?.isBluetoothA2dpOn == true
                    }.getOrDefault(false)
                    val sameDuration = metaDurNow > 0L && existingDur > 0L &&
                        kotlin.math.abs(metaDurNow - existingDur) <= 1500L
                    val curPosNow = ctrl.playbackState?.position ?: 0L
                    val isPlayingNow = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
                    val matchesLiveLyric = metaTitleNow.isNotBlank() && (
                        metaTitleNow == LrcCapsuleLyricEngine.liveLyric.value.line ||
                            metaTitleNow == existingPayload?.liveLyricLine
                        )
                    val isLyricInTitle = sameDuration &&
                        metaTitleNow.isNotBlank() &&
                        existingTitle.isNotBlank() &&
                        metaTitleNow != existingTitle &&
                        (isBtA2dpOn || matchesLiveLyric || (isPlayingNow && curPosNow > 2500L))

                    val newTrackIdentity = extractMetadataTrackIdentity(pkgName, metadata)
                    val detectedNewTrack = !isLyricInTitle && (
                        (metaTitleNow.isNotBlank() && existingTitle.isNotBlank() && metaTitleNow != existingTitle) ||
                            (newTrackIdentity.isNotBlank() && lastCommittedTrackIdentity.isNotBlank() && newTrackIdentity != lastCommittedTrackIdentity)
                        )

                    val activeGen = if (detectedNewTrack) {
                        val appLabel = appNameCache[pkgName] ?: pkgName
                        triggerImmediateTrackSwitchPlaceholder(
                            context = this@CapsuleNotificationListener,
                            notifKey = notifKey,
                            pkg = pkgName,
                            appName = appLabel,
                            newTitle = metaTitleNow
                        )
                    } else {
                        trackSwitchGeneration.get()
                    }

                    pendingMetadataCommitTask?.let { mediaProgressHandler.removeCallbacks(it) }
                    val commitTask = Runnable {
                        worker.execute {
                            if (activeGen != trackSwitchGeneration.get()) return@execute
                            val freshMeta = ctrl.metadata ?: metadata
                            val metaId = freshMeta.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim().orEmpty()
                            val metaTitle = freshMeta.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
                            val metaDur = freshMeta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
                            val trackChanged = if (isLyricInTitle) {
                                false
                            } else {
                                MediaProgressSyncEngine.onTrackMetadataCheck(metaId, metaTitle, metaDur) || detectedNewTrack
                            }
                            val built = MediaRecorderInterceptor.buildPayloadFromMediaController(this@CapsuleNotificationListener, ctrl) ?: return@execute
                            if (activeGen != trackSwitchGeneration.get()) return@execute
                            val snap = if (trackChanged) {
                                stopMediaProgressTicker()
                                lastQuantizedStep = -1
                                lastPushedSecond = -1L
                                MediaProgressSyncEngine.currentSnapshot()
                            } else {
                                updateMediaAnchorFromController(ctrl, fallbackDur = built.mediaDurationMs)
                            }
                            val trackToken = "trk_$activeGen"
                            built.iconBitmap?.let { bmp ->
                                UniversalBannerHost.applyTaggedCoverBitmap(notifKey, trackToken, bmp)
                                CapsuleOverlayAccessibilityService.applyTaggedCoverBitmap(notifKey, trackToken, bmp)
                            }
                            if (!isLyricInTitle) {
                                lastCommittedTrackIdentity = extractMetadataTrackIdentity(pkgName, freshMeta)
                            } else if (metaTitle.isNotBlank()) {
                                LrcCapsuleLyricEngine.publishExternalLyric(notifKey, metaTitle)
                            }
                            val updated = built.copy(
                                mediaDurationMs = snap.durationMs,
                                mediaPositionMs = snap.positionMs,
                                mediaPlaying = snap.isActivelyPlaying,
                                liveLyricLine = when {
                                    isLyricInTitle -> metaTitle
                                    trackChanged -> built.liveLyricLine
                                    built.liveLyricLine.isNotBlank() -> built.liveLyricLine
                                    else -> existingPayload?.liveLyricLine.orEmpty()
                                },
                                bannerNativeLyric = when {
                                    isLyricInTitle -> metaTitle
                                    trackChanged -> built.bannerNativeLyric
                                    built.bannerNativeLyric.isNotBlank() -> built.bannerNativeLyric
                                    else -> existingPayload?.bannerNativeLyric.orEmpty()
                                }
                            )
                            NotificationIconStore.putPayload(updated)
                            val prefsNow = CapsulePrefs(this@CapsuleNotificationListener).load()
                            AppLogger.i("Capsule", "直接会话元数据防抖同步 changed=$trackChanged lyricInTitle=$isLyricInTitle gen=$activeGen title=${updated.title}")
                            pushToCapsule(updated, prefs = prefsNow, isPinned = true)
                            if (trackChanged) {
                                startMediaProgressTicker(ctrl, notifKey, pollInitial = false)
                                scheduleImmediateMediaResync()
                            }
                        }
                    }
                    pendingMetadataCommitTask = commitTask
                    val delayMs = if (isLyricInTitle) 0L else METADATA_DEBOUNCE_MS
                    mediaProgressHandler.postDelayed(commitTask, delayMs)
                }
            }
            ctrl.registerCallback(cb, mediaProgressHandler)
            runCatching { ctrl.sendCommand(EVENT_COMMAND_QUERY_LYRIC, android.os.Bundle(), android.os.ResultReceiver(mediaProgressHandler)) }
            pinnedMediaToken = token
            pinnedMediaNotifKey = notifKey
            pinnedMediaController = ctrl
            pinnedMediaCallback = cb
            updateMediaAnchorFromController(ctrl, fallbackDur = 0L)
            startMediaProgressTicker(ctrl, notifKey)
        }.onFailure { e ->
            AppLogger.w("Capsule", "绑定直接 MediaController 回调失败：${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null) {
            suppressedKeys.remove(sbn.key)
            NotificationIconStore.removePayload(sbn.key)
            ConversationMessageParser.clearConversation(sbn.key)
            CapsuleOverlayAccessibilityService.dismissBannerIfKey(sbn.key)
            UniversalBannerHost.dismissIfKey(sbn.key)
            val cfgNow = CapsulePrefs(this).load()
            val shouldReSuppress = cfgNow.enableOverlayBanner &&
                cfgNow.suppressSystemHeadsUp &&
                CapsuleOverlayAccessibilityService.connected.value
            SystemHeadsUpController.resumeSuppressionAfterCall(this, sbn.key, shouldReSuppress)
            val wasPinned = sbn.key == _pinnedKey.value &&
                (_pinnedPackage.value == null || sbn.packageName == _pinnedPackage.value)
            val wasAutoMedia = sbn.key == autoMediaKey &&
                (autoMediaPackage == null || sbn.packageName == autoMediaPackage)
            val wasActive = sbn.key == _activeSourceKey.value &&
                sbn.packageName == _activeSourcePackage.value
            if (wasPinned) {
                detachPinnedMediaObserver()
                _pinnedKey.value = null
                _pinnedPackage.value = null
            }
            val removedKind = MediaRecorderInterceptor.classify(sbn)
            val isMediaRemovalDebounce = !wasPinned && (wasAutoMedia || wasActive) &&
                removedKind == MediaRecorderInterceptor.SpecialOngoingKind.MEDIA_PLAYBACK
            if (isMediaRemovalDebounce) {
                // 音乐/视频切歌时播放器可能先移除旧通知再重发新通知，走防抖延迟复核，避免切歌瞬间闪退销毁
                scheduleDelayedMediaDismiss("媒体通知已移除，自动收回胶囊", sbn.packageName, baseDelayMs = 2500L)
            } else {
                if (wasAutoMedia) {
                    detachPinnedMediaObserver()
                    autoMediaKey = null
                    autoMediaPackage = null
                }
                if (wasActive) {
                    clearActiveSource()
                }
                if (wasPinned || wasAutoMedia || wasActive) {
                    AppLogger.i("Notif", "来源通知移除 pkg=${sbn.packageName} wasPinned=$wasPinned wasAutoMedia=$wasAutoMedia wasActive=$wasActive")
                    if (CapsuleNavigationEngine.restoreActiveNavCapsuleIfNeeded(this)) {
                        AppLogger.i("Notif", "来源通知移除但导航运行中，已自动恢复导航胶囊")
                    } else {
                        CapsulePublisher.dismiss(this, reason = "来源通知已清除，胶囊同步销毁")
                        runCatching { stopService(Intent(this, CapsuleKeepAliveService::class.java)) }
                    }
                }
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
        // 导航优先级高于后台媒体/录音/LRC歌词；系统状态变化插播窗口期内也临时挂起媒体/录音/LRC刷新，到期自动恢复
        if (CapsuleNavigationEngine.isRunning() && payload.key != CapsuleNavigationEngine.NAV_KEY) {
            if (payload.isMedia || payload.isRecorder || payload.importance <= android.app.NotificationManager.IMPORTANCE_LOW) {
                return
            }
        }
        if (CapsulePublisher.isSysEventPreempting() && payload.key != "sys_status_snapshot") {
            if (payload.isMedia || payload.isRecorder || payload.importance <= android.app.NotificationManager.IMPORTANCE_LOW) {
                return
            }
        }
        cancelPendingMediaDismiss()
        _activeSourceKey.value = payload.key
        _activeSourcePackage.value = payload.packageName
        val engine = DeviceBrandRouter.currentEngine
        // 非荣耀机型（含 vivo OriginOS 及其他品牌）或手动开启通用模式时，始终立即弹出通用悬浮横幅/迷你胶囊，
        // 不再等待 vivo SuperX 白名单拒绝回执导致媒体播放横幅不弹出
        val isNativeCapsuleReady = DeviceBrandRouter.isNativeCapsuleReady(this)
        val useBannerOverlay = !isNativeCapsuleReady ||
            prefs.enableUniversalBannerMode ||
            (!prefs.enableCapsuleEngine && prefs.enableOverlayBanner)
        if (useBannerOverlay) {
            dispatchOverlayBanner(payload, prefs, collapsibleToPill = isPinned)
            if (!prefs.enableCapsuleEngine || !isNativeCapsuleReady) {
                NotificationIconStore.currentActivePayload = payload
                return
            }
        }
        if (!prefs.enableCapsuleEngine) {
            NotificationIconStore.currentActivePayload = payload
            return
        }
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
        if (!force && now - lastReloadMs < 4000L) return
        if (!reloadScheduled.compareAndSet(false, true)) return
        worker.execute {
            try {
                lastReloadMs = SystemClock.elapsedRealtime()
                val list = runCatching { activeNotifications }.getOrNull() ?: return@execute
                val parsed = list.mapNotNull { sbn ->
                    val cap = toCaptured(sbn) ?: return@mapNotNull null
                    // 同步缓存已有通知的图标与按钮，确保用户点选锁定时立刻带有真实图标
                    val prevPayload = NotificationIconStore.getPayload(cap.key)
                    val needFreshBmp = prevPayload == null ||
                        (!cap.isOngoing && prevPayload.title != cap.title) ||
                        cap.key == _pinnedKey.value
                    val bmp = if (needFreshBmp) {
                        NotificationIconStore.resolveNotificationBitmap(this, sbn)
                    } else {
                        prevPayload?.iconBitmap
                    }
                    val acts = NotificationActionExtractor.extractActions(sbn, isCall = cap.isCall)
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
                            channelName = cap.channelName,
                            isMedia = NotificationIconStore.extractMediaPlaybackInfo(this, sbn.notification).isMedia,
                            mediaDurationMs = NotificationIconStore.extractMediaPlaybackInfo(this, sbn.notification).durationMs,
                            mediaPositionMs = NotificationIconStore.extractMediaPlaybackInfo(this, sbn.notification).positionMs,
                            mediaPlaying = NotificationIconStore.extractMediaPlaybackInfo(this, sbn.notification).isPlaying
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
        val isCall = NotificationActionExtractor.detectIsCall(sbn, rawTitle, rawText, isOngoing)

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
        val actionTitles = NotificationActionExtractor.extractActions(sbn, isCall = isCall).map { it.title }

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
