package com.example.simplephotouploader

import java.time.Instant
import java.time.ZoneId

object UploadPolicy {
    val businessZone: ZoneId = ZoneId.of("Europe/Moscow")

    fun mayUpload(jobId: String?, status: String, deleted: Boolean): Boolean =
        jobId.isNullOrBlank() && !deleted && status in setOf("queued", "retrying", "sending")

    fun needsDateApproval(capturedAt: String, approved: Boolean, now: Instant = Instant.now()): Boolean {
        if (approved) return false
        val captureDay = runCatching { Instant.parse(capturedAt).atZone(businessZone).toLocalDate() }.getOrNull()
        return captureDay == null || captureDay.isBefore(now.atZone(businessZone).toLocalDate())
    }

    fun retryableHttp(code: Int): Boolean = code == 408 || code == 429 || code >= 500
}
