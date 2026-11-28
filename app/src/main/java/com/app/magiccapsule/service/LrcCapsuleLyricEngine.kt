package com.app.magiccapsule.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import com.app.magiccapsule.crash.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全音乐 App 通用 LRC 时间轴胶囊歌词引擎（低功耗按句精确唤醒版）：
 * 1. 多源拉取 LRC 时间轴歌词（优先酷狗公开词库 lyrics.kugou.com，兜底 LRCLIB）+ 内存与本地磁盘双层缓存（零重复请求）
 * 2. 零高频轮询：结合 MediaProgressSyncEngine 毫秒级本地进度，二分查找当前句并计算到下一句的精确间隔，
 *    仅在换句瞬间唤醒一次；暂停、缓冲、息屏、切歌立即停止调度
 * 3. 若播放器本身已通过状态栏 ticker / 蓝牙 / SuperX / Focus 推送原生歌词，则自动让位避免重复刷新
 */
object LrcCapsuleLyricEngine {

    data class LrcLine(
        val timeMs: Long,
        val text: String
    )

    data class LiveLyricEvent(
        val notifKey: String = "",
        val trackKey: String = "",
        val line: String = ""
    )

    private val _liveLyric = MutableStateFlow(LiveLyricEvent())
    val liveLyric: StateFlow<LiveLyricEvent> = _liveLyric.asStateFlow()
    private val subscribers = CopyOnWriteArraySet<(String, String) -> Unit>()

    fun addSubscriber(listener: (notifKey: String, lyricLine: String) -> Unit) {
        subscribers.add(listener)
    }

    fun removeSubscriber(listener: (notifKey: String, lyricLine: String) -> Unit) {
        subscribers.remove(listener)
    }

    /**
     * 供蓝牙广播/状态栏 ticker 等原生歌词源同步写入统一单例，让胶囊与顶层横幅都能独立收到同一份歌词流。
     */
    private val LYRIC_BREAK_PUNCTUATION = setOf(
        '，', '。', '、', '；', '：', '！', '？', '—', '…', '）', '】', '》', '」',
        ',', '.', ';', ':', '!', '?', ')', ']', '/'
    )

    /**
     * LRC 歌词智能多行排版过滤器：
     * - 超过 30 个字符时，优先在标点边界或英文单词空格边界智能断行，避免单词被腰斩或 UI 横向溢出
     * - 最多输出 maxLines 行（默认 3 行），应对 100+ 字符极端长句仍优雅截断且不撑破悬浮窗
     */
    fun formatLyricForDisplay(
        rawLine: String,
        maxCharsPerLine: Int = 30,
        maxLines: Int = 3
    ): String {
        val cleaned = rawLine
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return ""
        val limit = maxCharsPerLine.coerceAtLeast(10)
        val lineCap = maxLines.coerceAtLeast(1)
        if (cleaned.length <= limit) return cleaned

        val lines = ArrayList<String>(lineCap)
        var cursor = 0
        val total = cleaned.length

        while (cursor < total && lines.size < lineCap) {
            while (cursor < total && cleaned[cursor] == ' ') cursor++
            if (cursor >= total) break
            val remaining = total - cursor
            if (remaining <= limit) {
                lines.add(cleaned.substring(cursor).trim())
                cursor = total
                break
            }
            val isLastLine = lines.size == lineCap - 1
            if (isLastLine) {
                val cut = findSmartBreakIndex(cleaned, cursor, (cursor + limit - 1).coerceAtMost(total))
                val seg = cleaned.substring(cursor, cut).trimEnd(' ', ',', '，', '、', ';', '；', ':')
                lines.add(if (seg.isNotEmpty()) "$seg…" else cleaned.substring(cursor, (cursor + limit - 1).coerceAtMost(total)) + "…")
                cursor = total
                break
            }
            val endLimit = (cursor + limit).coerceAtMost(total)
            val breakIdx = findSmartBreakIndex(cleaned, cursor, endLimit)
            val seg = cleaned.substring(cursor, breakIdx).trim()
            if (seg.isNotEmpty()) {
                lines.add(seg)
            }
            cursor = breakIdx
        }
        return lines.joinToString("\n")
    }

