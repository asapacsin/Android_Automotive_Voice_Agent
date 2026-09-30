package com.novadrive.app.tools

import com.novadrive.app.AndroidActionExecutor
import com.novadrive.app.voice.RealtimeToolCatalog.END_CONVERSATION
import com.novadrive.app.voice.RealtimeToolCatalog.SET_SPEECH_OUTPUT
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult

/** Executes [SpeechDomain]: ending the conversation and silent/spoken output. */
class SpeechServer(private val executor: AndroidActionExecutor) : ToolServer {
    override val domain: ToolDomain = SpeechDomain

    override fun call(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult =
        when (call.name) {
            END_CONVERSATION -> env.result(call, executor.endConversation())
            SET_SPEECH_OUTPUT -> when (call.arguments["mode"]) {
                "silent" -> env.result(call, executor.setSpeechSilent(true))
                "spoken" -> env.result(call, executor.setSpeechSilent(false))
                else -> env.failed(call, "MODE_NOT_ALLOWED")
            }
            else -> env.failed(call, "UNKNOWN_TOOL")
        }
}
