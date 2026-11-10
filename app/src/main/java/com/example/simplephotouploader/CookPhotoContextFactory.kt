package com.example.simplephotouploader

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

data class CookOfflineRef(val kind: String, val id: String)

/** Builds the immutable data attached to one capture, not to an upload attempt. */
object CookPhotoContextFactory {
    fun createUnassigned(binding: CookBinding, source: String, capturedAt: Instant): String {
        require(binding.branchRef.isNotBlank() && binding.revision > 0)
        require(source in setOf("explicit_unknown", "shared"))
        val timezone = requireNotNull(binding.timezone)
        ZoneId.of(timezone)
        return JSONObject().put("schemaVersion", 1).put("capturedAt", capturedAt.toString())
            .put("timezone", timezone).put("branchRef", binding.branchRef).put("bindingRevision", binding.revision)
            .put("shiftRef", JSONObject.NULL).put("shiftRevision", JSONObject.NULL).put("cookRef", JSONObject.NULL)
            .put("cookNameSnapshot", JSONObject.NULL).put("selectionSource", source).put("rosterConfirmedAt", JSONObject.NULL)
            .toString()
    }

    fun create(
        binding: CookBinding,
        confirmedBinding: CookBinding,
        shift: CookShift,
        employee: CookEmployee?,
        selectionSource: String,
        capturedAt: Instant,
    ): String {
        require(binding == confirmedBinding && binding.branchRef.isNotBlank() && binding.revision > 0)
        require(shift.branchRef == binding.branchRef && shift.revision > 0 && shift.id.isNotBlank())
        require(binding.timezone == null || binding.timezone == shift.timezone)
        require(shift.cooks.isNotEmpty() && shift.operators.isNotEmpty() && shift.mainCookId in shift.cooks)
        require(shift.cooks.size == shift.cooks.toSet().size && shift.operators.size == shift.operators.toSet().size)
        val zone = ZoneId.of(shift.timezone)
        require(LocalDate.parse(shift.operationalDate) == capturedAt.atZone(zone).toLocalDate())
        require(!OffsetDateTime.parse(shift.confirmedAt).toInstant().isAfter(capturedAt))
        require(selectionSource in setOf("main_default", "one_shot", "explicit_unknown", "shared"))
        if (selectionSource in setOf("main_default", "one_shot")) {
            require(employee != null && employee.id in shift.cooks && employee.displayName.isNotBlank())
            require("cook" in employee.roles)
            require(selectionSource != "main_default" || employee.id == shift.mainCookId)
            require(selectionSource != "one_shot" || employee.id != shift.mainCookId)
        } else {
            require(employee == null)
        }
        val shiftRef = JSONObject().put("kind", "shift").put("id", shift.id)
        val cookRef = employee?.let { JSONObject().put("kind", "employee").put("id", it.id) }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("capturedAt", capturedAt.toString())
            .put("timezone", shift.timezone)
            .put("branchRef", binding.branchRef)
            .put("bindingRevision", binding.revision)
            .put("shiftRef", shiftRef)
            .put("shiftRevision", shift.revision)
            .put("cookRef", cookRef ?: JSONObject.NULL)
            .put("cookNameSnapshot", employee?.displayName ?: JSONObject.NULL)
            .put("selectionSource", selectionSource)
            .put("rosterConfirmedAt", shift.confirmedAt)
            .toString()
    }

    fun createProvisional(scope: CookOfflineScope, shift: OfflineCookShift, employee: CookEmployee?,
                          selectedRef: CookOfflineRef?, selectionSource: String, capturedAt: Instant): String {
        val binding = scope.binding
        require(shift.backendUrl == scope.backendUrl && shift.deviceUuid == scope.deviceUuid)
        require(shift.branchRef == binding.branchRef && shift.bindingRevision == binding.revision && binding.revision > 0)
        require(binding.timezone == null || binding.timezone == shift.timezone)
        CookOfflineValidation.shift(shift.copy(canonicalRevision = null))
        val zone = ZoneId.of(shift.timezone)
        require(LocalDate.parse(shift.operationalDate) == capturedAt.atZone(zone).toLocalDate())
        require(!OffsetDateTime.parse(shift.confirmedAt).toInstant().isAfter(capturedAt))
        require(selectionSource in setOf("main_default", "one_shot", "explicit_unknown", "shared"))
        val cooks = JSONArray(shift.cooksJson).let { array ->
            (0 until array.length()).map { ref(array.getJSONObject(it)) }
        }
        val main = ref(JSONObject(shift.mainCookJson))
        if (selectionSource in setOf("main_default", "one_shot")) {
            require(employee != null && selectedRef != null && selectedRef in cooks)
            require(employee.id == selectedRef.id && employee.displayName.isNotBlank() && "cook" in employee.roles)
            require(selectionSource != "main_default" || selectedRef == main)
            require(selectionSource != "one_shot" || selectedRef != main)
        } else {
            require(employee == null && selectedRef == null)
        }
        val cookRef = selectedRef?.let { JSONObject().put("kind", it.kind).put("id", it.id) }
        return JSONObject().put("schemaVersion", 1).put("capturedAt", capturedAt.toString())
            .put("timezone", shift.timezone).put("branchRef", binding.branchRef).put("bindingRevision", binding.revision)
            .put("shiftRef", JSONObject().put("kind", "provisional").put("id", shift.provisionalId))
            .put("shiftRevision", 1).put("cookRef", cookRef ?: JSONObject.NULL)
            .put("cookNameSnapshot", employee?.displayName ?: JSONObject.NULL)
            .put("selectionSource", selectionSource).put("rosterConfirmedAt", shift.confirmedAt).toString()
    }

    private fun ref(value: JSONObject) = CookOfflineRef(value.getString("kind"), value.getString("id"))
}
