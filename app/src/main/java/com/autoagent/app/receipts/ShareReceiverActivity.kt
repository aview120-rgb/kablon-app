package com.autoagent.app.receipts

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.autoagent.app.MainActivity
import com.autoagent.app.util.Notifier
import kotlinx.coroutines.launch

/** Entry point for "Share → AutoAgent" with an image or audio file. Copies it, then processes on-device. */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        val mime = intent.type
        if (intent.action != Intent.ACTION_SEND || uri == null) { finish(); return }
        lifecycleScope.launch {
            val msg = try {
                val file = ReceiptIngest.copyToStorage(this@ShareReceiverActivity, uri, mime)
                startActivity(
                    Intent(this@ShareReceiverActivity, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_ROUTE, "receipts")
                        .putExtra(MainActivity.EXTRA_RECEIPT_FILE, file.absolutePath)
                        .putExtra(MainActivity.EXTRA_RECEIPT_AUDIO, mime?.startsWith("audio") == true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                null
            } catch (e: Exception) {
                "Could not import: ${e.message}"
            }
            msg?.let { Notifier.event(this@ShareReceiverActivity, "Receipt import failed", it, "receipts") }
            finish()
        }
    }
}
