package com.autoagent.app.inbox

import com.autoagent.app.data.InboxEntry
import com.autoagent.app.util.HebrewText
import com.autoagent.app.util.L
import com.autoagent.app.util.TimeText
import java.util.Locale

/** Offline, rule-based summary (Hebrew + English): who wrote, when, what they ask for, and money amounts. */
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
            if (senders.isEmpty()) return L.t("No new messages.", "אין הודעות חדשות.")
            val sb = StringBuilder(
                L.t(
                    "You have $total message${if (total == 1) "" else "s"} from ${senders.size} sender${if (senders.size == 1) "" else "s"}. ",
                    "יש ${if (total == 1) "הודעה אחת" else "$total הודעות"} מ${if (senders.size == 1) "שולח אחד" else "-${senders.size} שולחים"}. ",
                )
            )
            senders.take(maxSenders).forEach { s ->
                sb.append(L.t("${s.sender} on ${s.app}, at ${TimeText.clock(s.lastAt)}", "${s.sender} ב${s.app}, בשעה ${TimeText.clock(s.lastAt)}"))
                if (s.asks.isNotEmpty()) sb.append(L.t(", asks: ", ", מבקש: ")).append(s.asks.first())
                else sb.append(": ").append(s.latest.take(80))
                if (s.money.isNotEmpty()) sb.append(L.t(". Amounts: ", ". סכומים: ")).append(s.money.joinToString())
                sb.append(". ")
            }
            if (senders.size > maxSenders) sb.append(L.t("And ${senders.size - maxSenders} more.", "ועוד ${senders.size - maxSenders}."))
            return sb.toString()
        }
    }

    private val askWordsEn = listOf(
        "please", "can you", "could you", "would you", "will you", "need", "send me", "call me", "let me know",
        "when", "where", "what time", "how much", "are you", "do you", "pls", "plz", "asap", "remind",
    )

    /** Hebrew request words; matched with prefixes (ו, ה, ש...) allowed. */
    private val askWordsHe = listOf(
        "בבקשה", "תוכל", "תוכלי", "תוכלו", "אפשר", "צריך", "צריכה", "צריכים", "תתקשר", "תתקשרי", "תחזור", "תחזרי",
        "מתי", "איפה", "כמה", "למה", "מה", "תשלח", "תשלחי", "תביא", "תביאי", "דחוף", "תגיד", "תגידי", "יש לך",
        "אתה יכול", "את יכולה", "תעדכן", "תעדכני", "תזכיר", "תזכירי", "תאשר", "תאשרי", "תענה", "תעני", "מחכה",
        "תעביר", "תעבירי", "תשלם", "תשלמי", "להעביר", "לשלם", "חייב", "חייבת",
    )

    private val moneyRegex = Regex(
        """(?:[$€£₪]\s?\d[\d,]*(?:\.\d{1,2})?)|(?:\d[\d,]*(?:\.\d{1,2})?\s?(?:usd|eur|gbp|ils|nis|₪|ש"ח|ש״ח|שח|שקל|שקלים|דולר|דולרים|יורו|אירו|dollars?|shekels?|euros?|bucks)(?![\p{L}]))""",
        RegexOption.IGNORE_CASE,
    )

    fun moneyIn(text: String): List<String> = moneyRegex.findAll(text).map { it.value.trim() }.distinct().toList()

    fun isAsk(sentence: String): Boolean {
        if (sentence.contains('?')) return true
        val norm = HebrewText.normalize(sentence)
        if (HebrewText.hasHebrew(sentence)) return askWordsHe.any { HebrewText.containsPhrase(norm, it) }
        val l = sentence.lowercase(Locale.ROOT)
        return askWordsEn.any { w -> l.startsWith(w) || l.contains(" $w ") || (l.contains(" $w") && w.length > 4) }
    }

    fun asksIn(text: String): List<String> =
        text.split(Regex("""(?<=[.!?\n])\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() && isAsk(it) }
            .map { it.take(140) }

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
