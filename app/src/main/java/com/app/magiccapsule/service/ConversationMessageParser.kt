package com.app.magiccapsule.service

import android.app.Notification
import android.app.PendingIntent
import java.util.concurrent.ConcurrentHashMap

data class ParsedConversationMessage(
    val cleanTitle: String,
    val cleanText: String,
    val carReadIntent: PendingIntent? = null,
    val carReplyIntent: PendingIntent? = null,
    val usedCarExtender: Boolean = false
)

/**
 * 参考 huimao28/wechat-notify-evo 的消息解析与会话聚合策略：
 * 1. 优先读取 Notification.CarExtender.UnreadConversation，获取干净的未读消息列表与原生的「标为已读 / 回复」PendingIntent
 * 2. 自动剥离微信/QQ 常见的 “[3条]” 计数前缀与重复发送者前缀，避免横幅和胶囊出现双重昵称
 * 3. 按会话键（packageName|key）在内存中维护最近 10 条末尾去重消息，并在原通知移除时同步清理
 */
object ConversationMessageParser {

    private const val MAX_MESSAGES_PER_CONV = 10
    private val LEADING_COUNTER_REGEX = Regex("^\\[(\\d+)条[^\\]]*\\]\\s*")
    private val conversationHistory = ConcurrentHashMap<String, MutableList<String>>()

    @Suppress("DEPRECATION")
    fun parse(
        convKey: String,
        notification: Notification,
        rawTitle: String,
        rawText: String,
        appName: String,
        isCall: Boolean
    ): ParsedConversationMessage {
        if (isCall) {
            return ParsedConversationMessage(
                cleanTitle = rawTitle.ifBlank { "$appName 来电" },
                cleanText = rawText.ifBlank { "语音/视频通话中，点击接听或挂断" }
            )
        }

        // 1. 优先读取 CarExtender 未读会话（微信/QQ 常在此暴露真实标为已读与未读消息数组）
        val unread = runCatching {
            Notification.CarExtender(notification).unreadConversation
        }.getOrNull()

        val carMessages = unread?.messages
            ?.mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .orEmpty()
        val carReadPi = unread?.readPendingIntent
        val carReplyPi = unread?.replyPendingIntent

        val cleanTitle = stripLeadingCounter(rawTitle).ifBlank { appName }
        val latestRaw = if (carMessages.isNotEmpty()) carMessages.last() else rawText

        val counterMatch = LEADING_COUNTER_REGEX.find(latestRaw)
        val parsedCount = counterMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        var body = stripLeadingCounter(latestRaw)

        // 若正文开头重复了与标题完全一致的发送者名称（例如 "苦命之人: [语音通话]"），去掉重复前缀
        body = stripDuplicateSender(cleanTitle, body)

        // 记录到内存会话队列（末尾去重，最多保留 10 条）
        val queue = conversationHistory.computeIfAbsent(convKey) { mutableListOf() }
        synchronized(queue) {
            if (carMessages.isNotEmpty()) {
                carMessages.forEach { msg ->
                    val cleaned = stripDuplicateSender(cleanTitle, stripLeadingCounter(msg))
                    if (cleaned.isNotBlank() && queue.lastOrNull() != cleaned) {
                        queue.add(cleaned)
                    }
                }
            } else if (body.isNotBlank() && queue.lastOrNull() != body) {
                queue.add(body)
            }
            while (queue.size > MAX_MESSAGES_PER_CONV) {
                queue.removeAt(0)
            }
        }

        val effectiveCount = maxOf(parsedCount, carMessages.size)
        val displayBody = if (effectiveCount > 1 && body.isNotBlank()) {
            "[${effectiveCount}条] $body"
        } else {
            body.ifBlank { cleanTitle }
        }

        return ParsedConversationMessage(
            cleanTitle = cleanTitle,
            cleanText = displayBody,
            carReadIntent = carReadPi,
            carReplyIntent = carReplyPi,
            usedCarExtender = carMessages.isNotEmpty() || carReadPi != null || carReplyPi != null
        )
    }

    fun clearConversation(convKey: String) {
        conversationHistory.remove(convKey)
    }

    private fun stripLeadingCounter(text: String): String =
        text.replace(LEADING_COUNTER_REGEX, "").trim()

    private fun stripDuplicateSender(title: String, body: String): String {
        if (title.isBlank() || body.isBlank()) return body
        val prefixes = listOf("$title: ", "$title：", "$title:")
        for (prefix in prefixes) {
            if (body.startsWith(prefix)) {
                return body.removePrefix(prefix).trim().ifBlank { body }
            }
        }
        return body
    }
}
