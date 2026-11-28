package com.app.magiccapsule.service

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification

/**
 * 模块化通知动作提取与来电识别引擎：
 * - 预编译 HashMap 实现 O(1) 英文按钮汉化（PlayOrPause / PREV / NEXT / MINILYRIC 等），减少热路径分支开销
 * - 统一提取 CallStyle 接听/挂断、标准 Notification.actions 与 CarExtender 标为已读入口
 */
object NotificationActionExtractor {

    private val LOCALIZED_LABELS: Map<String, String> = hashMapOf(
        "PREV" to "上一曲",
        "PRE" to "上一曲",
        "PREVIOUS" to "上一曲",
        "SKIPTOPREVIOUS" to "上一曲",
        "SKIPPREVIOUS" to "上一曲",
        "LAST" to "上一曲",
        "NEXT" to "下一曲",
        "SKIPTONEXT" to "下一曲",
        "SKIPNEXT" to "下一曲",
        "PLAY" to "播放",
        "PAUSE" to "暂停",
        "PLAY/PAUSE" to "播放/暂停",
        "PLAYPAUSE" to "播放/暂停",
        "PLAYORPAUSE" to "播放/暂停",
        "TOGGLEPLAY" to "播放/暂停",
        "TOGGLE" to "播放/暂停",
        "MINILYRIC" to "歌词",
        "LYRIC" to "歌词",
        "LYRICS" to "歌词",
        "DESKTOPLYRIC" to "歌词",
        "LRC" to "歌词",
        "SHOWLYRIC" to "歌词",
        "LOCKLYRIC" to "锁词",
        "LYRICLOCK" to "锁词",
        "UNLOCKLYRIC" to "解锁歌词",
        "LYRICUNLOCK" to "解锁歌词",
        "UNLOCK" to "解锁歌词",
        "CLOSELYRIC" to "关词",
        "HIDELYRIC" to "关词",
        "CLOSE" to "关闭",
        "DISMISS" to "关闭",
        "STOP" to "关闭",
        "EXIT" to "关闭",
        "LIKE" to "喜欢",
        "FAVORITE" to "喜欢",
        "FAV" to "喜欢",
        "LOVE" to "喜欢",
        "UNLIKE" to "取消喜欢",
        "UNFAVORITE" to "取消喜欢",
        "UNFAV" to "取消喜欢",
        "REPLY" to "回复",
        "QUICKREPLY" to "回复",
        "MARKASREAD" to "标为已读",
        "READ" to "标为已读",
        "MARKREAD" to "标为已读",
        "ANSWER" to "接听",
        "ACCEPT" to "接听",
        "DECLINE" to "挂断",
        "REJECT" to "挂断",
        "HANGUP" to "挂断",
        "OPEN" to "打开",
        "VIEW" to "打开",
        "COPY" to "复制",
        "DELETE" to "删除",
        "REMOVE" to "删除",
        "CANCEL" to "取消",
        "MUTE" to "静音"
    )

    fun localizeLabel(raw: String): String {
        if (raw.isBlank()) return ""
        val trimmed = raw.trim()
        val norm = buildString(trimmed.length) {
            for (ch in trimmed) {
                if (ch != ' ' && ch != '_' && ch != '-') append(ch.uppercaseChar())
            }
        }
        return LOCALIZED_LABELS[norm] ?: trimmed
    }

    fun isPositiveCallLabel(label: String): Boolean {
        val l = label.lowercase()
        return l.contains("接听") || l.contains("接受") || l.contains("同意") ||
            l.contains("answer") || l.contains("accept")
    }

    fun isDestructiveCallLabel(label: String): Boolean {
        val l = label.lowercase()
        return l.contains("挂断") || l.contains("拒绝") || l.contains("拒接") ||
            l.contains("decline") || l.contains("hang up") || l.contains("reject")
    }

    @Suppress("DEPRECATION")
    fun detectIsCall(sbn: StatusBarNotification, title: String, text: String, isOngoing: Boolean): Boolean {
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
        if (n.actions?.any { act ->
                val t = act.title?.toString()?.trim().orEmpty()
                isPositiveCallLabel(t) || isDestructiveCallLabel(t)
            } == true
        ) return true

        if (n.fullScreenIntent != null) return true
        val channelIdLower = n.channelId.orEmpty().lowercase()
        if (channelIdLower.contains("voip") || channelIdLower.contains("call") || channelIdLower.contains("ring")) {
            return true
        }
        val combined = "$title $text"
        return combined.contains("邀请你语音通话") ||
            combined.contains("邀请你视频通话") ||
            combined.contains("来电") ||
            combined.contains("正在呼叫") ||
            combined.contains("等待对方接听") ||
            (isOngoing && (combined.contains("语音通话") || combined.contains("视频通话") || combined.contains("通话中")))
    }

    @Suppress("DEPRECATION")
    fun extractActions(sbn: StatusBarNotification, isCall: Boolean = false): List<BannerAction> {
        val n = sbn.notification ?: return emptyList()
        val result = ArrayList<BannerAction>(5)
        val seenTitles = HashSet<String>(6)

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

        n.actions?.forEach { act ->
            val rawLabel = act.title?.toString()?.trim().orEmpty()
            val label = localizeLabel(rawLabel)
            if (label.isNotBlank() && seenTitles.add(label)) {
                result.add(
                    BannerAction(
                        title = label,
                        intent = act.actionIntent,
                        hasRemoteInput = !act.remoteInputs.isNullOrEmpty(),
                        isPositive = isPositiveCallLabel(label),
                        isDestructive = isDestructiveCallLabel(label)
                    )
                )
            }
        }

        if (!isCall) {
            val readPi = runCatching { Notification.CarExtender(n).unreadConversation?.readPendingIntent }.getOrNull()
            if (readPi != null && seenTitles.add("标为已读")) {
                result.add(BannerAction("标为已读", readPi, hasRemoteInput = false))
            }
        }

        if (isCall && result.isEmpty()) {
            result.add(BannerAction("接听 / 进入通话", n.fullScreenIntent ?: n.contentIntent, hasRemoteInput = false, isPositive = true))
        }

        return result.take(5)
    }
}
