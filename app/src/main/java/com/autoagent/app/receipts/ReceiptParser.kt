package com.autoagent.app.receipts

import java.util.Calendar
import java.util.Locale

/** Rule-based extraction of merchant, date and total from OCR text, dictation, or e-mail bodies. */
object ReceiptParser {
    data class Parsed(val merchant: String, val dateText: String, val dateMillis: Long?, val total: Double?, val currency: String)

    private val amount = Regex("""(?<![\d.,])(\d{1,3}(?:[,\s]\d{3})*(?:[.,]\d{2})|\d+(?:[.,]\d{2})?)(?![\d])""")
    private val totalKeys = listOf(
        "grand total", "total due", "amount due", "balance due", "total to pay", "to pay", "amount paid", "total amount",
        "total", "סה\"כ לתשלום", "סה\"כ", "סהכ", "לתשלום", "importe total", "montant total", "summe", "gesamt", "totale",
    )
    private val skipMerchant = listOf(
        "receipt", "invoice", "tax", "vat", "date", "time", "tel", "phone", "www", "http", "@", "total", "cashier",
        "order", "table", "קבלה", "חשבונית", "תאריך", "טלפון", "ח.פ", "עוסק",
    )
    private val months = mapOf(
        "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3, "may" to 4, "jun" to 5,
        "jul" to 6, "aug" to 7, "sep" to 8, "oct" to 9, "nov" to 10, "dec" to 11,
    )

    fun parse(text: String, monthFirst: Boolean = Locale.getDefault().country == "US"): Parsed {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val (dateText, millis) = findDate(text, monthFirst)
        return Parsed(
            merchant = findMerchant(lines),
            dateText = dateText,
            dateMillis = millis,
            total = findTotal(lines),
            currency = findCurrency(text),
        )
    }

    fun findCurrency(text: String): String = when {
        text.contains('₪') || Regex("""\b(ils|nis)\b|ש"ח|שח""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "ILS"
        text.contains('€') || Regex("""\beur\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "EUR"
        text.contains('£') || Regex("""\bgbp\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "GBP"
        text.contains('$') || Regex("""\busd\b|dollars?""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "USD"
        else -> ""
    }

    fun findMerchant(lines: List<String>): String {
        // Dictation like "receipt from Super Pharm ..." or "at Starbucks".
        lines.firstNotNullOfOrNull { l ->
            Regex("""(?:receipt|bill|paid|invoice)?\s*(?:from|at)\s+([\p{L}][\p{L}\p{N}&' .-]{1,40}?)(?:\s+(?:for|on|total|of|,)|$)""", RegexOption.IGNORE_CASE)
                .find(l)?.groupValues?.get(1)?.trim()
        }?.let { if (it.length >= 2) return it }
        return lines.take(8).firstOrNull { l ->
            val low = l.lowercase(Locale.ROOT)
            l.length in 3..40 &&
                l.count { it.isLetter() } >= 3 &&
                l.count { it.isDigit() } <= l.length / 3 &&
                skipMerchant.none { low.contains(it) }
        } ?: ""
    }

    private fun toNumber(s: String): Double? {
        var t = s.replace(" ", "")
        // "1.234,56" or "1,234.56" or "12,50"
        val lastComma = t.lastIndexOf(',')
        val lastDot = t.lastIndexOf('.')
        t = when {
            lastComma > lastDot && t.length - lastComma == 3 -> t.replace(".", "").replace(',', '.')
            else -> t.replace(",", "")
        }
        return t.toDoubleOrNull()
    }

    fun amountsIn(line: String): List<Double> =
        amount.findAll(line).mapNotNull { toNumber(it.groupValues[1]) }.filter { it in 0.01..1_000_000.0 }.toList()

    fun findTotal(lines: List<String>): Double? {
        for (key in totalKeys) {
            for ((i, l) in lines.withIndex()) {
                val low = l.lowercase(Locale.ROOT)
                if (!low.contains(key)) continue
                if (low.contains("sub") || low.contains("before") || low.contains("ביניים")) continue
                val onLine = amountsIn(l.substring(low.indexOf(key) + key.length))
                if (onLine.isNotEmpty()) return onLine.last()
                lines.getOrNull(i + 1)?.let { next -> amountsIn(next).lastOrNull()?.let { return it } }
            }
        }
        // Spoken: "total 45.90", "45 shekels", "it was 120 dollars".
        val all = lines.flatMap { l -> amountsIn(l).filter { a -> a != a.toLong().toDouble() || l.contains(Regex("[$€£₪]|dollar|shekel|euro|nis|ils", RegexOption.IGNORE_CASE)) } }
        if (all.isNotEmpty()) return all.max()
        return lines.flatMap { amountsIn(it) }.filter { it < 100_000 && it.toLong().toString().length < 6 }.maxOrNull()
    }

    fun findDate(text: String, monthFirst: Boolean): Pair<String, Long?> {
        Regex("""\b(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\b""").find(text)?.let { m ->
            val (y, mo, d) = m.destructured
            return m.value to millis(y.toInt(), mo.toInt() - 1, d.toInt())
        }
        Regex("""\b(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2,4})\b""").find(text)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            var y = m.groupValues[3].toInt()
            if (y < 100) y += 2000
            val (day, month) = when {
                a > 12 -> a to b
                b > 12 -> b to a
                monthFirst -> b to a
                else -> a to b
            }
            return m.value to millis(y, month - 1, day)
        }
        Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?,?\s+(\d{4})\b""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val mo = months[m.groupValues[2].lowercase(Locale.ROOT)] ?: 0
            return m.value to millis(m.groupValues[3].toInt(), mo, m.groupValues[1].toInt())
        }
        Regex("""\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})\b""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val mo = months[m.groupValues[1].lowercase(Locale.ROOT)] ?: 0
            return m.value to millis(m.groupValues[3].toInt(), mo, m.groupValues[2].toInt())
        }
        val low = text.lowercase(Locale.ROOT)
        if (low.contains("today")) return "today" to System.currentTimeMillis()
        if (low.contains("yesterday")) return "yesterday" to System.currentTimeMillis() - 86_400_000L
        return "" to null
    }

    private fun millis(y: Int, m: Int, d: Int): Long? {
        if (m !in 0..11 || d !in 1..31 || y !in 1990..2100) return null
        return Calendar.getInstance().apply {
            clear()
            set(y, m, d, 12, 0, 0)
        }.timeInMillis
    }
}
