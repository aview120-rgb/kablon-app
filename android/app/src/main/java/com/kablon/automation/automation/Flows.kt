package com.kablon.automation.automation

import com.kablon.automation.core.StepStatus
import com.kablon.automation.media.AiApiClient
import com.kablon.automation.media.MediaFiles
import com.kablon.automation.store.Tool
import com.kablon.automation.store.ToolCatalog
import kotlinx.coroutines.delay
import java.io.File

/** זרימות גלויות: שליחת הנחיה לאפליקציית AI, ובדיקה מהירה. */
object AiAppFlow {

    /**
     * פותח את אפליקציית ה-AI, מקליד את ההנחיה (ומצרף מדיה אם יש), שולח,
     * ומנסה לקרוא את התשובה מהמסך. מחזיר את התשובה או null.
     */
    suspend fun Automation.askAiApp(tool: Tool, prompt: String, media: File? = null, mime: String? = null, readAnswer: Boolean = true): String? {
        model = "${tool.name} (אפליקציה)"
        val pkg = ToolCatalog.installedPackage(svc, tool)
        if (pkg == null) {
            log("פתיחת ${tool.name}", StepStatus.FAIL, "האפליקציה לא מותקנת")
            AutomationController.requestUser("${tool.name} לא מותקנת. התקן מהחנות הפנימית ולחץ המשך, או עצור.")
            return null
        }

        step("פתיחת ${tool.name}" + if (media != null) " עם המדיה" else "") {
            if (media != null && mime != null) launchIntent(MediaFiles.shareIntent(svc, media, mime, pkg, prompt), pkg)
            else launchApp(pkg)
        }
        pauseMs(2500)
        tryConfirm(2)

        step("הקלדת ההנחיה") {
            val already = screenTexts().any { it.contains(prompt.take(25)) }
            already || typeText(prompt)
        }
        val before = screenTexts().toSet()
        step("שליחה", manualHint = "לא מצאתי את כפתור השליחה ב-${tool.name}. לחץ 'שלח' בעצמך ואז 'סיימתי ידנית – המשך'.") {
            clickLabels(Labels.SEND, 8000) || imeEnter()
        }
        if (!readAnswer) return null
        status("ממתין לתשובה מ-${tool.name}…")
        val answer = waitForAnswer(before, prompt)
        if (answer.isNullOrBlank()) {
            log("קריאת תשובה", StepStatus.FAIL, "לא זוהתה תשובה על המסך")
        } else {
            log("קריאת תשובה", StepStatus.OK, answer.take(600))
        }
        return answer
    }

    /** מחכה שטקסט חדש יופיע ויתייצב (המודל סיים לכתוב). */
    private suspend fun Automation.waitForAnswer(before: Set<String>, prompt: String, timeoutMs: Long = 120_000): String? {
        val end = System.currentTimeMillis() + timeoutMs
        var last = ""
        var stable = 0
        while (System.currentTimeMillis() < end) {
            pauseMs(2000)
            val fresh = screenTexts()
                .filter { it !in before && it.length > 15 && !prompt.startsWith(it) && !it.startsWith(prompt.take(20)) }
                .distinct()
            val joined = fresh.joinToString("\n")
            if (joined.isNotEmpty() && joined == last) {
                if (++stable >= 2) return joined
            } else {
                stable = 0
                last = joined
            }
        }
        return last.ifBlank { null }
    }

    /** בדיקה מהירה: פותח את אפליקציית ה-AI המועדפת ומקליד טקסט (בלי לשלוח). */
    suspend fun Automation.quickTest() {
        val tool = ToolCatalog.byId(prefs.preferredAiTool) ?: ToolCatalog.tools[1]
        model = tool.name
        val pkg = ToolCatalog.installedPackage(svc, tool)
        step("פתיחת ${tool.name}") { pkg != null && launchApp(pkg) }
        pauseMs(2000)
        tryConfirm(2)
        step("הקלדת טקסט בדיקה") { typeText("שלום! זו בדיקה של Kablon – אני מקליד בצורה גלויה 👋") }
        step("גלילה", optional = true) { scroll(true) }
        delay(500)
    }
}

/** זרימת TikTok: מדיה → מסך יצירה → סאונד → כיתוב → (פרסום). */
object TikTokFlow {

