package com.autoagent.app.access

/**
 * Per-app knowledge for driving messaging apps: how to open a chat with text already filled in, and which
 * view ids are the Send button. Unknown apps fall back to ACTION_SEND + generic "Send" labels.
 * Plain strings (no android.net.Uri) so it is unit-testable.
 */
data class MessengerProfile(
    val packageName: String,
    val label: String,
    /** Builds a deep link that opens the chat with [text] typed in, or null if the app has none for this recipient. */
    val deepLink: ((recipient: String, text: String) -> String?)? = null,
    val sendIds: List<String> = emptyList(),
)

object MessengerProfiles {
    private val GENERIC_IDS = listOf("send", "send_button", "sendButton", "btn_send", "chat_send_button", "conversation_send")

    /** Words used as the Send button's label/description in common languages (Hebrew first). */
    val SEND_WORDS = setOf("שלח", "שליחה", "שלח הודעה", "send", "send message", "enviar", "envoyer", "senden", "invia", "отправить", "إرسال")

    fun isPhone(s: String) = s.trim().matches(Regex("""\+?[\d\s\-()]{6,}"""))

    /** International digits for wa.me style links; Israeli local numbers (05x…) become 9725x…. */
    fun internationalDigits(phone: String, defaultCountry: String = "972"): String {
        val trimmed = phone.trim()
        val digits = trimmed.filter { it.isDigit() }
        return when {
            trimmed.startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.startsWith("0") -> defaultCountry + digits.drop(1)
            else -> digits
        }
    }

    fun encode(text: String): String = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private val whatsapp: (String, String) -> String? = { r, t ->
        if (isPhone(r)) "https://api.whatsapp.com/send?phone=${internationalDigits(r)}&text=${encode(t)}" else null
    }

    val ALL: List<MessengerProfile> = listOf(
        MessengerProfile("com.whatsapp", "WhatsApp", whatsapp, listOf("send", "conversation_send")),
        MessengerProfile("com.whatsapp.w4b", "WhatsApp Business", whatsapp, listOf("send", "conversation_send")),
        MessengerProfile("org.telegram.messenger", "Telegram", { r, t ->
            if (r.startsWith("@")) "tg://resolve?domain=${r.drop(1)}&text=${encode(t)}" else null
        }, listOf("chat_send_button")),
        MessengerProfile("org.thoughtcrime.securesms", "Signal", null, listOf("send_button")),
        MessengerProfile("com.google.android.apps.messaging", "Google Messages", { r, t ->
            if (isPhone(r)) "smsto:${r.filter { it.isDigit() || it == '+' }}?body=${encode(t)}" else null
        }, listOf("send_message_button_icon", "send_message_button")),
        MessengerProfile("com.facebook.orca", "Messenger", null, listOf("send_button")),
        MessengerProfile("com.viber.voip", "Viber", null, listOf("send_text")),
    )

    fun forPackage(pkg: String): MessengerProfile =
        ALL.firstOrNull { it.packageName == pkg } ?: MessengerProfile(pkg, pkg)

    /** Full view ids to look for, app-specific first. */
    fun sendViewIds(pkg: String): List<String> = (forPackage(pkg).sendIds + GENERIC_IDS).distinct().map { "$pkg:id/$it" }

    fun isSendLabel(s: CharSequence?): Boolean = s?.toString()?.trim()?.lowercase()?.let { it in SEND_WORDS } == true
}
