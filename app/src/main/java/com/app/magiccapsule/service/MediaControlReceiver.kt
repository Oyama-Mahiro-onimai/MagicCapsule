package com.app.magiccapsule.service

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import com.app.magiccapsule.capsule.CapsulePublisher
import com.app.magiccapsule.crash.AppLogger
import com.app.magiccapsule.data.CapsulePrefs

/**
 * 胶囊全量按钮同进程广播接收器（PendingIntent.getBroadcast）：
 * - 全部胶囊按钮（上一曲 / 暂停播放 / 下一曲 / 歌词 / 微信标为已读等透传按钮）均走本应用同 UID 广播，
 *   彻底杜绝跨 UID PendingIntent 在 RemoteViews 中被系统静默丢弃或因原通知刷新失效导致点不动的问题
 * - 媒体播控执行后立即触发 300ms / 1000ms 双次补同步，彻底解决点击上下曲需等状态更新才生效的问题
 */
class MediaControlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_MEDIA_PREV = "com.app.magiccapsule.broadcast.MEDIA_PREV"
        const val ACTION_MEDIA_PLAY_PAUSE = "com.app.magiccapsule.broadcast.MEDIA_PLAY_PAUSE"
        const val ACTION_MEDIA_NEXT = "com.app.magiccapsule.broadcast.MEDIA_NEXT"
        const val ACTION_MEDIA_REWIND = "com.app.magiccapsule.broadcast.MEDIA_REWIND"
        const val ACTION_MEDIA_FAST_FORWARD = "com.app.magiccapsule.broadcast.MEDIA_FAST_FORWARD"
        const val ACTION_MEDIA_FAVORITE = "com.app.magiccapsule.broadcast.MEDIA_FAVORITE"
        const val ACTION_MAPPED_ACTION = "com.app.magiccapsule.broadcast.MAPPED_ACTION"
        const val ACTION_CAPSULE_REFRESH = "com.app.magiccapsule.broadcast.CAPSULE_REFRESH"
        const val ACTION_CAPSULE_STOP = "com.app.magiccapsule.broadcast.CAPSULE_STOP"
        const val ACTION_STOP_NAVIGATION = "com.app.magiccapsule.broadcast.STOP_NAVIGATION"
        const val ACTION_SWITCH_NAV_ROUTE = "com.app.magiccapsule.broadcast.SWITCH_NAV_ROUTE"
        const val ACTION_NOOP_CONSUME = "com.app.magiccapsule.broadcast.NOOP_CONSUME"

        const val EXTRA_NOTIF_KEY = "extra_notif_key"
        const val EXTRA_ACTION_TITLE = "extra_action_title"
        const val EXTRA_ACTION_INDEX = "extra_action_index"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == ACTION_NOOP_CONSUME) return
        AppLogger.i("Capsule", "收到胶囊按钮广播：$action key=${intent.getStringExtra(EXTRA_NOTIF_KEY)} title=${intent.getStringExtra(EXTRA_ACTION_TITLE)} idx=${intent.getIntExtra(EXTRA_ACTION_INDEX, -1)}")
        when (action) {
            ACTION_MEDIA_PREV -> {
                val handled = CapsuleNotificationListener.dispatchPinnedMediaCommand(
                    context, CapsuleKeepAliveService.ACTION_MEDIA_PREV
                )
                if (!handled && !fireMatchingRealAction(context, setOf("上一曲"))) {
                    dispatchFallbackMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
                CapsuleNotificationListener.scheduleImmediateMediaResync()
            }
            ACTION_MEDIA_PLAY_PAUSE -> {
                val handled = CapsuleNotificationListener.dispatchPinnedMediaCommand(
                    context, CapsuleKeepAliveService.ACTION_MEDIA_PLAY_PAUSE
                )
                if (!handled && !fireMatchingRealAction(context, setOf("暂停", "播放", "播放/暂停"))) {
                    dispatchFallbackMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                }
                CapsuleNotificationListener.scheduleImmediateMediaResync()
            }
            ACTION_MEDIA_NEXT -> {
                val handled = CapsuleNotificationListener.dispatchPinnedMediaCommand(
                    context, CapsuleKeepAliveService.ACTION_MEDIA_NEXT
                )
                if (!handled && !fireMatchingRealAction(context, setOf("下一曲"))) {
                    dispatchFallbackMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                }
                CapsuleNotificationListener.scheduleImmediateMediaResync()
            }
            ACTION_MEDIA_REWIND -> {
                val handled = CapsuleNotificationListener.dispatchPinnedMediaCommand(
                    context, CapsuleKeepAliveService.ACTION_MEDIA_REWIND
                )
                if (handled || fireMatchingRealAction(context, setOf("快退"))) {
                    CapsuleNotificationListener.scheduleImmediateMediaResync()
                }
            }
            ACTION_MEDIA_FAST_FORWARD -> {
                val handled = CapsuleNotificationListener.dispatchPinnedMediaCommand(
                    context, CapsuleKeepAliveService.ACTION_MEDIA_FAST_FORWARD
                )
                if (handled || fireMatchingRealAction(context, setOf("快进"))) {
                    CapsuleNotificationListener.scheduleImmediateMediaResync()
                }
            }
            ACTION_MEDIA_FAVORITE -> {
                val key = intent.getStringExtra(EXTRA_NOTIF_KEY).orEmpty()
                val payload = NotificationIconStore.getPayload(key) ?: NotificationIconStore.currentActivePayload
                CapsuleNotificationListener.triggerNativeFavoriteAction(context, key, payload?.packageName)
            }
            ACTION_MAPPED_ACTION -> {
                val key = intent.getStringExtra(EXTRA_NOTIF_KEY).orEmpty()
                val title = intent.getStringExtra(EXTRA_ACTION_TITLE).orEmpty()
                val index = intent.getIntExtra(EXTRA_ACTION_INDEX, -1)
                val payload = NotificationIconStore.getPayload(key)
                    ?: NotificationIconStore.currentActivePayload
                val pkg = payload?.packageName

                // 歌词三态循环按钮（歌词 -> 锁词 -> 关词 -> 歌词）：专用状态机接管，防锁定后按钮消失或误跳 App
                if (title == "歌词" || title == "锁词" || title == "关词" || title == "解锁歌词" || title.contains("词")) {
                    CapsuleNotificationListener.triggerLyricCycleAction(context, key, pkg)
                    return
                }
                if (title == "结束导航") {
                    CapsuleNavigationEngine.stopNavigation(context)
                    return
                }
                if (title == "绕行换线") {
                    CapsuleNavigationEngine.switchToNextAlternativeRoute(context)
                    return
                }
                if (title == "喜欢" || title == "已赞" || title == "取消喜欢") {
                    if (CapsuleNotificationListener.triggerNativeFavoriteAction(context, key, pkg)) return
                }
                // 优先通过实时 MediaSession CustomAction 或最新 activeNotifications 动作执行（防止旧 PendingIntent 失效后误跳 App）
                if (title.isNotBlank() && CapsuleNotificationListener.triggerFreshNotificationAction(context, key, pkg, title)) {
                    return
                }
                if (payload != null) {
                    val act = payload.actions.firstOrNull { title.isNotBlank() && it.title == title }
                        ?: payload.actions.getOrNull(index)
                    val isNonJumpToggle = title.contains("词") || title == "标为已读" || title == "喜欢" || title == "取消喜欢" || title.contains("暂停") || title.contains("继续") || title.contains("停止") || title.contains("标记")
                    val targetPi = if (act != null && !act.hasRemoteInput && act.intent != null) {
                        act.intent
                    } else if (isNonJumpToggle) {
                        null
                    } else {
                        payload.contentIntent
                    }
                    val ok = sendPendingIntentCompat(context, targetPi)
                    AppLogger.i("Capsule", "兜底执行透传按钮[$title] key=$key ok=$ok")
                }
            }
            ACTION_CAPSULE_REFRESH -> {
                val cfg = CapsulePrefs(context).load()
                CapsulePublisher.refreshCurrent(context, cfg)
            }
            ACTION_STOP_NAVIGATION -> {
                CapsuleNavigationEngine.stopNavigation(context)
            }
            ACTION_SWITCH_NAV_ROUTE -> {
                CapsuleNavigationEngine.switchToNextAlternativeRoute(context)
            }
            ACTION_CAPSULE_STOP -> {
                CapsuleNavigationEngine.stopNavigation(context, silent = true)
                CapsuleNotificationListener.unpinAndDismiss(context)
                CapsuleOverlayAccessibilityService.dismissBanner()
                UniversalBannerHost.dismiss()
                SystemHeadsUpController.restoreDefault(context)
            }
        }
    }

    private fun fireMatchingRealAction(context: Context, titles: Set<String>): Boolean {
        val payload = NotificationIconStore.currentActivePayload
            ?: CapsuleNotificationListener.activeSourceKey.value?.let { NotificationIconStore.getPayload(it) }
            ?: return false
        val act = payload.actions.firstOrNull { it.title in titles && it.intent != null } ?: return false
        val ok = sendPendingIntentCompat(context, act.intent)
        AppLogger.i("Capsule", "通过原通知动作兜底执行[${act.title}] ok=$ok")
        return ok
    }

    private fun sendPendingIntentCompat(context: Context, pi: android.app.PendingIntent?): Boolean {
        if (pi == null) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                pi.send(context, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
            true
        }.getOrDefault(false)
    }

    private fun dispatchFallbackMediaKey(context: Context, keyCode: Int) {
        runCatching {
            val am = context.getSystemService(AudioManager::class.java) ?: return
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            AppLogger.i("Capsule", "已通过 AudioManager 兜底发送媒体按键 keyCode=$keyCode")
        }
    }
}
