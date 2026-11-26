package com.autoagent.app.calls

import com.autoagent.app.data.KeywordRule
import com.autoagent.app.util.HebrewText
import com.autoagent.app.util.L

/** Offline, rule-based replies for the call assistant (Hebrew and English). No cloud model. */
object CallBrain {
    val GREETING get() = L.t("This call is answered by an assistant and is recorded.", "השיחה נענית על ידי עוזר ומוקלטת.")
    val ASK get() = L.t("What do you need? Please tell me now.", "במה אפשר לעזור? אפשר לדבר עכשיו.")
    val UNKNOWN_REPLY get() = L.t("I will pass this to the owner.", "אני אעביר את זה לבעל המספר.")
    val ANYTHING_ELSE get() = L.t("Anything else?", "יש עוד משהו?")
    val CLOSING get() = L.t("Thank you. The owner will call you back. Goodbye.", "תודה. יחזרו אליך בהקדם. להתראות.")
    val NOT_HEARD get() = L.t("Sorry, I did not catch that. Please say it again.", "סליחה, לא שמעתי. אפשר לחזור על זה?")
    val SAVED get() = L.t("Got it. I saved your message.", "קיבלתי. שמרתי את ההודעה.")
    const val UNKNOWN = "UNKNOWN"

    data class Reply(val intent: String, val text: String)

    fun matches(utterance: String, rule: KeywordRule): Boolean {
        val u = HebrewText.normalize(utterance)
        return rule.keywords.split(',')
            .map { HebrewText.normalize(it) }
            .filter { it.isNotEmpty() }
            .any { HebrewText.containsPhrase(u, it) }
    }

    fun reply(utterance: String, rules: List<KeywordRule>, hebrew: Boolean = L.hebrew): Reply {
        for (rule in rules.filter { it.enabled }.sortedByDescending { it.priority }) {
            if (matches(utterance, rule)) {
                val text = if (hebrew && rule.responseHe.isNotBlank()) rule.responseHe else rule.response
                return Reply(rule.intent, text)
            }
        }
        return Reply(UNKNOWN, if (hebrew) "אני אעביר את זה לבעל המספר." else "I will pass this to the owner.")
    }
}
