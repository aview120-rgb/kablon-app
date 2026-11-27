package com.autoagent.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.autoagent.app.AutoAgentApp
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * When syncs happen:
 * - immediately when something changes and the phone is online (in-process),
 * - a WorkManager job with a "network connected" constraint: Android runs it the moment the phone is back
 *   online, even if the app was closed or the phone rebooted — this is what delivers everything collected offline,
 * - a periodic job every 15 minutes as a safety net.
 */
object SyncScheduler {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun syncNow(context: Context, reason: String) {
        val app = context.applicationContext as AutoAgentApp
        if (!app.settings.current.syncEnabled) return
        app.appScope.launch { SyncEngine.syncOnce(app, reason) }
        // Fallback that survives process death and waits for the network.
        WorkManager.getInstance(app).enqueueUniqueWork(
            "sync-pending", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(online)
                .setInitialDelay(5, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun ensurePeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        val app = context.applicationContext as AutoAgentApp
        if (!app.settings.current.syncEnabled) {
            wm.cancelUniqueWork("sync-periodic")
            return
        }
        wm.enqueueUniquePeriodicWork(
            "sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ok = SyncEngine.syncOnce(applicationContext, "worker")
        return when {
            ok -> Result.success()
            // Network or server trouble: WorkManager retries with exponential backoff.
            SyncEngine.lastErrorRetryable() && runAttemptCount < 8 -> Result.retry()
            else -> Result.success()
        }
    }
}
