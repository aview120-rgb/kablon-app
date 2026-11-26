package com.autoagent.app.voice

import android.content.Context
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.alarm.AlarmScheduler
import com.autoagent.app.calls.PhoneAccounts
import com.autoagent.app.data.AlarmEntity
import com.autoagent.app.data.ScheduledSend
import com.autoagent.app.data.SendChannel
import com.autoagent.app.inbox.Summarizer
import com.autoagent.app.schedule.SendScheduler
import com.autoagent.app.schedule.SmsSender
import com.autoagent.app.service.AgentStatus
import com.autoagent.app.util.Contacts
import com.autoagent.app.util.HebrewText
import com.autoagent.app.util.L
import com.autoagent.app.util.TimeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Offline command grammar (Hebrew and English). Everything here works without network. */
sealed interface Command {
    data class SetAlarm(val at: Long, val label: String) : Command
    /** [targets] are candidate spellings, e.g. "דנה" and "לדנה" when the Hebrew ל prefix is ambiguous. */
    data class CallNumber(val targets: List<String>) : Command
    data class ScheduleSms(val targets: List<String>, val at: Long, val text: String) : Command
    data object ReadSummaries : Command
    data class ToggleAutoAnswer(val on: Boolean) : Command
    data object Help : Command
    data class Incomplete(val en: String, val he: String) : Command
    data class Unknown(val heard: String) : Command
}

object VoiceCommands {

    // ---------- vocabulary ----------
    private val heFillers = setOf("היי", "הי", "אוקיי", "אוקי", "בבקשה", "נא", "יאללה", "רגע", "אז", "טוב")
    private val heAutoAnswer = listOf("מענה אוטומטי", "מענה קולי", "עונה אוטומטי", "מענה אוטומטית", "משיבון", "עוזר שיחות", "מענה לשיחות")
    private val heOn = setOf("תפעיל", "הפעל", "תפעילי", "להפעיל", "תדליק", "הדלק", "תדליקי", "תפתח", "פתח", "פועל", "דלוק", "תחזיר")
    private val heOff = setOf("תכבה", "כבה", "תכבי", "לכבות", "תבטל", "בטל", "תבטלי", "לבטל", "תפסיק", "הפסק", "תפסיקי", "עצור", "תעצור", "כבוי", "מבוטל", "תסגור", "סגור")
    private val heSmsVerbs = setOf(
        "תשלח", "שלח", "תשלחי", "לשלוח", "תשלחו", "תתזמן", "תזמן", "תתזמני", "לתזמן", "תכתוב", "כתוב", "תכתבי", "לכתוב",
    )
    private val heMsgWords = setOf("הודעה", "הודעת", "הודעות", "sms", "אסאמאס", "מסרון", "טקסט", "סמס", "מסר")
    private val heAlarmWords = setOf(
        "תעיר", "להעיר", "תעירי", "תעירו", "השכמה", "השכם", "מעורר", "אזעקה", "אלרם", "אלארם", "תזכיר", "תזכירי",
        "תזכורת", "להזכיר", "צלצול", "שעון", "תכוון", "כוון", "תכווני", "לכוון", "תקבע", "קבע", "תקבעי", "תגדיר", "הגדר",
    )
    private val heAlarmTriggers = setOf("תעיר", "להעיר", "תעירי", "תעירו", "השכמה", "השכם", "מעורר", "אזעקה", "אלרם", "אלארם", "תזכיר", "תזכירי", "תזכורת", "להזכיר")
    private val heCallVerbs = setOf(
        "תתקשר", "התקשר", "תתקשרי", "להתקשר", "תחייג", "חייג", "תחייגי", "לחייג", "תצלצל", "צלצל", "תצלצלי", "לצלצל", "תוציא", "חיוג", "שיחה",
    )
    private val heSummary = listOf("סיכום", "סכם", "תסכם", "תסכמי", "לסכם", "מה פספסתי", "מה חדש", "מה קרה", "תקריא", "הקרא", "תקריאי", "להקריא", "הודעות אחרונות", "התראות", "מי כתב", "מי שלח")
    private val heHelp = listOf("עזרה", "מה אתה יודע", "מה את יודעת", "מה אפשר", "פקודות", "מה אתה יכול", "מה את יכולה")
    private val heDelims = listOf(
        "שאומרת", "שאומר", "שכתוב בה", "שכתוב", "עם הטקסט", "עם התוכן", "עם המילים", "עם ההודעה", "בתוכן", "תוכן",
        "לאמר", "לומר", "להגיד", "שתגיד", "תגיד", "תגידי", "ותגיד", "ותגידי", "תכתוב", "ותכתוב", "ותכתבי", "תכתבי", "שאומרים",
    )
    private val heObjPronouns = setOf("לה", "לו", "להם", "להן", "לי", "לנו")
    private val shinExceptions = setOf("שאלה", "שאלות", "שנה", "שנים", "שתיים", "שתים", "שיר", "שירים", "שיחה", "שיעור", "שמח", "שמחה", "שבת", "שבוע", "שלום", "שולחן", "שעה", "שמונה", "שלוש", "שש", "שבע", "שוב", "שם", "שמי", "שלי", "שלך", "שלו", "שלה", "שלנו", "שמש", "שקט", "שוקולד", "שינה", "שיניים")

