package com.example.simplephotouploader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

class PhotoUploadForegroundService : Service() {

    companion object {
        private const val TAG = "PhotoUploadFgService"
        private const val CHANNEL_ID = "photo_upload_channel"
        private const val NOTIFICATION_ID = 1001
        private val started = AtomicBoolean(false)
        private val rerunRequested = AtomicBoolean(false)

        fun start(context: Context) {
            val intent = Intent(context, PhotoUploadForegroundService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started.compareAndSet(false, true)) {
            rerunRequested.set(true)
            return START_NOT_STICKY
        }

        try {
            startInForeground(getString(R.string.status_sending))
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start foreground service", error)
            started.set(false)
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            processQueue()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        started.set(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun processQueue() {
        val prefs = getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        val backendUrl = prefs.getString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL)
            ?.trimEnd('/')
            .orEmpty()
        val deviceUuid = prefs.getString(AppPrefs.KEY_DEVICE_UUID, "").orEmpty()

        if (backendUrl.isEmpty() || deviceUuid.isEmpty()) {
            finishWithStatus(getString(R.string.status_error))
            return
        }

        while (true) {
            val currentQueue = PhotoQueueStore.loadQueue(prefs)
            val cleanup = QueuedPhotoMaintenance.removeStaleEntries(
                currentQueue,
                ZoneId.systemDefault()
            )
            cleanup.removedPaths.forEach { removedPath ->
                PhotoQueueStore.dequeue(applicationContext, removedPath)
            }
            val queue = PhotoQueueStore.loadQueue(prefs)
            if (cleanup.removedCount > 0) {
                updateStatus(getString(R.string.status_stale_cleared, cleanup.removedCount))
            }
            if (queue.isEmpty()) {
                if (rerunRequested.getAndSet(false) || PhotoQueueStore.hasItems(applicationContext)) {
                    continue
                }
                finishWithStatus(getString(R.string.status_ready))
                return
            }

            updateStatus(getString(R.string.status_sending))

            for (entry in queue.toList()) {
                val file = File(entry)
                if (!file.exists()) {
                    PhotoHistoryStore.removeByPhotoPath(applicationContext, entry)
                    PhotoQueueStore.dequeue(applicationContext, entry)
                    continue
                }

            PhotoHistoryStore.markSending(applicationContext, entry)
            updateNotification(getString(R.string.notification_uploading, file.name))
            val clientUploadId = PhotoHistoryStore.getByPhotoPath(applicationContext, entry)?.id.orEmpty()

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("deviceUuid", deviceUuid)
                .addFormDataPart("clientUploadId", clientUploadId)
                .addFormDataPart(
                    "photo",
                    file.name,
                        file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                    )
                    .build()

                val request = Request.Builder()
                    .url("$backendUrl/upload-v2")
                    .post(body)
                    .build()

                val outcome = try {
                    client.newCall(request).execute().use { response ->
                        when {
                            response.code == 202 -> {
                                val responseBody = response.body?.string().orEmpty()
                                val jobId = JSONObject(responseBody).optJSONObject("job")?.optString("job_id").orEmpty()
                                if (jobId.isBlank()) {
                                    UploadOutcome.PermanentFailure(null)
                                } else {
                                    UploadOutcome.Accepted(jobId)
                                }
                            }
                            response.code >= 500 -> UploadOutcome.RetryableFailure
                            else -> UploadOutcome.PermanentFailure(null)
                        }
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Foreground upload failed for ${file.absolutePath}", error)
                    UploadOutcome.RetryableFailure
                }

                when (outcome) {
                    is UploadOutcome.Accepted -> {
                        file.delete()
                        PhotoHistoryStore.markUploadedToServer(applicationContext, entry, outcome.jobId)
                        PhotoQueueStore.dequeue(applicationContext, entry)
                        updateStatus(getString(R.string.status_server_received))
                        updateNotification(getString(R.string.notification_server_received))
                        UploadStatusSyncWorker.enqueueBurst(applicationContext)
                    }
                    UploadOutcome.RetryableFailure -> {
                        PhotoHistoryStore.markRetry(applicationContext, entry)
                        updateStatus(getString(R.string.status_retrying))
                        updateNotification(getString(R.string.notification_retrying))
                        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                            "photo-upload-queue",
                            ExistingWorkPolicy.KEEP,
                            OneTimeWorkRequestBuilder<PhotoUploadWorker>().build()
                        )
                        stopForegroundAndSelf()
                        return
                    }
                    is UploadOutcome.PermanentFailure -> {
                        PhotoHistoryStore.markError(applicationContext, entry, outcome.error)
                        updateStatus(getString(R.string.status_error))
                        updateNotification(getString(R.string.notification_error))
                        stopForegroundAndSelf()
                        return
                    }
                }
            }
        }
    }

    private fun finishWithStatus(status: String) {
        updateStatus(status)
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateStatus(text: String) {
        getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(AppPrefs.KEY_LAST_STATUS, text)
            .apply()
    }

    private fun startInForeground(contentText: String) {
        createNotificationChannel()
        val notification = buildNotification(contentText)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private sealed class UploadOutcome {
        data class Accepted(val jobId: String) : UploadOutcome()
        object RetryableFailure : UploadOutcome()
        data class PermanentFailure(val error: String?) : UploadOutcome()
    }
}
