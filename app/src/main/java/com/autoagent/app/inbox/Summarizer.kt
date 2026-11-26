package com.autoagent.app.inbox

import com.autoagent.app.data.InboxEntry
import com.autoagent.app.util.TimeText
import java.util.Locale

/** Offline, rule-based summary: who wrote, when, what they ask for, and any money amounts. */
object Summarizer {
    data class SenderSummary(
        val app: String,
        val sender: String,
        val count: Int,
        val lastAt: Long,
        val asks: List<String>,
        val money: List<String>,
        val latest: String,
    )

    data class Summary(val total: Int, val senders: List<SenderSummary>) {
        fun spoken(maxSenders: Int = 5): String {
            if (senders.isEmpty()) return "No new messages."
            val sb = StringBuilder("You have $total message${if (total == 1) "" else "s"} from ${senders.size} sender${if (senders.size == 1) "" else "s"}. ")
            senders.take(maxSenders).forEach { s ->
                sb.append("${s.sender} on ${s.app}, at ${TimeText.clock(s.lastAt)}")
                if (s.asks.isNotEmpty()) sb.append(", asks: ${s.asks.first()}") else sb.append(": ${s.latest.take(80)}")
                if (s.money.isNotEmpty()) sb.append(". Amounts: ${s.money.joinToString()}")
                sb.append(". ")
            }
            if (senders.size > maxSenders) sb.append("And ${senders.size - maxSenders} more.")
            return sb.toString()
        }
    }

    private val askWords = listOf(
        "please", "can you", "could you", "would you", "will you", "need", "send me", "call me", "let me know",
        "when", "where", "what time", "how much", "are you", "do you", "pls", "plz", "asap", "remind",
        "בבקשה", "תוכל", "תוכלי", "אפשר", "צריך", "תתקשר", "תחזור", "מתי", "איפה", "כמה",
    )

    private val moneyRegex = Regex(
        """(?:[$€£₪]\s?\d[\d,]*(?:\.\d{1,2})?)|(?:\d[\d,]*(?:\.\d{1,2})?\s?(?:usd|eur|gbp|ils|nis|₪|ש"ח|שח|dollars?|shekels?|euros?|bucks)\b)""",
        RegexOption.IGNORE_CASE,
    )

    fun moneyIn(text: String): List<String> = moneyRegex.findAll(text).map { it.value.trim() }.distinct().toList()

    fun asksIn(text: String): List<String> {
        return text.split(Regex("""(?<=[.!?\n])\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { sentence ->
                val l = sentence.lowercase(Locale.ROOT)
                sentence.contains('?') || askWords.any { w -> l.startsWith(w) || l.contains(" $w ") || l.contains(" $w") && w.length > 4 }
            }
            .map { it.take(140) }
    }

    fun summarize(entries: List<InboxEntry>): Summary {
        val groups = entries.groupBy { it.appLabel to it.sender }
        val senders = groups.map { (key, list) ->
            val sorted = list.sortedBy { it.postedAt }
            SenderSummary(
                app = key.first,
                sender = key.second,
                count = list.size,
                lastAt = sorted.last().postedAt,
                asks = sorted.flatMap { asksIn(it.text) }.distinct().takeLast(3),
                money = sorted.flatMap { moneyIn(it.text) }.distinct(),
                latest = sorted.last().text,
            )
        }.sortedWith(compareByDescending<SenderSummary> { it.asks.isNotEmpty() || it.money.isNotEmpty() }.thenByDescending { it.lastAt })
        return Summary(entries.size, senders)
    }
}
