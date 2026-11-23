package com.kablon.automation.automation

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.kablon.automation.core.Prefs
import com.kablon.automation.core.StepStatus
import com.kablon.automation.core.WorkflowLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * פעולות גלויות על המסך עבור זרימה אחת. כל פעולה עוברת דרך checkpoint()
 * כך שהשהה/עצור מהחלונית הצפה פועלים מיידית.
 */
class Automation(val svc: KablonAccessibilityService, val workflow: String) {
    val prefs = Prefs(svc)
    /** המודל/האפליקציה שמשתתפים כרגע (נרשם ביומן). */
    var model: String? = null

    private val timeoutMs get() = prefs.stepTimeoutSec * 1000L

    // ------------------------------------------------------------ שלבים ויומן

    fun status(text: String) = AutomationController.setStatus(text)

    fun log(step: String, st: StepStatus, detail: String = "") =
        WorkflowLog.add(workflow, step, st, detail, model)

    /**
     * מריץ שלב. אם הבלוק מחזיר false: בשלב רשות – מדלג; אחרת – מבקש מהמשתמש
     * להשלים ידנית ומחכה ל"המשך". מחזיר true אם השלב הושלם אוטומטית.
     */
    suspend fun step(name: String, optional: Boolean = false, manualHint: String? = null, block: suspend () -> Boolean): Boolean {
        AutomationController.checkpoint()
        status("▶ $name")
        val ok = try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            log(name, StepStatus.FAIL, e.message ?: e.javaClass.simpleName)
            false
        }
        if (ok) {
            log(name, StepStatus.OK)
            return true
        }
        if (optional) {
            log(name, StepStatus.SKIP, "לא נמצא – דילוג")
            return false
        }
        val msg = manualHint ?: "השלב \"$name\" לא הצליח אוטומטית. השלם אותו ידנית ואז לחץ \"סיימתי ידנית – המשך\"."
        AutomationController.requestUser(msg)
        log(name, StepStatus.MANUAL, "הושלם ידנית על ידי המשתמש")
        return false
    }

    suspend fun pauseMs(ms: Long) {
        var left = ms
        while (left > 0) {
            AutomationController.checkpoint()
            val d = minOf(250L, left)
            delay(d)
            left -= d
        }
    }

    // ------------------------------------------------------------ אפליקציות

    fun isInstalled(pkg: String) = svc.packageManager.getLaunchIntentForPackage(pkg) != null

    suspend fun launchApp(pkg: String): Boolean {
        AutomationController.checkpoint()
        val i = svc.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        svc.resetForeground()
        svc.startActivity(i)
        return waitForApp(pkg)
    }

    suspend fun launchIntent(intent: Intent, expectPkg: String?): Boolean {
        AutomationController.checkpoint()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        svc.resetForeground()
        svc.startActivity(intent)
        return expectPkg == null || waitForApp(expectPkg)
    }

    fun currentPackage(): String? = svc.rootInActiveWindow?.packageName?.toString()

    suspend fun waitForApp(pkg: String, timeout: Long = timeoutMs): Boolean {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            AutomationController.checkpoint()
            if (currentPackage() == pkg || svc.lastForegroundPackage == pkg) return true
            delay(400)
        }
        return false
    }

    fun back() = svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

    // ------------------------------------------------------------ חיפוש רכיבים

    /** שורשי כל החלונות הגלויים, ללא החלונית של Kablon עצמה. */
    fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            svc.windows
                .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                .sortedByDescending { it.isActive }
                .forEach { w -> w.root?.let { r -> if (r.packageName?.toString() != svc.packageName) out.add(r) } }
        } catch (_: Exception) {
        }
        if (out.isEmpty()) svc.rootInActiveWindow?.let { if (it.packageName?.toString() != svc.packageName) out.add(it) }
        return out
    }

    fun allNodes(limit: Int = 6000): List<AccessibilityNodeInfo> {
        val res = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots().forEach { queue.add(it) }
        while (queue.isNotEmpty() && res.size < limit) {
            val n = queue.removeFirst()
            res.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return res
    }

    private fun labelsOf(n: AccessibilityNodeInfo): List<String> {
        val l = ArrayList<String>(4)
        n.text?.toString()?.let { l.add(it) }
        n.contentDescription?.toString()?.let { l.add(it) }
        n.hintText?.toString()?.let { l.add(it) }
        return l.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun score(n: AccessibilityNodeInfo, labels: List<String>): Int {
        if (!n.isVisibleToUser) return 0
        var best = 0
        val own = labelsOf(n)
        val id = n.viewIdResourceName?.substringAfter(":id/")?.lowercase()
        for (want in labels) {
            val w = want.lowercase()
            for (s in own) {
                val sl = s.lowercase()
                best = maxOf(best, when {
                    sl == w -> 100
                    sl.length <= w.length + 12 && sl.contains(w) -> 60
                    sl.contains(w) -> 20
                    else -> 0
                })
            }
            if (id != null && id.contains(w.replace(' ', '_'))) best = maxOf(best, 40)
        }
        return best
    }

    /** מחזיר את הרכיב המתאים ביותר לאחת התוויות (טקסט / תיאור / רמז / מזהה). */
    fun find(labels: List<String>, filter: (AccessibilityNodeInfo) -> Boolean = { true }): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        for (n in allNodes()) {
            val s = score(n, labels)
            if (s > bestScore && filter(n)) { best = n; bestScore = s }
        }
        return best
    }

    suspend fun waitFor(labels: List<String>, timeout: Long = timeoutMs, filter: (AccessibilityNodeInfo) -> Boolean = { true }): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeout
        while (true) {
            AutomationController.checkpoint()
            find(labels, filter)?.let { return it }
            if (System.currentTimeMillis() >= end) return null
            delay(450)
        }
    }

    fun screenTexts(): List<String> = allNodes().flatMap { labelsOf(it) }

    // ------------------------------------------------------------ פעולות

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    /** לחיצה גלויה: מסמן את המקום, מנסה ACTION_CLICK ואם לא – מחווה אמיתית על המסך. */
    suspend fun click(n: AccessibilityNodeInfo): Boolean {
        AutomationController.checkpoint()
        val r = Rect()
        n.getBoundsInScreen(r)
        svc.showTap(r.exactCenterX(), r.exactCenterY())
        delay(180)
        val target = clickableAncestor(n)
        val ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (ok) { delay(350); return true }
        if (r.width() <= 0 || r.height() <= 0) return false
        val g = svc.tap(r.exactCenterX(), r.exactCenterY())
        delay(350)
        return g
    }

    /** מחפש ולוחץ, עם מגבלת ניסיונות מההגדרות. */
    suspend fun clickLabels(labels: List<String>, timeout: Long = timeoutMs, filter: (AccessibilityNodeInfo) -> Boolean = { true }): Boolean {
        val tries = prefs.maxRetries
        repeat(tries) { attempt ->
            status("מחפש: ${labels.first()} (ניסיון ${attempt + 1}/$tries)")
            val n = waitFor(labels, timeout / tries + 500, filter)
            if (n != null && click(n)) return true
        }
        return false
    }

    suspend fun tapAt(x: Float, y: Float): Boolean {
        AutomationController.checkpoint()
        svc.showTap(x, y)
        delay(150)
        return svc.tap(x, y)
    }

    private fun findEditable(labels: List<String>?): AccessibilityNodeInfo? {
        if (!labels.isNullOrEmpty()) {
            find(labels) { it.isEditable }?.let { return it }
        }
        roots().forEach { r -> r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it } }
        return allNodes().firstOrNull { it.isEditable && it.isVisibleToUser }
    }

    /**
     * הקלדה גלויה: הטקסט מופיע בשדה בהדרגה (כמו הקלדה), כדי שהמשתמש יראה אותו.
     */
    suspend fun typeText(text: String, fieldLabels: List<String>? = null, timeout: Long = timeoutMs): Boolean {
        val end = System.currentTimeMillis() + timeout
        var field: AccessibilityNodeInfo? = null
        while (field == null && System.currentTimeMillis() < end) {
            AutomationController.checkpoint()
            field = findEditable(fieldLabels)
            if (field == null) delay(450)
        }
        val f = field ?: return false
        click(f)
        f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val delayMs = prefs.typingDelayMs.toLong()
        val steps = if (delayMs == 0L) 1 else minOf(text.length, 60).coerceAtLeast(1)
        for (i in 1..steps) {
            AutomationController.checkpoint()
            val upTo = if (i == steps) text.length else (text.length * i) / steps
            if (!setText(f, text.substring(0, upTo))) return false
            if (delayMs > 0) delay(delayMs)
        }
        delay(200)
        f.refresh()
        val now = f.text?.toString().orEmpty()
        return now.isNotEmpty() || text.isEmpty()
    }

    private fun setText(n: AccessibilityNodeInfo, t: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** "אנטר" בשדה (חיפוש/שליחה) – API 30+. */
    fun imeEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val f = roots().firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } ?: return false
        return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** גלילה גלויה בין מסכים/רשימות. */
    suspend fun scroll(forward: Boolean = true): Boolean {
        AutomationController.checkpoint()
        val scrollable = allNodes().filter { it.isScrollable && it.isVisibleToUser }
            .maxByOrNull { val r = Rect(); it.getBoundsInScreen(r); r.width() * r.height() }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (scrollable?.performAction(action) == true) { delay(600); return true }
        val dm = svc.resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (y1, y2) = if (forward) dm.heightPixels * 0.75f to dm.heightPixels * 0.3f else dm.heightPixels * 0.3f to dm.heightPixels * 0.75f
        val ok = svc.swipe(x, y1, x, y2)
        delay(600)
        return ok
    }

    /**
     * ניסיון ללחוץ על כפתורי אישור/המשך פשוטים (חלונות קופצים, הרשאות).
     * מוגבל במספר ניסיונות; לא לוחץ על כפתורים מסוכנים. מחזיר כמה נלחצו.
     */
    suspend fun tryConfirm(maxAttempts: Int = prefs.maxRetries, extra: List<String> = emptyList()): Int {
        var clicked = 0
        val labels = extra + Labels.CONFIRM
        repeat(maxAttempts) {
            AutomationController.checkpoint()
            val n = find(labels) { node ->
                val t = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
                t.isNotEmpty() && t.length <= 30 &&
                    Labels.NEVER_AUTO.none { bad -> t.contains(bad, ignoreCase = true) } &&
                    score(node, labels) >= 60
            } ?: return clicked
            val label = (n.text ?: n.contentDescription)?.toString()
            if (!click(n)) return clicked
            clicked++
            log("אישור אוטומטי", StepStatus.INFO, "נלחץ: $label")
            delay(700)
        }
        return clicked
    }
}
