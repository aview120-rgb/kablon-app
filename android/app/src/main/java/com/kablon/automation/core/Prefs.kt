package com.kablon.automation.core

import android.content.Context
import android.content.SharedPreferences

/** הגדרות האפליקציה (נשמרות באחסון הפרטי של האפליקציה בלבד). */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("kablon_prefs", Context.MODE_PRIVATE)

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun put(key: String, v: String) = sp.edit().putString(key, v).apply()
    private fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    private fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    // ---- AI ----
    var aiProvider: String get() = str("ai_provider", PROVIDER_OPENAI); set(v) = put("ai_provider", v)
    var openAiKey: String get() = str("openai_key", ""); set(v) = put("openai_key", v.trim())
    var geminiKey: String get() = str("gemini_key", ""); set(v) = put("gemini_key", v.trim())
    var openAiTextModel: String get() = str("openai_text_model", "gpt-4o-mini"); set(v) = put("openai_text_model", v.trim())
    var openAiImageModel: String get() = str("openai_image_model", "gpt-image-1"); set(v) = put("openai_image_model", v.trim())
    var geminiTextModel: String get() = str("gemini_text_model", "gemini-2.0-flash"); set(v) = put("gemini_text_model", v.trim())
    var geminiImageModel: String get() = str("gemini_image_model", "gemini-2.5-flash-image"); set(v) = put("gemini_image_model", v.trim())
    /** אפליקציית ה-AI המועדפת לאוטומציה גלויה (מזהה כלי מתוך ToolCatalog). */
    var preferredAiTool: String get() = str("preferred_ai_tool", "chatgpt"); set(v) = put("preferred_ai_tool", v)

    // ---- לוגו וכיתוב ----
    var logoPath: String get() = str("logo_path", ""); set(v) = put("logo_path", v)
    /** 0=למעלה-ימין 1=למעלה-שמאל 2=למטה-ימין 3=למטה-שמאל */
    var logoCorner: Int get() = sp.getInt("logo_corner", 2); set(v) = putInt("logo_corner", v)
    var logoScalePct: Int get() = sp.getInt("logo_scale", 18); set(v) = putInt("logo_scale", v.coerceIn(3, 60))
    var logoMarginPct: Int get() = sp.getInt("logo_margin", 3); set(v) = putInt("logo_margin", v.coerceIn(0, 20))
    var captionSizePct: Int get() = sp.getInt("caption_size", 5); set(v) = putInt("caption_size", v.coerceIn(2, 15))
    var captionAtTop: Boolean get() = sp.getBoolean("caption_top", false); set(v) = putBool("caption_top", v)

    // ---- אוטומציה ----
    var maxRetries: Int get() = sp.getInt("max_retries", 3); set(v) = putInt("max_retries", v.coerceIn(1, 10))
    var stepTimeoutSec: Int get() = sp.getInt("step_timeout", 15); set(v) = putInt("step_timeout", v.coerceIn(3, 120))
    var typingDelayMs: Int get() = sp.getInt("typing_delay", 35); set(v) = putInt("typing_delay", v.coerceIn(0, 300))
    var autoPost: Boolean get() = sp.getBoolean("auto_post", false); set(v) = putBool("auto_post", v)

    // ---- פרויקט נוכחי (מעבר בין מסכים) ----
    var lastOutputPath: String get() = str("last_output", ""); set(v) = put("last_output", v)
    var lastOutputMime: String get() = str("last_output_mime", ""); set(v) = put("last_output_mime", v)
    var lastGalleryPath: String get() = str("last_gallery_path", ""); set(v) = put("last_gallery_path", v)
    var lastInstruction: String get() = str("last_instruction", ""); set(v) = put("last_instruction", v)
    var lastCaption: String get() = str("last_caption", ""); set(v) = put("last_caption", v)
    var lastPostText: String get() = str("last_post_text", ""); set(v) = put("last_post_text", v)
    var lastSoundQuery: String get() = str("last_sound", ""); set(v) = put("last_sound", v)

    companion object {
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_GEMINI = "gemini"
    }
}
