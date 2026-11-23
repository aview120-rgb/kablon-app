package com.kablon.automation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.kablon.automation.R
import com.kablon.automation.automation.AiAppFlow.askAiApp
import com.kablon.automation.automation.AutomationController
import com.kablon.automation.core.Prefs
import com.kablon.automation.core.StepStatus
import com.kablon.automation.core.StorageBudget
import com.kablon.automation.core.WorkflowLog
import com.kablon.automation.media.AiApiClient
import com.kablon.automation.media.MediaComposer
import com.kablon.automation.media.MediaFiles
import com.kablon.automation.media.OverlayOptions
import com.kablon.automation.media.VideoProcessor
import com.kablon.automation.store.TermuxBridge
import com.kablon.automation.store.ToolCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** קבלת תמונה/סרטון + הנחיה, עריכה ב-AI, לוגו + כיתוב, וניסיון הסרת סימן מים. */
@OptIn(UnstableApi::class)
class MediaActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private var sourceUri: Uri? = null
    private var sourceMime: String = ""
    private var busy = false
    private var pendingTermux: String? = null

    private lateinit var preview: ImageView
    private lateinit var sourceInfo: TextView
    private lateinit var resultText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var instruction: EditText
    private lateinit var caption: EditText
    private lateinit var wmCorner: Spinner

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) setSource(uri, MediaFiles.mimeOf(this, uri))
    }
    private val termuxPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val cmd = pendingTermux
        pendingTermux = null
        if (ok && cmd != null) runTermux(cmd) else if (!ok) toast("ללא הרשאת Termux לא ניתן להריץ ffmpeg")
    }
    private val legacyWrite = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) processOverlays() else toast("נדרשת הרשאת אחסון לשמירה לגלריה")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media)
        prefs = Prefs(this)
        preview = findViewById(R.id.preview)
        sourceInfo = findViewById(R.id.sourceInfo)
        resultText = findViewById(R.id.resultText)
        progress = findViewById(R.id.progress)
        instruction = findViewById(R.id.instruction)
        caption = findViewById(R.id.caption)
        wmCorner = findViewById(R.id.wmCorner)
        instruction.setText(prefs.lastInstruction)
        caption.setText(prefs.lastCaption)

        wmCorner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, CORNERS)
        wmCorner.setSelection(2)

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        findViewById<Button>(R.id.btnProcess).setOnClickListener { processOverlays() }
        findViewById<Button>(R.id.btnAiApi).setOnClickListener { aiApiEdit(instruction.text.toString(), "עריכה לפי הנחיה") }
        findViewById<Button>(R.id.btnAiApp).setOnClickListener { aiAppEdit(instruction.text.toString()) }
        findViewById<Button>(R.id.btnWatermark).setOnClickListener { watermark() }
        findViewById<Button>(R.id.btnToTikTok).setOnClickListener {
            saveTexts()
            startActivity(Intent(this, TikTokActivity::class.java))
        }

        if (savedInstanceState == null) handleIncoming(intent)
        savedInstanceState?.getString(KEY_URI)?.let { setSource(Uri.parse(it), savedInstanceState.getString(KEY_MIME).orEmpty()) }
        if (sourceUri == null && prefs.lastOutputPath.isNotBlank() && File(prefs.lastOutputPath).exists()) {
            setSource(Uri.fromFile(File(prefs.lastOutputPath)), prefs.lastOutputMime)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sourceUri?.let { outState.putString(KEY_URI, it.toString()); outState.putString(KEY_MIME, sourceMime) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        val logo = prefs.logoPath
        findViewById<TextView>(R.id.logoInfo).text =
            if (logo.isNotBlank() && File(logo).exists()) "לוגו: ✅ מוגדר • פינה: ${CORNERS[prefs.logoCorner]} • רוחב ${prefs.logoScalePct}%"
            else "לוגו: ❌ לא הוגדר – הגדר במסך ההגדרות"
    }

    override fun onPause() {
        super.onPause()
        saveTexts()
    }

    private fun saveTexts() {
        prefs.lastInstruction = instruction.text.toString()
        prefs.lastCaption = caption.text.toString()
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
            setSource(uri, intent.type ?: MediaFiles.mimeOf(this, uri))
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { instruction.setText(it) }
        }
    }

    private val isVideo get() = sourceMime.startsWith("video")

    private fun setSource(uri: Uri, mime: String) {
        sourceUri = uri
        sourceMime = mime
        lifecycleScope.launch {
            val thumb: Bitmap? = withContext(Dispatchers.IO) {
                runCatching {
                    if (mime.startsWith("video")) VideoProcessor.thumbnail(this@MediaActivity, uri)
                    else MediaComposer.loadBitmap(this@MediaActivity, uri, 1200)
                }.getOrNull()
            }
            preview.setImageBitmap(thumb)
            val size = withContext(Dispatchers.IO) { MediaFiles.sizeOf(this@MediaActivity, uri) }
            sourceInfo.text = "${if (mime.startsWith("video")) "🎬 סרטון" else "🖼️ תמונה"} • ${StorageBudget.format(size)} • $mime"
        }
    }

    private fun requireSource(): Uri? {
        val u = sourceUri
        if (u == null) toast("בחר קודם תמונה או סרטון")
        return u
    }

    private fun setBusy(b: Boolean, msg: String = "") {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        if (msg.isNotEmpty()) resultText.text = msg
    }

    private fun log(step: String, st: StepStatus, detail: String = "", model: String? = null) =
        WorkflowLog.add("מדיה", step, st, detail, model)

    /** בדיקת תקציב 80GB לפני כתיבה. */
    private suspend fun checkStorage(uri: Uri): Boolean {
        val need = withContext(Dispatchers.IO) { MediaFiles.sizeOf(this@MediaActivity, uri) } * 2 + 50_000_000
        val err = withContext(Dispatchers.IO) { StorageBudget.checkCanWrite(this@MediaActivity, need) }
        if (err != null) {
            toast(err); resultText.text = err; return false
        }
        return true
    }

    // ------------------------------------------------------------ לוגו + כיתוב

    private fun processOverlays() {
        val uri = requireSource() ?: return
        if (busy) return
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            legacyWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return
        }
        saveTexts()
        val opts = OverlayOptions.from(prefs, caption.text.toString())
        val video = isVideo
        lifecycleScope.launch {
            if (!checkStorage(uri)) return@launch
            setBusy(true, "מעבד…")
            val logo = withContext(Dispatchers.IO) { MediaComposer.loadLogo(prefs) }
            val result: Result<File> = if (video) {
                val out = MediaFiles.newOutputFile(this@MediaActivity, "mp4")
                VideoProcessor.addOverlays(this@MediaActivity, uri, out, logo, opts) { resultText.text = it }
            } else withContext(Dispatchers.Default) {
                runCatching {
                    val bmp = MediaComposer.loadBitmap(this@MediaActivity, uri)
                    val composed = MediaComposer.composeImage(bmp, logo, opts)
                    MediaFiles.saveBitmap(composed, MediaFiles.newOutputFile(this@MediaActivity, "jpg"))
                }
            }
            result.fold(
                onSuccess = { file ->
                    val mime = if (video) "video/mp4" else "image/jpeg"
                    val gallery = withContext(Dispatchers.IO) {
                        runCatching { MediaFiles.copyToGallery(this@MediaActivity, file, mime) }.getOrNull()
                    }
                    prefs.lastOutputPath = file.absolutePath
                    prefs.lastOutputMime = mime
                    prefs.lastGalleryPath = gallery ?: ""
                    log("לוגו + כיתוב", StepStatus.OK, "פלט: ${file.name}" + if (logo == null) " (ללא לוגו)" else "")
                    setBusy(false, "✅ נשמר: ${gallery ?: file.absolutePath}\nאפשר להמשיך ל-TikTok.")
                    setSource(Uri.fromFile(file), mime)
                },
                onFailure = { e ->
                    log("לוגו + כיתוב", StepStatus.FAIL, e.message ?: "")
                    setBusy(false, "❌ העיבוד נכשל: ${e.message}")
                },
            )
        }
    }

    // ------------------------------------------------------------ AI דרך API

    private fun aiApiEdit(prompt: String, stepName: String) {
        val uri = requireSource() ?: return
        if (busy) return
        if (isVideo) {
            toast("עריכת AI דרך API נתמכת כרגע לתמונות בלבד. לסרטון השתמש באפליקציית AI או ב-Termux."); return
        }
        if (prompt.isBlank()) {
            toast("כתוב הנחיה"); return
        }
        val api = AiApiClient(prefs)
        if (!api.hasKey) {
            toast("אין מפתח API בהגדרות – משתמש באפליקציית ה-AI בצורה גלויה")
            aiAppEdit(prompt); return
        }
        lifecycleScope.launch {
            if (!checkStorage(uri)) return@launch
            setBusy(true, "שולח ל-${api.imageModelName()}… (עד כמה דקות)")
            val res: Result<Bitmap> = withContext(Dispatchers.Default) {
                runCatching { MediaComposer.loadBitmap(this@MediaActivity, uri, 2048) }
            }.let { loaded -> if (loaded.isFailure) loaded else api.editImage(loaded.getOrThrow(), prompt) }
            res.fold(
                onSuccess = { out ->
                    val file = withContext(Dispatchers.IO) {
                        MediaFiles.saveBitmap(out, MediaFiles.newOutputFile(this@MediaActivity, "png", "_ai"))
                    }
                    log(stepName, StepStatus.OK, file.name, api.imageModelName())
                    setBusy(false, "✅ התקבלה תמונה ממודל ${api.imageModelName()}. עכשיו אפשר להוסיף לוגו + כיתוב.")
                    setSource(Uri.fromFile(file), "image/png")
                },
                onFailure = { e ->
                    log(stepName, StepStatus.FAIL, e.message ?: "", api.imageModelName())
                    setBusy(false, "❌ ${e.message}")
                },
            )
        }
    }

    // ------------------------------------------------------------ AI דרך אפליקציה (גלוי)

    /** מוודא שיש קובץ שלנו (FileProvider) לשיתוף. */
    private suspend fun ensureLocalFile(uri: Uri, video: Boolean): File = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext File(uri.path!!)
        val f = MediaFiles.newOutputFile(this@MediaActivity, if (video) "mp4" else "jpg", "_src")
        val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("לא ניתן לקרוא את הקובץ")
        input.use { inp -> f.outputStream().use { inp.copyTo(it) } }
        f
    }

    private fun aiAppEdit(prompt: String) {
        val uri = requireSource() ?: return
        if (prompt.isBlank()) {
            toast("כתוב הנחיה"); return
        }
        val tool = ToolCatalog.byId(prefs.preferredAiTool) ?: ToolCatalog.aiChatTools.first()
        val mime = sourceMime
        val video = isVideo
        lifecycleScope.launch {
            if (!checkStorage(uri)) return@launch
            val file = runCatching { ensureLocalFile(uri, video) }.getOrElse { toast(it.message ?: "שגיאה"); return@launch }
            val err = AutomationController.start(this@MediaActivity, "עריכה ב-${tool.name}") {
                askAiApp(tool, prompt, file, mime, readAnswer = false)
                AutomationController.requestUser(
                    "כשהתוצאה מוכנה ב-${tool.name}: שמור אותה לגלריה, לחץ 'סיימתי ידנית – המשך', וחזור ל-Kablon לבחור אותה."
                )
            }
            if (err != null) toast(err) else resultText.text = "▶ האוטומציה רצה ב-${tool.name} – עקוב על המסך."
        }
    }

    // ------------------------------------------------------------ סימן מים

    private fun watermark() {
        val uri = requireSource() ?: return
        if (!findViewById<CheckBox>(R.id.wmRights).isChecked) {
            toast("סמן שהתוכן שלך / שיש לך זכות לערוך אותו"); return
        }
        val video = isVideo
        log("ניסיון הסרת סימן מים", StepStatus.START, if (video) "וידאו – טשטוש אזור" else "תמונה – עריכה מחדש במודל")
        if (!video) {
            val extra = instruction.text.toString().takeIf { it.isNotBlank() }?.let { " Additional instruction: $it" } ?: ""
            if (AiApiClient(prefs).hasKey) aiApiEdit(AiApiClient.WATERMARK_PROMPT + extra, "הסרת סימן מים")
            else aiAppEdit("צייר מחדש את התמונה בדיוק כפי שהיא, ללא סימן מים / לוגו / טקסט שקוף שמוטבע עליה. שמור על כל שאר הפרטים זהים.")
            return
        }
        // וידאו: ffmpeg delogo דרך Termux (טשטוש אזור)
        val corner = wmCorner.selectedItemPosition
        lifecycleScope.launch {
            if (!checkStorage(uri)) return@launch
            setBusy(true, "מכין קובץ ל-Termux…")
            val res = runCatching {
                val local = ensureLocalFile(uri, true)
                withContext(Dispatchers.IO) {
                    val gallery = MediaFiles.copyToGallery(this@MediaActivity, local, "video/mp4")
                    val vi = VideoProcessor.info(this@MediaActivity, uri)
                    val w = (vi.width * 0.30).toInt()
                    val h = (vi.height * 0.10).toInt()
                    val mx = (vi.width * 0.02).toInt().coerceAtLeast(1)
                    val my = (vi.height * 0.02).toInt().coerceAtLeast(1)
                    val x = if (corner == 1 || corner == 3) mx else vi.width - w - mx
                    val y = if (corner == 0 || corner == 1) my else vi.height - h - my
                    val out = gallery.substringBeforeLast('.') + "_nowm.mp4"
                    TermuxBridge.delogoCommand(gallery, out, x.coerceAtLeast(1), y.coerceAtLeast(1), w, h)
                }
            }
            setBusy(false)
            res.fold(
                onSuccess = { cmd -> runTermux(cmd) },
                onFailure = { e ->
                    resultText.text = "❌ ${e.message}"
                    log("הסרת סימן מים", StepStatus.FAIL, e.message ?: "")
                },
            )
        }
    }

    private fun runTermux(cmd: String) {
        if (!TermuxBridge.isInstalled(this)) {
            toast("Termux לא מותקן – התקן אותו מהחנות הפנימית (F-Droid) והרץ את 'הגדרת Termux'")
            return
        }
        if (!TermuxBridge.hasPermission(this)) {
            pendingTermux = cmd
            termuxPerm.launch(TermuxBridge.PERMISSION)
            return
        }
        val err = TermuxBridge.run(this, cmd)
        if (err != null) {
            resultText.text = "❌ $err"
            log("הסרת סימן מים (Termux)", StepStatus.FAIL, err, "ffmpeg delogo")
        } else {
            resultText.text = "▶ ffmpeg רץ ב-Termux (חלון גלוי). בסיום הקובץ _nowm יופיע בגלריה – בחר אותו כאן."
            log("הסרת סימן מים (Termux)", StepStatus.OK, "נשלח ל-Termux", "ffmpeg delogo")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val KEY_URI = "source_uri"
        private const val KEY_MIME = "source_mime"
        val CORNERS = listOf("למעלה-ימין", "למעלה-שמאל", "למטה-ימין", "למטה-שמאל")
    }
}
