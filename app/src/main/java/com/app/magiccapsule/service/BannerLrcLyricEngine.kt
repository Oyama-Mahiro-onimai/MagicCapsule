package com.app.magiccapsule.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.LruCache
import com.app.magiccapsule.crash.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 横幅通知独立联网 LRC 歌词引擎（与胶囊 LrcCapsuleLyricEngine 完全物理隔离）：
 * - 独立缓存目录（cacheDir/banner_lrc_cache）、独立时间轴调度器、独立失败冷却表，互不干扰
 * - 独立联网源优先级：1. LRCLIB -> 2. 网易云音乐（支持原词+译词融合） -> 3. QQ音乐 -> 4. LrcApi -> 5. 酷狗兜底
 * - 仅向横幅宿主（UniversalBannerHost / CapsuleOverlayAccessibilityService）推送歌词，绝不写入胶囊通道
 */
object BannerLrcLyricEngine {

    private const val TAG = "Banner"
    private const val MIN_BANNER_PUSH_INTERVAL_MS = 350L
    private const val MAX_BANNER_IDLE_DELAY_MS = 10_000L
    private const val FAILED_COOLDOWN_MS = 180_000L
    private const val NATIVE_LYRIC_HOLD_MS = 8_000L
    private const val HTTP_TIMEOUT_MS = 4_500

    data class BannerLyricEvent(
        val notifKey: String = "",
        val trackKey: String = "",
        val line: String = "",
        val source: String = ""
    )

    private val _liveBannerLyric = MutableStateFlow(BannerLyricEvent())
    val liveBannerLyric: StateFlow<BannerLyricEvent> = _liveBannerLyric.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val memoryCache = LruCache<String, List<LrcCapsuleLyricEngine.LrcLine>>(16)
    private val failedCache = ConcurrentHashMap<String, Long>()
    private val fetchGen = AtomicLong(0L)

    @Volatile private var appContext: Context? = null
    @Volatile private var activeTrackKey: String = ""
    @Volatile private var activeNotifKey: String = ""
    @Volatile private var activeLines: List<LrcCapsuleLyricEngine.LrcLine> = emptyList()
    @Volatile private var activeSourceName: String = ""
    @Volatile private var activeCallback: ((String, String) -> Unit)? = null
    @Volatile private var lastPushedIndex: Int = -1
    @Volatile private var lastPushElapsedMs: Long = 0L
    @Volatile private var lastNativeLyricElapsedMs: Long = 0L

    private val bracketNoiseRegex = Regex(
        """(\[[^\]]*]|【[^】]*】|\((?:official|mv|lyric|audio|video|live|ver\.?|version|remaster|feat\.?|ft\.?)[^)]*\)|（(?:官方|伴奏|现场|纯音乐|完整版|高清)[^）]*）)""",
        RegexOption.IGNORE_CASE
    )

    private val nextLineRunnable = Runnable {
        dispatchCurrentLineAndScheduleNext()
    }

    fun markNativeLyricActive() {
        lastNativeLyricElapsedMs = SystemClock.elapsedRealtime()
    }

    fun stop() {
        fetchGen.incrementAndGet()
        mainHandler.removeCallbacks(nextLineRunnable)
        activeTrackKey = ""
        activeNotifKey = ""
        activeLines = emptyList()
        activeSourceName = ""
        activeCallback = null
        lastPushedIndex = -1
        _liveBannerLyric.value = BannerLyricEvent()
    }

