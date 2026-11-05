package com.example.simplephotouploader

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PhotoContextSynchronizer {
    fun sync(dao: PhotoRecordDao, send: (PhotoRecordEntity) -> String) {
        for (item in dao.getPendingPhotoContexts()) {
            val snapshot = item.photoContext ?: continue
            val resolution = send(item)
            if (resolution !in setOf("resolved", "unresolved", "not_applicable")) {
                throw IOException("Invalid photo context acknowledgement")
            }
            dao.acknowledgePhotoContext(item.id, snapshot, resolution)
        }
    }
}

class PhotoContextSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val WORK_NAME = "photo-context-sync"
        fun enqueue(context: Context) {
            val work = OneTimeWorkRequestBuilder<PhotoContextSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dao = PilotDatabase.get(applicationContext).photoRecordDao()
        if (dao.getPendingPhotoContexts().isEmpty()) return@withContext Result.success()
        val prefs = applicationContext.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        val backend = prefs.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL).orEmpty()
        val deviceId = prefs.getString(AppPrefs.KEY_DEVICE_UUID, "").orEmpty()
        if (backend.isBlank() || deviceId.isBlank()) return@withContext Result.retry()
        try {
            val client = CookWorkflowClient(backend)
            if (client.capabilities()?.photoContext != true) return@withContext Result.retry()
            PhotoContextSynchronizer.sync(dao) { item ->
                client.syncPhotoContext(deviceId, item.id, requireNotNull(item.photoContext))
            }
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: org.json.JSONException) {
            Result.retry()
        }
    }
}
