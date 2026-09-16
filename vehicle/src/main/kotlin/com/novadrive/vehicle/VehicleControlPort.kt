package com.novadrive.vehicle

import com.novadrive.contracts.CommandBounds
import com.novadrive.contracts.HvacObservedState
import kotlinx.coroutines.flow.StateFlow

/**
 * The single vehicle-control boundary for cabin climate.
 *
 * Everything above this interface (voice session, tool dispatcher, UI) depends only on it and
 * must not know which backend is behind it. Implementations:
 *  - `SimulatedVehicleControl` (module `simulator`) — the phone build's stateful simulated backend.
 *  - Later: an Android Automotive (`CarPropertyManager`), CAN bus, OEM SDK or remote-vehicle adapter.
 *
 * Swapping the backend must not require changes to the LLM tool schema or the dispatcher.
 */
interface VehicleControlPort {
    /** Live climate state, for UI that should reflect the vehicle rather than a hard-coded label. */
    val climateState: StateFlow<ClimateState>

    suspend fun setHvacPower(on: Boolean): VehicleActionResult

    /** Absolute target temperature. Out-of-range values are rejected, never clamped silently. */
    suspend fun setCabinTemperature(celsius: Double): VehicleActionResult

    /** Relative change from the *current* vehicle state. Clamped to the limits; see [VehicleActionResult.Success.limitReached]. */
    suspend fun changeCabinTemperature(deltaCelsius: Double): VehicleActionResult

    /** Absolute fan level. Out-of-range values are rejected, never clamped silently. */
    suspend fun setFanLevel(level: Int): VehicleActionResult

    /** Relative change from the *current* fan level. Clamped to the limits; see [VehicleActionResult.Success.limitReached]. */
    suspend fun changeFanLevel(delta: Int): VehicleActionResult

    suspend fun getClimateState(): ClimateState
}

/**
 * Explicit state semantics: turning the HVAC off keeps the temperature and fan setpoints, so
 * turning it back on restores them. Setpoints may be changed while the power is off; the power
 * state is reported in every result so the assistant can say so.
 */
data class ClimateState(
    val powerOn: Boolean,
    val targetTemperatureCelsius: Double,
    val fanLevel: Int,
) {
    init {
        require(targetTemperatureCelsius in ClimateLimits.MIN_TEMPERATURE_C..ClimateLimits.MAX_TEMPERATURE_C) {
            "temperature $targetTemperatureCelsius outside ${ClimateLimits.MIN_TEMPERATURE_C}..${ClimateLimits.MAX_TEMPERATURE_C}"
        }
        require(fanLevel in ClimateLimits.MIN_FAN_LEVEL..ClimateLimits.MAX_FAN_LEVEL) {
            "fan level $fanLevel outside ${ClimateLimits.MIN_FAN_LEVEL}..${ClimateLimits.MAX_FAN_LEVEL}"
        }
    }

    fun toObserved(): HvacObservedState =
        HvacObservedState(cabinTemperatureCelsius = targetTemperatureCelsius, fanLevel = fanLevel, powerOn = powerOn)

    companion object {
        val DEFAULT = ClimateState(powerOn = false, targetTemperatureCelsius = 24.0, fanLevel = 2)
    }
}

/** Limits come from the product spec (docs/MVP_SPEC.md: 16–32 °C) via [CommandBounds]. */
object ClimateLimits {
    const val MIN_TEMPERATURE_C: Double = CommandBounds.HVAC_TEMP_MIN_C
    const val MAX_TEMPERATURE_C: Double = CommandBounds.HVAC_TEMP_MAX_C
    const val MIN_FAN_LEVEL: Int = CommandBounds.FAN_MIN
    const val MAX_FAN_LEVEL: Int = CommandBounds.FAN_MAX

    /** 「调高一点」 / 「调低一点」 */
    const val DEFAULT_TEMPERATURE_STEP_C: Double = 1.0

    /** 「风量调大」 / 「风量调小」 */
    const val DEFAULT_FAN_STEP: Int = 1
}

/**
 * Outcome of a vehicle action. Backend-neutral on purpose: an AAOS, CAN or OEM adapter maps its
 * own errors onto these, so the tool layer can tell success from every kind of non-success.
 */
sealed interface VehicleActionResult {
    /** The action took effect and [state] is the state read back afterwards. */
    data class Success(val state: ClimateState, val limitReached: Boolean = false) : VehicleActionResult

    /** The request itself was wrong (e.g. 50 °C). Nothing changed. */
    data class InvalidArgument(val reason: String) : VehicleActionResult

    /** This vehicle does not have the feature. */
    data class Unsupported(val feature: String) : VehicleActionResult

    /** The feature exists but cannot be used right now (e.g. system offline, ignition off). */
    data class Unavailable(val reason: String) : VehicleActionResult

    /** The app is not permitted to control this property. */
    data class PermissionDenied(val reason: String) : VehicleActionResult

    /** The backend attempted the action and it failed. */
    data class Failure(val reason: String) : VehicleActionResult
}
