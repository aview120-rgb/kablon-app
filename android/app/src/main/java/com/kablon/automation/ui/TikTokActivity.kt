package com.kablon.automation.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.kablon.automation.R
import com.kablon.automation.automation.AutomationController
import com.kablon.automation.automation.FullFlow.runFull
import com.kablon.automation.core.Prefs
import com.kablon.automation.core.StepStatus
import com.kablon.automation.core.WorkflowLog
import com.kablon.automation.media.AiApiClient
import com.kablon.automation.media.MediaComposer
import com.kablon.automation.media.VideoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** הכנה והפעלה של זרימת TikTok עם יומן שלבים. */
@OptIn(UnstableApi::class)
class TikTokActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var topic: EditText
    private lateinit var postText: EditText
    private lateinit var sound: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tiktok)
        prefs = Prefs(this)
        topic = findViewById(R.id.ttTopic)
        postText = findViewById(R.id.ttPostText)
        sound = findViewById(R.id.ttSound)
        val autoPost = findViewById<CheckBox>(R.id.ttAutoPost)
        val useApp = findViewById<CheckBox>(R.id.ttUseAiApp)

        topic.setText(prefs.lastInstruction.ifBlank { prefs.lastCaption })
        postText.setText(prefs.lastPostText)
        sound.setText(prefs.lastSoundQuery)
        autoPost.isChecked = prefs.autoPost
        useApp.isChecked = !AiApiClient(prefs).hasKey

        findViewById<Button>(R.id.btnSuggestApi).setOnClickListener { suggestViaApi() }
        findViewById<Button>(R.id.btnRunTikTok).setOnClickListener {
            save()
            val f = prefs.lastOutputPath.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
            val mime = prefs.lastOutputMime.ifBlank { null }
            if (f == null) toast("אין מדיה מוכנה – הזרימה תפתח את TikTok ותבקש ממך לבחור קובץ מהגלריה.")
            val topicText = topic.text.toString()
            val post = postText.text.toString()
            val snd = sound.text.toString()
            val ask = useApp.isChecked
            val ap = autoPost.isChecked
            val err = AutomationController.start(this, "TikTok") {
                runFull(f, mime, topicText, post, snd, ask, ap) { s, c ->
                    if (prefs.lastSoundQuery.isBlank()) prefs.lastSoundQuery = s
                    if (prefs.lastPostText.isBlank()) prefs.lastPostText = c
                }
            }
            if (err != null) toast(err)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WorkflowLog.version.collect { refreshLog() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // ייתכן שהזרימה עדכנה הצעות בזמן שהיינו ברקע
        if (postText.text.isNullOrBlank()) postText.setText(prefs.lastPostText)
        if (sound.text.isNullOrBlank()) sound.setText(prefs.lastSoundQuery)
        showMedia()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun save() {
        prefs.lastPostText = postText.text.toString()
        prefs.lastSoundQuery = sound.text.toString()
        prefs.autoPost = findViewById<CheckBox>(R.id.ttAutoPost).isChecked
    }

    private fun showMedia() {
        val path = prefs.lastOutputPath
        val info = findViewById<TextView>(R.id.ttMediaInfo)
        val img = findViewById<ImageView>(R.id.ttPreview)
        val f = File(path)
        if (path.isBlank() || !f.exists()) {
            info.text = "אין מדיה מוכנה. עבד תמונה/סרטון במסך 'עיבוד מדיה' (או שהזרימה תבקש לבחור מהגלריה)."
            img.setImageDrawable(null)
            return
        }
        info.text = "${f.name}\n${prefs.lastGalleryPath}"
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    if (prefs.lastOutputMime.startsWith("video")) VideoProcessor.thumbnail(this@TikTokActivity, Uri.fromFile(f))
                    else MediaComposer.loadBitmap(this@TikTokActivity, Uri.fromFile(f), 1000)
                }.getOrNull()
            }
            img.setImageBitmap(bmp)
        }
    }

    private fun suggestViaApi() {
        val api = AiApiClient(prefs)
        if (!api.hasKey) {
            toast("אין מפתח API – סמן 'לקבל הצעה מאפליקציית ה-AI' והפעל את הזרימה"); return
        }
        val t = topic.text.toString().ifBlank { postText.text.toString() }
        if (t.isBlank()) {
            toast("כתוב נושא קצר"); return
        }
        lifecycleScope.launch {
            toast("שואל את ${api.textModelName()}…")
            api.chat(AiApiClient.suggestionPrompt(t)).fold(
                onSuccess = { ans ->
                    val p = AiApiClient.parseSuggestion(ans)
                    if (p == null) {
                        toast("לא הצלחתי לפענח את התשובה")
                        WorkflowLog.add("TikTok", "הצעת סאונד וכיתוב", StepStatus.FAIL, ans.take(300), api.textModelName())
                    } else {
                        sound.setText(p.first); postText.setText(p.second); save()
                        WorkflowLog.add("TikTok", "הצעת סאונד וכיתוב", StepStatus.OK, "סאונד: ${p.first} | כיתוב: ${p.second}", api.textModelName())
                    }
                },
                onFailure = { e ->
                    toast(e.message ?: "שגיאה")
                    WorkflowLog.add("TikTok", "הצעת סאונד וכיתוב", StepStatus.FAIL, e.message ?: "", api.textModelName())
                },
            )
        }
    }

    private fun refreshLog() {
        val entries = WorkflowLog.readAll().takeLast(25).reversed()
        findViewById<TextView>(R.id.ttLog).text =
            if (entries.isEmpty()) "(ריק)" else entries.joinToString("\n") { it.format() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
