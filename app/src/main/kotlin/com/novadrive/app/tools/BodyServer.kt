package com.novadrive.app.tools

import com.novadrive.app.vehicle.BodyToolOutcome
import com.novadrive.app.vehicle.SeatToolHandler
import com.novadrive.app.vehicle.WindowToolHandler
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import com.novadrive.vehicle.VehicleControlPort
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Executes [BodyDomain] over the vehicle port; with no port every call is VEHICLE_UNAVAILABLE. */
class BodyServer(cabin: VehicleControlPort?) : ToolServer {
    override val domain: ToolDomain = BodyDomain

    private val windows = cabin?.let(::WindowToolHandler)
    private val seat = cabin?.let(::SeatToolHandler)

    override fun call(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult =
        when (call.name) {
            "control_window" -> respond(call, env, windows?.let { runBlocking { it.handle(call.arguments) } })
            "control_seat" -> respond(call, env, seat?.let { runBlocking { it.handle(call.arguments) } })
            else -> env.failed(call, "UNKNOWN_TOOL")
        }

    private fun respond(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv, outcome: BodyToolOutcome?): ToolDispatchResult {
        val result = outcome ?: unavailable(call)
        // Only ok=true results become referents; onBodyResult ignores the rest.
        env.driverContext()?.let {
            it.onBodyResult(
                call.name,
                call.arguments["action"].orEmpty(),
                call.arguments["value"]?.toDoubleOrNull(),
                result.output,
                it.currentEpoch(),
            )
        }
        // A failure is worth hearing too: the driver must not assume the window moved.
        com.novadrive.app.voice.SpeechAuthority.arbiter.onConfirmation()
        return ToolDispatchResult(
            null,
            null,
            blockedReason = result.errorCode,
            successChip = result.chip,
            output = result.output,
        )
    }

    private fun unavailable(call: DomainVoiceEvent.ToolCall) = BodyToolOutcome(
        ok = false,
        output = JSONObject()
            .put("ok", false)
            .put("tool", call.name)
            .put("action", call.arguments["action"].orEmpty())
            .put("error", VEHICLE_UNAVAILABLE)
            .put("detail", "no vehicle backend")
            .put("instruction", com.novadrive.app.vehicle.ClimateToolHandler.FAILURE_INSTRUCTION)
            .toString(),
        chip = null,
        errorCode = VEHICLE_UNAVAILABLE,
    )

    private companion object {
        const val VEHICLE_UNAVAILABLE = "VEHICLE_UNAVAILABLE"
    }
}
