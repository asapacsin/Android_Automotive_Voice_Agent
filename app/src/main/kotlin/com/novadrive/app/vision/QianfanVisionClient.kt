package com.novadrive.app.vision

import com.novadrive.app.voice.BaiduProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Supplies the `Authorization: Bearer` value, or null when nothing is configured. */
fun interface VisionAuth {
    suspend fun bearer(): String?
}

/**
 * Baidu Qianfan v2 (OpenAI-compatible) chat completions with an image part.
 *
 * Privacy: the image leaves the phone only when the driver explicitly asks about the camera
 * view. Neither the image nor the answer is logged here.
 */
class QianfanVisionClient(
    private val auth: VisionAuth,
    private val model: () -> String,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val requireTls: Boolean = true,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) : VisionPort {
    override suspend fun ask(question: String, jpeg: ByteArray): VisionResult = withContext(Dispatchers.IO) {
        val url = endpoint.toHttpUrlOrNull()
            ?: return@withContext VisionResult.NotConfigured("invalid vision endpoint")
        if (requireTls && !url.isHttps) return@withContext VisionResult.NotConfigured("vision endpoint must use HTTPS")
        val bearer = try {
            auth.bearer()
        } catch (failure: Exception) {
            return@withContext VisionResult.AuthFailed(failure.message ?: "credential lookup failed")
        }
        if (bearer.isNullOrBlank()) {
            return@withContext VisionResult.NotConfigured("no Baidu credential for the vision model")
        }
        val body = requestBody(model(), question, jpeg)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .post(body.toRequestBody(JSON))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                parse(response.code, response.body?.string().orEmpty())
            }
        } catch (failure: Exception) {
            VisionResult.Failed("network: ${failure.javaClass.simpleName}")
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://qianfan.baidubce.com/v2/chat/completions"
        const val DEFAULT_MODEL = "ernie-4.5-turbo-vl-32k"
        const val MAX_ANSWER_CHARS = 300
        private val JSON = "application/json; charset=utf-8".toMediaType()

        const val SYSTEM_PROMPT =
            "你是车载助手的视觉模块。只根据图片回答，用简体中文，一到两句话，不超过60个字。" +
                "看不清或图片里没有就直接说，不要编造。"

        fun requestBody(model: String, question: String, jpeg: ByteArray): String {
            val dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", question))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl)))
            return JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        .put(JSONObject().put("role", "user").put("content", content)),
                )
                .put("temperature", 0.2)
                .put("max_tokens", 200)
                .toString()
        }

        /** Handles both the v2 (`error{code,message}`) and legacy (`error_code/error_msg`) error shapes. */
        fun parse(httpCode: Int, body: String): VisionResult {
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return VisionResult.Failed("HTTP $httpCode: response was not JSON")
            val error = json.optJSONObject("error")
            val errorCode = error?.optString("code").orEmpty()
                .ifBlank { json.opt("error_code")?.toString().orEmpty() }
            val errorMessage = BaiduProtocol.sanitize(
                error?.optString("message").orEmpty().ifBlank { json.optString("error_msg") },
            )
            if (httpCode == 401 || httpCode == 403 || isAuthCode(errorCode, errorMessage)) {
                return VisionResult.AuthFailed("HTTP $httpCode $errorCode $errorMessage".trim())
            }
            if (httpCode !in 200..299 || errorCode.isNotBlank()) {
                return VisionResult.Failed("HTTP $httpCode $errorCode $errorMessage".trim())
            }
            val text = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
                .trim()
            if (text.isBlank()) return VisionResult.Failed("HTTP $httpCode: empty answer")
            return VisionResult.Answer(text.take(MAX_ANSWER_CHARS))
        }

        /** 110/111 are the legacy "access token invalid/expired" codes. */
        private val AUTH_CODES = setOf("110", "111", "invalid_iam_token", "unauthorized", "invalid_api_key")

        private fun isAuthCode(code: String, message: String): Boolean {
            if (code.lowercase() in AUTH_CODES) return true
            val lower = message.lowercase()
            return listOf("access token", "authentication", "api key").any { it in lower }
        }
    }
}
