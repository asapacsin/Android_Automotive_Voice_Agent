package com.novadrive.app.tools

import com.novadrive.app.vision.CameraQuestionHandler
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult

/** Executes [VisionDomain]: a camera question, answered asynchronously. */
class VisionServer(private val camera: CameraQuestionHandler) : ToolServer {
    override val domain: ToolDomain = VisionDomain

    override fun call(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult =
        when (call.name) {
            CameraQuestionHandler.TOOL -> {
                val question = call.arguments["question"]
                // A vision request takes seconds: hand it to the session as async work instead of
                // blocking the event loop. The answer is spoken when it arrives.
                ToolDispatchResult(
                    null,
                    null,
                    successChip = "📷 正在看",
                    deferredOutput = {
                        val outcome = camera.ask(question)
                        com.novadrive.app.voice.SpeechAuthority.arbiter.onConfirmation()
                        outcome.output
                    },
                )
            }
            else -> env.failed(call, "UNKNOWN_TOOL")
        }
}
