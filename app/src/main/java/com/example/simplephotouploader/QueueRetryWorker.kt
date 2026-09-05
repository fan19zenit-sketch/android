package com.example.simplephotouploader

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

// Delayed kicks never become prerequisites for freshly captured photos.
class QueueRetryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val operation = if (inputData.getBoolean("sync", false)) {
            UploadStatusSyncWorker.enqueue(applicationContext)
        } else {
            PhotoUploadWorker.enqueue(applicationContext)
        }
        operation.result.get()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context, sync: Boolean): Operation =
            WorkManager.getInstance(context).enqueueUniqueWork(
                if (sync) "photo-status-retry-kick" else "photo-upload-retry-kick",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<QueueRetryWorker>()
                    .setInputData(workDataOf("sync" to sync))
                    .setInitialDelay(30, TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
    }
}
