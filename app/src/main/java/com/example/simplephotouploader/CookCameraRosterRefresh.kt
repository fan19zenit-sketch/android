package com.example.simplephotouploader

import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

internal fun CookShift.photoAuthors(operatorEnabled: Boolean, pizza: Boolean): List<String> =
    if (pizza) cooks else (cooks + (if (operatorEnabled) operators else emptyList()) + trainees).distinct()

internal object CookCameraRosterRefresh {
    fun newer(template: CookBranchShiftTemplate, shift: CookShift, now: Instant): Boolean {
        if (template.branchRef != shift.branchRef || template.operationalDate != shift.operationalDate ||
            template.timezone != shift.timezone || template.confirmedAt == null) return false
        val confirmed = OffsetDateTime.parse(template.confirmedAt).toInstant()
        return confirmed <= now && confirmed > OffsetDateTime.parse(shift.confirmedAt).toInstant()
    }

    fun withTemplate(shift: CookShift, template: CookBranchShiftTemplate): CookShift = shift.copy(
        confirmedAt = requireNotNull(template.confirmedAt), cooks = template.cooks.sorted(),
        operators = template.operators.sorted(), assistants = template.assistants.sorted(), trainees = template.trainees.sorted(),
    )

    // If the acknowledgement is lost, retry the identical shift revision, not a new write.
    fun requestId(shift: CookShift, template: CookBranchShiftTemplate): String = UUID.nameUUIDFromBytes(
        "bot-shift:${shift.id}:${template.revision}:${template.confirmedAt}:${shift.mainCookId}".toByteArray(Charsets.UTF_8)
    ).toString()
}