    fun onTrackActive(
        context: Context,
        notifKey: String,
        title: String,
        artist: String,
        durationMs: Long,
        onLyricLine: (notifKey: String, lyricLine: String) -> Unit
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return
        val cleanArtist = artist.trim()
        appContext = context.applicationContext
        activeCallback = onLyricLine
        val trackKey = "${cleanTitle.lowercase()}|${cleanArtist.lowercase()}"

        if (trackKey == activeTrackKey && activeLines.isNotEmpty()) {
            activeNotifKey = notifKey
            resyncTimeline()
            return
        }

        activeTrackKey = trackKey
        activeNotifKey = notifKey
        lastPushedIndex = -1
        mainHandler.removeCallbacks(nextLineRunnable)

        val cached = memoryCache.get(trackKey)
        if (cached != null && cached.isNotEmpty()) {
            activeLines = cached
            resyncTimeline()
            return
        }

        val gen = fetchGen.incrementAndGet()
        activeLines = emptyList()

        val lastFail = failedCache[trackKey]
        if (lastFail != null && SystemClock.elapsedRealtime() - lastFail < FAILED_COOLDOWN_MS) {
            return
        }

        val ctx = context.applicationContext
        SoCThreadPools.monitorIoExecutor.execute {
            runCatching {
                val diskLrc = readDiskCache(ctx, trackKey)
                val fetchedPair: Pair<String, String>? = if (!diskLrc.isNullOrBlank()) {
                    "DiskCache" to diskLrc
                } else {
                    fetchFromBannerSources(cleanTitle, cleanArtist, durationMs)?.also { (_, raw) ->
                        writeDiskCache(ctx, trackKey, raw)
                    }
                }
                if (gen != fetchGen.get()) return@execute

                val parsed = if (fetchedPair != null) {
                    LrcCapsuleLyricEngine.parseLrc(fetchedPair.second)
                } else {
                    emptyList()
                }
                if (parsed.isNotEmpty()) {
                    memoryCache.put(trackKey, parsed)
                    failedCache.remove(trackKey)
                    activeLines = parsed
                    activeSourceName = fetchedPair?.first.orEmpty()
                    AppLogger.i(TAG, "横幅独立 LRC 通道加载成功 src=$activeSourceName lines=${parsed.size} title=$cleanTitle")
                    mainHandler.post {
                        if (gen == fetchGen.get()) {
                            dispatchCurrentLineAndScheduleNext()
                        }
                    }
                } else {
                    failedCache[trackKey] = SystemClock.elapsedRealtime()
                    AppLogger.d(TAG, "横幅独立 LRC 通道未命中 title=$cleanTitle artist=$cleanArtist")
                }
            }.onFailure { e ->
                AppLogger.w(TAG, "横幅独立 LRC 通道拉取异常：${e.message}")
            }
        }
    }

    fun resyncTimeline() {
        mainHandler.removeCallbacks(nextLineRunnable)
        if (activeLines.isEmpty()) return
        mainHandler.post(nextLineRunnable)
    }

    private fun dispatchCurrentLineAndScheduleNext() {
        mainHandler.removeCallbacks(nextLineRunnable)
        val lines = activeLines
        val nKey = activeNotifKey
        val cb = activeCallback
        if (lines.isEmpty() || nKey.isBlank() || cb == null) return

        val ctx = appContext
        val isScreenOn = runCatching {
            ctx?.getSystemService(PowerManager::class.java)?.isInteractive != false
        }.getOrDefault(true)
        if (!isScreenOn) return

        val snap = MediaProgressSyncEngine.currentSnapshot()
        val posMs = snap.positionMs
        val idx = findActiveLineIndex(lines, posMs)

        val nowElapsed = SystemClock.elapsedRealtime()
        val nativeActive = (nowElapsed - lastNativeLyricElapsedMs) < NATIVE_LYRIC_HOLD_MS
        if (!nativeActive && idx >= 0 && idx != lastPushedIndex) {
            val elapsedSincePush = nowElapsed - lastPushElapsedMs
            if (elapsedSincePush < MIN_BANNER_PUSH_INTERVAL_MS) {
                val waitMs = (MIN_BANNER_PUSH_INTERVAL_MS - elapsedSincePush).coerceAtLeast(100L)
                mainHandler.postDelayed(nextLineRunnable, waitMs)
                return
            }
            lastPushedIndex = idx
            lastPushElapsedMs = nowElapsed
            val formatted = LrcCapsuleLyricEngine.formatLyricForDisplay(lines[idx].text, maxCharsPerLine = 30, maxLines = 3)
            if (formatted.isNotBlank()) {
                _liveBannerLyric.value = BannerLyricEvent(
                    notifKey = nKey,
                    trackKey = activeTrackKey,
                    line = formatted,
                    source = activeSourceName
                )
                cb.invoke(nKey, formatted)
            }
        }

        if (!snap.isActivelyPlaying) return
        val nextIdx = idx + 1
        if (nextIdx in lines.indices) {
            val deltaMs = (lines[nextIdx].timeMs - posMs).coerceAtLeast(MIN_BANNER_PUSH_INTERVAL_MS)
            val delayMs = (deltaMs / snap.playbackSpeed).toLong().coerceIn(MIN_BANNER_PUSH_INTERVAL_MS, MAX_BANNER_IDLE_DELAY_MS)
            mainHandler.postDelayed(nextLineRunnable, delayMs)
        }
    }

