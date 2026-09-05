package com.example.simplephotouploader

import android.content.Context
import android.content.SharedPreferences

object PhotoQueueStore {
    fun loadQueue(prefs: SharedPreferences): MutableList<String> {
        return PilotDatabase.get(App.instance)
            .photoRecordDao()
            .getQueuedPaths()
            .toMutableList()
    }

    fun saveQueue(prefs: SharedPreferences, data: List<String>) {
        // Deprecated for pilot queue logic: mass overwrites can drop newer items.
    }

    fun enqueue(context: Context, path: String): Boolean {
        val dao = PilotDatabase.get(context).photoRecordDao()
        val existing = dao.getByPhotoPath(path) ?: return false
        if (!UploadPolicy.mayUpload(existing.jobId, existing.status, existing.chatDeleted)) return false
        if (existing.queuedInUploadQueue) {
            return false
        }
        dao.setQueuedState(path, true)
        return true
    }

    fun hasItems(context: Context): Boolean {
        return PilotDatabase.get(context).photoRecordDao().getQueuedPaths().isNotEmpty()
    }

    fun dequeue(context: Context, path: String) {
        PilotDatabase.get(context).photoRecordDao().setQueuedState(path, false)
    }
}
