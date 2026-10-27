package com.example.simplephotouploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run seed on 3.1, install 3.2 with adb install -r, then run verify. Emulator fixtures only. */
@RunWith(AndroidJUnit4::class)
class UpgradePersistenceTest {
    @Test fun preservesQueuedPhotoAndRegistrationAcrossUpdate() {
        val mode = InstrumentationRegistry.getArguments().getString("upgrade_mode")
        assumeTrue(mode == "seed" || mode == "verify")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
        val file = File(context.filesDir, "upgrade-fixture.jpg")
        val dao = PilotDatabase.get(context).photoRecordDao()
        if (mode == "seed") {
            prefs.edit().putString(AppPrefs.KEY_BACKEND_URL, "http://127.0.0.1:9")
                .putString(AppPrefs.KEY_CITY, "Upgrade fixture")
                .putString(AppPrefs.KEY_DEVICE_UUID, "60c4be62-4a2b-485d-964b-52d02c6a88cc").commit()
            file.writeBytes(byteArrayOf(4, 3, 2, 1))
            val id = PhotoHistoryStore.addQueued(context, file.absolutePath)
            prefs.edit().putString("upgrade_fixture_id", id).commit()
        } else {
            assertEquals("Upgrade fixture", prefs.getString(AppPrefs.KEY_CITY, null))
            assertEquals("60c4be62-4a2b-485d-964b-52d02c6a88cc", prefs.getString(AppPrefs.KEY_DEVICE_UUID, null))
            assertTrue(file.isFile)
            assertArrayEquals(byteArrayOf(4, 3, 2, 1), file.readBytes())
            assertEquals(prefs.getString("upgrade_fixture_id", null), dao.getByPhotoPath(file.absolutePath)?.id)
            assertTrue(dao.getByPhotoPath(file.absolutePath)!!.queuedInUploadQueue)
        }
    }
}
