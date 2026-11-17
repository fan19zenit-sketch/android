package com.example.simplephotouploader

import org.junit.Assert.*
import org.junit.Test

class CookCaptureSelectionTest {
    @Test fun oneShotIsConsumedOnlyAfterPersistence() {
        val state = CookCaptureSelection()
        state.setRoster(setOf("main", "second"), "main")
        state.selectOneShot("second")
        val first = state.reserve()!!
        assertEquals("second", first.cookId)
        assertNull(state.reserve())
        state.finish(first, persisted = false)
        val retry = state.reserve()!!
        assertEquals("second", retry.cookId)
        state.finish(retry, persisted = true)
        assertEquals("main", state.reserve()!!.cookId)
    }

    @Test fun changedRosterDuringCaptureDoesNotOverwriteNewSelection() {
        val state = CookCaptureSelection()
        state.setRoster(setOf("a", "b"), "a")
        state.selectOneShot("b")
        val first = state.reserve()!!
        state.setRoster(setOf("c", "d"), "c")
        state.selectOneShot("d")
        state.finish(first, persisted = true)
        assertEquals("d", state.reserve()!!.cookId)
    }

    @Test fun cameraRosterRefreshWaitsForCaptureAndPreservesCurrentChoices() {
        val state = CookCaptureSelection()
        state.setRoster(setOf("main", "persistent", "once"), "main")
        state.selectPersistent("persistent")
        state.selectOneShot("once")
        val reservation = state.reserve()!!
        val updated = setOf("main", "persistent", "once", "new")
        assertFalse(state.canRefreshRoster(updated, "main"))
        assertEquals("once", reservation.cookId)
        state.finish(reservation, persisted = false)
        assertFalse(state.canRefreshRoster(updated - "once", "main"))
        assertFalse(state.canRefreshRoster(updated - "persistent", "main"))
        state.refreshRoster(updated, "main")
        val retry = state.reserve()!!
        assertEquals("once", retry.cookId)
        state.finish(retry, persisted = true)
        assertEquals("persistent", state.reserve()!!.cookId)
    }

    @Test fun pizzaCookRemainsSelectedAcrossPhotosAndDoesNotRelabelAnInFlightCapture() {
        val state = CookCaptureSelection()
        state.setRoster(setOf("first", "second"), "first")
        val first = state.reserve()!!
        state.selectPersistent("second")
        state.finish(first, true)
        assertEquals("first", first.cookId)
        val second = state.reserve()!!
        assertEquals("second", second.cookId)
        assertEquals("one_shot", second.selectionSource)
        state.finish(second, true)
        val third = state.reserve()!!
        assertEquals("second", third.cookId)
        state.finish(third, true)
        state.selectPersistent("first")
        assertEquals("first", state.reserve()!!.cookId)
    }

    @Test fun singlePhotoCanUseOriginalCookWhileSecondCookIsPersistent() {
        val state = CookCaptureSelection()
        state.setRoster(setOf("first", "second"), "first")
        state.selectPersistent("second")
        state.selectOneShot("first")
        val single = state.reserve()!!
        assertEquals("first", single.cookId)
        assertEquals("main_default", single.selectionSource)
        state.finish(single, true)
        val next = state.reserve()!!
        assertEquals("second", next.cookId)
        assertEquals("one_shot", next.selectionSource)
    }

    @Test fun staleFinishDoesNotReleaseActiveCapture() {
        val state = CookCaptureSelection()
        state.setRoster(setOf("a"), "a")
        val old = state.reserve()!!
        state.finish(old, true)
        val active = state.reserve()!!
        state.finish(old, true)
        assertNull(state.reserve())
        state.finish(active, true)
        assertNotNull(state.reserve())
    }

    @Test fun unknownAndSharedAreExplicitAndResetAfterSavingOnly() {
        val state = CookCaptureSelection()
        state.setRoster(setOf("main", "second"), "main")
        state.selectUnassigned("shared")
        val first = state.reserve()!!
        assertNull(first.cookId)
        assertEquals("shared", first.selectionSource)
        state.finish(first, false)
        val retry = state.reserve()!!
        assertEquals("shared", retry.selectionSource)
        state.finish(retry, true)
        assertEquals("main", state.reserve()!!.cookId)
    }
}