    private fun findSmartBreakIndex(text: String, start: Int, maxEnd: Int): Int {
        val minPreferred = start + ((maxEnd - start) / 2).coerceAtLeast(1)
        for (i in (maxEnd - 1) downTo minPreferred) {
            if (text[i] in LYRIC_BREAK_PUNCTUATION) {
                return (i + 1).coerceAtMost(maxEnd)
            }
        }
        for (i in maxEnd downTo minPreferred) {
            if (i < text.length && text[i] == ' ') {
                return i
            }
        }
        return maxEnd
    }

    /**
     * 迷你胶囊单行紧凑歌词格式化（严格控长，防止遮挡状态栏左右图标）
     */
    fun formatLyricForPill(rawLine: String, maxChars: Int = 16): String {
        val flat = rawLine.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()
        if (flat.length <= maxChars) return flat
        val cut = findSmartBreakIndex(flat, 0, (maxChars - 1).coerceAtLeast(6))
        val seg = flat.substring(0, cut).trimEnd(' ', ',', '，', '、')
        return if (seg.isNotEmpty()) "$seg…" else flat.take((maxChars - 1).coerceAtLeast(1)) + "…"
    }

    fun publishExternalLyric(notifKey: String, lyricLine: String) {
        if (notifKey.isBlank() || lyricLine.isBlank()) return
        val formatted = formatLyricForDisplay(lyricLine)
        if (formatted.isBlank()) return
        markNativeLyricActive()
        _liveLyric.value = LiveLyricEvent(notifKey = notifKey, trackKey = activeTrackKey, line = formatted)
        for (sub in subscribers) {
            runCatching { sub.invoke(notifKey, formatted) }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val memoryCache = ConcurrentHashMap<String, List<LrcLine>>()
    private val failedCache = ConcurrentHashMap<String, Long>()
    private val fetchGen = AtomicInteger(0)

    @Volatile private var activeTrackKey: String = ""
    @Volatile private var activeNotifKey: String = ""
    @Volatile private var activeLines: List<LrcLine> = emptyList()
    @Volatile private var lastEmittedLineIndex: Int = -1
    @Volatile private var activeCallback: ((String, String) -> Unit)? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var suppressedByNativeLyric: Boolean = false
    @Volatile private var lastPushElapsedMs: Long = 0L
    private const val MIN_LYRIC_PUSH_INTERVAL_MS = 1200L
    private const val MAX_LYRIC_IDLE_DELAY_MS = 20_000L

    private val lineRegex = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?]""")
    private val offsetRegex = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    private val nextLineTask = Runnable {
        dispatchCurrentLineAndScheduleNext()
    }

    fun markNativeLyricActive() {
        suppressedByNativeLyric = true
        mainHandler.removeCallbacks(nextLineTask)
    }

    fun stop() {
        fetchGen.incrementAndGet()
        mainHandler.removeCallbacks(nextLineTask)
        activeTrackKey = ""
        activeNotifKey = ""
        activeLines = emptyList()
        lastEmittedLineIndex = -1
        activeCallback = null
        suppressedByNativeLyric = false
        lastPushElapsedMs = 0L
        _liveLyric.value = LiveLyricEvent()
    }

    /**
     * 曲目变更或启动监听时调用：加载（或缓存命中）LRC 时间轴，并按当前播放进度对齐调度。
     */
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
        val trackKey = buildTrackKey(cleanTitle, cleanArtist)

        if (trackKey == activeTrackKey && notifKey == activeNotifKey) {
            if (!suppressedByNativeLyric && activeLines.isNotEmpty()) {
                resyncTimeline()
            }
            return
        }

        // 新曲目：重置状态与原生让位标记
        mainHandler.removeCallbacks(nextLineTask)
        activeTrackKey = trackKey
        activeNotifKey = notifKey
        activeLines = emptyList()
        lastEmittedLineIndex = -1
        suppressedByNativeLyric = false
        lastPushElapsedMs = 0L
        val gen = fetchGen.incrementAndGet()

        // 1. 优先命中内存缓存
        memoryCache[trackKey]?.let { cached ->
            activeLines = cached
            if (cached.isNotEmpty()) {
                AppLogger.d("Capsule", "LRC 命中内存缓存 title=$cleanTitle lines=${cached.size}")
                resyncTimeline()
            }
            return
        }

        // 2. 近期拉取失败 5 分钟内不重复请求
        val lastFail = failedCache[trackKey]
        if (lastFail != null && System.currentTimeMillis() - lastFail < 5 * 60_000L) {
            return
        }

        val ctx = context.applicationContext
        SoCThreadPools.monitorIoExecutor.execute {
            if (fetchGen.get() != gen) return@execute
            // 2a. 先查磁盘缓存
            val diskLrc = readDiskCache(ctx, trackKey)
            val rawLrc = if (!diskLrc.isNullOrBlank()) {
                diskLrc
            } else {
                val fetched = fetchFromAllSources(cleanTitle, cleanArtist, durationMs)
                if (!fetched.isNullOrBlank()) {
                    writeDiskCache(ctx, trackKey, fetched)
                }
                fetched
            }

            if (fetchGen.get() != gen) return@execute
            val parsed = if (!rawLrc.isNullOrBlank()) parseLrc(rawLrc) else emptyList()
            if (parsed.isNotEmpty()) {
                memoryCache[trackKey] = parsed
                activeLines = parsed
                AppLogger.i("Capsule", "LRC 歌词就绪 title=$cleanTitle lines=${parsed.size}")
                mainHandler.post {
                    if (fetchGen.get() == gen && !suppressedByNativeLyric) {
                        dispatchCurrentLineAndScheduleNext()
                    }
                }
            } else {
                failedCache[trackKey] = System.currentTimeMillis()
                AppLogger.d("Capsule", "LRC 未匹配到时间轴歌词 title=$cleanTitle artist=$cleanArtist")
            }
        }
    }

    /**
     * 播放状态变化（暂停/继续/快进/快退/缓冲恢复）时立即重新对齐时间轴。
     */
    fun resyncTimeline() {
        mainHandler.removeCallbacks(nextLineTask)
        if (suppressedByNativeLyric || activeLines.isEmpty()) return
        mainHandler.post {
            dispatchCurrentLineAndScheduleNext()
        }
    }

    private fun dispatchCurrentLineAndScheduleNext() {
        mainHandler.removeCallbacks(nextLineTask)
        if (suppressedByNativeLyric) return
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
        val elapsedSincePush = nowElapsed - lastPushElapsedMs
        if (idx >= 0 && idx != lastEmittedLineIndex) {
            if (lastPushElapsedMs > 0L && elapsedSincePush < MIN_LYRIC_PUSH_INTERVAL_MS) {
                // 降频节拍保护：合并 <1.2s 的极高频碎句，延后至节拍窗口末尾平滑推送
                val waitMs = (MIN_LYRIC_PUSH_INTERVAL_MS - elapsedSincePush).coerceAtLeast(150L)
                mainHandler.postDelayed(nextLineTask, waitMs)
                return
            }
            lastEmittedLineIndex = idx
            lastPushElapsedMs = nowElapsed
            val text = lines[idx].text
            if (text.isNotBlank()) {
                _liveLyric.value = LiveLyricEvent(notifKey = nKey, trackKey = activeTrackKey, line = text)
                cb.invoke(nKey, text)
                for (sub in subscribers) {
                    runCatching { sub.invoke(nKey, text) }
                }
            }
        }

        // 仅在活跃推进态（STATE_PLAYING 且 speed > 0）调度下一句唤醒；暂停/缓冲立即冻结
        if (!snap.isActivelyPlaying || snap.playbackSpeed <= 0f) return
        val nextIdx = idx + 1
        if (nextIdx >= lines.size) return
        val deltaMs = (lines[nextIdx].timeMs - posMs).coerceAtLeast(MIN_LYRIC_PUSH_INTERVAL_MS)
        val delayMs = (deltaMs / snap.playbackSpeed).toLong().coerceIn(MIN_LYRIC_PUSH_INTERVAL_MS, MAX_LYRIC_IDLE_DELAY_MS)
        mainHandler.postDelayed(nextLineTask, delayMs)
    }

    private fun findActiveLineIndex(lines: List<LrcLine>, posMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= posMs + 120L) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    fun parseLrc(rawLrc: String): List<LrcLine> {
        val offsetMs = offsetRegex.find(rawLrc)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val result = ArrayList<LrcLine>(64)
        for (rawLine in rawLrc.lineSequence()) {
            val matches = lineRegex.findAll(rawLine).toList()
            if (matches.isEmpty()) continue
            val lyricText = rawLine.replace(lineRegex, "")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim()
            val formattedLyric = formatLyricForDisplay(lyricText)
            if (formattedLyric.isBlank()) continue
            // 过滤纯元信息行
            if (lyricText.startsWith("作词") || lyricText.startsWith("作曲") || lyricText.startsWith("编曲")) {
                // 保留也无妨，短促跳过即可
            }
            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toLongOrNull() ?: continue
                val fracStr = m.groupValues[3]
                val msPart = when (fracStr.length) {
                    0 -> 0L
                    1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                    else -> fracStr.take(3).toLongOrNull() ?: 0L
                }
                val t = (min * 60_000L + sec * 1000L + msPart + offsetMs).coerceAtLeast(0L)
                result.add(LrcLine(t, formattedLyric))
            }
        }
        result.sortBy { it.timeMs }
        return result
    }

    /**
     * 源 1：酷狗公开 LRC 接口（中文曲库命中率最高，支持 duration 精确匹配）
     */
    private fun fetchFromKuGou(title: String, artist: String, durationMs: Long): String? {
        return runCatching {
            val keyword = if (artist.isNotBlank()) "$artist - $title" else title
            val encKw = URLEncoder.encode(keyword, "UTF-8")
            val durParam = if (durationMs > 0L) "&duration=$durationMs" else ""
            val searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$encKw$durParam"
            val searchBody = httpGet(searchUrl) ?: return null
            val candidates = JSONObject(searchBody).optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val best = candidates.getJSONObject(0)
            val id = best.optString("id")
            val accesskey = best.optString("accesskey")
            if (id.isBlank() || accesskey.isBlank()) return null

            val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accesskey&fmt=lrc&charset=utf8"
            val dlBody = httpGet(dlUrl) ?: return null
            val b64 = JSONObject(dlBody).optString("content")
            if (b64.isBlank()) return null
            String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * 源 2：LRCLIB 公开同步歌词接口（欧美/日韩/纯英文曲库覆盖极佳）
     */
    private fun fetchFromLrcLib(title: String, artist: String, durationMs: Long): String? {
        return runCatching {
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
            for (i in 0 until minOf(arr.length(), 5)) {
                val synced = arr.getJSONObject(i).optString("syncedLyrics")
                if (synced.isNotBlank()) return synced
            }
            null
        }.getOrNull()
    }

    private val bracketNoiseRegex = Regex("""(\[[^\]]*]|【[^】]*】|\((?:official|mv|lyric|audio|video|live|ver\.?|version|remaster|feat\.?|ft\.?)[^)]*\)|（(?:官方|伴奏|现场|纯音乐|完整版|高清)[^）]*）)""", RegexOption.IGNORE_CASE)

    private fun normalizeTitleForFallback(raw: String): String {
        return raw.replace(bracketNoiseRegex, " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim('-', '·', '|', '/')
            .trim()
    }

    /**
     * 5 源级联拉取（首个命中有效时间轴 LRC 即短路返回，零多余请求）：
     * 1. 酷狗词库 -> 2. 网易云音乐(华语/ACG/日语极强) -> 3. QQ音乐(腾讯版权曲库) -> 4. LRCLIB(欧美/日韩) -> 5. LrcApi 聚合兜底
     * 若原始歌名含 [官方MV]/(Live) 等杂项导致全未命中，自动清洗歌名后再二次兜底查询。
     */
    private fun fetchFromAllSources(title: String, artist: String, durationMs: Long): String? {
        fun tryCascade(t: String, a: String): String? {
            val candidates = listOf(
                "KuGou" to { fetchFromKuGou(t, a, durationMs) },
                "NetEase" to { fetchFromNetEase(t, a, durationMs) },
                "QQMusic" to { fetchFromQQMusic(t, a) },
                "LRCLIB" to { fetchFromLrcLib(t, a, durationMs) },
                "LrcApi" to { fetchFromLrcApi(t, a) }
            )
            for ((srcName, fetcher) in candidates) {
                val raw = fetcher()
                if (!raw.isNullOrBlank() && parseLrc(raw).isNotEmpty()) {
                    AppLogger.i("Capsule", "LRC 命中来源=$srcName title=$t artist=$a")
                    return raw
                }
            }
            return null
        }

        tryCascade(title, artist)?.let { return it }

        val cleanedTitle = normalizeTitleForFallback(title)
        val cleanedArtist = artist.substringBefore('/').substringBefore(',').substringBefore('、').trim()
        if (cleanedTitle.isNotBlank() && (cleanedTitle != title || cleanedArtist != artist)) {
            tryCascade(cleanedTitle, cleanedArtist)?.let { return it }
            if (cleanedArtist.isNotBlank()) {
                tryCascade(cleanedTitle, "")?.let { return it }
            }
        }
        return null
    }

    /**
     * 源 2：网易云音乐公开接口（华语、ACG/二次元、日语、独立音乐覆盖率极高）
     */
    private fun fetchFromNetEase(title: String, artist: String, durationMs: Long): String? {
        return runCatching {
            val kw = URLEncoder.encode(if (artist.isNotBlank()) "$title $artist" else title, "UTF-8")
            val searchUrl = "https://music.163.com/api/search/get/web?s=$kw&type=1&limit=5&offset=0"
            val headers = mapOf(
                "Referer" to "https://music.163.com/",
                "Origin" to "https://music.163.com"
            )
            val searchBody = httpGet(searchUrl, headers) ?: return null
            val songs = JSONObject(searchBody).optJSONObject("result")?.optJSONArray("songs") ?: return null
            if (songs.length() == 0) return null

            var bestId = -1L
            var bestScore = Long.MAX_VALUE
            for (i in 0 until minOf(songs.length(), 5)) {
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

            val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$bestId&lv=-1&tv=-1"
            val lyricBody = httpGet(lyricUrl, headers) ?: return null
            val root = JSONObject(lyricBody)
            val lrc = root.optJSONObject("lrc")?.optString("lyric").orEmpty()
            if (lrc.isNotBlank()) lrc else null
        }.getOrNull()
    }

    /**
     * 源 3：QQ 音乐公开接口（华语主流/腾讯独家版权曲库覆盖率极佳）
     */
    private fun fetchFromQQMusic(title: String, artist: String): String? {
        return runCatching {
            val kw = URLEncoder.encode(if (artist.isNotBlank()) "$title $artist" else title, "UTF-8")
            val headers = mapOf("Referer" to "https://y.qq.com/")
            val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=3&w=$kw&format=json"
            val searchBody = httpGet(searchUrl, headers) ?: return null
            val list = JSONObject(searchBody)
                .optJSONObject("data")
                ?.optJSONObject("song")
                ?.optJSONArray("list") ?: return null
            if (list.length() == 0) return null

            for (i in 0 until minOf(list.length(), 3)) {
                val mid = list.getJSONObject(i).optString("songmid")
                if (mid.isBlank()) continue
                val lrcUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&format=json&nobase64=0"
                val lrcBody = httpGet(lrcUrl, headers) ?: continue
                val b64 = JSONObject(lrcBody).optString("lyric")
                if (b64.isBlank()) continue
                val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                if (decoded.contains("[")) return decoded
            }
            null
        }.getOrNull()
    }

    /**
     * 源 5：LrcApi 公开多源聚合接口兜底（填补新歌与小众曲目盲区）
     */
    private fun fetchFromLrcApi(title: String, artist: String): String? {
        return runCatching {
            val encTitle = URLEncoder.encode(title, "UTF-8")
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            val url = buildString {
                append("https://api.lrc.cx/lyrics?title=").append(encTitle)
                if (artist.isNotBlank()) append("&artist=").append(encArtist)
            }
            val body = httpGet(url) ?: return null
            if (body.contains("[") && body.length in 16..262_144) body else null
        }.getOrNull()
    }

    private fun httpGet(urlStr: String, extraHeaders: Map<String, String> = emptyMap()): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MagicCapsule/1.0")
                for ((k, v) in extraHeaders) {
                    setRequestProperty(k, v)
                }
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

    private fun buildTrackKey(title: String, artist: String): String {
        return "${title.lowercase()}|${artist.lowercase()}"
    }

    private fun hashKey(key: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun cacheFile(context: Context, trackKey: String): File {
        val dir = File(context.cacheDir, "lrc_cache")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${hashKey(trackKey)}.lrc")
    }

    private fun readDiskCache(context: Context, trackKey: String): String? {
        return runCatching {
            val f = cacheFile(context, trackKey)
            if (f.exists() && f.length() in 16..262_144) f.readText(Charsets.UTF_8) else null
        }.getOrNull()
    }

    private fun writeDiskCache(context: Context, trackKey: String, content: String) {
        runCatching {
            cacheFile(context, trackKey).writeText(content, Charsets.UTF_8)
        }
    }
}
