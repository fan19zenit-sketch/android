package com.example.simplephotouploader

import org.junit.Assert.*
import org.junit.Test

class CaptureGateTest {
    @Test fun onlyOneCaptureCanOwnTheGate() {
        val gate = CaptureGate()
        val first = gate.begin()!!
        assertNull(gate.begin())
        assertTrue(gate.busy)
        assertTrue(gate.finish(first))
        assertFalse(gate.busy)
    }

    @Test fun lateCallbackCannotUnlockANewerCapture() {
        val gate = CaptureGate()
        val old = gate.begin()!!
        assertTrue(gate.finish(old))
        val current = gate.begin()!!
        assertFalse(gate.finish(old))
        assertTrue(gate.busy)
        assertTrue(gate.finish(current))
    }
}
