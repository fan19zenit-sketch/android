package com.example.simplephotouploader

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class CookWorkflowClientTest {
    private fun response(request: Request, code: Int, body: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody()).build()

    @Test fun disabledServerDoesNotClaimCapability() {
        val client = CookWorkflowClient("https://example.test") { response(it, 404, "{}") }
        assertNull(client.capabilities())
    }

    @Test fun rosterAndManualCreationUseCorrectScopedEndpoints() {
        val paths = mutableListOf<String>()
        val client = CookWorkflowClient("https://example.test") { request ->
            paths.add(request.url.encodedPath)
            when (request.url.encodedPath) {
                "/cook-v2/employees" -> {
                    assertEquals("device-a", request.url.queryParameter("deviceUuid"))
                    response(request, 200, """{"branchRef":"a","employees":[{"employeeId":"c","displayName":"Повар","roles":["cook"]}]}""")
                }
                "/cook-v2/employees/manual" -> {
                    val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    assertTrue(body.contains("request-1"))
                    response(request, 200, """{"employeeId":"c","displayName":"Повар","roles":["cook"]}""")
                }
                else -> throw AssertionError(request.url.encodedPath)
            }
        }
        assertEquals("a", client.roster("device-a")!!.branchRef)
        assertEquals("c", client.addManual("device-a", "request-1", "Повар", listOf("cook"))!!.id)
        assertEquals(listOf("/cook-v2/employees", "/cook-v2/employees/manual"), paths)
    }

    @Test fun unknownDeviceDoesNotLookLikeEmptyRoster() {
        val client = CookWorkflowClient("https://example.test") { response(it, 404, "{}") }
        try {
            client.roster("unknown")
            fail("Expected failure")
        } catch (_: IOException) { }
    }

    @Test fun shiftRequestRetainsStableIdentityAndApiMustExplicitlySupportContext() {
        val requests = mutableListOf<String>()
        val client = CookWorkflowClient("https://example.test") { request ->
            when (request.url.encodedPath) {
                "/cook-v2/capabilities" -> response(request, 200,
                    """{"schemaVersion":1,"roster":true,"shift":true,"photoContext":false}""")
                "/cook-v2/shifts" -> {
                    requests.add(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                    response(request, 200, """{"shiftId":"shift-1","shiftRevision":2,"branchRef":"a"}""")
                }
                else -> throw AssertionError(request.url.encodedPath)
            }
        }
        assertFalse(client.capabilities()!!.supportsAttributedCapture)
        val shift = CookShift("shift-1", 1, "a", "2026-11-05", "Europe/Moscow",
            "2026-11-05T11:00:00+03:00", listOf("cook-1"), listOf("operator-1"), "cook-1")
        assertEquals("shift-1" to 2, client.confirmShift("device-a", "request-1", shift))
        assertTrue(requests.single().contains("\"requestId\":\"request-1\""))
        assertTrue(requests.single().contains("\"mainCookId\":\"cook-1\""))
    }

    @Test fun photoContextRequiresMatchingExplicitAcknowledgement() {
        var valid = true
        val client = CookWorkflowClient("https://example.test") { request ->
            assertEquals("PUT", request.method)
            assertEquals("/cook-v2/photo-context", request.url.encodedPath)
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertTrue(body.contains("\"clientUploadId\":\"photo-1\""))
            val id = if (valid) "photo-1" else "another"
            response(request, 200, """{"clientUploadId":"$id","schemaVersion":1,"photoContextStatus":"stored","resolution":"unresolved"}""")
        }
        assertEquals("unresolved", client.syncPhotoContext("device-a", "photo-1", "{\"schemaVersion\":1}"))
        valid = false
        try {
            client.syncPhotoContext("device-a", "photo-1", "{\"schemaVersion\":1}")
            fail("Wrong acknowledgement must fail")
        } catch (_: IOException) { }
    }

    @Test fun initializesExistingBindingWithoutSendingCity() {
        val client = CookWorkflowClient("https://example.test") { request ->
            assertEquals("POST", request.method)
            assertEquals("/cook-v2/binding/initialize", request.url.encodedPath)
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertTrue(body.contains("\"deviceUuid\":\"device-a\""))
            assertFalse(body.contains("city"))
            response(request, 200, """{"branchRef":"a","bindingRevision":2}""")
        }
        assertEquals(CookBinding("a", 2), client.initializeBinding("device-a"))
    }

    @Test fun provisionalEmployeeRequiresExplicitMatchingAcknowledgement() {
        val provisional = "11111111-1111-4111-8111-111111111111"
        var acknowledgement = true
        val client = CookWorkflowClient("https://example.test") { request ->
            val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertEquals(provisional, body.getString("provisionalEmployeeId"))
            assertEquals("a", body.getString("branchRef"))
            assertEquals(2, body.getInt("bindingRevision"))
            val result = JSONObject().put("employeeId", "canonical").put("displayName", "Повар")
                .put("roles", org.json.JSONArray(listOf("cook"))).put("branchRef", "a")
            if (acknowledgement) result.put("provisionalEmployeeId", provisional).put("bindingRevision", 2)
            response(request, 200, result.toString())
        }
        val binding = CookBinding("a", 2)
        assertEquals("canonical", client.addManual("device", "request", "Повар", listOf("cook"), binding, provisional)!!.id)
        acknowledgement = false
        try {
            client.addManual("device", "request", "Повар", listOf("cook"), binding, provisional)
            fail("A 2xx from an old server must not acknowledge the mapping")
        } catch (_: IOException) { }
        try {
            client.addManual("device", "request", "Повар", listOf("cook"), null, provisional)
            fail("A provisional identity requires a binding")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun provisionalShiftRequiresMatchingBindingAndShiftIdentity() {
        val provisional = "22222222-2222-4222-8222-222222222222"
        var returnedShift = "shift-1"
        var returnedRevision = 2
        val client = CookWorkflowClient("https://example.test") { request ->
            val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertEquals(provisional, body.getString("provisionalShiftId"))
            response(request, 200, JSONObject().put("shiftId", returnedShift).put("shiftRevision", 1)
                .put("branchRef", "a").put("bindingRevision", returnedRevision).put("provisionalShiftId", provisional).toString())
        }
        val shift = CookShift("shift-1", 1, "a", "2026-11-05", "Europe/Moscow",
            "2026-11-05T11:00:00+03:00", listOf("cook"), listOf("operator"), "cook")
        val binding = CookBinding("a", 2)
        assertEquals("shift-1" to 1, client.confirmShift("device", "request", shift, binding, provisional))
        returnedShift = "different"
        try {
            client.confirmShift("device", "request", shift, binding, provisional)
            fail("A different shift must not be accepted")
        } catch (_: IOException) { }
        returnedShift = "shift-1"
        returnedRevision = 3
        try {
            client.confirmShift("device", "request", shift, binding, provisional)
            fail("A changed binding must not be accepted")
        } catch (_: IOException) { }
    }
}
