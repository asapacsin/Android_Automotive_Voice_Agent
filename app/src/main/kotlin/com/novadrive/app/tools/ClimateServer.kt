package com.novadrive.app.tools

import com.novadrive.app.ToolCallGuards
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import kotlinx.coroutines.runBlocking

/** Executes [ClimateDomain] and records the outcome on the turn's [com.novadrive.app.voice.DriverContext]. */
class ClimateServer(private val climate: ClimateToolHandler) : ToolServer {
    override val domain: ToolDomain = ClimateDomain

    override fun call(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult =
        when (call.name) {
            ClimateToolHandler.TOOL -> {
                val outcome = runBlocking { climate.handle(call.arguments) }
                env.driverContext()?.let { context ->
                    context.onClimateResult(
                        action = call.arguments["action"].orEmpty(),
                        value = call.arguments["value"]?.toDoubleOrNull(),
                        output = outcome.output,
                        epoch = context.currentEpoch(),
                    )
                }
                // Failures are worth hearing too, even while navigating: the driver must not
                // assume the climate changed when it did not.
                com.novadrive.app.voice.SpeechAuthority.arbiter.onConfirmation()
                ToolDispatchResult(
                    null,
                    null,
                    blockedReason = outcome.errorCode,
                    successChip = outcome.chip,
                    output = ToolCallGuards.withPowerAdvice(outcome.output),
                )
            }
            else -> env.failed(call, "UNKNOWN_TOOL")
        }
}
