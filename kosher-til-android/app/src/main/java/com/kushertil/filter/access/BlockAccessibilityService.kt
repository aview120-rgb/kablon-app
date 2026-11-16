package com.kushertil.filter.access

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kushertil.filter.data.Blocklist
import com.kushertil.filter.data.Prefs
import com.kushertil.filter.lock.BlockActivity

/**
 * Second filtering layer: blocks forbidden apps and forbidden URLs inside
 * browsers (DNS filtering alone misses in-app webviews and can be bypassed by
 * manually set DNS), and provides a light tamper guard that bounces the user
 * out of the system screens used to uninstall the app or disable its admin.
 */
class BlockAccessibilityService : AccessibilityService() {

    // Browser address-bar view ids, by package.
    private val browserUrlBars = mapOf(
        "com.android.chrome" to "com.android.chrome:id/url_bar",
        "com.chrome.beta" to "com.chrome.beta:id/url_bar",
        "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "com.brave.browser" to "com.brave.browser:id/url_bar",
        "com.opera.browser" to "com.opera.browser:id/url_field",
        "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar"
    )

    private var lastBlockMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!Prefs.filteringActiveNow(this)) return

        // 1) Blocked application.
        if (Blocklist.blockedApps().contains(pkg)) {
            block("app:$pkg")
            return
        }

        // 2) Tamper guard around system settings.
        if (pkg == "com.android.settings") {
            if (isTamperScreen(event)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        }

        // 3) Browser URL check.
        val urlBarId = browserUrlBars[pkg] ?: return
        val root = rootInActiveWindow ?: return
        try {
            val host = readHost(root, urlBarId)
            if (Blocklist.isDomainBlocked(host)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                block("url:$host")
            }
        } finally {
            root.recycle()
        }
    }

    private fun readHost(root: AccessibilityNodeInfo, viewId: String): String? {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId) ?: return null
        for (n in nodes) {
            val text = n.text?.toString()
            n.recycle()
            val host = Blocklist.hostFromUrlText(text)
            if (host != null) return host
        }
        return null
    }

    /** Heuristic: are we on a screen that would remove the app or its admin? */
    private fun isTamperScreen(event: AccessibilityEvent): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val hay = StringBuilder()
            collectText(root, hay, 0)
            val t = hay.toString().lowercase()
            val mentionsApp = t.contains("כושר-טיל") || t.contains("kosher") || t.contains(packageName)
            val dangerous = t.contains("uninstall") || t.contains("הסר") ||
                t.contains("deactivate") || t.contains("בטל") ||
                t.contains("device admin") || t.contains("מנהל התקן") ||
                t.contains("force stop") || t.contains("אלץ עצירה")
            mentionsApp && dangerous
        } finally {
            root.recycle()
        }
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        node ?: return
        if (depth > 40) return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i)
            collectText(c, sb, depth + 1)
            c?.recycle()
        }
    }

    private fun block(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockMs < 600) return // debounce
        lastBlockMs = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        val i = Intent(this, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(BlockActivity.EXTRA_REASON, reason)
        startActivity(i)
    }

    override fun onInterrupt() {}
}
