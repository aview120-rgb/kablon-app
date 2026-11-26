package com.autoagent.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeText {
    fun format(millis: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "EEE d MMM, HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
    }

    fun full(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    fun clock(millis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    /** Next occurrence of hour:minute (today if still ahead, otherwise tomorrow). */
    fun nextOccurrence(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /**
     * Parses spoken/typed times: "7", "7:30", "7 30", "7.30 pm", "19:45", "noon", "midnight",
     * "in 10 minutes", "in 2 hours", optionally preceded by "tomorrow". Returns epoch millis or null.
     */
    fun parse(input: String, now: Long = System.currentTimeMillis()): Long? {
        var s = input.lowercase(Locale.ROOT).trim()
            .replace("a.m.", "am").replace("p.m.", "pm").replace("o'clock", "").trim()
        val rel = Regex("""in\s+(\d+|an?|one|half an?)\s*(minute|minutes|min|mins|hour|hours|hr|hrs)""").find(s)
        if (rel != null) {
            val qty = when (val q = rel.groupValues[1]) {
                "a", "an", "one" -> 1.0
                "half a", "half an" -> 0.5
                else -> q.toDoubleOrNull() ?: return null
            }
            val unitMs = if (rel.groupValues[2].startsWith("h")) 3_600_000L else 60_000L
            return now + (qty * unitMs).toLong()
        }
        val tomorrow = s.contains("tomorrow")
        s = s.replace("tomorrow", "").replace("today", "").trim()
        val hm: Pair<Int, Int> = when {
            s.contains("noon") -> 12 to 0
            s.contains("midnight") -> 0 to 0
            else -> {
                val m = Regex("""(\d{1,2})(?:[:.\s](\d{2}))?\s*(am|pm)?""").find(s) ?: return null
                var h = m.groupValues[1].toInt()
                val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                when (m.groupValues[3]) {
                    "pm" -> if (h < 12) h += 12
                    "am" -> if (h == 12) h = 0
                }
                if (h > 23 || min > 59) return null
                h to min
            }
        }
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hm.first)
            set(Calendar.MINUTE, hm.second)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (tomorrow) c.add(Calendar.DAY_OF_YEAR, 1) else if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }
}
