package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.SpeakingStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Azure AI Speech adapter for [AssistantVoice] (ADR-016): speaks one clause of Gemini's words in
 * the owner's chosen voice via the Azure TTS REST API, streaming raw 24 kHz mono PCM16LE.
 *
 * Exactly one adapter is wired at a time (ADR-008). The [SpeakingStyle] -> `mstts:express-as`
 * mapping lives here because the style names are Azure vendor vocabulary.
 *
 * Only whole 16-bit samples are delivered; an odd trailing byte is carried into the next chunk and,
 * if the stream ends on an odd byte, that last byte is dropped.
 */
class AzureSpeechVoice(
    private val config: AzureSpeechConfig,
    private val http: OkHttpClient = defaultHttp(),
    private val baseUrl: String = "https://${config.host}",
) : AssistantVoice {

    override suspend fun synthesize(
        text: String,
        style: SpeakingStyle,
        onPcm: suspend (ByteArray) -> Unit,
    ) {
        if (text.isBlank()) return
        val request = Request.Builder()
            .url("$baseUrl/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", config.key)
            .header("X-Microsoft-OutputFormat", "raw-24khz-16bit-mono-pcm")
            .header("User-Agent", "NovaDrive")
            .post(azureSsml(text, config.voice, style).toByteArray(Charsets.UTF_8).toRequestBody(SSML_MEDIA_TYPE))
            .build()
        val call = http.newCall(request)
        val cancelledByUs = java.util.concurrent.atomic.AtomicBoolean(false)
        // Barge-in must stop a blocked read now, not at the read timeout: a cancelled coroutine
        // cancels the call, which fails the read, and that failure is reported as the cancellation.
        coroutineScope {
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    cancelledByUs.set(true)
                    call.cancel()
                }
            }
            try {
                stream(call, text, onPcm) { cancelledByUs.get() }
            } finally {
                watcher.cancel()
            }
        }
    }

    private suspend fun stream(
        call: okhttp3.Call,
        text: String,
        onPcm: suspend (ByteArray) -> Unit,
        cancelledByUs: () -> Boolean,
    ) {
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw AssistantVoiceException(codeFor(response.code))
                    val source = response.body?.byteStream()
                        ?: throw AssistantVoiceException("AZURE_TTS_EMPTY")
                    val buf = ByteArray(READ_BUFFER)
                    var carry = -1
                    var total = 0L
                    var first = true
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = source.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        total += n
                        val avail = n + if (carry >= 0) 1 else 0
                        val even = avail - (avail % 2)
                        if (even > 0) {
                            val out = ByteArray(even)
                            var off = 0
                            if (carry >= 0) { out[0] = carry.toByte(); off = 1 }
                            System.arraycopy(buf, 0, out, off, even - off)
                            carry = if (avail % 2 == 1) buf[n - 1].toInt() and 0xFF else -1
                            if (first) {
                                first = false
                                DebugVoiceLog.log(
                                    "azure_tts_first_audio ms=${elapsedMs(start)} chars=${text.length}",
                                )
                            }
                            onPcm(out)
                        } else {
                            carry = buf[n - 1].toInt() and 0xFF
                        }
                    }
                    if (total == 0L) throw AssistantVoiceException("AZURE_TTS_EMPTY")
                    DebugVoiceLog.log("azure_tts_done bytes=$total ms=${elapsedMs(start)}")
                }
            } catch (e: AssistantVoiceException) {
                DebugVoiceLog.log("azure_tts_failed code=${e.code}")
                throw e
            } catch (e: IOException) {
                // Our own cancel (barge-in) closed the socket. The coroutine may not be marked
                // cancelling yet on this thread (measured race); a callTimeout also cancels the call, so
                // ask whether WE cancelled it.
                if (cancelledByUs()) throw CancellationException("assistant voice cancelled")
                coroutineContext.ensureActive()
                if (e is java.io.InterruptedIOException) {
                    // callTimeout or a stalled read: the lane must not wait on a trickling response.
                    DebugVoiceLog.log("azure_tts_failed code=AZURE_TTS_TIMEOUT")
                    throw AssistantVoiceException("AZURE_TTS_TIMEOUT", "AZURE_TTS_TIMEOUT")
                }
                DebugVoiceLog.log("azure_tts_failed code=AZURE_TTS_NETWORK")
                throw AssistantVoiceException("AZURE_TTS_NETWORK", "AZURE_TTS_NETWORK")
            }
        }
    }

    companion object {
        private const val READ_BUFFER = 4800
        const val CALL_TIMEOUT_S = 8L
        private val SSML_MEDIA_TYPE = "application/ssml+xml".toMediaType()

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            // A whole clause, first byte to last; normal synthesis of one clause is well under 2 s.
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

        private fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

        private fun codeFor(http: Int): String = when (http) {
            401, 403 -> "AZURE_TTS_AUTH"
            429 -> "AZURE_TTS_THROTTLED"
            400 -> "AZURE_TTS_BAD_REQUEST"
            else -> "AZURE_TTS_HTTP_$http"
        }
    }
}

/** ADR-016 style table: Azure `express-as` style and styledegree, or null for no wrapper. */
internal fun azureStyleFor(style: SpeakingStyle): Pair<String, String>? = when (style) {
    SpeakingStyle.DEFAULT -> null
    SpeakingStyle.SWEET -> "affectionate" to "1.2"
    SpeakingStyle.TSUNDERE -> "disgruntled" to "0.6"
    SpeakingStyle.GENTLE -> "gentle" to "1.0"
    SpeakingStyle.LIVELY -> "cheerful" to "1.2"
}

internal fun azureSsml(text: String, voice: String, style: SpeakingStyle): String {
    val escaped = xmlEscape(text)
    val body = azureStyleFor(style)?.let { (s, d) ->
        "<mstts:express-as style=\"$s\" styledegree=\"$d\">$escaped</mstts:express-as>"
    } ?: escaped
    return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" " +
        "xmlns:mstts=\"https://www.w3.org/2001/mstts\" xml:lang=\"zh-CN\">" +
        "<voice name=\"${xmlEscape(voice)}\">$body</voice></speak>"
}

private fun xmlEscape(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(c)
    }
}