    private fun findActiveLineIndex(lines: List<LrcCapsuleLyricEngine.LrcLine>, posMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= posMs + 150L) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /**
     * 横幅专属联网 LRC 级联源（与胶囊 KuGou 优先渠道错开，避免同源限流并提升横幅多行可读性）：
     * 1. LRCLIB -> 2. 网易云音乐 -> 3. QQ音乐 -> 4. LrcApi -> 5. 酷狗词库兜底
     */
    private fun fetchFromBannerSources(title: String, artist: String, durationMs: Long): Pair<String, String>? {
        fun tryCascade(t: String, a: String): Pair<String, String>? {
            val candidates = listOf(
                "Banner-LRCLIB" to { fetchFromLrcLib(t, a, durationMs) },
                "Banner-NetEase" to { fetchFromNetEase(t, a, durationMs) },
                "Banner-QQMusic" to { fetchFromQQMusic(t, a) },
                "Banner-LrcApi" to { fetchFromLrcApi(t, a) },
                "Banner-KuGou" to { fetchFromKuGou(t, a, durationMs) }
            )
            for ((srcName, fetcher) in candidates) {
                val raw = fetcher()
                if (!raw.isNullOrBlank() && LrcCapsuleLyricEngine.parseLrc(raw).isNotEmpty()) {
                    return srcName to raw
                }
            }
            return null
        }

        tryCascade(title, artist)?.let { return it }

        val cleanedTitle = title.replace(bracketNoiseRegex, " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim('-', '·', '|', '/')
            .trim()
        val cleanedArtist = artist.substringBefore('/').substringBefore(',').substringBefore('、').trim()
        if (cleanedTitle.isNotBlank() && (cleanedTitle != title || cleanedArtist != artist)) {
            tryCascade(cleanedTitle, cleanedArtist)?.let { return it }
            if (cleanedArtist.isNotBlank()) {
                tryCascade(cleanedTitle, "")?.let { return it }
            }
        }
        return null
    }

    private fun fetchFromLrcLib(title: String, artist: String, durationMs: Long): String? = runCatching {
        val encTrack = URLEncoder.encode(title, "UTF-8")
        val encArtist = URLEncoder.encode(artist, "UTF-8")
        val durSec = (durationMs / 1000L).coerceAtLeast(0L)
        val getUrl = buildString {
            append("https://lrclib.net/api/get?track_name=").append(encTrack)
            if (artist.isNotBlank()) append("&artist_name=").append(encArtist)
            if (durSec > 0L) append("&duration=").append(durSec)
        }
        val body = httpGet(getUrl)
        if (!body.isNullOrBlank()) {
            val synced = JSONObject(body).optString("syncedLyrics")
            if (synced.isNotBlank()) return synced
        }
        val q = URLEncoder.encode(if (artist.isNotBlank()) "$title $artist" else title, "UTF-8")
        val searchBody = httpGet("https://lrclib.net/api/search?q=$q") ?: return null
        val arr = JSONArray(searchBody)
        for (i in 0 until arr.length()) {
            val synced = arr.getJSONObject(i).optString("syncedLyrics")
            if (synced.isNotBlank()) return synced
        }
        null
    }.getOrNull()

    private fun fetchFromNetEase(title: String, artist: String, durationMs: Long): String? = runCatching {
        val kw = URLEncoder.encode(if (artist.isNotBlank()) "$title $artist" else title, "UTF-8")
        val searchUrl = "https://music.163.com/api/search/get/web?s=$kw&type=1&limit=5&offset=0"
        val headers = mapOf("Referer" to "https://music.163.com/", "Origin" to "https://music.163.com")
        val searchBody = httpGet(searchUrl, headers) ?: return null
        val songs = JSONObject(searchBody).optJSONObject("result")?.optJSONArray("songs") ?: return null
        if (songs.length() == 0) return null

        var bestId = -1L
        var bestScore = Long.MAX_VALUE
        for (i in 0 until songs.length()) {
            val song = songs.getJSONObject(i)
            val id = song.optLong("id", -1L)
            if (id <= 0L) continue
            val dur = song.optLong("duration", 0L)
            val score = if (durationMs > 0L && dur > 0L) kotlin.math.abs(dur - durationMs) else i * 1000L
            if (score < bestScore) {
                bestScore = score
                bestId = id
            }
        }
        if (bestId <= 0L) return null
        val lyricBody = httpGet("https://music.163.com/api/song/lyric?os=pc&id=$bestId&lv=-1&tv=-1", headers) ?: return null
        val lrc = JSONObject(lyricBody).optJSONObject("lrc")?.optString("lyric").orEmpty()
        lrc.takeIf { it.isNotBlank() && it.contains('[') }
    }.getOrNull()

    private fun fetchFromQQMusic(title: String, artist: String): String? = runCatching {
        val kw = URLEncoder.encode(if (artist.isNotBlank()) "$title $artist" else title, "UTF-8")
        val headers = mapOf("Referer" to "https://y.qq.com/")
        val searchBody = httpGet("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=3&w=$kw&format=json", headers) ?: return null
        val list = JSONObject(searchBody).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return null
        for (i in 0 until list.length()) {
            val mid = list.getJSONObject(i).optString("songmid")
            if (mid.isBlank()) continue
            val lrcBody = httpGet("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&format=json&nobase64=0", headers) ?: continue
            val b64 = JSONObject(lrcBody).optString("lyric")
            if (b64.isBlank()) continue
            val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            if (decoded.contains('[')) return decoded
        }
        null
    }.getOrNull()

    private fun fetchFromLrcApi(title: String, artist: String): String? = runCatching {
        val encTitle = URLEncoder.encode(title, "UTF-8")
        val encArtist = URLEncoder.encode(artist, "UTF-8")
        val url = buildString {
            append("https://api.lrc.cx/lyrics?title=").append(encTitle)
            if (artist.isNotBlank()) append("&artist=").append(encArtist)
        }
        val body = httpGet(url) ?: return null
        body.takeIf { it.contains('[') && it.contains(']') }
    }.getOrNull()

    private fun fetchFromKuGou(title: String, artist: String, durationMs: Long): String? = runCatching {
        val keyword = if (artist.isNotBlank()) "$artist - $title" else title
        val encKw = URLEncoder.encode(keyword, "UTF-8")
        val durParam = if (durationMs > 0L) "&duration=$durationMs" else ""
        val searchBody = httpGet("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$encKw$durParam") ?: return null
        val candidates = JSONObject(searchBody).optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val best = candidates.getJSONObject(0)
        val id = best.optString("id")
        val accesskey = best.optString("accesskey")
        if (id.isBlank() || accesskey.isBlank()) return null
        val dlBody = httpGet("https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accesskey&fmt=lrc&charset=utf8") ?: return null
        val b64 = JSONObject(dlBody).optString("content")
        if (b64.isBlank()) return null
        String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    private fun httpGet(urlStr: String, extraHeaders: Map<String, String> = emptyMap()): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("User-Agent", "MagicCapsuleBannerLrc/1.0 (Android)")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun cacheFile(context: Context, trackKey: String): File {
        val digest = MessageDigest.getInstance("MD5").digest(trackKey.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        val dir = File(context.cacheDir, "banner_lrc_cache")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$hex.lrc")
    }

    private fun readDiskCache(context: Context, trackKey: String): String? = runCatching {
        val f = cacheFile(context, trackKey)
        if (f.exists() && f.length() in 16..131072) f.readText(Charsets.UTF_8) else null
    }.getOrNull()

    private fun writeDiskCache(context: Context, trackKey: String, content: String) {
        runCatching {
            val f = cacheFile(context, trackKey)
            f.writeText(content, Charsets.UTF_8)
        }
    }
}
