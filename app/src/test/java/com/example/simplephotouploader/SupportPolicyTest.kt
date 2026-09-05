package com.example.simplephotouploader

import org.junit.Assert.*
import org.junit.Test

class SupportPolicyTest {
    @Test fun configurationHasSafeBounds() {
        assertEquals(SupportSettings(false, 60, true), SupportPolicy.settings(false, 60, true, 120))
        assertEquals(SupportSettings(), SupportPolicy.settings(false, 0, true, 120))
        assertEquals(SupportSettings(), SupportPolicy.settings(false, 301, true, 120))
        assertEquals(SupportSettings(), SupportPolicy.settings(false, 60, true, 901))
        assertEquals(SupportSettings(), SupportPolicy.settings(false, 60, false, 86401))
        assertEquals(SupportSettings(), SupportPolicy.settings(false, 60, true, 0))
    }

    @Test fun cacheExpiresAndResetsAfterReboot() {
        assertTrue(SupportPolicy.cacheValid(1000, 2000, 60, 10, 10))
        assertFalse(SupportPolicy.cacheValid(1000, 61000, 60, 10, 10))
        assertFalse(SupportPolicy.cacheValid(1000, 2000, 60, 10, 11))
        assertFalse(SupportPolicy.cacheValid(1000, 0, 60, 10, 10))
        assertFalse(SupportPolicy.cacheValid(1000, 2000, 60, -1, -1))
    }

    @Test fun onlyOfficialDownloadFilesAreAllowed() {
        val root = "https://194-55-235-241.sslip.io"
        assertTrue(SupportPolicy.allowedDownload("$root/downloads/photo-3.1.apk"))
        listOf("http://194-55-235-241.sslip.io/downloads/a.apk", "$root/downloads/../a.apk",
            "$root/downloads/%2e%2e/a.apk", "$root@evil.test/downloads/a.apk", "$root:443/downloads/a.apk",
            "$root/downloads/a.apk?x=1", "$root/downloads/a.apk#x", "https://evil.test/downloads/a.apk")
            .forEach { assertFalse(it, SupportPolicy.allowedDownload(it)) }
    }
}
