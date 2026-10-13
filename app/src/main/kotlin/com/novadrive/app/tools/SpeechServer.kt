package com.novadrive.app.tools

import com.novadrive.app.AndroidActionExecutor
import com.novadrive.app.PersonaProfiles
import com.novadrive.app.SpeakingStyle
import com.novadrive.app.SpeakingStyleState
import com.novadrive.app.voice.RealtimeToolCatalog.END_CONVERSATION
import com.novadrive.app.voice.RealtimeToolCatalog.SET_SPEECH_OUTPUT
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import org.json.JSONObject

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
            SpeechDomain.SET_SPEAKING_STYLE -> setSpeakingStyle(call, env)
            else -> env.failed(call, "UNKNOWN_TOOL")
        }

    private fun setSpeakingStyle(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult {
        val raw = call.arguments["style"]
        if (raw !in SpeechDomain.STYLES) return env.failed(call, "STYLE_NOT_ALLOWED")
        val style = SpeakingStyle.fromWire(raw)
        // set() applies the style before persisting, so a failed save still changes this process.
        val persisted = try {
            SpeakingStyleState.set(style)
            true
        } catch (e: Exception) {
            false
        }
        com.novadrive.app.voice.SpeechAuthority.arbiter.onConfirmation()
        return ToolDispatchResult(
            null,
            null,
            successChip = "✓ 语气：${style.chipLabel}",
            output = styleOutput(style, persisted),
        )
    }

    companion object {
        /** Replaces the setup's tone lines; the wording itself is owned by [PersonaProfiles.toneLines]. */
        const val REPLACES_EARLIER = "这两句取代之前的「性格基调」和「语音风格」。"
        const val RESPOND_NOW = "现在用这种语气简短回应一句。"
        const val DEFAULT_INSTRUCTION = "从现在起恢复平常的语气说话，之前换过的语气不再适用。现在简短回应一句。"

        fun instructionFor(style: SpeakingStyle): String {
            val (tone, voice) = PersonaProfiles.toneLines(style) ?: return DEFAULT_INSTRUCTION
            return "从现在起：$tone $voice $REPLACES_EARLIER$RESPOND_NOW"
        }

        fun styleOutput(style: SpeakingStyle, persisted: Boolean): String {
            val json = JSONObject()
                .put("ok", true)
                .put("tool", SpeechDomain.SET_SPEAKING_STYLE)
                .put("style", style.wireName)
                .put("instruction", instructionFor(style))
            if (!persisted) json.put("persisted", false)
            return json.toString()
        }
    }
}
