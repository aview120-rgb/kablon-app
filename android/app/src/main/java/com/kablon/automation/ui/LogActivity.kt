package com.kablon.automation.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kablon.automation.R
import com.kablon.automation.core.WorkflowLog
import kotlinx.coroutines.launch

/** יומן שלבים: מה עבד, איפה נעצר, ואיזה מודל השתתף. */
class LogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        val text = findViewById<TextView>(R.id.logText)

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            AlertDialog.Builder(this).setMessage("למחוק את כל היומן?")
                .setPositiveButton("מחק") { _, _ -> WorkflowLog.clear() }
                .setNegativeButton("ביטול", null).show()
        }
        findViewById<Button>(R.id.btnShareLog).setOnClickListener {
            val all = WorkflowLog.readAll().joinToString("\n") { it.format() }
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, all), "שתף יומן"
            ))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WorkflowLog.version.collect {
                    val entries = WorkflowLog.readAll().reversed()
                    text.text = if (entries.isEmpty()) "היומן ריק. הפעל זרימה כדי לראות כאן כל שלב." else
                        entries.joinToString("\n\n") { it.format() }
                }
            }
        }
    }
}
