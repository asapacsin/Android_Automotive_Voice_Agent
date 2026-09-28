package com.novadrive.app.nav.amap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.novadrive.app.DebugVoiceLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches Amap Web Service static map pictures for the translated-ABI text panel. One request at
 * a time; while one is in flight only the newest pending URL is kept, older ones are dropped.
 *
 * The URL holds the Web key and coordinates: it is never logged. Logs are `static_map ok
 * bytes=N` or `static_map error=<http_NNN|api_INFOCODE|io|decode>` only.
 */
internal class StaticMapFetcher(private val onResult: (Bitmap?, String?) -> Unit) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "nova-static-map") }
    private val pending = AtomicReference<String?>(null)

    fun request(url: String) {
        if (pending.getAndSet(url) == null) executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val url = pending.get() ?: return
            val (bitmap, error) = fetch(url)
            onResult(bitmap, error)
            if (pending.compareAndSet(url, null)) return
        }
    }

    private fun fetch(url: String): Pair<Bitmap?, String?> {
        val error = runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) return@use "http_${response.code}"
                val type = response.header("Content-Type").orEmpty()
                if (type.contains("json") || type.contains("text")) {
                    val code = runCatching { JSONObject(String(bytes)).optString("infocode") }.getOrNull()
                    return@use "api_${code ?: "unknown"}"
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use "decode"
                DebugVoiceLog.log("static_map ok bytes=${bytes.size}")
                return bitmap to null
            }
        }.getOrElse { "io" }
        DebugVoiceLog.log("static_map error=$error")
        return null to error
    }
}
