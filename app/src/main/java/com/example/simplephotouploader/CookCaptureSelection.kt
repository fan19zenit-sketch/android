package com.example.simplephotouploader

/** Keeps a one-shot choice reserved until the corresponding photo is persisted locally. */
class CookCaptureSelection {
    class Reservation internal constructor(
        val cookId: String?,
        val selectionSource: String,
        internal val selectionRevision: Long,
        internal val captureId: Long,
    )

    private var mainCook: String? = null
    private var cooks: Set<String> = emptySet()
    private var oneShot: String? = null
    private var special: String? = null
    private var revision = 0L
    private var captureCounter = 0L
    private var activeCapture: Long? = null

    @Synchronized fun current(): String? = if (special != null) null else oneShot ?: mainCook
    @Synchronized fun currentSource(): String = special ?: if (oneShot == null) "main_default" else "one_shot"

    @Synchronized fun setRoster(cookIds: Set<String>, mainCookId: String) {
        require(mainCookId.isNotBlank() && mainCookId in cookIds)
        require(cookIds.none { it.isBlank() })
        cooks = cookIds.toSet()
        mainCook = mainCookId
        oneShot = null
        special = null
        revision++
    }

    @Synchronized fun selectOneShot(cookId: String) {
        require(cookId in cooks)
        oneShot = if (cookId == mainCook) null else cookId
        special = null
        revision++
    }

    @Synchronized fun selectUnassigned(source: String) {
        require(source in setOf("explicit_unknown", "shared") && mainCook != null)
        special = source
        oneShot = null
        revision++
    }

    @Synchronized fun reserve(): Reservation? {
        if (activeCapture != null || mainCook == null) return null
        captureCounter++
        activeCapture = captureCounter
        val chosen = if (special == null) oneShot ?: mainCook else null
        return Reservation(chosen, currentSource(), revision, captureCounter)
    }

    @Synchronized fun finish(reservation: Reservation, persisted: Boolean) {
        if (activeCapture != reservation.captureId) return
        activeCapture = null
        if (persisted && reservation.selectionSource != "main_default" && revision == reservation.selectionRevision) {
            oneShot = null
            special = null
            revision++
        }
    }
}
