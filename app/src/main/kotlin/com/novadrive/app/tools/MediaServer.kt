package com.novadrive.app.tools

import com.novadrive.app.AndroidActionExecutor
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult

/** Executes [MediaDomain]: the bundled track, play or stop. */
class MediaServer(private val executor: AndroidActionExecutor) : ToolServer {
    override val domain: ToolDomain = MediaDomain

    override fun call(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult =
        when (call.name) {
            "control_music" -> {
                when (call.arguments["action"]) {
                    // One bundled track, no library: a request that named a song, an artist or a
                    // style cannot be satisfied, and starting the bundled track would make ok=true
                    // mean "you got what you asked for". Refused here because this is the only
                    // bridge to a device action.
                    "play" -> env.result(call, executor.playMusic())
                    "stop" -> env.result(call, executor.stopMusic())
                    else -> env.failed(call, "ACTION_NOT_ALLOWED")
                }
            }
            else -> env.failed(call, "UNKNOWN_TOOL")
        }
}
