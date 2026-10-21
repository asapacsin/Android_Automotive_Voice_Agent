package com.novadrive.app.tools

import com.novadrive.app.LiveInfoTool
import com.novadrive.app.voice.RealtimeToolCatalog.QUERY_LIVE_INFO
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult

/** Executes [LiveInfoDomain] (SPEC-011) through [LiveInfoTool]. */
class LiveInfoServer(private val liveInfo: LiveInfoTool) : ToolServer {
    override val domain: ToolDomain = LiveInfoDomain

    override fun call(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult =
        when (call.name) {
            QUERY_LIVE_INFO -> liveInfo.dispatch(call) { c, code -> env.failed(c, code) }
            else -> env.failed(call, "UNKNOWN_TOOL")
        }
}
