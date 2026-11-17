package com.example.simplephotouploader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Bot, API and both devices use a loopback-only synthetic fixture; no MAX or branch is contacted. */
@RunWith(AndroidJUnit4::class)
class CookBranchShiftIntegrationTest {
    @Test fun botTemplateIsReadByBothDevicesWithoutSharingTheirMainCook() {
        assumeTrue(BuildConfig.BUILD_TYPE == "localtest" &&
            InstrumentationRegistry.getArguments().getString("isolatedCookBackend") == "1")
        val base = "http://127.0.0.1:18433"
        val api = CookWorkflowClient(base)
        val a = "test-device-a"
        val b = "test-device-b"
        val binding = api.initializeBinding(a)
        assertEquals(binding, api.initializeBinding(b))
        val cookA = requireNotNull(api.addManual(a, UUID.randomUUID().toString(), "Бот Тест Повар А", listOf("cook"), binding))
        val cookB = requireNotNull(api.addManual(a, UUID.randomUUID().toString(), "Бот Тест Повар Б", listOf("cook"), binding))
        val operator = requireNotNull(api.addManual(a, UUID.randomUUID().toString(), "Бот Тест Оператор", listOf("operator"), binding))
        val fixture = Request.Builder().url("$base/fixture/branch-shift-from-bot?cook_a=${cookA.id}&cook_b=${cookB.id}&operator=${operator.id}")
            .post(ByteArray(0).toRequestBody()).build()
        PhotoApi.execute(fixture).use { response -> assertEquals(200, response.code) }
        assertTrue(requireNotNull(api.capabilities(a)).branchShiftTemplate)
        val now = Instant.now()
        val zone = ZoneId.of(requireNotNull(binding.timezone))
        val day = now.atZone(zone).toLocalDate().toString()
        val template = requireNotNull(api.branchShift(a, day, binding))
        assertEquals(template, api.branchShift(b, day, binding))
        assertEquals(listOf(cookA.id, cookB.id), template.cooks)
        assertEquals(listOf(operator.id), template.operators)
        val confirmedAt = now.minusSeconds(1).atZone(zone).toOffsetDateTime().toString()
        for ((device, employee, upload) in listOf(Triple(a, cookA, "test-photo-a"), Triple(b, cookB, "test-photo-b"))) {
            val shift = CookShift(UUID.randomUUID().toString(), 0, binding.branchRef, day, zone.id,
                confirmedAt, template.cooks, template.operators, employee.id, template.assistants)
            val saved = requireNotNull(api.confirmShift(device, UUID.randomUUID().toString(), shift, binding))
            val photo = CookPhotoContextFactory.create(binding, binding, shift.copy(revision = saved.second),
                employee, "main_default", now)
            assertEquals("resolved", api.syncPhotoContext(device, upload, photo))
        }
    }
}
