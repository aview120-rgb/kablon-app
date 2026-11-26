package com.autoagent.app.util

import java.util.Locale

object HebrewText {
    private val niqqud = Regex("[֑-ׇ]")

    fun hasHebrew(s: String): Boolean = s.any { it in 'א'..'ת' }

    /** Lower-case, strip niqqud, unify quotes/dashes, drop punctuation (keeps digits, ':' '.' '+' and Hebrew/Latin letters). */
    fun normalize(s: String): String = s
        .replace(niqqud, "")
        .replace('״', '"').replace('׳', '\'').replace('״', '"').replace('׳', '\'')
        .replace('–', '-').replace('—', '-')
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}:.+'"\- ]"""), " ")
        .replace(Regex("""(?<=\p{L})-(?=\p{N})"""), " ")
        .replace(Regex("""(?<!\d):|:(?!\d)"""), " ")
        .replace(Regex("""(?<=\p{N})\.(?=\s|$)"""), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val prefixes = "והבלשכמ"

    /** True when [word] equals [kw] or is [kw] with up to two Hebrew one-letter prefixes (ו, ה, ב, ל, ש, כ, מ). */
    fun matchesWithPrefix(word: String, kw: String): Boolean {
        if (word == kw) return true
        if (word.length <= kw.length || word.length > kw.length + 2 || !word.endsWith(kw)) return false
        return word.substring(0, word.length - kw.length).all { it in prefixes }
    }

    /** Does [text] (normalized) contain the phrase [kw], allowing Hebrew prefixes on its first word? */
    fun containsPhrase(text: String, kw: String): Boolean {
        val words = text.split(' ')
        val kwWords = kw.split(' ').filter { it.isNotEmpty() }
        if (kwWords.isEmpty()) return false
        for (i in 0..words.size - kwWords.size) {
            if (!matchesWithPrefix(words[i], kwWords[0])) continue
            var ok = true
            for (j in 1 until kwWords.size) if (words[i + j] != kwWords[j]) { ok = false; break }
            if (ok) return true
        }
        return false
    }
}
