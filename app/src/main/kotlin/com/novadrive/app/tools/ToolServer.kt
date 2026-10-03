package com.novadrive.app.tools

import com.novadrive.app.AndroidActionResult
import com.novadrive.app.voice.DriverContext
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult

/** A domain bound to its executors (MCP-shaped call, ADR-015). One server per [ToolDomain]. */
interface ToolServer {
    val domain: ToolDomain

    /** Executes a validated call for a tool this server's domain declares. */
    fun call(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult
}

/** What the dispatcher lends every server: the shared result formats and the turn's context. */
class ToolCallEnv(
    val driverContext: () -> DriverContext?,
    private val failedFormat: (DomainVoiceEvent.ToolCall, String, Map<String, Any>) -> ToolDispatchResult,
    private val resultFormat: (DomainVoiceEvent.ToolCall, AndroidActionResult) -> ToolDispatchResult,
) {
    fun failed(call: DomainVoiceEvent.ToolCall, code: String, details: Map<String, Any> = emptyMap()) =
        failedFormat(call, code, details)

    fun result(call: DomainVoiceEvent.ToolCall, action: AndroidActionResult) = resultFormat(call, action)
}
