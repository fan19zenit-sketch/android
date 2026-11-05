package com.example.simplephotouploader

import java.io.File
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoUploadMultipartTest {
    private fun bodyText(context: String?): String {
        val file = File.createTempFile("upload", ".jpg")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val body = PhotoUploadMultipart.build("device", "original-upload-id", file, context)
            return Buffer().also { body.writeTo(it) }.readUtf8()
        } finally {
            file.delete()
        }
    }

    @Test fun snapshotIsIncludedWithoutChangingUploadIdentity() {
        val text = bodyText("{\"schemaVersion\":1,\"cookNameSnapshot\":\"test\"}")
        assertTrue(text.contains("name=\"photoContext\""))
        assertTrue(text.contains("{\"schemaVersion\":1,\"cookNameSnapshot\":\"test\"}"))
        assertTrue(text.contains("original-upload-id"))
        assertEquals(1, "name=\"photoContext\"".toRegex().findAll(text).count())
    }

    @Test fun legacyPhotoHasNoContextField() {
        assertFalse(bodyText(null).contains("name=\"photoContext\""))
    }
}
