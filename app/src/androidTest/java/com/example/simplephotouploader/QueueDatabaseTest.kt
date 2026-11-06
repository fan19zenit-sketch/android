package com.example.simplephotouploader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

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
            .addMigrations(PilotDatabase.MIGRATION_1_2, PilotDatabase.MIGRATION_2_3, PilotDatabase.MIGRATION_3_4, PilotDatabase.MIGRATION_4_5).build().use { db ->
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
            .addMigrations(PilotDatabase.MIGRATION_2_3, PilotDatabase.MIGRATION_3_4, PilotDatabase.MIGRATION_4_5).build().use { db ->
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

    @Test fun contextAcknowledgementIsSeparateAndRequiresExactSnapshot() {
        Room.inMemoryDatabaseBuilder(context, PilotDatabase::class.java).build().use { db ->
            val dao = db.photoRecordDao()
            dao.insert(record().copy(photoContext = "snapshot"))
            assertTrue(dao.getPendingPhotoContexts().isEmpty())
            dao.acknowledgeUpload(record().photoPath, "job")
            assertEquals("stable-id", dao.getPendingPhotoContexts().single().id)
            assertEquals(0, dao.acknowledgePhotoContext("stable-id", "other", "resolved"))
            assertEquals(1, dao.acknowledgePhotoContext("stable-id", "snapshot", "unresolved"))
            assertEquals("stable-id", dao.getPendingPhotoContexts().single().id)
            val stored = dao.getByPhotoPath(record().photoPath)!!
            assertEquals("unresolved", stored.photoContextAck)
            assertEquals("snapshot", stored.photoContext)
            assertEquals("job", stored.jobId)
            PhotoContextSynchronizer.sync(dao) { "resolved" }
            assertTrue(dao.getPendingPhotoContexts().isEmpty())
            assertEquals("resolved", dao.getByPhotoPath(record().photoPath)!!.photoContextAck)
            assertEquals(0, dao.acknowledgePhotoContext("stable-id", "snapshot", "unresolved"))
        }
    }

    @Test fun migrationFromVersionThreePreservesPhotoContext() {
        val name = "migration-v3-qa"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE photo_records (id TEXT NOT NULL PRIMARY KEY, jobId TEXT, messageId TEXT, chatDeleted INTEGER NOT NULL, photoPath TEXT NOT NULL, thumbnailPath TEXT, capturedAt TEXT NOT NULL, sentAt TEXT, status TEXT NOT NULL, error TEXT, queuedInUploadQueue INTEGER NOT NULL, manualRetryApproved INTEGER NOT NULL DEFAULT 0, photoContext TEXT)")
            db.execSQL("CREATE UNIQUE INDEX index_photo_records_photoPath ON photo_records(photoPath)")
            db.execSQL("CREATE INDEX index_photo_records_capturedAt ON photo_records(capturedAt)")
            db.execSQL("CREATE INDEX index_photo_records_queuedInUploadQueue ON photo_records(queuedInUploadQueue)")
            db.execSQL("INSERT INTO photo_records VALUES ('id', 'job', NULL, 0, '/test/photo.jpg', NULL, '2026-11-05T12:00:00Z', NULL, 'uploaded_to_server', NULL, 0, 0, 'saved-snapshot')")
            db.version = 3
        }
        Room.databaseBuilder(context, PilotDatabase::class.java, name)
            .addMigrations(PilotDatabase.MIGRATION_3_4, PilotDatabase.MIGRATION_4_5).build().use { db ->
                val item = db.photoRecordDao().getPendingPhotoContexts().single()
                assertEquals("id", item.id)
                assertEquals("job", item.jobId)
                assertEquals("saved-snapshot", item.photoContext)
                assertNull(item.photoContextAck)
            }
        context.deleteDatabase(name)
    }

    @Test fun migrationFromVersionFourPreservesQueueAndContext() {
        val name = "migration-v4-qa"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE photo_records (id TEXT NOT NULL PRIMARY KEY, jobId TEXT, messageId TEXT, chatDeleted INTEGER NOT NULL, photoPath TEXT NOT NULL, thumbnailPath TEXT, capturedAt TEXT NOT NULL, sentAt TEXT, status TEXT NOT NULL, error TEXT, queuedInUploadQueue INTEGER NOT NULL, manualRetryApproved INTEGER NOT NULL DEFAULT 0, photoContext TEXT, photoContextAck TEXT)")
            db.execSQL("CREATE UNIQUE INDEX index_photo_records_photoPath ON photo_records(photoPath)")
            db.execSQL("CREATE INDEX index_photo_records_capturedAt ON photo_records(capturedAt)")
            db.execSQL("CREATE INDEX index_photo_records_queuedInUploadQueue ON photo_records(queuedInUploadQueue)")
            db.execSQL("INSERT INTO photo_records VALUES ('old', NULL, NULL, 0, '/old.jpg', NULL, '2026-11-05T12:00:00Z', NULL, 'queued', NULL, 1, 0, 'snapshot', NULL)")
            db.version = 4
        }
        Room.databaseBuilder(context, PilotDatabase::class.java, name)
            .addMigrations(PilotDatabase.MIGRATION_4_5).build().use { db ->
                val item = db.photoRecordDao().getByPhotoPath("/old.jpg")!!
                assertEquals("old", item.id)
                assertEquals("snapshot", item.photoContext)
                assertTrue(item.queuedInUploadQueue)
                assertNull(item.captureDeviceUuid)
                assertNull(item.captureBackendUrl)
            }
        context.deleteDatabase(name)
    }

    @Test fun contextSyncRetriesOnlyUnacknowledgedSnapshots() {
        Room.inMemoryDatabaseBuilder(context, PilotDatabase::class.java).build().use { db ->
            val dao = db.photoRecordDao()
            dao.insert(record().copy(id = "one", photoPath = "/one", jobId = "job-one", photoContext = "first"))
            dao.insert(record().copy(id = "two", photoPath = "/two", jobId = "job-two", photoContext = "second",
                capturedAt = "2026-09-04T14:01:00Z"))
            val sent = mutableListOf<String>()
            try {
                PhotoContextSynchronizer.sync(dao) { item ->
                    sent.add(item.id)
                    if (item.id == "two") throw IOException("offline")
                    "resolved"
                }
                fail("Expected network failure")
            } catch (_: IOException) { }
            assertEquals(listOf("one", "two"), sent)
            assertEquals(listOf("two"), dao.getPendingPhotoContexts().map { it.id })
            PhotoContextSynchronizer.sync(dao) { item -> sent.add(item.id); "resolved" }
            assertEquals(listOf("one", "two", "two"), sent)
            assertTrue(dao.getPendingPhotoContexts().isEmpty())
            assertEquals("job-one", dao.getByPhotoPath("/one")!!.jobId)
            assertEquals("job-two", dao.getByPhotoPath("/two")!!.jobId)
        }
    }

    @Test fun conflictDoesNotBlockFollowingPhotoOrChangeDelivery() {
        Room.inMemoryDatabaseBuilder(context, PilotDatabase::class.java).build().use { db ->
            val dao = db.photoRecordDao()
            dao.insert(record().copy(id = "one", photoPath = "/one", jobId = "job-one", status = "sent",
                queuedInUploadQueue = false, photoContext = "first"))
            dao.insert(record().copy(id = "two", photoPath = "/two", jobId = "job-two", status = "sent",
                queuedInUploadQueue = false, photoContext = "second", capturedAt = "2026-09-04T14:01:00Z"))
            val calls = mutableListOf<String>()
            PhotoContextSynchronizer.sync(dao) { item ->
                calls.add(item.id)
                if (item.id == "one") throw CookApiHttpException(409)
                "resolved"
            }
            assertEquals(listOf("one", "two"), calls)
            val first = dao.getByPhotoPath("/one")!!
            assertEquals("conflict", first.photoContextAck)
            assertEquals("first", first.photoContext)
            assertEquals("sent", first.status)
            assertEquals("job-one", first.jobId)
            assertEquals("resolved", dao.getByPhotoPath("/two")!!.photoContextAck)
            assertTrue(dao.getPendingPhotoContexts().isEmpty())
            PhotoContextSynchronizer.sync(dao) { fail("Conflict must not be retried automatically"); "resolved" }
        }
    }

    @Test fun temporaryHttpFailureKeepsMetadataPending() {
        for (status in listOf(408, 429, 500, 503)) {
            Room.inMemoryDatabaseBuilder(context, PilotDatabase::class.java).build().use { db ->
                val dao = db.photoRecordDao()
                dao.insert(record().copy(jobId = "job", photoContext = "snapshot"))
                try {
                    PhotoContextSynchronizer.sync(dao) { throw CookApiHttpException(status) }
                    fail("Temporary HTTP $status must be retried")
                } catch (error: CookApiHttpException) { assertEquals(status, error.status) }
                assertNull(dao.getByPhotoPath(record().photoPath)!!.photoContextAck)
                assertEquals(1, dao.getPendingPhotoContexts().size)
            }
        }
    }
}
