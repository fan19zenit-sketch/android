package com.example.simplephotouploader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import android.system.Os
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

/** New captures only. The queued payload must display upright even in a client that ignores EXIF. */
internal object CaptureJpeg {
    const val PREFIX = "IMG_upright_"

    fun prepare(file: File, recovered: Boolean = false) {
        // A killed camera process can leave a partial JPEG. Never publish it on manual recovery.
        java.io.RandomAccessFile(file, "r").use {
            if (it.length() < 4 || it.readUnsignedShort() != 0xffd8) throw IOException("Missing JPEG header")
            if (recovered) {
                // Camera buffers can include zero padding after the end marker.
                var end = it.length() - 1
                do { it.seek(end--) } while (it.read() == 0 && end > 1)
                it.seek(end)
                if (it.readUnsignedShort() != 0xffd9) throw IOException("Incomplete camera JPEG")
            }
        }
        val exif = ExifInterface(file)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        if (orientation !in 2..8) return
        val modified = file.lastModified()
        val out = File(file.parentFile, file.name + ".preparing")
        try {
            val bitmap = decodeUpright(file, orientation)
            try {
                out.outputStream().use {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) throw IOException("JPEG encode failed")
                }
            } finally { bitmap.recycle() }
            ExifInterface(out).apply {
                for (tag in listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_DATETIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                    ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME,
                    ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
                    ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL)) {
                    exif.getAttribute(tag)?.let { setAttribute(tag, it) }
                }
                setAttribute(ExifInterface.TAG_ORIENTATION, "1")
                saveAttributes()
            }
            java.io.FileOutputStream(out, true).use { it.fd.sync() }
            out.setLastModified(modified)
            Os.rename(out.absolutePath, file.absolutePath)
        } catch (oom: OutOfMemoryError) {
            throw IOException("Insufficient memory for full-resolution JPEG", oom)
        } finally { out.delete() }
    }

    private fun decodeUpright(file: File, orientation: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath) ?: throw IOException("Invalid camera JPEG")
        return try {
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(270f); postScale(-1f, 1f) }
                    8 -> setRotate(270f)
                }
            }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, false)
        } finally { raw.recycle() }
    }
}
