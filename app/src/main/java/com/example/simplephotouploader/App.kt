package com.example.simplephotouploader

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        val prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
        if (prefs.getString(AppPrefs.KEY_BACKEND_URL, null)?.trimEnd('/') == AppPrefs.LEGACY_BACKEND_URL) {
            prefs.edit().putString(AppPrefs.KEY_BACKEND_URL, AppPrefs.DEFAULT_BACKEND_URL).apply()
        }
        PilotDatabase.get(this)
        PhotoHistoryStore.migrateFromLegacyPrefsIfNeeded(this)
        if (PhotoQueueStore.hasItems(this)) PhotoUploadWorker.enqueue(this)
        if (PilotDatabase.get(this).photoRecordDao().getPendingServerRecords().isNotEmpty()) {
            UploadStatusSyncWorker.enqueue(this)
        }
        val existingFiles = File(filesDir, "photos").listFiles().orEmpty()
        ioScope.launch {
            val dao = PilotDatabase.get(this@App).photoRecordDao()
            for (file in existingFiles) {
                if (file.extension != "jpg" || file.length() == 0L || dao.getByPhotoPath(file.absolutePath) != null) continue
                PilotDatabase.get(this@App).runInTransaction {
                    PhotoHistoryStore.addQueued(this@App, file.absolutePath, Instant.ofEpochMilli(file.lastModified()).toString())
                    dao.getByPhotoPath(file.absolutePath)?.let {
                        dao.update(it.copy(status = "needs_review", queuedInUploadQueue = false,
                            error = getString(R.string.history_recovered)))
                    }
                }
            }
        }
    }

    companion object {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var instance: App
            private set
    }
}
