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
            .addMigrations(PilotDatabase.MIGRATION_1_2, PilotDatabase.MIGRATION_2_3).build().use { db ->
                val restored = db.photoRecordDao().getByPhotoPath("/test/photo.jpg")!!
                assertEquals(record(), restored)
                assertFalse(restored.manualRetryApproved)
                assertNull(restored.photoContext)
            }
        context.deleteDatabase(name)
    }

    @Test fun migrationFromVersionTwoPreservesQueuedAndSentPhotos() {
        val name = "migration-v2-qa"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE photo_records (id TEXT NOT NULL PRIMARY KEY, jobId TEXT, messageId TEXT, chatDeleted INTEGER NOT NULL, photoPath TEXT NOT NULL, thumbnailPath TEXT, capturedAt TEXT NOT NULL, sentAt TEXT, status TEXT NOT NULL, error TEXT, queuedInUploadQueue INTEGER NOT NULL, manualRetryApproved INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE UNIQUE INDEX index_photo_records_photoPath ON photo_records(photoPath)")
            db.execSQL("CREATE INDEX index_photo_records_capturedAt ON photo_records(capturedAt)")
            db.execSQL("CREATE INDEX index_photo_records_queuedInUploadQueue ON photo_records(queuedInUploadQueue)")
            db.execSQL("INSERT INTO photo_records VALUES ('queued-id', NULL, NULL, 0, '/test/queued.jpg', '/test/thumb.jpg', '2026-11-05T12:00:00Z', NULL, 'queued', NULL, 1, 1)")
            db.execSQL("INSERT INTO photo_records VALUES ('sent-id', 'job-id', 'mid', 0, '/test/sent.jpg', NULL, '2026-11-05T12:01:00Z', '2026-11-05T12:02:00Z', 'sent', NULL, 0, 0)")
            db.version = 2
        }
        Room.databaseBuilder(context, PilotDatabase::class.java, name)
            .addMigrations(PilotDatabase.MIGRATION_2_3).build().use { db ->
                val queued = db.photoRecordDao().getByPhotoPath("/test/queued.jpg")!!
                val sent = db.photoRecordDao().getByPhotoPath("/test/sent.jpg")!!
                assertEquals("queued-id", queued.id)
                assertEquals("/test/thumb.jpg", queued.thumbnailPath)
                assertTrue(queued.queuedInUploadQueue)
                assertTrue(queued.manualRetryApproved)
                assertNull(queued.photoContext)
                assertEquals("job-id", sent.jobId)
                assertEquals("mid", sent.messageId)
                assertEquals("sent", sent.status)
                assertNull(sent.photoContext)
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
