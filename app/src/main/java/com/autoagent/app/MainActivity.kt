package com.autoagent.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.autoagent.app.service.AgentStatus
import com.autoagent.app.ui.AppRoot
import com.autoagent.app.ui.theme.AutoAgentTheme
import kotlinx.coroutines.launch

data class LaunchRequest(
    val route: String? = null,
    val dial: String? = null,
    val receiptFile: String? = null,
    val receiptAudio: Boolean = false,
    val nonce: Long = System.nanoTime(),
)

class MainActivity : ComponentActivity() {
    private var request by mutableStateOf(LaunchRequest())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        request = parse(intent)
        setContent {
            AutoAgentTheme {
                AppRoot(request)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request = parse(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { AgentStatus.refresh(this@MainActivity) }
    }

    private fun parse(i: Intent?): LaunchRequest {
        if (i == null) return LaunchRequest()
        val dial = if (i.action == Intent.ACTION_DIAL || i.action == Intent.ACTION_VIEW) i.data?.schemeSpecificPart else null
        return LaunchRequest(
            route = if (dial != null) "home" else i.getStringExtra(EXTRA_ROUTE),
            dial = dial,
            receiptFile = i.getStringExtra(EXTRA_RECEIPT_FILE),
            receiptAudio = i.getBooleanExtra(EXTRA_RECEIPT_AUDIO, false),
        )
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_RECEIPT_FILE = "receipt_file"
        const val EXTRA_RECEIPT_AUDIO = "receipt_audio"
    }
}
