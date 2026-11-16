package com.kushertil.filter.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.kushertil.filter.BuildConfig
import com.kushertil.filter.R
import com.kushertil.filter.access.BlockAccessibilityService
import com.kushertil.filter.admin.KosherAdminReceiver
import com.kushertil.filter.data.FilterController
import com.kushertil.filter.data.PolicyClient
import com.kushertil.filter.data.Prefs
import com.kushertil.filter.lock.MaintenanceActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var permContainer: LinearLayout
    private var titleTaps = 0
    private var lastTapMs = 0L

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                Prefs.setEnabled(this, true)
                FilterController.reconcile(this)
            }
            refresh()
        }

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        permContainer = findViewById(R.id.permContainer)

        findViewById<Button>(R.id.enableButton).setOnClickListener { enableFlow() }
        findViewById<Button>(R.id.requestReleaseButton).setOnClickListener {
            Thread { PolicyClient.requestUnlock(this, "user request from app") }.start()
            val c = Prefs.managerContact(this)
            Toast.makeText(this,
                if (c.isNotBlank()) "בקשה נשלחה. פנה למנהל: $c" else "בקשה נשלחה למנהל",
                Toast.LENGTH_LONG).show()
        }

        // Hidden maintenance entry: tap the title 7 times quickly.
        findViewById<TextView>(R.id.appTitle).setOnClickListener { onTitleTap() }

        findViewById<TextView>(R.id.versionLabel).text =
            "v${BuildConfig.VERSION_NAME} · " +
                (if (Prefs.serverUrl(this).isBlank()) "לא מחובר לשרת" else "מחובר לשרת")

        FilterController.schedulePeriodicSync(this)
        FilterController.syncAndReconcile(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun onTitleTap() {
        val now = System.currentTimeMillis()
        titleTaps = if (now - lastTapMs < 800) titleTaps + 1 else 1
        lastTapMs = now
        if (titleTaps >= 7) {
            titleTaps = 0
            startActivity(Intent(this, MaintenanceActivity::class.java))
        }
    }

    private fun enableFlow() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            vpnConsent.launch(prepare)       // ask for VPN consent; enable on result
        } else {
            Prefs.setEnabled(this, true)
            FilterController.reconcile(this)
            refresh()
        }
    }

    private fun refresh() {
        val active = Prefs.filteringActiveNow(this)
        val allGranted = hasVpn() && hasAccessibility() && hasOverlay()
        statusText.setText(
            when {
                active && allGranted -> R.string.status_active
                active -> R.string.status_partial
                else -> R.string.status_inactive
            }
        )
        buildPermissionRows()
    }

    private fun buildPermissionRows() {
        permContainer.removeAllViews()
        addRow(getString(R.string.perm_vpn), hasVpn()) { enableFlow() }
        addRow(getString(R.string.perm_accessibility), hasAccessibility()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addRow(getString(R.string.perm_admin), hasAdmin()) { requestAdmin() }
        addRow(getString(R.string.perm_overlay), hasOverlay()) { requestOverlay() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addRow(getString(R.string.perm_notifications), hasNotifications()) {
                notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun addRow(title: String, granted: Boolean, onGrant: () -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.row_permission, permContainer, false)
        row.findViewById<TextView>(R.id.permTitle).text = title
        row.findViewById<TextView>(R.id.permState)
            .setText(if (granted) R.string.perm_granted else R.string.perm_missing)
        val btn = row.findViewById<Button>(R.id.permButton)
        btn.visibility = if (granted) View.GONE else View.VISIBLE
        btn.setOnClickListener { onGrant() }
        permContainer.addView(row)
    }

    // --- permission checks ---------------------------------------------------

    private fun hasVpn() = VpnService.prepare(this) == null

    private fun hasAdmin(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(KosherAdminReceiver.component(this))
    }

    private fun hasOverlay() = Settings.canDrawOverlays(this)

    private fun hasNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasAccessibility(): Boolean {
        val expected = "$packageName/${BlockAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, KosherAdminReceiver.component(this))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_explanation))
        startActivity(intent)
    }

    private fun requestOverlay() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }
}