    suspend fun Automation.runTikTok(media: File?, mime: String?, postText: String, soundQuery: String, autoPost: Boolean) {
        val tool = ToolCatalog.byId("tiktok")!!
        val pkg = ToolCatalog.installedPackage(svc, tool)
        if (pkg == null) {
            log("TikTok", StepStatus.FAIL, "TikTok לא מותקן")
            throw IllegalStateException("TikTok לא מותקן – התקן מהחנות הפנימית")
        }
        val m = model
        model = if (m.isNullOrBlank()) "TikTok" else "$m → TikTok"

        // 1) פתיחה ישירה למסך העריכה עם המדיה (שיתוף) – הדרך האמינה ביותר
        val openedWithMedia = media != null && mime != null && step("פתיחת TikTok עם המדיה (מסך יצירה)", optional = true) {
            launchIntent(MediaFiles.shareIntent(svc, media, mime, pkg), pkg)
        }
        if (!openedWithMedia) {
            step("פתיחת TikTok") { launchApp(pkg) }
            pauseMs(3000)
            tryConfirm(2)
            step("כפתור יצירה (+)") { clickLabels(Labels.TT_CREATE) }
            pauseMs(1500)
            tryConfirm(1)
            step("מעבר להעלאה מהגלריה") { clickLabels(Labels.TT_UPLOAD) }
            pauseMs(1500)
            step("בחירת הקובץ האחרון בגלריה", manualHint = "בחר את הקובץ של Kablon בגלריה (הראשון ברשימה), ואז לחץ 'סיימתי ידנית – המשך'.") {
                selectFirstGalleryItem()
            }
            step("הבא (אחרי בחירה)", optional = true) { clickLabels(Labels.TT_NEXT, 6000) }
        }
        pauseMs(3500)
        tryConfirm(2)

        // 2) סאונד
        if (soundQuery.isNotBlank()) {
            step("בחירת סאונד: \"$soundQuery\"",
                manualHint = "בחר ידנית סאונד מתאים (\"$soundQuery\"), חזור למסך העריכה ולחץ 'סיימתי ידנית – המשך'.") {
                pickSound(soundQuery)
            }
            pauseMs(1500)
        }

        // 3) הבא → מסך פרסום
        step("מעבר למסך הפרסום (הבא)") { clickLabels(Labels.TT_NEXT) }
        pauseMs(2500)
        tryConfirm(1)

        // 4) כיתוב + האשטגים
        if (postText.isNotBlank()) {
            step("כתיבת תיאור הפוסט") { typeText(postText, Labels.TT_DESCRIPTION) }
            pauseMs(800)
        }

        // 5) פרסום
        if (autoPost) {
            step("פרסום", manualHint = "לא מצאתי את כפתור 'פרסם'. לחץ עליו ידנית ואז 'סיימתי ידנית – המשך'.") {
                clickLabels(Labels.TT_POST) { n -> n.isEnabled }
            }
            pauseMs(3000)
            tryConfirm(2)
        } else {
            AutomationController.requestUser("✅ הכל מוכן ב-TikTok. בדוק את הפוסט ולחץ 'פרסם' בעצמך (או שמור טיוטה), ואז 'סיימתי ידנית – המשך' לסיום.")
            log("פרסום", StepStatus.MANUAL, "פרסום ידני (פרסום אוטומטי כבוי בהגדרות)")
        }
    }

    /** לוחץ על הפריט הראשון ברשת הגלריה של TikTok. */
    private suspend fun Automation.selectFirstGalleryItem(): Boolean {
        val dm = svc.resources.displayMetrics
        val candidates = allNodes().filter { n ->
            val r = android.graphics.Rect(); n.getBoundsInScreen(r)
            n.isVisibleToUser && (n.isClickable || n.isCheckable) &&
                r.width() in (dm.widthPixels / 6)..(dm.widthPixels / 2) &&
                kotlin.math.abs(r.width() - r.height()) < r.width() / 3 &&
                r.top > dm.heightPixels / 6
        }.sortedWith(compareBy({ val r = android.graphics.Rect(); it.getBoundsInScreen(r); r.top },
            { val r = android.graphics.Rect(); it.getBoundsInScreen(r); r.left }))
        val first = candidates.firstOrNull() ?: return false
        return click(first)
    }

    private suspend fun Automation.pickSound(query: String): Boolean {
        if (!clickLabels(Labels.TT_ADD_SOUND, 10_000)) return false
        pauseMs(2000)
        if (!clickLabels(Labels.TT_SEARCH, 8000)) return false
        pauseMs(800)
        if (!typeText(query)) return false
        if (!imeEnter()) clickLabels(Labels.TT_SEARCH, 4000)
        pauseMs(3500)
        // תוצאה ראשונה: רכיב שמכיל מילה מהשאילתה ואינו שדה הקלט
        val word = query.split(' ').filter { it.length > 1 }.maxByOrNull { it.length } ?: query
        val result = waitFor(listOf(word), 8000) { !it.isEditable } ?: return false
        if (!click(result)) return false
        pauseMs(1500)
        // לחיצה על "השתמש/✓" אם מופיע
        clickLabels(Labels.TT_USE_SOUND, 6000)
        pauseMs(1500)
        // אם עדיין בחלונית הסאונד – חזרה לעריכה
        if (find(Labels.TT_SEARCH) { it.isEditable } != null) back()
        return true
    }
}

/** זרימה מלאה: הצעת סאונד/כיתוב מ-AI (API או אפליקציה גלויה) ואז TikTok. */
object FullFlow {
    suspend fun Automation.runFull(
        media: File?, mime: String?, topic: String, postText: String, soundQuery: String,
        askAiApp: Boolean, autoPost: Boolean, onSuggestion: (String, String) -> Unit,
    ) {
        var sound = soundQuery
        var caption = postText
        if (sound.isBlank() || caption.isBlank()) {
            val api = AiApiClient(prefs)
            var answer: String? = null
            if (!askAiApp && api.hasKey) {
                model = api.textModelName()
                step("הצעת סאונד וכיתוב מ-${api.textModelName()}", optional = true) {
                    answer = api.chat(AiApiClient.suggestionPrompt(topic)).getOrThrow(); true
                }
            } else if (askAiApp) {
                val tool = ToolCatalog.byId(prefs.preferredAiTool) ?: ToolCatalog.aiChatTools.first()
                with(AiAppFlow) { answer = askAiApp(tool, AiApiClient.suggestionPrompt(topic)) }
            }
            answer?.let { AiApiClient.parseSuggestion(it) }?.let { (s, c) ->
                if (sound.isBlank()) sound = s
                if (caption.isBlank()) caption = c
                log("הצעת המודל", StepStatus.INFO, "סאונד: $s | כיתוב: $c")
                onSuggestion(s, c)
            }
        }
        with(TikTokFlow) { runTikTok(media, mime, caption, sound, autoPost) }
    }
}
