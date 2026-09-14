package com.novadrive.safety

import com.novadrive.contracts.CommandBounds
import com.novadrive.contracts.PolicyDecision
import com.novadrive.contracts.PolicyReasons
import com.novadrive.contracts.SafetyVerdict
import com.novadrive.contracts.StructuredCommand
import com.novadrive.contracts.VehicleSafetySnapshot
import kotlin.math.abs

interface CommandValidator {
    fun validate(command: StructuredCommand): String?
}

class BootstrapCommandValidator : CommandValidator {
    override fun validate(command: StructuredCommand): String? =
        when (command) {
            is StructuredCommand.StartNavigation -> {
                val coordinate = command.destination.coordinate
                when {
                    command.destination.label.isBlank() -> "invalid.navigation.empty_destination"
                    coordinate != null && !coordinate.hasPlausibleWgsOrGcjRange() ->
                        "invalid.navigation.coordinate_range"
                    else -> null
                }
            }

            is StructuredCommand.PlayMedia ->
                command.query.takeIf { it.isBlank() }?.let { "invalid.media.empty_query" }

            is StructuredCommand.SetVolume ->
                if (command.volumePercent !in CommandBounds.VOLUME_MIN..CommandBounds.VOLUME_MAX) {
                    "invalid.media.volume_bounds"
                } else {
                    null
                }

            is StructuredCommand.PlaceCall ->
                if (!command.query.hasAnyIdentity()) "invalid.phone.missing_identity" else null

            is StructuredCommand.SetCabinTemperature ->
                if (command.celsius !in CommandBounds.HVAC_TEMP_MIN_C..CommandBounds.HVAC_TEMP_MAX_C) {
                    "invalid.hvac.temperature_bounds"
                } else {
                    null
                }

            is StructuredCommand.SetFanLevel ->
                if (command.level !in CommandBounds.FAN_MIN..CommandBounds.FAN_MAX) {
                    "invalid.hvac.fan_bounds"
                } else {
                    null
                }

            is StructuredCommand.CancelNavigation,
            is StructuredCommand.PauseMedia,
            is StructuredCommand.EndCall,
            -> null
        }
}

interface SafetyPolicy {
    fun evaluate(command: StructuredCommand, snapshot: VehicleSafetySnapshot): SafetyVerdict
}

class BootstrapSafetyPolicy : SafetyPolicy {
    override fun evaluate(command: StructuredCommand, snapshot: VehicleSafetySnapshot): SafetyVerdict =
        when (command) {
            is StructuredCommand.StartNavigation ->
                if (command.destination.restricted) {
                    deny(PolicyReasons.DENY_RESTRICTED_DESTINATION, "目的地受限，无法开始导航。")
                } else {
                    allow()
                }

            is StructuredCommand.PlaceCall ->
                when {
                    snapshot.callsBlocked ->
                        deny(PolicyReasons.DENY_CALLS_BLOCKED, "当前隐私设置禁止拨号。")
                    command.blocked ->
                        deny(PolicyReasons.DENY_NUMBER_BLOCKED, "该号码已被策略拦截。")
                    else ->
                        confirm(PolicyReasons.CONFIRM_PLACE_CALL, "即将拨打电话，请确认。")
                }

            is StructuredCommand.SetCabinTemperature -> {
                val delta = abs(command.celsius - snapshot.cabinTemperatureCelsius)
                if (delta >= 5.0) {
                    confirm(PolicyReasons.CONFIRM_LARGE_HVAC, "温度变化较大，请确认后再调节。")
                } else {
                    allow()
                }
            }

            is StructuredCommand.SetVolume ->
                if (command.volumePercent >= 80) {
                    confirm(PolicyReasons.CONFIRM_HIGH_VOLUME, "音量较高，请确认后再调节。")
                } else {
                    allow()
                }

            else -> allow()
        }

    private fun allow() = SafetyVerdict(PolicyDecision.ALLOW, PolicyReasons.ALLOW_DEFAULT, "允许执行。")

    private fun confirm(code: String, message: String) =
        SafetyVerdict(PolicyDecision.CONFIRM, code, message)

    private fun deny(code: String, message: String) =
        SafetyVerdict(PolicyDecision.DENY, code, message)
}
