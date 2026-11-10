package com.example.simplephotouploader

import android.os.SystemClock
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID

class ConnectionTrace(val operation: String, val endpoint: String) : EventListener() {
    val id: String = UUID.randomUUID().toString().replace("-", "").take(8)
    private val start = SystemClock.elapsedRealtime()
    @Volatile var phase = "connect"
        private set
    private var recorded = false

    fun stage(value: String) {
        phase = value
        DiagnosticJournal.stage(operation, phase, endpoint, id)
    }
    fun finish(http: Int = 0, error: String = "") {
        if (recorded) return
        recorded = true
        DiagnosticJournal.complete(id)
        if (operation == "support" && error.isEmpty() && http < 400) return
        DiagnosticJournal.record(App.instance, operation, phase, endpoint, id,
            SystemClock.elapsedRealtime() - start, http, error)
    }
    override fun callStart(call: Call) {
        stage("connect")
        if (operation != "support") DiagnosticJournal.record(App.instance, operation, "start", endpoint, id)
    }
    override fun dnsStart(call: Call, domainName: String) = stage("dns")
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = stage("connect")
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = stage("connect")
    override fun secureConnectStart(call: Call) = stage("tls")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = stage("request")
    override fun connectionAcquired(call: Call, connection: okhttp3.Connection) = stage("request")
    override fun requestBodyStart(call: Call) = stage("upload")
    override fun requestBodyEnd(call: Call, byteCount: Long) = stage("response")
    override fun responseHeadersStart(call: Call) = stage("response")
    override fun responseBodyStart(call: Call) = stage("response")
    override fun callFailed(call: Call, ioe: IOException) = finish(error = ConnectionPolicy.error(ioe, phase))
}
