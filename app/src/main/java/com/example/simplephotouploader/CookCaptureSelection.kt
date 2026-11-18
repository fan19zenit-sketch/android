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
    private var persistentCook: String? = null
    private var special: String? = null
    private var revision = 0L
    private var captureCounter = 0L
    private var activeCapture: Long? = null

    @Synchronized fun current(): String? = if (special != null) null else oneShot ?: persistentCook ?: mainCook
    @Synchronized fun currentSource(): String = special ?: if ((oneShot ?: persistentCook ?: mainCook) == mainCook) "main_default" else "one_shot"

    @Synchronized fun persistentAuthor(): String? = persistentCook

    @Synchronized fun canRefreshRoster(cookIds: Set<String>, mainCookId: String): Boolean =
        activeCapture == null && mainCook == mainCookId && mainCookId in cookIds &&
            listOfNotNull(oneShot, persistentCook).all { it in cookIds }

    @Synchronized fun refreshRoster(cookIds: Set<String>, mainCookId: String) {
        check(canRefreshRoster(cookIds, mainCookId))
        cooks = cookIds.toSet()
        revision++
    }

    @Synchronized fun setRoster(cookIds: Set<String>, mainCookId: String) {
        require(mainCookId.isNotBlank() && mainCookId in cookIds)
        require(cookIds.none { it.isBlank() })
        cooks = cookIds.toSet()
        mainCook = mainCookId
        oneShot = null
        persistentCook = null
        special = null
        revision++
    }

    @Synchronized fun selectPersistent(cookId: String) {
        require(cookId in cooks)
        persistentCook = if (cookId == mainCook) null else cookId
        oneShot = null
        special = null
        revision++
    }

    @Synchronized fun selectOneShot(cookId: String) {
        require(cookId in cooks)
        oneShot = if (cookId == (persistentCook ?: mainCook)) null else cookId
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
        val chosen = if (special == null) oneShot ?: persistentCook ?: mainCook else null
        return Reservation(chosen, currentSource(), revision, captureCounter)
    }

    @Synchronized fun finish(reservation: Reservation, persisted: Boolean) {
        if (activeCapture != reservation.captureId) return
        activeCapture = null
        if (persisted && (oneShot != null || special != null) && revision == reservation.selectionRevision) {
            oneShot = null
            special = null
            revision++
        }
    }
}
