package com.example.simplephotouploader

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/** Compatibility entry point. WorkManager owns all uploads, including retries. */
class PhotoUploadForegroundService : Service() {
    companion object {
        fun start(context: Context) = PhotoUploadWorker.enqueue(context)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PhotoUploadWorker.enqueue(applicationContext)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
