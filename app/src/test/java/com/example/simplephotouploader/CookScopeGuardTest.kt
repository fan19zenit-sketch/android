package com.example.simplephotouploader

import org.junit.Assert.*
import org.junit.Test

class CookScopeGuardTest {
    @Test fun staleOrNotYetLoadedScopeCannotSupplyTheOldBinding() {
        val binding = CookBinding("branch", 1, "Europe/Moscow")
        val old = "server-a\u0000device-a\u0000city-a"
        assertEquals(binding, CookScopeGuard.binding(binding, old, old))
        assertNull(CookScopeGuard.binding(binding, null, old))
        for (changed in listOf("server-b\u0000device-a\u0000city-a", "server-a\u0000device-b\u0000city-a",
                               "server-a\u0000device-a\u0000city-b")) {
            assertNull(CookScopeGuard.binding(binding, old, changed))
            assertFalse(CookScopeGuard.matches(old, changed))
        }
    }
}
