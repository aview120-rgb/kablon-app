package com.autoagent.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * Formats times and parses spoken/typed time expressions in Hebrew and English, offline.
 *
 * Hebrew: "בשבע", "ב-7:30", "בשבע וחצי", "שמונה ורבע בערב", "רבע לשמונה", "עשרים לתשע", "בעוד 10 דקות",
 * "בעוד רבע שעה", "בעוד שעתיים", "מחר בשמונה בבוקר", "בצהריים", "בחצות", "עשר בלילה".
 * English: "7", "7:30", "7 30 pm", "19:45", "noon", "midnight", "in 10 minutes", "in an hour", "tomorrow at 8",
 * "quarter to 8", "half past 7".
 */
object TimeText {
    fun format(millis: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "EEE d MMM, HH:mm"
        return SimpleDateFormat(pattern, L.locale).format(Date(millis))
    }

    fun full(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", L.locale).format(Date(millis))

    fun day(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", L.locale).format(Date(millis))

    fun clock(millis: Long): String = SimpleDateFormat("HH:mm", L.locale).format(Date(millis))

    /** Spoken form of a time, e.g. "מחר ב-07:30" / "tomorrow at 07:30". */
    fun spoken(millis: Long, now: Long = System.currentTimeMillis()): String {
        val a = Calendar.getInstance().apply { timeInMillis = now }
        val b = Calendar.getInstance().apply { timeInMillis = millis }
        val days = ((startOfDay(b) - startOfDay(a)) / 86_400_000L).toInt()
        val hm = clock(millis)
        return when (days) {
            0 -> L.t("today at $hm", "היום ב-$hm")
            1 -> L.t("tomorrow at $hm", "מחר ב-$hm")
            else -> format(millis)
        }
    }

    private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    data class Match(val millis: Long, val start: Int, val end: Int)

    // ---------------- tokenizing ----------------

    private val heUnits = mapOf(
        "אחת" to 1, "אחד" to 1, "שתיים" to 2, "שתים" to 2, "שניים" to 2, "שני" to 2, "שתי" to 2,
        "שלוש" to 3, "שלושה" to 3, "ארבע" to 4, "ארבעה" to 4, "חמש" to 5, "חמישה" to 5,
        "שש" to 6, "שישה" to 6, "שבע" to 7, "שבעה" to 7, "שמונה" to 8, "תשע" to 9, "תשעה" to 9,
    )
    private val heTens = mapOf("עשרים" to 20, "שלושים" to 30, "ארבעים" to 40, "חמישים" to 50)
    private val enNums = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fifty" to 50,
    )
    private val splitTargets = heUnits.keys + heTens.keys + setOf(
        "עשר", "עשרה", "חצי", "רבע", "חצות", "צהריים", "הצהריים", "ערב", "בוקר", "לילה", "שעה", "שעתיים",
        "דקה", "דקות", "עשרים",
    )

    /** Normalizes and splits Hebrew one-letter prefixes off number/time words ("בשבע" → "ב", "שבע"). */
    fun tokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in HebrewText.normalize(text).split(' ')) {
            if (raw.isEmpty()) continue
            var w = raw
            Regex("""^(\d{1,2}(?::\d{2})?)(am|pm)$""").find(w)?.let { out += it.groupValues[1]; out += it.groupValues[2]; w = "" }
            if (w.isEmpty()) continue
            val digit = Regex("""^([בלו])-?(\d.*)$""").find(w)
            if (digit != null) { out += digit.groupValues[1]; out += digit.groupValues[2]; continue }
            var prefix = ""
            var rest = w
            while (rest.length > 2 && rest[0] in "בלו" && rest !in splitTargets && rest != "בעוד" && rest != "לפני") {
                val cand = rest.substring(1)
                if (cand in splitTargets || cand.matches(Regex("\\d.*"))) { prefix += rest[0]; rest = cand; break }
                if (cand.length > 2 && cand[0] in "בלה" && cand.substring(1) in splitTargets) {
                    prefix += rest[0]; prefix += cand[0]; rest = cand.substring(1); break
                }
                break
            }
            prefix.forEach { out += it.toString() }
            out += rest
        }
        return out
    }

    private fun intOf(t: String): Int? = t.toIntOrNull()

    /** Reads a number starting at [i]; returns value and the index after it. */
    private fun readNumber(tk: List<String>, i: Int): Pair<Int, Int>? {
        val t = tk.getOrNull(i) ?: return null
        intOf(t)?.let { return it to i + 1 }
        heTens[t]?.let { tens ->
            if (tk.getOrNull(i + 1) == "ו") {
                val u = tk.getOrNull(i + 2)?.let { heUnits[it] }
                if (u != null) return tens + u to i + 3
            }
            return tens to i + 1
        }
        heUnits[t]?.let { u ->
            val n = tk.getOrNull(i + 1)
            if (n == "עשרה" || n == "עשר") return 10 + u to i + 2
            return u to i + 1
        }
        if (t == "עשר" || t == "עשרה") return 10 to i + 1
        enNums[t]?.let { v ->
            if (v >= 20) {
                val u = tk.getOrNull(i + 1)?.let { enNums[it] }
                if (u != null && u < 10) return v + u to i + 2
            }
            return v to i + 1
        }
        return null
    }

    private val minuteWords = setOf("דקה", "דקות", "minute", "minutes", "min", "mins")
    private val hourWords = setOf("שעה", "שעות", "hour", "hours", "hr", "hrs")
    private val relStarts = setOf("בעוד", "עוד", "תוך", "בתוך", "in", "within")

    private fun relative(tk: List<String>, now: Long): Match? {
        for (i in tk.indices) {
            if (tk[i] !in relStarts) continue
            var j = i + 1
            if (tk.getOrNull(j) == "כ" || tk.getOrNull(j) == "about") j++
            var minutes: Double? = null
            when (tk.getOrNull(j)) {
                "שעה", "hour" -> { minutes = 60.0; j++ }
                "שעתיים" -> { minutes = 120.0; j++ }
                "דקה", "minute" -> { minutes = 1.0; j++ }
                "דקותיים" -> { minutes = 2.0; j++ }
                "רבע" -> if (tk.getOrNull(j + 1) == "שעה") { minutes = 15.0; j += 2 }
                "חצי" -> if (tk.getOrNull(j + 1) == "שעה") { minutes = 30.0; j += 2 }
                "half" -> if (tk.getOrNull(j + 1) in setOf("an", "a") && tk.getOrNull(j + 2) == "hour") { minutes = 30.0; j += 3 }
                "a", "an" -> when (tk.getOrNull(j + 1)) {
                    "hour" -> { minutes = 60.0; j += 2 }
                    "minute" -> { minutes = 1.0; j += 2 }
                    "quarter" -> if (tk.getOrNull(j + 2) == "of" && tk.getOrNull(j + 4) == "hour") { minutes = 15.0; j += 5 }
                }
            }
            if (minutes == null) {
                val (n, k) = readNumber(tk, j) ?: continue
                val unit = tk.getOrNull(k) ?: continue
                minutes = when (unit) {
                    in minuteWords -> n.toDouble()
                    in hourWords -> n * 60.0
                    else -> continue
                }
                j = k + 1
            }
            if (tk.getOrNull(j) == "ו" && tk.getOrNull(j + 1) == "חצי" && minutes >= 60) { minutes += 30; j += 2 }
            else if (tk.getOrNull(j) == "and" && tk.getOrNull(j + 1) == "a" && tk.getOrNull(j + 2) == "half") { minutes += 30; j += 3 }
            return Match(now + (minutes * 60_000).toLong(), i, j)
        }
        return null
    }

    private enum class Period { AM, PM, NIGHT, NOON }

    private fun periodAt(tk: List<String>, i: Int): Pair<Period, Int>? {
        var j = i
        if (tk.getOrNull(j) == "ב" || tk.getOrNull(j) == "in" || tk.getOrNull(j) == "at") j++
        if (tk.getOrNull(j) == "the") j++
        return when (tk.getOrNull(j)) {
            "בוקר", "am", "a.m", "a.m.", "morning" -> Period.AM to j + 1
            "ערב", "pm", "p.m", "p.m.", "evening", "afternoon" -> Period.PM to j + 1
            "לילה", "night" -> Period.NIGHT to j + 1
            "צהריים", "הצהריים" -> Period.NOON to j + 1
            "אחר", "אחרי" -> if (tk.getOrNull(j + 1) == "הצהריים" || tk.getOrNull(j + 1) == "ה") Period.PM to j + 2 else null
            "אחה\"צ" -> Period.PM to j + 1
            "לפנות" -> if (tk.getOrNull(j + 1) == "בוקר") Period.NIGHT to j + 2 else null
            else -> null
        }
    }

    private val dayWords = mapOf("מחר" to 1, "מחרתיים" to 2, "היום" to 0, "tomorrow" to 1, "today" to 0, "tonight" to 0)

    /**
     * Finds the first time expression in [tk]. If [requireCue] is true a bare number only counts as a time
     * when it has a cue (ב/ל/at/for, ":"-format, a period word, or a day word).
     */
    fun find(tk: List<String>, now: Long = System.currentTimeMillis(), requireCue: Boolean = true, preferMorning: Boolean = false): Match? {
        relative(tk, now)?.let { return it }
        var dayOffset: Int? = null
        var dayIdx = -1
        for ((i, t) in tk.withIndex()) dayWords[t]?.let { if (dayOffset == null) { dayOffset = it; dayIdx = i } }

        fun build(h0: Int, m: Int, period: Period?, start: Int, end: Int): Match? {
            var h = h0
            if (h !in 0..24 || m !in 0..59) return null
            if (h == 24) h = 0
            when (period) {
                Period.AM -> if (h == 12) h = 0
                Period.PM -> if (h < 12) h += 12
                Period.NIGHT -> if (h in 7..11) h += 12 else if (h == 12) h = 0
                Period.NOON -> if (h in 1..6) h += 12
                null -> {}
            }
            val c = Calendar.getInstance().apply {
                timeInMillis = now; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            }
            val off = dayOffset
            val morning = preferMorning && period == null && h in 4..11
            if (morning) {
                // Wake-up alarm: "7" means 07:00 (today if still ahead, otherwise tomorrow, or on the named day).
                if (off != null) c.add(Calendar.DAY_OF_YEAR, off) else if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
            } else if (off != null) {
                if (period == null && h in 1..6) c.set(Calendar.HOUR_OF_DAY, h + 12)
                c.add(Calendar.DAY_OF_YEAR, off)
                if (off == 0 && c.timeInMillis <= now && period == null && h <= 12) c.add(Calendar.HOUR_OF_DAY, 12)
            } else if (period == null && h in 1..12) {
                // Ambiguous 12-hour time: take whichever of h / h+12 comes next.
                if (c.timeInMillis <= now) c.add(Calendar.HOUR_OF_DAY, 12)
                if (c.timeInMillis <= now) c.add(Calendar.HOUR_OF_DAY, 12)
            } else if (c.timeInMillis <= now) {
                c.add(Calendar.DAY_OF_YEAR, 1)
            }
            val s = if (dayIdx >= 0 && dayIdx < start && start - dayIdx <= 2) dayIdx else start
            val e = if (dayIdx >= end && dayIdx - end <= 1) dayIdx + 1 else end
            return Match(c.timeInMillis, s, e)
        }

        for (i in tk.indices) {
            val t = tk[i]
            if (t == "חצות" || t == "midnight") return build(0, 0, Period.AM, if (i > 0 && tk[i - 1] == "ב") i - 1 else i, i + 1)
            if ((t == "צהריים" || t == "הצהריים" || t == "noon") && readNumber(tk, i - 1) == null) {
                val prev = tk.getOrNull(i - 1)
                if (prev != "אחר" && prev != "אחרי" && prev?.let { readNumber(tk, i - 2) } == null) {
                    return build(12, 0, null, if (prev == "ב" || prev == "at") i - 1 else i, i + 1)
                }
            }
        }

        val cues = setOf("ב", "ל", "at", "for", "by", "בשעה", "שעה")
        for (i in tk.indices) {
            var start = i
            var j = i
            var cued = false
            if (tk[j] in cues) { cued = true; j++; if (tk.getOrNull(j) == "שעה" || tk.getOrNull(j) == "השעה") j++ }
            val t = tk.getOrNull(j) ?: continue
            // "רבע ל 8", "עשרים ל 9", "quarter to 8", "20 to 9"
            run {
                val (mins, k) = when (t) {
                    "רבע", "quarter" -> 15 to j + 1
                    else -> readNumber(tk, j) ?: return@run
                }
                if (tk.getOrNull(k) == "ל" || tk.getOrNull(k) == "to") {
                    val (h, k2) = readNumber(tk, k + 1) ?: return@run
                    if (mins in 1..30 && h in 1..12) {
                        val p = periodAt(tk, k2)
                        return build(if (h == 1) 0 else h - 1, 60 - mins, p?.first, start, p?.second ?: k2)
                            ?.let { if (h == 1 && p == null) it.copy(millis = it.millis + 12 * 3_600_000L) else it }
                    }
                }
                if (tk.getOrNull(k) == "past" || tk.getOrNull(k) == "after") {
                    val (h, k2) = readNumber(tk, k + 1) ?: return@run
                    val p = periodAt(tk, k2)
                    return build(h, mins, p?.first, start, p?.second ?: k2)
                }
            }
            if (t == "half" && tk.getOrNull(j + 1) == "past") {
                val (h, k2) = readNumber(tk, j + 2) ?: continue
                val p = periodAt(tk, k2)
                return build(h, 30, p?.first, start, p?.second ?: k2)
            }
            // "7:30"
            Regex("""^(\d{1,2})[:.](\d{2})$""").find(t)?.let { m ->
                val p = periodAt(tk, j + 1)
                return build(m.groupValues[1].toInt(), m.groupValues[2].toInt(), p?.first, start, p?.second ?: (j + 1))
            }
            val (h, k) = readNumber(tk, j) ?: continue
            if (h > 24) continue
            var minutes = 0
            var end = k
            if (tk.getOrNull(k) == "ו") {
                when (tk.getOrNull(k + 1)) {
                    "חצי" -> { minutes = 30; end = k + 2 }
                    "רבע" -> { minutes = 15; end = k + 2 }
                    else -> readNumber(tk, k + 1)?.let { (mm, k2) -> if (mm < 60) { minutes = mm; end = k2 } }
                }
                if (tk.getOrNull(end) == "דקות") end++
            } else {
                tk.getOrNull(k)?.let { n -> if (n.length == 2 && n.toIntOrNull()?.let { it < 60 } == true) { minutes = n.toInt(); end = k + 1 } }
                if (tk.getOrNull(k) in setOf("o'clock", "oclock")) end = k + 1
            }
            val p = periodAt(tk, end)
            if (requireCue && !cued && p == null && dayOffset == null) continue
            if (!cued && tk.getOrNull(k) in minuteWords + hourWords) continue
            return build(h, minutes, p?.first, start, p?.second ?: end)
        }
        return null
    }

    /** Parses a whole phrase as a time; returns epoch millis or null. */
    fun parse(input: String, now: Long = System.currentTimeMillis(), preferMorning: Boolean = false): Long? =
        find(tokenize(input), now, requireCue = false, preferMorning = preferMorning)?.millis

    /** Next occurrence of hour:minute (today if still ahead, otherwise tomorrow). */
    fun nextOccurrence(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }
}