    private val enDelims = listOf("saying", "that says", "with text", "with the text", "which says", "and say", "say")

    fun parse(raw: String, now: Long = System.currentTimeMillis()): Command {
        if (raw.isBlank()) return Command.Unknown(raw)
        return if (HebrewText.hasHebrew(raw)) parseHebrew(raw, now) else parseEnglish(raw, now)
    }

    // ================= Hebrew =================

    private fun hasAny(tokens: List<String>, set: Set<String>) = tokens.any { t -> set.any { HebrewText.matchesWithPrefix(t, it) } }
    private fun indexOfAny(tokens: List<String>, set: Set<String>, from: Int = 0): Int {
        for (i in from until tokens.size) if (set.any { HebrewText.matchesWithPrefix(tokens[i], it) }) return i
        return -1
    }

    private fun parseHebrew(raw: String, now: Long): Command {
        val norm = HebrewText.normalize(raw)
        val words = norm.split(' ').filter { it.isNotEmpty() && it !in heFillers }
        val text = words.joinToString(" ")
        val tk = TimeText.tokenize(text).filter { it !in heFillers }

        // 1) Auto-answer on/off
        if (heAutoAnswer.any { HebrewText.containsPhrase(text, it) } || (text.contains("מענה") && (text.contains("אוטומט") || text.contains("שיחות")))) {
            return when {
                hasAny(words, heOff) || text.contains("לא לענות") -> Command.ToggleAutoAnswer(false)
                hasAny(words, heOn) || text.contains("תענה") -> Command.ToggleAutoAnswer(true)
                else -> Command.Incomplete("Say turn auto-answer on, or off.", "תגיד תפעיל מענה אוטומטי, או תכבה מענה אוטומטי.")
            }
        }

        val startsWithAlarm = words.take(2).any { w -> heAlarmTriggers.any { HebrewText.matchesWithPrefix(w, it) } }

        // 2) Scheduled SMS
        val smsVerb = indexOfAny(tk, heSmsVerbs)
        if (!startsWithAlarm && smsVerb >= 0 && (hasAny(tk, heMsgWords) || tk.drop(smsVerb + 1).any { it.startsWith("ל") && it.length > 1 } || tk.contains("ל"))) {
            return parseHebrewSms(raw, tk, smsVerb, now)
        }

        // 3) Alarm / reminder
        if (hasAny(words, heAlarmTriggers) || (text.contains("שעון") && text.contains("מעורר"))) {
            val cleaned = tk.filterNot { t -> heAlarmWords.any { HebrewText.matchesWithPrefix(t, it) } || t == "לי" || t == "אותי" }
            val wake = words.any { w -> listOf("תעיר", "להעיר", "תעירי", "תעירו", "השכמה", "השכם").any { HebrewText.matchesWithPrefix(w, it) } } ||
                (text.contains("מעורר") && !words.any { it.startsWith("תזכ") })
            val m = TimeText.find(cleaned, now, requireCue = false, preferMorning = wake)
                ?: return Command.Incomplete("For what time?", "לאיזו שעה? למשל: תעיר אותי בשבע וחצי.")
            val reminder = words.any { it.startsWith("תזכיר") || it.startsWith("תזכורת") || it == "להזכיר" }
            val rest = cleaned.filterIndexed { i, _ -> i < m.start || i >= m.end }
                .dropWhile { it == "ש" || it == "ל" || it == "על" }
                .joinToString(" ").removePrefix("ש").trim()
            val label = when {
                reminder && rest.isNotBlank() -> "תזכורת: $rest"
                reminder -> "תזכורת"
                else -> "שעון מעורר"
            }
            return Command.SetAlarm(m.millis, label)
        }

        // 4) Call
        val callIdx = indexOfAny(words, heCallVerbs)
        if (callIdx >= 0 && callIdx <= 2) {
            var target = words.drop(callIdx + 1).filter { it !in setOf("עכשיו", "בטלפון", "מיד", "את", "אל", "שיחה", "אליה", "אליו") }
            if (words[callIdx] in setOf("תוציא", "חיוג", "שיחה") && target.firstOrNull() == "שיחה") target = target.drop(1)
            if (target.isEmpty()) return Command.Incomplete("Who should I call?", "למי להתקשר?")
            return Command.CallNumber(targetCandidates(target))
        }

        // 5) Summaries
        if (heSummary.any { HebrewText.containsPhrase(text, it) }) return Command.ReadSummaries

        // 6) Help
        if (heHelp.any { text.contains(it) }) return Command.Help
        return Command.Unknown(raw)
    }

