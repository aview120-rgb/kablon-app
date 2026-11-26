package com.autoagent.app.access

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoagent.app.ui.theme.AutoAgentTheme
import com.autoagent.app.util.L
import kotlinx.coroutines.CompletableDeferred

/**
 * Turns the screen on over the lock screen and asks Android to dismiss the keyguard so a scheduled message can
 * be sent. With a swipe-only lock this succeeds by itself; with a PIN/fingerprint Android shows its own unlock
 * prompt and the send continues the moment you unlock. Nothing here bypasses the lock.
 */
class UnlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val who = intent.getStringExtra(EXTRA_WHO).orEmpty()
        setContent {
            AutoAgentTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(L.t("Unlock to send your scheduled message", "פתח את הנעילה כדי לשלוח את ההודעה המתוזמנת"), style = MaterialTheme.typography.headlineSmall)
                        if (who.isNotBlank()) Text(L.t("To: $who", "אל: $who"), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) { done(true); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = done(true)
            override fun onDismissCancelled() = done(false)
            override fun onDismissError() = done(false)
        })
    }

    private fun done(ok: Boolean) {
        pending?.complete(ok)
        pending = null
        finish()
    }

    override fun onDestroy() {
        pending?.complete(false)
        pending = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_WHO = "who"
        @Volatile private var pending: CompletableDeferred<Boolean>? = null

        /** Starts the unlock flow; the result completes true when the screen is unlocked. */
        fun request(context: Context, who: String): CompletableDeferred<Boolean> {
            pending?.complete(false)
            val d = CompletableDeferred<Boolean>()
            pending = d
            context.startActivity(
                Intent(context, UnlockActivity::class.java)
                    .putExtra(EXTRA_WHO, who)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            )
            return d
        }
    }
}
