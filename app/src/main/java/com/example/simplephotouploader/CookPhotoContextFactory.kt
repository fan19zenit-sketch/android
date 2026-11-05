package com.example.simplephotouploader

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import org.json.JSONObject

/** Builds the immutable data attached to one capture, not to an upload attempt. */
object CookPhotoContextFactory {
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
}