    /** "לדנה כהן" → ["דנה כהן", "לדנה כהן"]; "ל" "0501234567" → ["0501234567"]. */
    private fun targetCandidates(tokens: List<String>): List<String> {
        var t = tokens
        if (t.firstOrNull() == "ל" || t.firstOrNull() == "אל") t = t.drop(1)
        if (t.isEmpty()) return emptyList()
        val joined = t.joinToString(" ")
        if (joined.count { it.isDigit() } >= 3 && joined.none { it.isLetter() }) return listOf(joined.filter { it.isDigit() || it == '+' })
        val first = t.first()
        return if (first.startsWith("ל") && first.length > 2) {
            listOf((listOf(first.substring(1)) + t.drop(1)).joinToString(" "), joined)
        } else {
            listOf(joined)
        }
    }

    private fun stripShin(word: String): String =
        if (word.length > 3 && word.startsWith("ש") && word !in shinExceptions) word.substring(1) else word

    private fun parseHebrewSms(raw: String, tk: List<String>, verb: Int, now: Long): Command {
        val time = TimeText.find(tk, now, requireCue = true)
            ?: return Command.Incomplete("When should I send it?", "מתי לשלוח? למשל: תשלח לדנה מחר בשמונה שאני בדרך.")

        // delimiter
        var delim = -1
        var delimLen = 0
        for (d in heDelims) {
            val dw = d.split(' ')
            for (i in verb + 1..tk.size - dw.size) {
                if ((0 until dw.size).all { tk[i + it] == dw[it] } && (i < time.start || i >= time.end)) {
                    if (delim == -1 || i < delim) { delim = i; delimLen = dw.size }
                    break
                }
            }
        }
        val colon = raw.indexOf(':').takeIf { it >= 0 && !raw.substring(maxOf(0, it - 2), minOf(raw.length, it + 3)).matches(Regex(".*\\d:\\d.*")) }

        // message tokens
        var msgFrom: Int
        var msgTo: Int
        if (delim >= 0) {
            msgFrom = delim + delimLen
            while (msgFrom < tk.size && tk[msgFrom] in heObjPronouns) msgFrom++
            msgTo = if (time.start > delim) time.start else tk.size
        } else {
            msgFrom = time.end
            msgTo = tk.size
        }

        // recipient: first ל-word (or "ל" + token) after the verb, outside the time and message spans
        var r = -1
        for (i in verb + 1 until tk.size) {
            if (i in time.start until time.end || (i in msgFrom until msgTo)) continue
            val w = tk[i]
            if (w == "ל" && i + 1 < tk.size) { r = i; break }
            if (w.startsWith("ל") && w.length > 1 && w !in heObjPronouns && w !in setOf("לשלוח", "לתזמן", "לכתוב", "להגיד", "לומר", "לאמר")) { r = i; break }
        }
        if (r < 0) return Command.Incomplete("To whom?", "למי לשלוח?")
        val stop = listOf(time.start.takeIf { it > r }, delim.takeIf { it > r }, msgFrom.takeIf { it > r && delim < 0 }).filterNotNull().minOrNull() ?: tk.size
        val recip = tk.subList(r, minOf(stop, r + 4)).filter { t -> heMsgWords.none { HebrewText.matchesWithPrefix(t, it) } }
        if (delim < 0 && msgFrom <= r) msgFrom = maxOf(msgFrom, r + recip.size)

        var msgTokens = tk.subList(minOf(msgFrom, tk.size), maxOf(minOf(msgTo, tk.size), minOf(msgFrom, tk.size)))
            .filter { t -> heMsgWords.none { it == t } }
        if (msgTokens.isEmpty() && colon != null) {
            val after = raw.substring(colon + 1).trim()
            if (after.isNotEmpty()) return Command.ScheduleSms(targetCandidates(recip), time.millis, after)
        }
        if (msgTokens.isEmpty()) return Command.Incomplete("What should the message say?", "מה לכתוב בהודעה?")
        msgTokens = listOf(stripShin(msgTokens.first())) + msgTokens.drop(1)
        val text = recoverRaw(raw, msgTokens, stripFirstShin = true) ?: msgTokens.joinToString(" ")
        return Command.ScheduleSms(targetCandidates(recip), time.millis, text)
    }

