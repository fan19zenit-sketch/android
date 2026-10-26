package com.example.simplephotouploader

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object PhotoApi {
    @Volatile private var preferredBase: String? = null
    @Volatile private var preferredAt: Long = 0
    val client: OkHttpClient = OkHttpClient.Builder()
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .eventListenerFactory { it.request().tag(ConnectionTrace::class.java) ?: okhttp3.EventListener.NONE }
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("X-App-Version", BuildConfig.VERSION_NAME)
                .header("X-App-Version-Code", BuildConfig.VERSION_CODE.toString())
                .header("X-App-Package", BuildConfig.APPLICATION_ID)
                .header("User-Agent", "PhotoVChistotu/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                .build())
        }.build()

    fun execute(request: Request, transport: OkHttpClient = client): Response {
        val url = request.url.toString()
        val official = listOf(AppPrefs.DEFAULT_BACKEND_URL, ConnectionPolicy.RESERVE_API)
        val base = official.firstOrNull { url.startsWith("$it/") }
        val operation = when {
            request.url.encodedPath.endsWith("/register-device") -> "registration"
            request.url.encodedPath.endsWith("/upload-v2") -> "upload"
            request.method == "DELETE" -> "delete"
            else -> "status"
        }
        val available = ConnectionPolicy.apiAddresses(url, request.method)
        val preferred = preferredBase.takeIf { android.os.SystemClock.elapsedRealtime() - preferredAt < 300000 }
        val attempts = if (base == null) listOf(url) else
            available.sortedBy { if (preferred != null && it.startsWith("$preferred/")) 0 else 1 }
        var failure: IOException? = null
        for ((index, target) in attempts.withIndex()) {
            val trace = ConnectionTrace(operation, when {
                base == null -> "custom"
                target.startsWith(AppPrefs.DEFAULT_BACKEND_URL) -> "primary"
                else -> "reserve"
            })
            try {
                val actual = request.newBuilder().url(target).tag(ConnectionTrace::class.java, trace)
                    .header("X-Client-Trace", trace.id).build()
                // Registration has a shorter budget than large photo transfers.
                val active = if (operation == "registration") transport.newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                    .callTimeout(25, TimeUnit.SECONDS).build() else transport
                val response = active.newCall(actual).execute()
                try {
                    val source = response.body?.source()
                    source?.request(65537)
                    if (source != null && source.buffer.size > 65536) throw IOException("response_too_large")
                    val bytes = source?.readByteArray() ?: byteArrayOf()
                    val buffered = response.newBuilder().body(bytes.toResponseBody(response.body?.contentType())).build()
                    trace.finish(response.code, if (response.isSuccessful) "" else "http_${response.code}")
                    if (response.code in setOf(502, 503, 504) && index < attempts.lastIndex) {
                        buffered.close()
                        continue
                    }
                    if (base != null && response.isSuccessful) {
                        preferredBase = official.first { target.startsWith("$it/") }
                        preferredAt = android.os.SystemClock.elapsedRealtime()
                    }
                    return buffered
                } finally { response.close() }
            } catch (error: IOException) {
                val code = ConnectionPolicy.error(error, trace.phase)
                trace.finish(error = code)
                failure = IOException(ConnectionPolicy.message(code, trace.id), error)
            }
        }
        throw failure ?: IOException("connection_failed")
    }
}
