package com.novadrive.app.tools

import com.novadrive.app.PhoneCallTool
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult

/** Executes [PhoneDomain]; the confirmation rules live in [PhoneCallTool]. */
class PhoneServer(private val phone: PhoneCallTool) : ToolServer {
    override val domain: ToolDomain = PhoneDomain

    override fun call(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult =
        when (call.name) {
            "place_call" -> phone.call(call) { c, code -> env.failed(c, code) }
            else -> env.failed(call, "UNKNOWN_TOOL")
        }
}
