package com.autoagent.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Runs a server task at its time on the phone, without network (the result was prepared beforehand). */
class RemoteTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(RemoteTaskExecutor.EXTRA_TASK_ID) ?: return Result.success()
        RemoteTaskExecutor.runNotification(applicationContext, id)
        return Result.success()
    }
}
