package com.example.simplephotouploader

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

data class CookCapabilities(val roster: Boolean, val shift: Boolean, val photoContext: Boolean) {
    val supportsAttributedCapture: Boolean get() = roster && shift && photoContext
}
data class CookEmployee(val id: String, val displayName: String, val roles: List<String>)
data class CookRoster(val branchRef: String, val employees: List<CookEmployee>)
data class CookBinding(val branchRef: String, val revision: Int)
data class CookShift(val id: String, val revision: Int, val branchRef: String, val operationalDate: String,
                     val timezone: String, val confirmedAt: String, val cooks: List<String>,
                     val operators: List<String>, val mainCookId: String)

/** No call here implies photo metadata was accepted; capability and acknowledgement are separate. */
class CookWorkflowClient(baseUrl: String, private val execute: (Request) -> Response = { PhotoApi.execute(it) }) {
    private val base = baseUrl.trimEnd('/').toHttpUrl()

    private fun request(path: String, params: Map<String, String> = emptyMap(), body: JSONObject? = null,
                        method: String = "POST"): JSONObject? {
        val builder = base.newBuilder().addPathSegment("cook-v2")
        path.split('/').forEach { builder.addPathSegment(it) }
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val request = Request.Builder().url(builder.build()).apply {
            if (body != null) method(method, body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        }.build()
        execute(request).use { response ->
            if (response.code == 404 && path == "capabilities") return null
            if (!response.isSuccessful) throw IOException("Cook API HTTP ${response.code}")
            val raw = response.body?.string() ?: throw IOException("Cook API response missing")
            return try { JSONObject(raw) } catch (error: Exception) { throw IOException("Invalid Cook API response", error) }
        }
    }

    fun capabilities(): CookCapabilities? = request("capabilities")?.let {
        if (it.optInt("schemaVersion") != 1) throw IOException("Unsupported Cook API schema")
        CookCapabilities(it.optBoolean("roster"), it.optBoolean("shift"), it.optBoolean("photoContext"))
    }

    fun initializeBinding(deviceId: String): CookBinding {
        val json = request("binding/initialize", body = JSONObject().put("deviceUuid", deviceId))
            ?: throw IOException("Missing device binding")
        val branch = json.optString("branchRef")
        val revision = json.optInt("bindingRevision")
        if (branch.isBlank() || revision < 1) throw IOException("Invalid device binding")
        return CookBinding(branch, revision)
    }

    fun roster(deviceId: String): CookRoster? = request("employees", mapOf("deviceUuid" to deviceId))?.let { json ->
        val employees = json.getJSONArray("employees")
        CookRoster(json.getString("branchRef"), (0 until employees.length()).map { index ->
            val entry = employees.getJSONObject(index)
            CookEmployee(entry.getString("employeeId"), entry.getString("displayName"), strings(entry.getJSONArray("roles")))
        })
    }

    fun addManual(deviceId: String, requestId: String, name: String, roles: List<String>): CookEmployee? =
        request("employees/manual", body = JSONObject().put("deviceUuid", deviceId).put("requestId", requestId)
            .put("displayName", name).put("roles", JSONArray(roles)))?.let {
            CookEmployee(it.getString("employeeId"), it.getString("displayName"), strings(it.getJSONArray("roles")))
        }

    fun confirmShift(deviceId: String, requestId: String, shift: CookShift): Pair<String, Int>? =
        request("shifts", body = JSONObject().put("deviceUuid", deviceId).put("requestId", requestId)
            .put("shiftId", shift.id).put("operationalDate", shift.operationalDate).put("timezone", shift.timezone)
            .put("confirmedAt", shift.confirmedAt).put("cooks", JSONArray(shift.cooks))
            .put("operators", JSONArray(shift.operators)).put("mainCookId", shift.mainCookId))?.let {
            it.getString("shiftId") to it.getInt("shiftRevision")
        }

    fun shifts(deviceId: String, day: String): List<CookShift>? =
        request("shifts", mapOf("deviceUuid" to deviceId, "operationalDate" to day))?.let { json ->
            val array = json.getJSONArray("shifts")
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                CookShift(item.getString("shiftId"), item.getInt("shiftRevision"), item.getString("branchRef"),
                    item.getString("operationalDate"), item.getString("timezone"), item.getString("confirmedAt"),
                    strings(item.getJSONArray("cooks")), strings(item.getJSONArray("operators")), item.getString("mainCookId"))
            }
        }

    fun syncPhotoContext(deviceId: String, uploadId: String, photoContext: String): String {
        val context = try { JSONObject(photoContext) } catch (error: Exception) {
            throw IOException("Invalid saved photo context", error)
        }
        val acknowledgement = request("photo-context", body = JSONObject().put("deviceUuid", deviceId)
            .put("clientUploadId", uploadId).put("photoContext", context), method = "PUT")
            ?: throw IOException("Missing photo context acknowledgement")
        if (acknowledgement.optString("clientUploadId") != uploadId || acknowledgement.optInt("schemaVersion") != 1 ||
            acknowledgement.optString("photoContextStatus") != "stored") {
            throw IOException("Photo context was not acknowledged")
        }
        val resolution = acknowledgement.optString("resolution")
        if (resolution !in setOf("resolved", "unresolved", "not_applicable")) {
            throw IOException("Invalid photo context resolution")
        }
        return resolution
    }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }
}
