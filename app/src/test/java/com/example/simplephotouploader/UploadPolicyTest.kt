package com.example.simplephotouploader

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class UploadPolicyTest {
    @Test fun `acknowledged photos cannot enter upload again even with stale queued flag`() {
        for (status in listOf("queued", "retrying", "sending", "sent", "error")) {
            assertFalse(UploadPolicy.mayUpload("server-job", status, false))
        }
    }

    @Test fun `deleted and final photos are not uploaded`() {
        assertFalse(UploadPolicy.mayUpload(null, "queued", true))
        for (status in listOf("sent", "error", "needs_review", "uploaded_to_server", "sending_to_chat")) {
            assertFalse(UploadPolicy.mayUpload(null, status, false))
        }
    }

    @Test fun `interrupted uploads resume without creating new identity`() {
        for (status in listOf("queued", "retrying", "sending")) {
            assertTrue(UploadPolicy.mayUpload(null, status, false))
        }
    }

    @Test fun `day boundary is Moscow midnight`() {
        val now = Instant.parse("2026-09-05T21:00:00Z")
        assertTrue(UploadPolicy.needsDateApproval("2026-09-05T20:59:59Z", false, now))
        assertFalse(UploadPolicy.needsDateApproval("2026-09-05T21:00:00Z", false, now))
    }

    @Test fun `historical upload needs explicit approval and never changes capture date`() {
        val original = "2026-09-01T12:00:00Z"
        val now = Instant.parse("2026-09-05T12:00:00Z")
        assertTrue(UploadPolicy.needsDateApproval(original, false, now))
        assertFalse(UploadPolicy.needsDateApproval(original, true, now))
        assertTrue(UploadPolicy.needsDateApproval("invalid", false, now))
    }

    @Test fun `temporary HTTP failures retry while bad requests need attention`() {
        for (code in listOf(408, 429, 500, 502, 503)) assertTrue(UploadPolicy.retryableHttp(code))
        for (code in listOf(200, 202, 400, 401, 403, 404, 413)) assertFalse(UploadPolicy.retryableHttp(code))
    }
}
