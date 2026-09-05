package com.example.simplephotouploader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueDatabaseTest {
    private inline fun <T> PilotDatabase.use(block: (PilotDatabase) -> T): T =
        try { block(this) } finally { close() }

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun record() = PhotoRecordEntity("stable-id", null, null, false,
        "/test/photo.jpg", null, "2026-09-04T14:00:00Z", null, "queued", null, true)

    @Test fun migrationPreservesQueueAndIdentity() {
        val name = "migration-qa"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE photo_records (id TEXT NOT NULL PRIMARY KEY, jobId TEXT, messageId TEXT, chatDeleted INTEGER NOT NULL, photoPath TEXT NOT NULL, thumbnailPath TEXT, capturedAt TEXT NOT NULL, sentAt TEXT, status TEXT NOT NULL, error TEXT, queuedInUploadQueue INTEGER NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX index_photo_records_photoPath ON photo_records(photoPath)")
            db.execSQL("CREATE INDEX index_photo_records_capturedAt ON photo_records(capturedAt)")
            db.execSQL("CREATE INDEX index_photo_records_queuedInUploadQueue ON photo_records(queuedInUploadQueue)")
            db.execSQL("INSERT INTO photo_records VALUES ('stable-id', NULL, NULL, 0, '/test/photo.jpg', NULL, '2026-09-04T14:00:00Z', NULL, 'queued', NULL, 1)")
            db.version = 1
        }
        Room.databaseBuilder(context, PilotDatabase::class.java, name)
            .addMigrations(PilotDatabase.MIGRATION_1_2).build().use { db ->
                val restored = db.photoRecordDao().getByPhotoPath("/test/photo.jpg")!!
                assertEquals(record(), restored)
                assertFalse(restored.manualRetryApproved)
            }
        context.deleteDatabase(name)
    }

    @Test fun acceptedPhotoCannotBeRetried() {
        Room.inMemoryDatabaseBuilder(context, PilotDatabase::class.java).build().use { db ->
            val dao = db.photoRecordDao()
            dao.insert(record())
            dao.acknowledgeUpload(record().photoPath, "server-job")
            assertTrue(dao.getQueuedPaths().isEmpty())
            assertEquals(0, dao.retryLocal(record().photoPath))
            assertEquals("stable-id", dao.getPendingServerRecords().single().id)
        }
    }

    @Test fun manualRetryKeepsOriginalIdentity() {
        Room.inMemoryDatabaseBuilder(context, PilotDatabase::class.java).build().use { db ->
            val dao = db.photoRecordDao()
            dao.insert(record().copy(status = "needs_review", queuedInUploadQueue = false))
            assertEquals(1, dao.retryLocal(record().photoPath))
            val retried = dao.getQueuedRecords().single()
            assertEquals("stable-id", retried.id)
            assertTrue(retried.manualRetryApproved)
            assertEquals(0, dao.retryLocal(record().photoPath))
        }
    }

    @Test fun pendingLookupDoesNotStopAtTenPhotos() {
        Room.inMemoryDatabaseBuilder(context, PilotDatabase::class.java).build().use { db ->
            repeat(25) { i -> db.photoRecordDao().insert(record().copy(
                id = "id-$i", photoPath = "/test/$i.jpg", jobId = "job-$i",
                status = "uploaded_to_server", queuedInUploadQueue = false)) }
            assertEquals(25, db.photoRecordDao().getPendingServerRecords().size)
        }
    }
}