    /** Recovers the original punctuation/case of the message if it is the tail of the utterance. */
    private fun recoverRaw(raw: String, msgTokens: List<String>, stripFirstShin: Boolean): String? {
        if (msgTokens.isEmpty()) return null
        val lower = raw.lowercase()
        val first = msgTokens.first()
        val candidates = if (stripFirstShin) listOf("ש$first", first) else listOf(first)
        for (c in candidates) {
            val idx = lower.lastIndexOf(c).takeIf { it >= 0 } ?: continue
            // Only use it when the remaining raw text has the same words as the message tokens.
            var tail = raw.substring(idx).trim()
            val tailTokens = TimeText.tokenize(tail)
            val expect = if (c != first) listOf(c) + msgTokens.drop(1) else msgTokens
            if (tailTokens.joinToString(" ") == expect.joinToString(" ") ||
                HebrewText.normalize(tail) == HebrewText.normalize(expect.joinToString(" "))
            ) {
                if (c != first) tail = tail.substring(1)
                return tail.trim().trimEnd('.')
            }
        }
        return null
    }

    // ================= English =================

    private fun parseEnglish(raw: String, now: Long): Command {
        val s = HebrewText.normalize(raw)
        val tk = TimeText.tokenize(raw)

        Regex("""(?:turn|switch|set)\s+(on|off)\s+(?:the\s+)?auto[\s-]?answer""").find(s)?.let { return Command.ToggleAutoAnswer(it.groupValues[1] == "on") }
        Regex("""auto[\s-]?answer(?:ing)?\s+(on|off)""").find(s)?.let { return Command.ToggleAutoAnswer(it.groupValues[1] == "on") }
        Regex("""(?:turn|switch)\s+(?:the\s+)?auto[\s-]?answer(?:ing)?\s+(on|off)""").find(s)?.let { return Command.ToggleAutoAnswer(it.groupValues[1] == "on") }
        Regex("""(enable|disable|start|stop)\s+(?:the\s+)?auto[\s-]?answer""").find(s)?.let {
            return Command.ToggleAutoAnswer(it.groupValues[1] in setOf("enable", "start"))
        }

        val smsVerb = tk.indexOfFirst { it in setOf("schedule", "send", "text", "message", "sms") }
        if (smsVerb >= 0 && (tk.any { it in setOf("sms", "text", "message") }) && (tk.contains("to") || tk[smsVerb] == "text" || tk[smsVerb] == "message")) {
            val time = TimeText.find(tk, now, requireCue = true)
                ?: return Command.Incomplete("When should I send it?", "מתי לשלוח?")
            val delimIdx = enDelims.map { d ->
                val dw = d.split(' ')
                (smsVerb + 1..tk.size - dw.size).firstOrNull { i -> (dw.indices).all { tk[i + it] == dw[it] } && (i < time.start || i >= time.end) }?.let { it to dw.size }
            }.filterNotNull().minByOrNull { it.first }
            val colon = raw.indexOf(':').takeIf { it >= 0 && !Regex("""\d:\d""").containsMatchIn(raw.substring(maxOf(0, it - 1), minOf(raw.length, it + 2))) }
            val toIdx = tk.indexOf("to").takeIf { it in (smsVerb + 1) until time.start.coerceAtLeast(smsVerb + 2) }
            val rStart = toIdx?.plus(1) ?: (smsVerb + 1)
            val rEnd = listOfNotNull(time.start.takeIf { it > rStart }, delimIdx?.first?.takeIf { it > rStart }).minOrNull() ?: (rStart + 1)
            val recip = tk.subList(rStart, minOf(rEnd, tk.size)).filter { it !in setOf("sms", "text", "message", "a", "an") }
            if (recip.isEmpty()) return Command.Incomplete("To whom?", "למי לשלוח?")
            val msgTokens = when {
                delimIdx != null -> tk.subList(delimIdx.first + delimIdx.second, if (time.start > delimIdx.first) time.start else tk.size)
                else -> tk.subList(minOf(time.end, tk.size), tk.size)
            }
            val text = when {
                colon != null && raw.substring(colon + 1).isNotBlank() -> raw.substring(colon + 1).trim()
                msgTokens.isNotEmpty() -> recoverRaw(raw, msgTokens, stripFirstShin = false) ?: msgTokens.joinToString(" ")
                else -> return Command.Incomplete("What should the message say?", "מה לכתוב בהודעה?")
            }
            return Command.ScheduleSms(listOf(recip.joinToString(" ").let { r -> if (r.count { it.isDigit() } >= 3 && r.none { it.isLetter() }) r.filter { it.isDigit() || it == '+' } else r }), time.millis, text)
        }

        if (Regex("""\b(alarm|wake me|remind me|reminder)\b""").containsMatchIn(s)) {
            val cleaned = tk.filterNot { it in setOf("set", "an", "alarm", "wake", "me", "up", "remind", "reminder", "a", "please") }
            val m = TimeText.find(cleaned, now, requireCue = false, preferMorning = s.contains("wake") || (s.contains("alarm") && !s.contains("remind"))) ?: return Command.Incomplete("For what time?", "לאיזו שעה?")
            val reminder = s.contains("remind")
            val rest = cleaned.filterIndexed { i, _ -> i < m.start || i >= m.end }.dropWhile { it in setOf("to", "for", "about", "at") }.joinToString(" ")
            return Command.SetAlarm(m.millis, if (reminder && rest.isNotBlank()) "Reminder: $rest" else if (reminder) "Reminder" else "Voice alarm")
        }

        Regex("""^(?:please\s+)?(?:call|dial|phone|ring)\s+(.+)""").find(s)?.let {
            val target = it.groupValues[1].removeSuffix(" now").removeSuffix(" please").trim()
            val digits = target.filter { c -> c.isDigit() || c == '+' }
            return Command.CallNumber(listOf(if (digits.count(Char::isDigit) >= 3 && target.none(Char::isLetter)) digits else target))
        }

        if (Regex("""summar|what did i miss|read (?:my )?(?:messages|notifications|inbox)|what'?s new""").containsMatchIn(s)) return Command.ReadSummaries
        if (s.contains("help") || s.contains("what can you do")) return Command.Help
        return Command.Unknown(raw)
    }

