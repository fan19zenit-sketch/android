package com.example.simplephotouploader

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object QueuedPhotoMaintenance {
    data class CleanupResult(
        val removedPaths: List<String>,
        val removedCount: Int,
    )

    fun removeStaleEntries(queue: MutableList<String>, zoneId: ZoneId = ZoneId.systemDefault()): CleanupResult {
        val today = LocalDate.now(zoneId)
        var removedCount = 0
        val removedPaths = mutableListOf<String>()
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val file = File(entry)
            if (!file.exists()) {
                iterator.remove()
                continue
            }

            val fileDate = Instant.ofEpochMilli(file.lastModified()).atZone(zoneId).toLocalDate()
            if (fileDate.isBefore(today)) {
                file.delete()
                PhotoHistoryStore.removeByPhotoPath(App.instance, entry)
                iterator.remove()
                removedPaths += entry
                removedCount += 1
            }
        }
        return CleanupResult(removedPaths, removedCount)
    }
}
