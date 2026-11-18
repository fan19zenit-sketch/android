package com.example.simplephotouploader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CaptureJpegTest {
    @Test fun interruptedCapturePreservesItsIdentityAndNeverUploadsAPartialFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "jpeg-" + java.util.UUID.randomUUID()).also { check(it.mkdir()) }
        val file = File(dir, CaptureJpeg.PREFIX + "test.jpg")
        val dao = PilotDatabase.get(context).photoRecordDao()
        val frozen = """{"cookNameSnapshot":"Fixture","selectionSource":"one_shot"}"""
        try {
            val id = PhotoHistoryStore.addQueued(context, file.path, "2026-11-17T23:59:59Z", frozen,
                "fixture-device", "http://127.0.0.1", locallyPreparing = true)
            assertFalse(dao.getQueuedRecords().any { it.id == id })
            file.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2))
            PhotoHistoryStore.holdOrDropPreparation(context, file)
            val held = checkNotNull(dao.getByPhotoPath(file.path))
            assertEquals("needs_review", held.status)
            assertEquals(id, held.id)
            assertEquals(frozen, held.photoContext)
            assertEquals("2026-11-17T23:59:59Z", held.capturedAt)
            assertTrue(runCatching { CaptureJpeg.prepare(file, recovered = true) }.exceptionOrNull() is java.io.IOException)
            assertEquals(4L, file.length())
            val missing = File(dir, "never-saved.jpg")
            PhotoHistoryStore.addQueued(context, missing.path, locallyPreparing = true)
            PhotoHistoryStore.holdOrDropPreparation(context, missing)
            assertNull(dao.getByPhotoPath(missing.path))
        } finally {
            dao.deleteByPhotoPath(file.path)
            file.delete()
            dir.delete()
        }
    }

    @Test fun asymmetricSceneSurvivesAllExifTransformsAndTransportRetries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val orders = listOf("0123", "1032", "3210", "2301", "0213", "2031", "3120", "1302")
        val raw = Bitmap.createBitmap(97, 65, Bitmap.Config.ARGB_8888)
        for (y in 0 until raw.height) for (x in 0 until raw.width)
            raw.setPixel(x, y, colors[(if (y < 32) 0 else 2) + (if (x < 48) 0 else 1)])
        try {
            for (o in 1..8) {
                val file = File.createTempFile("jpeg-corners", ".jpg", context.cacheDir)
                try {
                    file.outputStream().use { assertTrue(raw.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                    ExifInterface(file).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, o.toString())
                        setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:17 23:59:59")
                        saveAttributes()
                    }
                    val before = file.readBytes()
                    CaptureJpeg.prepare(file)
                    val canonical = file.readBytes()
                    if (o == 1) assertArrayEquals(before, canonical)
                    CaptureJpeg.prepare(file)
                    assertArrayEquals(canonical, file.readBytes())
                    val received = Buffer()
                    PhotoUploadMultipart.build("fixture", "fixed-id", file, null).parts.last().body.writeTo(received)
                    assertArrayEquals(canonical, received.readByteArray())
                    assertEquals("2026:11:17 23:59:59", ExifInterface(file).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                    assertEquals(1, ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
                    // BitmapFactory ignores EXIF, just like an attachment preview that uses raw pixels.
                    val display = BitmapFactory.decodeByteArray(canonical, 0, canonical.size)
                    assertEquals(if (o >= 5) 65 else 97, display.width)
                    assertEquals(if (o >= 5) 97 else 65, display.height)
                    val corners = listOf(5 to 5, display.width-6 to 5, 5 to display.height-6, display.width-6 to display.height-6)
                    corners.forEachIndexed { index, (x,y) ->
                        val actual = display.getPixel(x,y)
                        val expected = colors[orders[o-1][index].digitToInt()]
                        for (shift in listOf(0, 8, 16)) {
                            assertTrue("EXIF=$o corner=$index", kotlin.math.abs(((actual shr shift) and 255) -
                                ((expected shr shift) and 255)) < 18)
                        }
                    }
                    display.recycle()
                } finally { file.delete() }
            }
        } finally { raw.recycle() }
    }
}
