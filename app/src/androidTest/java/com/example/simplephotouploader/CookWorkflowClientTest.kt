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
}
