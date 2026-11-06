package com.example.simplephotouploader

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ConnectionPolicy {
    const val RESERVE_API = "https://app.sushigohub.ru/photo-api"
    const val RESERVE_SUPPORT = "https://app.sushigohub.ru/photo-support"

    fun apiAddresses(url: String, method: String): List<String> {
        val bases = listOf(AppPrefs.DEFAULT_BACKEND_URL, RESERVE_API)
        val base = bases.firstOrNull { url.startsWith("$it/") } ?: return listOf(url)
        val suffix = url.removePrefix(base)
        val path = suffix.substringBefore('?')
        val parsed = url.toHttpUrlOrNull() ?: return listOf(url)
        val expectedQuery = when (path) {
            "/cook-v2/employees", "/cook-v2/binding" -> setOf("deviceUuid")
            "/cook-v2/shifts" -> setOf("deviceUuid", "operationalDate")
            else -> emptySet()
        }
        val queryAllowed = if (method == "GET" && expectedQuery.isNotEmpty())
            parsed.queryParameterNames == expectedQuery && expectedQuery.all { parsed.queryParameterValues(it).size == 1 }
        else parsed.query == null
        val job = "/upload-jobs/[0-9a-fA-F-]{36}"
        val allowed = queryAllowed && when (method) {
            "POST" -> path in setOf("/register-device", "/upload-v2", "/cook-v2/upload",
                "/cook-v2/binding/initialize", "/cook-v2/employees/manual", "/cook-v2/shifts")
            "GET" -> Regex(job).matches(path) || path in setOf("/cook-v2/capabilities", "/cook-v2/binding",
                "/cook-v2/employees", "/cook-v2/shifts")
            "PUT" -> path == "/cook-v2/photo-context"
            "DELETE" -> Regex("$job/chat-message").matches(path)
            else -> false
        }
        return if (allowed) listOf(url, bases.first { it != base } + suffix) else listOf(url)
    }

    fun error(error: IOException, phase: String): String = when (error) {
        is UnknownHostException -> "dns_failed"
        is SSLPeerUnverifiedException -> "certificate_failed"
        is SSLException -> "tls_failed"
        is SocketTimeoutException -> "${phase}_timeout"
        is ConnectException -> "connect_failed"
        else -> "${phase}_interrupted"
    }

    fun message(code: String, trace: String): String {
        val explanation = when {
            code == "dns_failed" -> "Не удалось найти адрес сервера"
            code.startsWith("certificate") || code.startsWith("tls") -> "Не удалось установить защищённое соединение"
            code.startsWith("connect") -> "Телефон не смог подключиться к серверу"
            code.startsWith("upload") -> "Передача данных прервалась"
            code.startsWith("response") -> "Нет подтверждения от сервера"
            else -> "Соединение с сервером прервалось"
        }
        return "$explanation. Код: $code / $trace. Данные сохранены на телефоне."
    }
}
