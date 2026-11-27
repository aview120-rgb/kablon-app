package com.autoagent.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * App language. "auto" follows the phone; "he" / "en" force a language.
 * Every user-facing sentence goes through [t] so the UI, notifications, spoken replies and the call
 * assistant all switch together.
 */
object L {
    const val AUTO = "auto"
    const val HE = "he"
    const val EN = "en"

    // Hebrew first: the app starts right-to-left even before settings are read.
    private val _hebrew = MutableStateFlow(true)
    val hebrewFlow: StateFlow<Boolean> = _hebrew.asStateFlow()
    val hebrew: Boolean get() = _hebrew.value

    var mode: String = AUTO
        private set

    fun systemIsHebrew(): Boolean = Locale.getDefault().language.let { it == "iw" || it == "he" }

    fun apply(newMode: String) {
        mode = newMode
        _hebrew.value = when (newMode) {
            HE -> true
            EN -> false
            else -> systemIsHebrew()
        }
    }

    fun t(en: String, he: String): String = if (hebrew) he else en

    /** Locale for date formatting. */
    val locale: Locale get() = if (hebrew) Locale.forLanguageTag("he-IL") else if (systemIsHebrew()) Locale.US else Locale.getDefault()

    /** BCP-47 tag for speech recognition and text-to-speech. */
    val speechTag: String get() = if (hebrew) "he-IL" else if (systemIsHebrew()) "en-US" else Locale.getDefault().toLanguageTag()

    val speechLocale: Locale get() = Locale.forLanguageTag(speechTag)
}
