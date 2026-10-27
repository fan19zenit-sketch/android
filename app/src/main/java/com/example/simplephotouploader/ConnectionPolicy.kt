package com.example.simplephotouploader

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

object ConnectionPolicy {
    const val RESERVE_API = "https://app.sushigohub.ru/photo-api"
    const val RESERVE_SUPPORT = "https://app.sushigohub.ru/photo-support"

    fun apiAddresses(url: String, method: String): List<String> {
        val bases = listOf(AppPrefs.DEFAULT_BACKEND_URL, RESERVE_API)
        val base = bases.firstOrNull { url.startsWith("$it/") } ?: return listOf(url)
        val suffix = url.removePrefix(base)
        val job = "/upload-jobs/[0-9a-fA-F-]{36}"
        val allowed = when (method) {
            "POST" -> suffix in setOf("/register-device", "/upload-v2")
            "GET" -> Regex(job).matches(suffix)
            "DELETE" -> Regex("$job/chat-message").matches(suffix)
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
