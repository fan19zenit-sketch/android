package com.example.simplephotouploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedServerJobTest {
    @Test fun serverDeduplicationUpdatesEveryLocalReference() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = PilotDatabase.get(context).photoRecordDao()
        val jobId = "shared-deduplicated-job"
        try {
            repeat(2) { i -> dao.insert(PhotoRecordEntity("shared-$i", jobId, null, false,
                "/test/shared-$i.jpg", null, "2026-09-04T12:00:00Z", null,
                "uploaded_to_server", null, false)) }
            PhotoHistoryStore.syncStatuses(context, listOf(mapOf("job_id" to jobId, "status" to "sent")))
            assertEquals(2, dao.getAllByJobId(jobId).count { it.status == "sent" })
            PhotoHistoryStore.markChatDeleted(context, jobId)
            assertTrue(dao.getAllByJobId(jobId).all { it.chatDeleted })
            PhotoHistoryStore.markServerNeedsReview(context, jobId, "HTTP 404")
            assertTrue(dao.getAllByJobId(jobId).all { it.status == "needs_review" && it.chatDeleted })
        } finally {
            repeat(2) { dao.deleteByPhotoPath("/test/shared-$it.jpg") }
        }
    }
}
