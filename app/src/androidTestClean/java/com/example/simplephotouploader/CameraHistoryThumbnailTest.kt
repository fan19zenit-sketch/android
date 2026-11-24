package com.example.simplephotouploader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View.MeasureSpec
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraHistoryThumbnailTest {
    @Test fun bothPhotoOrientationsHaveTransparentCorners() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_SimplePhotoUploader)
            val console = LayoutInflater.from(context).inflate(R.layout.camera_console, null)
            val thumbnail = console.findViewById<ImageView>(R.id.settingsButton)
            val size = thumbnail.layoutParams.width
            val spec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
            thumbnail.measure(spec, spec)
            thumbnail.layout(0, 0, size, size)
            assertTrue(thumbnail.isClickable)
            assertEquals(ImageView.ScaleType.CENTER_CROP, thumbnail.scaleType)

            for ((width, height) in listOf(120 to 80, 80 to 120)) {
                val photo = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(Color.RED) }
                thumbnail.setImageBitmap(photo)
                val rendered = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                thumbnail.draw(Canvas(rendered))
                assertEquals(Color.RED, rendered.getPixel(size / 2, size / 2))
                for (x in listOf(0, size - 1)) for (y in listOf(0, size - 1)) {
                    assertEquals(0, Color.alpha(rendered.getPixel(x, y)))
                }
            }
        }
    }
}
