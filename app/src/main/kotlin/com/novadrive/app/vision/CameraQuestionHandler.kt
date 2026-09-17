package com.novadrive.app.vision

import com.novadrive.app.DebugVoiceLog
import org.json.JSONObject

/**
 * 「看看前面有什么」: capture the current camera frame, ask the vision model, and return a tool
 * result the voice model can speak. Depends only on [CameraVisionSurface] and [VisionPort].
 *
 * Every failure is reported as `ok=false` with an instruction not to describe anything, so the
 * assistant can never invent what the camera shows.
 */
class CameraQuestionHandler(
    private val surface: () -> CameraVisionSurface?,
    private val vision: VisionPort,
) {
    data class Outcome(val ok: Boolean, val output: String, val spokenText: String, val errorCode: String?)

    suspend fun ask(rawQuestion: String?): Outcome {
        val question = rawQuestion?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_QUESTION_CHARS) ?: DEFAULT_QUESTION
        val camera = surface() ?: return failure("CAMERA_UNAVAILABLE", "摄像头不可用。")
        if (!camera.cameraPermitted()) {
            camera.requestCameraPermission()
            return failure("CAMERA_PERMISSION_DENIED", "需要相机权限，已经弹出授权，请允许后再问我一次。")
        }
        camera.showVisionText(LOOKING)
        val jpeg = camera.captureJpeg(MAX_EDGE_PX)
        if (jpeg == null || jpeg.isEmpty()) {
            return failure("NO_CAMERA_FRAME", "没有拿到摄像头画面。").also { camera.showVisionText(it.spokenText) }
        }
        DebugVoiceLog.log("vision_request bytes=${jpeg.size}")
        val outcome = when (val result = vision.ask(question, jpeg)) {
            is VisionResult.Answer -> Outcome(
                ok = true,
                output = JSONObject()
                    .put("ok", true)
                    .put("tool", TOOL)
                    .put("answer", result.text)
                    .put("instruction", "用一两句话把 answer 告诉用户，不要补充图片里没有的内容。")
                    .toString(),
                spokenText = result.text,
                errorCode = null,
            )
            is VisionResult.NotConfigured -> failure("VISION_NOT_CONFIGURED", "看图功能还没有配置好。", result.reason)
            is VisionResult.AuthFailed -> failure("VISION_AUTH_FAILED", "看图服务的密钥无效，需要在开发者设置里配置视觉 API Key。", result.detail)
            is VisionResult.Failed -> failure("VISION_REQUEST_FAILED", "看图服务暂时不可用。", result.detail)
        }
        DebugVoiceLog.log("vision_result ok=${outcome.ok} code=${outcome.errorCode ?: "-"}")
        camera.showVisionText(outcome.spokenText)
        return outcome
    }

    private fun failure(code: String, userMessage: String, detail: String? = null): Outcome {
        if (detail != null) DebugVoiceLog.log("vision_failure code=$code detail=$detail")
        return Outcome(
            ok = false,
            output = JSONObject()
                .put("ok", false)
                .put("tool", TOOL)
                .put("error", code)
                .put("message", userMessage)
                .put("instruction", "没有看到画面。只把 message 告诉用户，绝对不要描述或猜测画面内容。")
                .toString(),
            spokenText = userMessage,
            errorCode = code,
        )
    }

    companion object {
        const val TOOL = "describe_camera_view"
        const val DEFAULT_QUESTION = "请描述画面里有什么。"
        const val MAX_QUESTION_CHARS = 200
        const val MAX_EDGE_PX = 768
        const val LOOKING = "正在看…"
    }
}
