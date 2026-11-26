package com.autoagent.app.calls

import com.autoagent.app.data.KeywordRule
import com.autoagent.app.util.HebrewText
import com.autoagent.app.util.L

/** Offline, rule-based replies for the call assistant (Hebrew and English). No cloud model. */
object CallBrain {
    /** Mandatory first sentence. */
    val GREETING get() = L.t("This call is answered by an assistant and is recorded.", "השיחה נענית על ידי עוזר ומוקלטת.")
    val ASK get() = L.t(
        "I am the digital assistant. The owner cannot answer right now. How can I help?",
        "אני הנציג הדיגיטלי. הוא לא יכול לענות כרגע. במה אפשר לעזור?",
    )
    val ANYTHING_ELSE get() = L.t("Anything else?", "יש עוד משהו?")
    val CLOSING get() = L.t("Thank you. The owner will call you back soon. Goodbye.", "תודה רבה. הוא יחזור אליך בהקדם. יום טוב!")
    val NOT_HEARD get() = L.t("Sorry, I did not hear you. Please say it again.", "סליחה, לא שמעתי. אפשר לחזור על זה?")
    val SAVED get() = L.t("Got it, I saved your message. Anything else?", "קיבלתי, שמרתי את ההודעה. יש עוד משהו?")
    const val UNKNOWN = "UNKNOWN"

    fun unknownReply(hebrew: Boolean = L.hebrew): String =
        if (hebrew) "אני נציג דיגיטלי ולא הבנתי מה אמרת. הוא לא פנוי כרגע. אם תרצה להשאיר הודעה, אפשר לומר אותה עכשיו, והוא יחזור אליך בהקדם."
        else "I am a digital assistant and did not understand that. The owner is not available right now. If you want to leave a message, say it now and the owner will call you back soon."

    val UNKNOWN_REPLY get() = unknownReply()

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
        return Reply(UNKNOWN, unknownReply(hebrew))
    }
}
