package com.app.magiccapsule.service

import android.app.Notification
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.service.notification.StatusBarNotification
import com.app.magiccapsule.model.CapsuleConfig

/**
 * 媒体播放与录音/录屏常驻状态识别器：
 * - 精准识别音乐/播客/视频媒体播放（MediaStyle / EXTRA_MEDIA_SESSION / CATEGORY_TRANSPORT）
 *   以及录音机/录屏等录制常驻通知（包名特征 + 计时器/录制关键词），避免误放行 VPN/网速等高频常驻通知
 * - 支持从无通知的活跃 MediaSession 合成胶囊展示负载（如录音机回放、浏览器音频、视频后台播放等）
 */
object MediaRecorderInterceptor {

    enum class SpecialOngoingKind {
        NONE,
        MEDIA_PLAYBACK,
        RECORDER
    }

    private val RECORDER_PKG_KEYWORDS = listOf(
        "soundrecorder",
        "voicerecorder",
        "audiorecorder",
        "screenrecorder",
        "screenrecord",
        "dictaphone",
        "sound_recorder",
        "voice_recorder",
        "memo.recorder"
    )

    private val RECORDER_TEXT_KEYWORDS = listOf(
        "正在录音",
        "录音中",
        "暂停录音",
        "停止录音",
        "继续录音",
        "正在录屏",
        "录屏中",
        "正在录制",
        "录制中",
        "recording",
        "voice recorder",
        "screen recording"
    )

    private val PAUSED_KEYWORDS = listOf(
        "已暂停",
        "暂停中",
        "录音已暂停",
        "paused"
    )

    private val RESUME_ACTION_KEYWORDS = listOf(
        "继续",
        "恢复",
        "resume",
        "continue"
    )

    private val MEDIA_CHANNEL_KEYWORDS = listOf(
        "播放",
        "音乐",
        "媒体",
        "控制条",
        "music",
        "media",
        "player",
        "playback"
    )

    private val VIDEO_PKG_KEYWORDS = listOf(
        "bilibili",
        "bili",
        "youtube",
        "iqiyi",
        "qiyi",
        "youku",
        "qqlive",
        "mgtv",
        "douyin",
        "aweme",
        "kuaishou",
        "tiktok",
        "netflix",
        "disney",
        "primevideo",
        "hulu",
        "vimeo",
        "twitch",
        "mxtech",
        "vlc",
        "mpv",
        "potplayer",
        "kmplayer",
        "nplayer",
        "nova.video",
        "kodi",
        "emby",
        "jellyfin",
        "plex",
        "danmaku",
        "acfun",
        "xgplayer",
        "video",
        "movie",
        "cinema",
        "chrome",
        "edge",
        "firefox",
        "via",
        "quark",
        "ucmobile"
    )

    private val VIDEO_NAME_KEYWORDS = listOf(
        "视频",
        "影视",
        "影院",
        "哔哩哔哩",
        "抖音",
        "快手",
        "爱奇艺",
        "优酷",
        "腾讯视频",
        "芒果",
        "youtube",
        "bilibili"
    )

    fun detectMediaContentType(
        packageName: String,
        appName: String = "",
        isRecorder: Boolean = false
    ): MediaContentType {
        val pkgLower = packageName.lowercase()
        val nameLower = appName.lowercase()
        if (isRecorder ||
            RECORDER_PKG_KEYWORDS.any { pkgLower.contains(it) } ||
            nameLower.contains("录音") ||
            nameLower.contains("录屏")
        ) {
            return MediaContentType.RECORDER
        }
        if (VIDEO_PKG_KEYWORDS.any { pkgLower.contains(it) } ||
            VIDEO_NAME_KEYWORDS.any { nameLower.contains(it) }
        ) {
            return MediaContentType.VIDEO
        }
        return MediaContentType.MUSIC
    }

    private val MEDIA_PKG_KEYWORDS = listOf(
        "kugou",
        "cloudmusic",
        "qqmusic",
        "kuwo",
        "migu.music",
        "ximalaya",
        "spotify",
        "apple.android.music",
        "luna.music",
        "qishui",
        "saltplayer",
        "poweramp",
        "foobar2000",
        "aimp",
        "symfonium",
        "musicolet",
        "bili",
        "podcast"
    )

    fun classify(
        sbn: StatusBarNotification,
        title: String = "",
        text: String = "",
        actionTitles: List<String> = emptyList(),
        channelName: String = ""
    ): SpecialOngoingKind {
        val n = sbn.notification ?: return SpecialOngoingKind.NONE
        val extras = n.extras
        val pkgLower = sbn.packageName.orEmpty().lowercase()

        // 1. 优先识别录音机 / 录屏等录制通知（部分录音机在回放时也会带 MediaSession，录制态优先走录音机分支）
        val isRecorderPkg = RECORDER_PKG_KEYWORDS.any { pkgLower.contains(it) }
        val hasChronometer = extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) == true
        val mergedText = buildString {
            append(title)
            append(' ')
            append(text)
            if (actionTitles.isNotEmpty()) {
                append(' ')
                append(actionTitles.joinToString(" "))
            }
        }.lowercase()
        val hasRecorderKeyword = mergedText.isNotBlank() && RECORDER_TEXT_KEYWORDS.any { mergedText.contains(it) }

