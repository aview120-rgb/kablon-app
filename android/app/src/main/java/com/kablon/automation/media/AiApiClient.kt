package com.kablon.automation.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.kablon.automation.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * פנייה ישירה למודלי AI דרך API (אופציונלי – דורש מפתח בהגדרות).
 * ללא מפתח, Kablon משתמש באוטומציה גלויה מול אפליקציית ה-AI במקום.
 */
class AiApiClient(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()

    val hasKey: Boolean
        get() = if (prefs.aiProvider == Prefs.PROVIDER_GEMINI) prefs.geminiKey.isNotBlank() else prefs.openAiKey.isNotBlank()

    /** שם המודל שישתתף (לרישום ביומן). */
    fun textModelName() =
        if (prefs.aiProvider == Prefs.PROVIDER_GEMINI) "Gemini API (${prefs.geminiTextModel})" else "OpenAI API (${prefs.openAiTextModel})"

    fun imageModelName() =
        if (prefs.aiProvider == Prefs.PROVIDER_GEMINI) "Gemini API (${prefs.geminiImageModel})" else "OpenAI API (${prefs.openAiImageModel})"

    suspend fun chat(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (prefs.aiProvider == Prefs.PROVIDER_GEMINI) geminiText(prompt) else openAiText(prompt)
        }
    }

    suspend fun editImage(src: Bitmap, instruction: String): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            if (prefs.aiProvider == Prefs.PROVIDER_GEMINI) geminiImage(src, instruction) else openAiImage(src, instruction)
        }
    }

    // ---------------------------------------------------------------- OpenAI

    private fun openAiText(prompt: String): String {
        require(prefs.openAiKey.isNotBlank()) { "חסר מפתח OpenAI בהגדרות" }
        val body = JSONObject()
            .put("model", prefs.openAiTextModel)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val req = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer ${prefs.openAiKey}")
            .post(body.toString().toRequestBody(json))
            .build()
        val o = execute(req)
        return o.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
    }

    private fun openAiImage(src: Bitmap, instruction: String): Bitmap {
        require(prefs.openAiKey.isNotBlank()) { "חסר מפתח OpenAI בהגדרות" }
        val png = toPng(src, 1536)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", prefs.openAiImageModel)
            .addFormDataPart("prompt", instruction)
            .addFormDataPart("image", "image.png", png.toRequestBody("image/png".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("https://api.openai.com/v1/images/edits")
            .header("Authorization", "Bearer ${prefs.openAiKey}")
            .post(body)
            .build()
        val o = execute(req)
        val b64 = o.getJSONArray("data").getJSONObject(0).optString("b64_json")
        require(b64.isNotBlank()) { "המודל לא החזיר תמונה" }
        return decode(b64)
    }

    // ---------------------------------------------------------------- Gemini

    private fun geminiUrl(model: String) =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

    private fun geminiText(prompt: String): String {
        require(prefs.geminiKey.isNotBlank()) { "חסר מפתח Gemini בהגדרות" }
        val body = JSONObject().put("contents", JSONArray().put(
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        ))
        val req = Request.Builder().url(geminiUrl(prefs.geminiTextModel))
            .header("x-goog-api-key", prefs.geminiKey)
            .post(body.toString().toRequestBody(json)).build()
        val parts = execute(req).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString().trim()
    }

    private fun geminiImage(src: Bitmap, instruction: String): Bitmap {
        require(prefs.geminiKey.isNotBlank()) { "חסר מפתח Gemini בהגדרות" }
        val b64 = Base64.encodeToString(toPng(src, 1536), Base64.NO_WRAP)
        val parts = JSONArray()
            .put(JSONObject().put("text", instruction))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/png").put("data", b64)))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
        val req = Request.Builder().url(geminiUrl(prefs.geminiImageModel))
            .header("x-goog-api-key", prefs.geminiKey)
            .post(body.toString().toRequestBody(json)).build()
        val out = execute(req).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        var text = ""
        for (i in 0 until out.length()) {
            val p = out.getJSONObject(i)
            val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
            if (inline != null) return decode(inline.getString("data"))
            text += p.optString("text")
        }
        throw IllegalStateException("המודל לא החזיר תמונה. תשובה: ${text.take(300)}")
    }

    // ---------------------------------------------------------------- util

    private fun execute(req: Request): JSONObject {
        http.newCall(req).execute().use { resp ->
            val s = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(s).getJSONObject("error").optString("message") }.getOrNull()
                throw IllegalStateException("שגיאת API ${resp.code}: ${msg ?: s.take(300)}")
            }
            return JSONObject(s)
        }
    }

    private fun toPng(src: Bitmap, maxDim: Int): ByteArray {
        val m = maxOf(src.width, src.height)
        val b = if (m > maxDim) {
            val s = maxDim.toFloat() / m
            Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
        } else src
        return ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }

    private fun decode(b64: String): Bitmap {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("פענוח התמונה מהמודל נכשל")
    }

    companion object {
        const val WATERMARK_PROMPT =
            "Recreate this exact image without any watermark, logo stamp, username overlay or semi-transparent text mark. " +
                "Keep the composition, people, colors, lighting and all other details identical. Do not add anything new."

        fun suggestionPrompt(topic: String) =
            "אני מכין פוסט ל-TikTok. נושא/תיאור: \"$topic\". " +
                "הצע: (1) שאילתת חיפוש קצרה לסאונד/שיר טרנדי שמתאים ב-TikTok, (2) כיתוב קצר וקליט בעברית עם 3-5 האשטגים. " +
                "ענה אך ורק ב-JSON בפורמט: {\"sound\":\"...\",\"caption\":\"...\"}"

        /** חילוץ {"sound","caption"} מתשובת מודל (גם אם יש טקסט מסביב). */
        fun parseSuggestion(text: String): Pair<String, String>? {
            val s = text.indexOf('{'); val e = text.lastIndexOf('}')
            if (s < 0 || e <= s) return null
            return runCatching {
                val o = JSONObject(text.substring(s, e + 1))
                o.optString("sound").trim() to o.optString("caption").trim()
            }.getOrNull()
        }
    }
}
