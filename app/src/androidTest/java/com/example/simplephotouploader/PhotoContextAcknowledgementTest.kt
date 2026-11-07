package com.example.simplephotouploader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoContextAcknowledgementTest {
    private fun payload(): JSONObject = JSONObject().put("clientUploadId", "photo")
        .put("schemaVersion", 1).put("photoContextStatus", "stored").put("resolution", "resolved")

    @Test fun acceptsOnlyExactExplicitAcknowledgement() {
        assertEquals("resolved", PhotoContextAcknowledgement.resolution(payload(), "photo"))
        assertEquals("unresolved", PhotoContextAcknowledgement.resolution(payload().put("resolution", "unresolved"), "photo"))
        assertNull(PhotoContextAcknowledgement.resolution(null, "photo"))
        assertNull(PhotoContextAcknowledgement.resolution(JSONObject().put("job", JSONObject()), "photo"))
        assertNull(PhotoContextAcknowledgement.resolution(payload(), "another-photo"))
        assertNull(PhotoContextAcknowledgement.resolution(payload().put("schemaVersion", 2), "photo"))
        assertNull(PhotoContextAcknowledgement.resolution(payload().put("photoContextStatus", "pending"), "photo"))
        assertNull(PhotoContextAcknowledgement.resolution(payload().put("resolution", "unknown"), "photo"))
    }
}
