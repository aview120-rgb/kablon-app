package com.kablon.automation.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * שירות הנגישות: מבצע את הפעולות הגלויות על המסך ומציג את החלונית הצפה
 * (TYPE_ACCESSIBILITY_OVERLAY – לא דורש הרשאת "הצגה מעל אפליקציות אחרות").
 */
class KablonAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: KablonAccessibilityService? = null
            private set
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, KablonAccessibilityService::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == me
            }
        }
    }

    private var scope: CoroutineScope? = null
    private var overlay: OverlayPanel? = null
    @Volatile var lastForegroundPackage: String? = null
        private set

    fun resetForeground() {
        lastForegroundPackage = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val panel = OverlayPanel(this)
        overlay = panel
        panel.attach()
        s.launch {
            combine(AutomationController.state, AutomationController.status) { st, msg -> st to msg }
                .collect { (st, msg) -> panel.update(st, msg) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = event.packageName?.toString()
            if (p != null && p != packageName) lastForegroundPackage = p
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    private fun teardown() {
        if (instance === this) instance = null
        _connected.value = false
        AutomationController.stop()
        overlay?.detach()
        overlay = null
        scope?.cancel()
        scope = null
    }

    /** מציג סימון עגול במקום שבו מתבצעת לחיצה – כדי שהמשתמש יראה מה קורה. */
    suspend fun showTap(x: Float, y: Float) = withContext(Dispatchers.Main) {
        overlay?.flashTap(x.toInt(), y.toInt())
    }

    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return gesture(path, 60)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 400): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return gesture(path, durationMs)
    }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }
    }
}
