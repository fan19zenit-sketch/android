package com.example.simplephotouploader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoRouteGuardTest {
    private val item = PhotoRecordEntity("id", null, null, false, "/test.jpg", null, "2026-11-05T08:00:00Z",
        null, "queued", null, true, photoContext = """{"branchRef":"branch","bindingRevision":2}""",
        captureDeviceUuid = "device", captureBackendUrl = "https://test.example")

    @Test fun attributedPhotoIsHeldForChangedDeviceBackendOrBranch() {
        assertTrue(PhotoRouteGuard.sameDeviceAndBackend(item, "device", "https://test.example/"))
        assertFalse(PhotoRouteGuard.sameDeviceAndBackend(item, "other", "https://test.example"))
        assertFalse(PhotoRouteGuard.sameDeviceAndBackend(item, "device", "https://other.example"))
        assertFalse(PhotoRouteGuard.sameDeviceAndBackend(item.copy(captureDeviceUuid = null), "device", "https://test.example"))
        assertTrue(PhotoRouteGuard.sameBinding(item, CookBinding("branch", 2)))
        assertFalse(PhotoRouteGuard.sameBinding(item, CookBinding("branch", 3)))
        assertFalse(PhotoRouteGuard.sameBinding(item, CookBinding("other", 2)))
        assertFalse(PhotoRouteGuard.sameBinding(item.copy(photoContext = "bad"), CookBinding("branch", 2)))
    }

    @Test fun legacyPhotoWithoutContextKeepsExistingBehavior() {
        val legacy = item.copy(photoContext = null, captureDeviceUuid = null, captureBackendUrl = null)
        assertTrue(PhotoRouteGuard.sameDeviceAndBackend(legacy, "other", "https://other.example"))
        assertTrue(PhotoRouteGuard.sameBinding(legacy, CookBinding("other", 3)))
    }
}
