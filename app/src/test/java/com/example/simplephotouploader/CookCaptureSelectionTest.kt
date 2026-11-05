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
