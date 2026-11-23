package com.kablon.automation.automation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.kablon.automation.R
import com.kablon.automation.automation.AutomationController.State

/**
 * חלונית צפה מעל כל האפליקציות: סטטוס בזמן אמת + השהה / המשך / עצור.
 * ניתנת לגרירה ולמזעור לנקודה קטנה.
 */
class OverlayPanel(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var attached = false
    private var minimized = false

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
    ).toInt()

    private val bg = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(context.getColor(R.color.overlay_bg))
        setStroke(dp(1), 0x55FFFFFF)
    }

    private val title = TextView(context).apply {
        text = "⠿ Kablon"
        setTextColor(Color.WHITE)
        textSize = 13f
        setPadding(dp(4), 0, dp(4), dp(4))
    }
    private val statusText = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        maxLines = 5
        maxWidth = dp(260)
    }
    private fun btn(label: String, color: Int, onClick: () -> Unit) = Button(context).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(color) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(6) }
    }

    private val pauseBtn = btn("⏸ השהה", 0xFF455A64.toInt()) {
        when (AutomationController.state.value) {
            State.RUNNING -> AutomationController.pause()
            State.PAUSED, State.NEEDS_USER -> AutomationController.resume()
            else -> {}
        }
    }
    private val stopBtn = btn("⏹ עצור", 0xFFC62828.toInt()) { AutomationController.stop() }
    private val minBtn = btn("—", 0xFF37474F.toInt()) { setMinimized(true) }

    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(title)
        addView(statusText)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
            addView(pauseBtn); addView(stopBtn); addView(minBtn)
        })
    }

    private val bubble = TextView(context).apply {
        text = "K"
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(context.getColor(R.color.accent))
        }
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        visibility = View.GONE
        setOnClickListener { setMinimized(false) }
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = bg
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(body)
        addView(bubble)
        visibility = View.GONE
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(12)
        y = dp(120)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(v: View) {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; moved = false; false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    if (moved && attached) {
                        params.x = sx + dx; params.y = sy + dy
                        runCatching { wm.updateViewLayout(root, params) }
                    }
                    moved
                }
                else -> moved
            }
        }
    }

    fun attach() {
        if (attached) return
        enableDrag(title); enableDrag(bubble)
        runCatching { wm.addView(root, params); attached = true }
    }

    fun detach() {
        if (!attached) return
        runCatching { wm.removeView(root) }
        attached = false
    }

    private fun setMinimized(m: Boolean) {
        minimized = m
        body.visibility = if (m) View.GONE else View.VISIBLE
        bubble.visibility = if (m) View.VISIBLE else View.GONE
        root.background = if (m) null else bg
        root.setPadding(if (m) 0 else dp(12), if (m) 0 else dp(10), if (m) 0 else dp(12), if (m) 0 else dp(10))
    }

    fun update(state: State, message: String) {
        root.visibility = if (state == State.IDLE) View.GONE else View.VISIBLE
        statusText.text = message
        val alert = state == State.NEEDS_USER || state == State.FAILED
        bg.setColor(context.getColor(if (alert) R.color.overlay_alert else R.color.overlay_bg))
        if (alert && minimized) setMinimized(false)
        title.text = when (state) {
            State.RUNNING -> "⠿ Kablon • פועל"
            State.PAUSED -> "⠿ Kablon • מושהה"
            State.NEEDS_USER -> "⠿ Kablon • ✋ נדרשת התערבות"
            State.FINISHED -> "⠿ Kablon • ✅ הסתיים"
            State.FAILED -> "⠿ Kablon • ❌ נכשל"
            State.STOPPED -> "⠿ Kablon • ⏹ נעצר"
            State.IDLE -> "⠿ Kablon"
        }
        pauseBtn.text = when (state) {
            State.NEEDS_USER -> "✔ סיימתי ידנית – המשך"
            State.PAUSED -> "▶ המשך"
            else -> "⏸ השהה"
        }
        val active = state == State.RUNNING || state == State.PAUSED || state == State.NEEDS_USER
        pauseBtn.isEnabled = active
        stopBtn.isEnabled = active
        pauseBtn.alpha = if (active) 1f else 0.4f
        stopBtn.alpha = if (active) 1f else 0.4f
    }

    /** עיגול מהבהב במיקום הלחיצה (לא חוסם מגע). */
    fun flashTap(x: Int, y: Int) {
        if (!attached) return
        val size = dp(56)
        val dot = View(context).apply { setBackgroundResource(R.drawable.tap_indicator) }
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x - size / 2
            this.y = y - size / 2
        }
        runCatching {
            wm.addView(dot, p)
            main.postDelayed({ runCatching { wm.removeView(dot) } }, 450)
        }
    }
}
