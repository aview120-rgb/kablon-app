package com.kushertil.filter.lock

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kushertil.filter.R

/**
 * Hidden development/maintenance screen. Reached only from MainActivity via a
 * deliberate gesture (tap the version label several times). Entering the
 * maintenance code stands the filter down and releases device admin so the app
 * can be uninstalled during development.
 */
class MaintenanceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unlock)

        findViewById<TextView>(R.id.blockTitle).setText(R.string.unlock_title)
        findViewById<TextView>(R.id.blockBody).text = "מצב תחזוקה (פיתוח)"
        val input = findViewById<EditText>(R.id.codeInput)

        if (!MaintenanceManager.isAvailable(this)) {
            Toast.makeText(this, "מצב תחזוקה אינו זמין בגרסה זו", Toast.LENGTH_LONG).show()
            finish(); return
        }

        findViewById<Button>(R.id.unlockOk).setOnClickListener {
            when (val r = MaintenanceManager.tryCode(this, input.text.toString())) {
                is MaintenanceManager.Outcome.Released -> {
                    Toast.makeText(this, "הסינון כובה. ניתן להסיר את האפליקציה.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
                is MaintenanceManager.Outcome.Wrong ->
                    Toast.makeText(this, getString(R.string.unlock_wrong) + " (${r.attemptsLeft})", Toast.LENGTH_SHORT).show()
                is MaintenanceManager.Outcome.LockedOut ->
                    Toast.makeText(this, getString(R.string.unlock_locked_out, r.seconds), Toast.LENGTH_LONG).show()
                MaintenanceManager.Outcome.Unavailable -> finish()
            }
            input.text?.clear()
        }

        findViewById<Button>(R.id.goHome).setOnClickListener { finish() }
    }
}
