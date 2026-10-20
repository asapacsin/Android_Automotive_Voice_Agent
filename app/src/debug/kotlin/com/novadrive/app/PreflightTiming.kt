package com.novadrive.app

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P53 debug split of the warm place search. Text-free (I-8): only the URL path is logged, never the
 * query string, host, key or coordinates.
 */
object PreflightTiming {
    data class CallTiming(
        val dnsMs: Long = 0,
        val connectMs: Long = 0,
        val tlsMs: Long = 0,
        val ttfbMs: Long = 0,
        val bodyMs: Long = 0,
        val reused: Boolean = false,
        /** Negotiated protocol as OkHttp names it (http/1.1, h2, ...); "unknown" without a response. */
        val protocol: String = "unknown",
        /** Response header Connection equals close (case-insensitive): the server ends the connection. */
        val connClose: Boolean = false,
        /** Ms since the previous call on this recorder ended; -1 for the first. */
        val gapMs: Long = -1,
    )

    fun pathOnly(url: String): String = url.toHttpUrlOrNull()?.encodedPath ?: "unknown"

    fun splitLine(call: Int, url: String, timing: CallTiming): String =
        "preflight_split call=$call path=${pathOnly(url)} dns_ms=${timing.dnsMs} connect_ms=${timing.connectMs} " +
            "tls_ms=${timing.tlsMs} ttfb_ms=${timing.ttfbMs} body_ms=${timing.bodyMs} reused=${timing.reused} " +
            "protocol=${timing.protocol} conn_close=${timing.connClose} gap_ms=${timing.gapMs}"

    fun warmLine(runsMs: List<Long>): String = "preflight_search_warm runs=${runsMs.size} ms=${runsMs.joinToString(",")}"

    /** Collects one [CallTiming] per call while [recording] is true. */
    class Recorder {
        @Volatile var recording = false
        val calls = CopyOnWriteArrayList<Pair<String, CallTiming>>()
        @Volatile private var lastEnd = 0L

        fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
            .eventListenerFactory { call -> if (recording) Listener(call.request().url.toString()) else EventListener.NONE }
            .build()

        private inner class Listener(private val url: String) : EventListener() {
            private var dnsStart = 0L; private var dns = 0L
            private var connStart = 0L; private var conn = 0L
            private var tlsStart = 0L; private var tls = 0L
            private var reqEnd = 0L; private var ttfb = 0L
            private var bodyStart = 0L; private var body = 0L
            private var connected = false
            private var gap = -1L
            private var protocol = "unknown"
            private var connClose = false
            private fun now() = System.nanoTime()
            private fun ms(n: Long) = n / 1_000_000

            override fun callStart(call: Call) { val last = lastEnd; if (last != 0L) gap = ms(now() - last) }
            override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
                protocol = response.protocol.toString()
                connClose = response.header("Connection")?.trim()?.equals("close", ignoreCase = true) == true
            }
            override fun dnsStart(call: Call, domainName: String) { dnsStart = now() }
            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { dns += now() - dnsStart }
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { connStart = now(); connected = true }
            override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: okhttp3.Protocol?) { conn += now() - connStart }
            override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: okhttp3.Protocol?, ioe: java.io.IOException) { conn += now() - connStart }
            override fun secureConnectStart(call: Call) { tlsStart = now() }
            override fun secureConnectEnd(call: Call, handshake: Handshake?) { tls += now() - tlsStart }
            override fun requestHeadersEnd(call: Call, request: okhttp3.Request) { reqEnd = now() }
            override fun requestBodyEnd(call: Call, byteCount: Long) { reqEnd = now() }
            override fun responseHeadersStart(call: Call) { if (reqEnd != 0L) ttfb = now() - reqEnd }
            override fun responseBodyStart(call: Call) { bodyStart = now() }
            override fun responseBodyEnd(call: Call, byteCount: Long) { if (bodyStart != 0L) body = now() - bodyStart }
            override fun connectionAcquired(call: Call, connection: Connection) {}
            override fun callEnd(call: Call) = record()
            override fun callFailed(call: Call, ioe: java.io.IOException) = record()

            private fun record() {
                // Connect time includes TLS in OkHttp's events; report them separately.
                calls += url to CallTiming(ms(dns), ms(conn - tls).coerceAtLeast(0), ms(tls), ms(ttfb), ms(body), reused = !connected, protocol = protocol, connClose = connClose, gapMs = gap)
                lastEnd = now()
            }
        }
    }
}