    // ================= execution =================

    private fun resolveNumber(context: Context, targets: List<String>): Pair<String?, String> {
        val first = targets.firstOrNull() ?: return null to L.t("Who?", "למי?")
        val digits = first.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 3 && first.none { it.isLetter() }) return digits to digits
        for (t in targets) {
            val matches = Contacts.findByName(context, t)
            if (matches.isEmpty()) continue
            if (matches.size > 1 && matches.map { it.name.lowercase() }.distinct().size > 1) {
                return null to L.t(
                    "I found several contacts: ${matches.take(3).joinToString { it.name }}. Please say the full name.",
                    "מצאתי כמה אנשי קשר: ${matches.take(3).joinToString { it.name }}. תגיד את השם המלא.",
                )
            }
            return matches.first().number to matches.first().name
        }
        if (!Contacts.hasPermission(context)) return null to L.t("Contacts permission is off.", "ההרשאה לאנשי קשר כבויה.")
        return null to L.t("I could not find $first in your contacts.", "לא מצאתי את $first באנשי הקשר.")
    }

    /** Executes a command and returns the sentence to speak back (in the app language). */
    suspend fun execute(context: Context, cmd: Command): String {
        val app = context.applicationContext as AutoAgentApp
        return when (cmd) {
            is Command.SetAlarm -> {
                val id = withContext(Dispatchers.IO) { app.db.alarms().insert(AlarmEntity(triggerAt = cmd.at, label = cmd.label)) }
                if (AlarmScheduler.schedule(context, AlarmEntity(id = id, triggerAt = cmd.at, label = cmd.label))) {
                    AgentStatus.refresh(context)
                    app.log("voice", L.t("Alarm set for ${TimeText.full(cmd.at)}", "שעון מעורר נקבע ל-${TimeText.full(cmd.at)}"), true)
                    L.t("Alarm set for ${TimeText.spoken(cmd.at)}.", "קבעתי ${cmd.label.substringBefore(':')} ל${TimeText.spoken(cmd.at)}.")
                } else {
                    L.t(
                        "I saved the alarm, but exact alarms are not allowed. Turn on Alarms and reminders in Permissions.",
                        "שמרתי את השעון המעורר, אבל אין הרשאה לשעונים מדויקים. תפעיל \"שעונים ותזכורות\" במסך ההרשאות.",
                    )
                }
            }
            is Command.CallNumber -> {
                val (number, label) = withContext(Dispatchers.IO) { resolveNumber(context, cmd.targets) }
                if (number == null) label else {
                    val err = PhoneAccounts.placeCall(context, number)
                    if (err == null) { app.log("voice", L.t("Calling $label", "מתקשר ל$label"), true); L.t("Calling $label.", "מתקשר ל$label.") } else err
                }
            }
            is Command.ScheduleSms -> {
                if (!SmsSender.hasPermission(context)) return L.t("SMS permission is off. Grant it in Permissions.", "ההרשאה ל-SMS כבויה. אפשר להפעיל אותה במסך ההרשאות.")
                val (number, label) = withContext(Dispatchers.IO) { resolveNumber(context, cmd.targets) }
                if (number == null) return label
                val res = SendScheduler.create(
                    context,
                    ScheduledSend(
                        channel = SendChannel.SMS, packageName = "sms", appLabel = "SMS",
                        recipient = number, recipientLabel = label, text = cmd.text, sendAt = cmd.at,
                    ),
                )
                when (res) {
                    is SendScheduler.CreateResult.Ok -> L.t(
                        "SMS to $label scheduled for ${TimeText.spoken(cmd.at)}: ${cmd.text}",
                        "תזמנתי SMS ל$label ל${TimeText.spoken(cmd.at)}: ${cmd.text}",
                    )
                    is SendScheduler.CreateResult.Rejected -> res.reason
                }
            }
            Command.ReadSummaries -> {
                val entries = withContext(Dispatchers.IO) { app.db.inbox().since(System.currentTimeMillis() - 86_400_000L) }
                Summarizer.summarize(entries).spoken()
            }
            is Command.ToggleAutoAnswer -> {
                app.settings.setAutoAnswer(cmd.on)
                AgentStatus.refresh(context)
                app.log("voice", L.t("Auto-answer turned ${if (cmd.on) "on" else "off"}", if (cmd.on) "מענה אוטומטי הופעל" else "מענה אוטומטי כובה"), true)
                if (cmd.on) L.t("Auto-answer is on for numbers that are not in your contacts.", "מענה אוטומטי פועל, רק למספרים שלא באנשי הקשר.")
                else L.t("Auto-answer is off.", "מענה אוטומטי כבוי.")
            }
            Command.Help -> L.t(
                "You can say: set alarm for 7 30. Call 050 123 4567, or call a contact name. " +
                    "Schedule SMS to Dana at 6 pm saying I am on my way. Read my summaries. Turn auto-answer on or off.",
                "אפשר לומר: תעיר אותי בשבע וחצי. תזכיר לי בעוד עשר דקות להוציא את הכביסה. תתקשר לדנה, או תתקשר ל-050 123 4567. " +
                    "תשלח הודעה לדנה מחר בשמונה שאני מאחר. תקריא סיכום. תפעיל או תכבה מענה אוטומטי.",
            )
            is Command.Incomplete -> L.t(cmd.en, cmd.he)
            is Command.Unknown -> L.t(
                "Sorry, I did not understand: ${cmd.heard}. Say help to hear the commands.",
                "סליחה, לא הבנתי: ${cmd.heard}. תגיד עזרה כדי לשמוע מה אפשר.",
            )
        }
    }
}
