package com.example.simplephotouploader

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ConnectionPolicyTest {
    @Test fun failoverIsOnlyForKnownHttpsRoutesAndNeverForQueryStringsOrCustomServers() {
        val primary = AppPrefs.DEFAULT_BACKEND_URL
        assertEquals(listOf("$primary/upload-v2", "${ConnectionPolicy.RESERVE_API}/upload-v2"),
            ConnectionPolicy.apiAddresses("$primary/upload-v2", "POST"))
        val id = "60c4be62-4a2b-485d-964b-52d02c6a88cc"
        assertEquals(2, ConnectionPolicy.apiAddresses("$primary/upload-jobs/$id/chat-message", "DELETE").size)
        for (url in listOf("$primary/admin", "$primary/upload-v2?token=private",
            "http://194.55.235.241:8000/upload-v2", "http://127.0.0.1:1234/upload-v2",
            "https://194-55-235-241.sslip.io@evil.test/mobile-api/upload-v2")) {
            assertEquals(listOf(url), ConnectionPolicy.apiAddresses(url, "POST"))
        }
    }
    @Test fun errorIncludesFailurePhaseWithoutRawExceptionOrAddress() {
        assertEquals("dns_failed", ConnectionPolicy.error(UnknownHostException("private-host"), "dns"))
        assertEquals("tls_failed", ConnectionPolicy.error(SSLHandshakeException("secret"), "tls"))
        assertEquals("upload_timeout", ConnectionPolicy.error(SocketTimeoutException("private path"), "upload"))
        assertEquals("response_timeout", ConnectionPolicy.error(SocketTimeoutException(), "response"))
        assertFalse(ConnectionPolicy.message("tls_failed", "01234567").contains("secret"))
    }
}
