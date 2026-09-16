package com.novadrive.app.voice

import com.novadrive.app.BaiduCredentials
import com.novadrive.ingress.realtime.VoiceProviderException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BaiduAccessTokenClient(private val http: OkHttpClient) {
    @Volatile private var cached: CachedToken? = null

    suspend fun getToken(tokenEndpoint: String, credentials: BaiduCredentials): String {
        val now = System.currentTimeMillis()
        cached?.takeIf { now + TOKEN_SKEW_MS < it.expiresAtMs }?.let { return it.value }
        if (credentials.apiKey.isBlank() || credentials.secretKey.isBlank()) {
            throw VoiceProviderException("BAIDU_CREDENTIALS_MISSING", "Baidu API Key and Secret Key are required")
        }
        val base = tokenEndpoint.toHttpUrlOrNull()
            ?: throw VoiceProviderException("BAIDU_TOKEN_ENDPOINT_INVALID", "invalid Baidu token endpoint")
        if (!base.isHttps) throw VoiceProviderException("BAIDU_TLS_REQUIRED", "Baidu token endpoint requires HTTPS")
        val url = base.newBuilder()
            .setQueryParameter("grant_type", "client_credentials")
            .setQueryParameter("client_id", credentials.apiKey)
            .setQueryParameter("client_secret", credentials.secretKey)
            .build()
        val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody()).header("Accept", "application/json").build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, failure: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(BaiduRealtimeClient.mapFailure(failure, null))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            val body = it.body?.string().orEmpty()
                            if (!it.isSuccessful) throw VoiceProviderException("BAIDU_AUTH_FAILED", "Baidu authentication failed (HTTP ${it.code})")
                            val json = runCatching { JSONObject(body) }.getOrElse {
                                throw VoiceProviderException("BAIDU_AUTH_FAILED", "Baidu token response was invalid")
                            }
                            if (json.has("error")) throw VoiceProviderException("BAIDU_AUTH_FAILED", "Baidu authentication was rejected")
                            val token = json.optString("access_token")
                            val expires = json.optLong("expires_in", 0)
                            if (token.isBlank() || expires <= 0) throw VoiceProviderException("BAIDU_AUTH_FAILED", "Baidu token response was incomplete")
                            cached = CachedToken(token, now + expires * 1_000)
                            token
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (failure: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }
    }

    private data class CachedToken(val value: String, val expiresAtMs: Long)
    private companion object { const val TOKEN_SKEW_MS = 5 * 60 * 1_000L }
}