        if (isRecorderPkg && (hasChronometer || hasRecorderKeyword || extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) != true)) {
            return SpecialOngoingKind.RECORDER
        }
        if (hasChronometer && hasRecorderKeyword) {
            return SpecialOngoingKind.RECORDER
        }

        // 2. 识别媒体播放通知（标准 MediaStyle + 酷狗/网易云等自定义播放控制条通知）
        val hasMediaSession = extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        val isTransport = n.category == Notification.CATEGORY_TRANSPORT
        val templateName = extras?.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val isMediaTemplate = templateName.contains("MediaStyle", ignoreCase = true)
        val chLower = "${n.channelId.orEmpty()} $channelName".lowercase()
        val isMediaChannel = chLower.isNotBlank() && MEDIA_CHANNEL_KEYWORDS.any { chLower.contains(it) }
        val isOngoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        val isKnownMediaPkg = isOngoing && MEDIA_PKG_KEYWORDS.any { pkgLower.contains(it) }
        if (hasMediaSession || isTransport || isMediaTemplate || (isOngoing && isMediaChannel) || isKnownMediaPkg) {
            return SpecialOngoingKind.MEDIA_PLAYBACK
        }

        return SpecialOngoingKind.NONE
    }

    fun shouldAutoIntercept(kind: SpecialOngoingKind, prefs: CapsuleConfig): Boolean {
        if (!prefs.hasAnyDisplayEngine() || !prefs.enableMediaRecorderCapsule) return false
        return when (kind) {
            SpecialOngoingKind.MEDIA_PLAYBACK -> prefs.interceptMediaPlayback
            SpecialOngoingKind.RECORDER -> prefs.interceptRecorder
            SpecialOngoingKind.NONE -> false
        }
    }

    fun extractRecorderChronometerBaseMs(n: Notification?): Long {
        if (n == null) return 0L
        val showChrono = n.extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) == true
        return if (showChrono && n.`when` > 0L) n.`when` else 0L
    }

    fun isRecorderPaused(title: String, text: String, actionTitles: List<String>): Boolean {
        val combined = "$title $text".lowercase()
        if (PAUSED_KEYWORDS.any { combined.contains(it) }) return true
        val acts = actionTitles.map { it.lowercase() }
        return acts.any { act -> RESUME_ACTION_KEYWORDS.any { act.contains(it) } }
    }

    /**
     * 为无通知的活跃 MediaSession（例如录音机音频回放、浏览器/视频后台播放等）构建胶囊负载
     */
    fun buildPayloadFromMediaController(
        context: Context,
        ctrl: MediaController
    ): ActiveMirroredPayload? {
        val pkg = ctrl.packageName.orEmpty()
        if (pkg.isBlank() || pkg == context.packageName) return null

        val state = ctrl.playbackState
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val meta = ctrl.metadata
        val appName = runCatching {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg)

        val metaTitle = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val metaArtist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val metaAlbum = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
        val title = metaTitle.ifBlank { appName }
        val text = when {
            metaArtist.isNotBlank() -> metaArtist
            metaAlbum.isNotBlank() -> metaAlbum
            isPlaying -> "正在播放"
            else -> "已暂停"
        }

        val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val iconBmp = if (art != null && !art.isRecycled) {
            NotificationIconStore.roundedFromBitmap(art)
        } else {
            NotificationIconStore.resolveAppIconBitmap(context, pkg)
        }

        val dur = (meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
        val supportsSeek = ((state?.actions ?: 0L) and (
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND
        )) != 0L
        // 拦截空壳僵尸会话（如 B站后台挂起：dur=0ms、seek=false、无标题无歌手，却伪报 STATE_PLAYING）
        if (metaTitle.isBlank() && metaArtist.isBlank() && metaAlbum.isBlank() && dur <= 0L && !supportsSeek) {
            return null
        }
        val pos = (state?.position ?: 0L).coerceAtLeast(0L)
        val sessionKey = "media_session:$pkg"

        return ActiveMirroredPayload(
            key = sessionKey,
            packageName = pkg,
            appName = appName,
            title = title,
            text = text,
            iconBitmap = iconBmp,
            contentIntent = ctrl.sessionActivity,
            actions = emptyList(),
            isMedia = true,
            mediaDurationMs = dur,
            mediaPositionMs = pos,
            mediaPlaying = isPlaying,
            mediaContentType = detectMediaContentType(pkg, appName, isRecorder = false),
            mediaSupportsSeek = ((state?.actions ?: 0L) and (
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_REWIND
            )) != 0L
        )
    }
}
