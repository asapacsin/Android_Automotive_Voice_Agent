package com.novadrive.app.tools

import com.novadrive.app.AllowedApp
import com.novadrive.app.AndroidActionExecutor
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult

/** Executes [AppsDomain]: opening an allow-listed app. */
class AppsServer(private val executor: AndroidActionExecutor) : ToolServer {
    override val domain: ToolDomain = AppsDomain

    override fun call(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult =
        when (call.name) {
            "open_app" -> {
                val app = when (call.arguments["app"]) {
                    "maps" -> AllowedApp.MAPS
                    "settings" -> AllowedApp.SETTINGS
                    else -> return env.failed(call, "APP_NOT_ALLOWED")
                }
                env.result(call, executor.openApp(app))
            }
            else -> env.failed(call, "UNKNOWN_TOOL")
        }
}
