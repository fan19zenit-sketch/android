package com.example.simplephotouploader

import java.net.URI

data class SupportSettings(
    val volumeCapture: Boolean = true,
    val retrySeconds: Long = 30,
    val uploadsPaused: Boolean = false,
)

object SupportPolicy {
    fun settings(volume: Boolean, retry: Long, paused: Boolean, ttl: Long): SupportSettings {
        if (ttl !in 1..86400 || retry !in 30..300 || (paused && ttl > 900)) return SupportSettings()
        return SupportSettings(volume, retry, paused)
    }

    fun cacheValid(received: Long, now: Long, ttl: Long, boot: Int, currentBoot: Int): Boolean =
        boot >= 0 && boot == currentBoot && ttl in 1..86400 && now >= received && now - received < ttl * 1000

    fun allowedDownload(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.rawQuery == null && uri.rawFragment == null &&
            ((uri.rawAuthority == "194-55-235-241.sslip.io" &&
                Regex("/downloads/[A-Za-z0-9._-]+\\.apk").matches(uri.rawPath)) ||
             (uri.rawAuthority == "app.sushigohub.ru" &&
                Regex("/photo-downloads/[A-Za-z0-9._-]+\\.apk").matches(uri.rawPath)))
    }.getOrDefault(false)

    fun downloadAddresses(url: String): List<String> {
        if (!allowedDownload(url)) return emptyList()
        val primary = "https://194-55-235-241.sslip.io/downloads/"
        val reserve = "https://app.sushigohub.ru/photo-downloads/"
        return if (url.startsWith(primary)) listOf(url, reserve + url.removePrefix(primary))
        else listOf(url, primary + url.removePrefix(reserve))
    }
}
