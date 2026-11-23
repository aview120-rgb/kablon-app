package com.kablon.automation.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kablon.automation.R
import com.kablon.automation.core.Prefs
import com.kablon.automation.store.ToolCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private val aiTools = ToolCatalog.aiChatTools

    private val pickLogo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = File(filesDir, "logo.png")
                    val input = contentResolver.openInputStream(uri) ?: error("לא ניתן לקרוא")
                    input.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
                    // אימות שזו תמונה תקינה
                    BitmapFactory.decodeFile(dest.absolutePath) ?: error("קובץ לא תקין")
                    prefs.logoPath = dest.absolutePath
                }.isSuccess
            }
            toast(if (ok) "הלוגו נשמר" else "שמירת הלוגו נכשלה")
            showLogo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        findViewById<RadioButton>(if (prefs.aiProvider == Prefs.PROVIDER_GEMINI) R.id.provGemini else R.id.provOpenAi).isChecked = true
        et(R.id.openAiKey).setText(prefs.openAiKey)
        et(R.id.openAiTextModel).setText(prefs.openAiTextModel)
        et(R.id.openAiImageModel).setText(prefs.openAiImageModel)
        et(R.id.geminiKey).setText(prefs.geminiKey)
        et(R.id.geminiTextModel).setText(prefs.geminiTextModel)
        et(R.id.geminiImageModel).setText(prefs.geminiImageModel)

        val aiSpinner = findViewById<Spinner>(R.id.preferredAi)
        aiSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, aiTools.map { it.name })
        aiSpinner.setSelection(aiTools.indexOfFirst { it.id == prefs.preferredAiTool }.coerceAtLeast(0))

        val corner = findViewById<Spinner>(R.id.logoCorner)
        corner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, MediaActivity.CORNERS)
        corner.setSelection(prefs.logoCorner)
        et(R.id.logoScale).setText(prefs.logoScalePct.toString())
        et(R.id.logoMargin).setText(prefs.logoMarginPct.toString())
        et(R.id.captionSize).setText(prefs.captionSizePct.toString())
        findViewById<CheckBox>(R.id.captionTop).isChecked = prefs.captionAtTop

        et(R.id.maxRetries).setText(prefs.maxRetries.toString())
        et(R.id.stepTimeout).setText(prefs.stepTimeoutSec.toString())
        et(R.id.typingDelay).setText(prefs.typingDelayMs.toString())
        findViewById<CheckBox>(R.id.autoPostDefault).isChecked = prefs.autoPost

        findViewById<Button>(R.id.btnPickLogo).setOnClickListener {
            pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs.aiProvider = if (findViewById<RadioButton>(R.id.provGemini).isChecked) Prefs.PROVIDER_GEMINI else Prefs.PROVIDER_OPENAI
            prefs.openAiKey = et(R.id.openAiKey).text.toString()
            prefs.openAiTextModel = et(R.id.openAiTextModel).text.toString().ifBlank { "gpt-4o-mini" }
            prefs.openAiImageModel = et(R.id.openAiImageModel).text.toString().ifBlank { "gpt-image-1" }
            prefs.geminiKey = et(R.id.geminiKey).text.toString()
            prefs.geminiTextModel = et(R.id.geminiTextModel).text.toString().ifBlank { "gemini-2.0-flash" }
            prefs.geminiImageModel = et(R.id.geminiImageModel).text.toString().ifBlank { "gemini-2.5-flash-image" }
            prefs.preferredAiTool = aiTools.getOrNull(aiSpinner.selectedItemPosition)?.id ?: "chatgpt"
            prefs.logoCorner = corner.selectedItemPosition
            prefs.logoScalePct = num(R.id.logoScale, 18)
            prefs.logoMarginPct = num(R.id.logoMargin, 3)
            prefs.captionSizePct = num(R.id.captionSize, 5)
            prefs.captionAtTop = findViewById<CheckBox>(R.id.captionTop).isChecked
            prefs.maxRetries = num(R.id.maxRetries, 3)
            prefs.stepTimeoutSec = num(R.id.stepTimeout, 15)
            prefs.typingDelayMs = num(R.id.typingDelay, 35)
            prefs.autoPost = findViewById<CheckBox>(R.id.autoPostDefault).isChecked
            toast("נשמר")
            finish()
        }
        showLogo()
    }

    private fun et(id: Int) = findViewById<EditText>(id)
    private fun num(id: Int, def: Int) = et(id).text.toString().toIntOrNull() ?: def

    private fun showLogo() {
        val p = prefs.logoPath
        val iv = findViewById<ImageView>(R.id.logoPreview)
        if (p.isNotBlank() && File(p).exists()) iv.setImageBitmap(BitmapFactory.decodeFile(p)) else iv.setImageDrawable(null)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
