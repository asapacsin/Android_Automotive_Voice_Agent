package com.novadrive.ingress.realtime

import com.novadrive.contracts.OrchestrationResult
import com.novadrive.contracts.StructuredCommand
import com.novadrive.ingress.StructuredCommandIngress

data class ToolDispatchResult(
    val command: StructuredCommand?,
    val orchestration: OrchestrationResult?,
    val blockedReason: String? = null,
    val successChip: String? = null,
)

/**
 * Dispatches structured tool calls only. Never parses assistant prose.
 */
class RealtimeToolDispatcher(
    private val ingress: StructuredCommandIngress,
) {
    fun dispatch(call: DomainVoiceEvent.ToolCall): ToolDispatchResult {
        return when (call.name) {
            "set_temperature" -> {
                val celsius = call.arguments["temperature_c"]?.toDoubleOrNull()
                    ?: return ToolDispatchResult(null, null, blockedReason = "INVALID_TOOL_ARGS")
                val zone = call.arguments["zone"] ?: "all"
                val command =
                    StructuredCommand.SetCabinTemperature(
                        correlationId = call.callId,
                        celsius = celsius,
                    )
                val result = ingress.submit(command)
                val chip =
                    if (result.verifiedSuccess) {
                        hvacSuccessChip(zone, celsius)
                    } else {
                        null
                    }
                ToolDispatchResult(command, result, successChip = chip)
            }
            else -> ToolDispatchResult(null, null, blockedReason = "UNKNOWN_TOOL")
        }
    }
}

fun hvacSuccessChip(zone: String, celsius: Double): String {
    val zoneLabel =
        when (zone) {
            "driver" -> "主驾"
            "passenger" -> "副驾"
            else -> "全车"
        }
    val shown = if (celsius == celsius.toLong().toDouble()) celsius.toLong().toString() else celsius.toString()
    return "✓ $zoneLabel ${shown}°C"
}
