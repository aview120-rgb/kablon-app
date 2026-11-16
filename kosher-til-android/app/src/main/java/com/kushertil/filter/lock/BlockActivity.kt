package com.kushertil.filter.lock

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kushertil.filter.R
import com.kushertil.filter.data.PolicyClient
import com.kushertil.filter.data.Prefs

/**
 * Full-screen "content blocked" screen. There is NO local unlock here: in the
 * managed model the user can only request a release, which the manager approves
 * from the server. The device never releases itself.
 */
class BlockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_block)

        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
        findViewById<TextView>(R.id.blockReason).text =
            getString(R.string.blocked_body) + if (reason.isNotBlank()) "\n($reason)" else ""

        findViewById<Button>(R.id.requestRelease).setOnClickListener {
            val contact = Prefs.managerContact(this)
            Thread { PolicyClient.requestUnlock(this, reason) }.start()
            Toast.makeText(
                this,
                if (contact.isNotBlank()) "בקשה נשלחה. פנה למנהל: $contact" else "בקשת שחרור נשלחה למנהל",
                Toast.LENGTH_LONG
            ).show()
        }

        findViewById<Button>(R.id.goHome).setOnClickListener { goHome() }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    @Deprecated("blocked")
    override fun onBackPressed() {
        goHome()
    }

    companion object {
        const val EXTRA_REASON = "reason"
    }
}
