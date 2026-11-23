package com.kablon.automation.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kablon.automation.BuildConfig
import com.kablon.automation.R
import com.kablon.automation.automation.AiAppFlow.quickTest
import com.kablon.automation.automation.AutomationController
import com.kablon.automation.automation.KablonAccessibilityService
import com.kablon.automation.core.Notifier
import com.kablon.automation.core.StorageBudget
import com.kablon.automation.store.ToolCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val note = findViewById<TextView>(R.id.storageNote)
        findViewById<View>(R.id.storageNoteToggle).setOnClickListener {
            note.visibility = if (note.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btnCleanup).setOnClickListener {
            lifecycleScope.launch {
                val freed = withContext(Dispatchers.IO) { StorageBudget.cleanup(this@MainActivity) }
                toast("שוחררו ${StorageBudget.format(freed)}")
                refreshStorage()
            }
        }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("בחר \"${getString(R.string.accessibility_label)}\" והפעל")
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener { ToolCatalog.openAppInfo(this, packageName) }
        findViewById<Button>(R.id.btnNotifications).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }

        findViewById<Button>(R.id.btnPause).setOnClickListener {
            when (AutomationController.state.value) {
                AutomationController.State.RUNNING -> AutomationController.pause()
                AutomationController.State.PAUSED, AutomationController.State.NEEDS_USER -> AutomationController.resume()
                else -> {}
            }
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener { AutomationController.stop() }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            AutomationController.start(this, "בדיקה מהירה") { quickTest() }?.let { toast(it) }
        }

        findViewById<Button>(R.id.navStore).setOnClickListener { startActivity(Intent(this, StoreActivity::class.java)) }
        findViewById<Button>(R.id.navMedia).setOnClickListener { startActivity(Intent(this, MediaActivity::class.java)) }
        findViewById<Button>(R.id.navTikTok).setOnClickListener { startActivity(Intent(this, TikTokActivity::class.java)) }
        findViewById<Button>(R.id.navLog).setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        findViewById<Button>(R.id.navSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<TextView>(R.id.versionText).text = "גרסה ${BuildConfig.VERSION_NAME}"

        val stateText = findViewById<TextView>(R.id.autoState)
        val pauseBtn = findViewById<Button>(R.id.btnPause)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(AutomationController.state, AutomationController.status, KablonAccessibilityService.connected) { a, b, c -> Triple(a, b, c) }
                    .collect { (st, msg, conn) ->
                        stateText.text = "מצב: ${st.name}\n$msg" + if (!conn) "\n⚠️ שירות הנגישות לא מחובר" else ""
                        pauseBtn.text = if (st == AutomationController.State.RUNNING) getString(R.string.btn_pause) else getString(R.string.btn_resume)
                        refreshPermissions()
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshStorage()
    }

    private fun refreshPermissions() {
        val acc = KablonAccessibilityService.isEnabledInSettings(this)
        val live = KablonAccessibilityService.instance != null
        findViewById<TextView>(R.id.permAccessibility).text = getString(R.string.perm_accessibility) + ": " + when {
            acc && live -> "✅ פעיל"
            acc -> "⏳ מופעל, ממתין לחיבור"
            else -> "❌ כבוי"
        }
        findViewById<Button>(R.id.btnAccessibility).visibility = if (acc) View.GONE else View.VISIBLE
        val n = Notifier.canNotify(this)
        findViewById<TextView>(R.id.permNotifications).text = getString(R.string.perm_notifications) + ": " + if (n) "✅" else "❌"
        findViewById<Button>(R.id.btnNotifications).visibility = if (n) View.GONE else View.VISIBLE
    }

    private fun refreshStorage() {
        lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { StorageBudget.snapshot(this@MainActivity) }
            val f = StorageBudget::format
            findViewById<TextView>(R.id.storageDevice).text =
                "טלפון: ${f(s.deviceUsed)} בשימוש מתוך ${f(s.deviceTotal)} (פנוי ${f(s.deviceFree)})"
            findViewById<ProgressBar>(R.id.storageDeviceBar).progress = s.devicePct
            findViewById<TextView>(R.id.storageApp).text =
                "Kablon: ${f(s.appUsed)} מתוך יעד 80GB (${s.budgetPct}%) • זמין לעבודה כעת: ${f(s.usableNow)}\n" +
                    "מרווח שמור לטלפון ולמערכת: לפחות ${f(StorageBudget.MIN_FREE_RESERVE)} פנויים תמיד"
            findViewById<ProgressBar>(R.id.storageAppBar).progress = s.budgetPct
            val warn = findViewById<TextView>(R.id.storageWarn)
            val msgs = mutableListOf<String>()
            if (s.budgetExceedsDevice) msgs += "⚠️ נפח המחיצה במכשיר קטן מ-80GB + מרווח – התקציב בפועל יוגבל לפי השטח הפנוי."
            if (s.deviceFree < StorageBudget.MIN_FREE_RESERVE * 2) msgs += "⚠️ מקום פנוי נמוך בטלפון – מומלץ לנקות."
            warn.text = msgs.joinToString("\n")
            warn.visibility = if (msgs.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
