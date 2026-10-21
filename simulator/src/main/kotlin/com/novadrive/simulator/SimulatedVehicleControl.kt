package com.novadrive.simulator

import com.novadrive.vehicle.CabinLimits
import com.novadrive.vehicle.CabinState
import com.novadrive.vehicle.ClimateLimits
import com.novadrive.vehicle.ClimateState
import com.novadrive.vehicle.VehicleActionResult
import com.novadrive.vehicle.VehicleControlPort
import com.novadrive.vehicle.SeatId
import com.novadrive.vehicle.WindowId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Operations a test can make fail, independently of any real-vehicle API naming. */
enum class ClimateOperation { POWER, SET_TEMPERATURE, CHANGE_TEMPERATURE, SET_FAN, CHANGE_FAN }

/** Backend-neutral failure kinds a test can inject. */
enum class InjectedClimateFailure { UNSUPPORTED, UNAVAILABLE, PERMISSION_DENIED, EXECUTION_FAILURE }

/**
 * Failure injection. [failNext] fails exactly one upcoming operation; [failAlways] fails every
 * call to the listed operations until removed. A failed operation never changes state.
 */
class ClimateFaults(
    @Volatile var failNext: InjectedClimateFailure? = null,
    val failAlways: MutableMap<ClimateOperation, InjectedClimateFailure> = mutableMapOf(),
)

/**
 * SIMULATED vehicle climate backend. This is not vehicle control: it is a stateful stand-in
 * that lets the phone build prove the voice -> tool -> [VehicleControlPort] -> state -> result
 * chain. Commands mutate in-memory state exactly as a real adapter would mutate the car.
 */
class SimulatedVehicleControl(
    initial: ClimateState = ClimateState.DEFAULT,
    val faults: ClimateFaults = ClimateFaults(),
    initialCabin: CabinState = CabinState.DEFAULT,
) : VehicleControlPort {
    private val lock = Any()
    private val state = MutableStateFlow(initial)
    private val cabin = MutableStateFlow(initialCabin)

    override val cabinState: StateFlow<CabinState> = cabin.asStateFlow()

    override suspend fun setWindows(windows: Set<WindowId>, openPercent: Int): VehicleActionResult<CabinState> =
        mutateCabin { current ->
            when {
                windows.isEmpty() -> VehicleActionResult.InvalidArgument("no window selected")
                openPercent !in CabinLimits.WINDOW_MIN..CabinLimits.WINDOW_MAX ->
                    VehicleActionResult.InvalidArgument(
                        "window percent $openPercent outside ${CabinLimits.WINDOW_MIN}..${CabinLimits.WINDOW_MAX}",
                    )
                else -> VehicleActionResult.Success(
                    current.copy(windows = current.windows + windows.associateWith { openPercent }),
                )
            }
        }

    override suspend fun changeWindows(windows: Set<WindowId>, deltaPercent: Int): VehicleActionResult<CabinState> =
        mutateCabin { current ->
            if (windows.isEmpty()) {
                VehicleActionResult.InvalidArgument("no window selected")
            } else {
                var limited = false
                val updated = windows.associateWith { id ->
                    val wanted = current.windows.getValue(id).toLong() + deltaPercent
                    val bounded = wanted.coerceIn(CabinLimits.WINDOW_MIN.toLong(), CabinLimits.WINDOW_MAX.toLong())
                    // A window already at the limit in the asked direction is clamped too.
                    if (bounded != wanted) limited = true
                    bounded.toInt()
                }
                VehicleActionResult.Success(current.copy(windows = current.windows + updated), limited)
            }
        }

    override suspend fun setSeatHeight(seat: SeatId, level: Int): VehicleActionResult<CabinState> =
        mutateCabin { current ->
            if (level !in CabinLimits.SEAT_MIN..CabinLimits.SEAT_MAX) {
                VehicleActionResult.InvalidArgument(
                    "seat level $level outside ${CabinLimits.SEAT_MIN}..${CabinLimits.SEAT_MAX}",
                )
            } else {
                VehicleActionResult.Success(current.copy(seatHeights = current.seatHeights + (seat to level)))
            }
        }

    override suspend fun changeSeatHeight(seat: SeatId, delta: Int): VehicleActionResult<CabinState> =
        mutateCabin { current ->
            val wanted = current.seatHeights.getValue(seat).toLong() + delta
            val bounded = wanted.coerceIn(CabinLimits.SEAT_MIN.toLong(), CabinLimits.SEAT_MAX.toLong())
            VehicleActionResult.Success(
                current.copy(seatHeights = current.seatHeights + (seat to bounded.toInt())),
                limitReached = bounded != wanted,
            )
        }

    override suspend fun getCabinState(): CabinState = cabin.value

    /**
     * [block] returns a Success carrying the *proposed* state; only then is it published, and the
     * result carries the state read back from the flow, as a real adapter would report it.
     */
    private fun mutateCabin(block: (CabinState) -> VehicleActionResult<CabinState>): VehicleActionResult<CabinState> =
        synchronized(lock) {
            when (val outcome = block(cabin.value)) {
                is VehicleActionResult.Success -> {
                    cabin.value = outcome.state
                    VehicleActionResult.Success(cabin.value, outcome.limitReached)
                }
                else -> outcome
            }
        }

    override val climateState: StateFlow<ClimateState> = state.asStateFlow()

    override suspend fun setHvacPower(on: Boolean): VehicleActionResult<ClimateState> = applyPower(on)

    override suspend fun setCabinTemperature(celsius: Double): VehicleActionResult<ClimateState> = applySetTemperature(celsius)

    override suspend fun changeCabinTemperature(deltaCelsius: Double): VehicleActionResult<ClimateState> =
        mutate(ClimateOperation.CHANGE_TEMPERATURE) { current ->
            if (!deltaCelsius.isFinite()) {
                invalid("temperature delta must be a finite number")
            } else {
                val wanted = current.targetTemperatureCelsius + deltaCelsius
                val bounded = wanted.coerceIn(ClimateLimits.MIN_TEMPERATURE_C, ClimateLimits.MAX_TEMPERATURE_C)
                Change(current.copy(targetTemperatureCelsius = bounded), limitReached = bounded != wanted)
            }
        }

    override suspend fun setFanLevel(level: Int): VehicleActionResult<ClimateState> = applySetFan(level)

    override suspend fun changeFanLevel(delta: Int): VehicleActionResult<ClimateState> =
        mutate(ClimateOperation.CHANGE_FAN) { current ->
            val wanted = current.fanLevel.toLong() + delta
            val bounded = wanted
                .coerceIn(ClimateLimits.MIN_FAN_LEVEL.toLong(), ClimateLimits.MAX_FAN_LEVEL.toLong())
                .toInt()
            Change(current.copy(fanLevel = bounded), limitReached = bounded.toLong() != wanted)
        }

    override suspend fun getClimateState(): ClimateState = state.value

    /** Benchmark reset: a known state and no pending faults. */
    fun reset(to: ClimateState) {
        synchronized(lock) {
            state.value = to
            faults.failNext = null
            faults.failAlways.clear()
        }
    }

    // Non-suspending entry points, used by the legacy synchronous InMemoryVehicleSimulator path.

    fun applyPower(on: Boolean): VehicleActionResult<ClimateState> =
        mutate(ClimateOperation.POWER) { current -> Change(current.copy(powerOn = on)) }

    fun applySetTemperature(celsius: Double): VehicleActionResult<ClimateState> =
        mutate(ClimateOperation.SET_TEMPERATURE) { current ->
            if (!celsius.isFinite() ||
                celsius !in ClimateLimits.MIN_TEMPERATURE_C..ClimateLimits.MAX_TEMPERATURE_C
            ) {
                invalid(
                    "temperature $celsius outside " +
                        "${ClimateLimits.MIN_TEMPERATURE_C}..${ClimateLimits.MAX_TEMPERATURE_C}",
                )
            } else {
                Change(current.copy(targetTemperatureCelsius = celsius))
            }
        }

    fun applySetFan(level: Int): VehicleActionResult<ClimateState> =
        mutate(ClimateOperation.SET_FAN) { current ->
            if (level !in ClimateLimits.MIN_FAN_LEVEL..ClimateLimits.MAX_FAN_LEVEL) {
                invalid("fan level $level outside ${ClimateLimits.MIN_FAN_LEVEL}..${ClimateLimits.MAX_FAN_LEVEL}")
            } else {
                Change(current.copy(fanLevel = level))
            }
        }

    private sealed interface Outcome
    private data class Change(val next: ClimateState, val limitReached: Boolean = false) : Outcome
    private data class Rejected(val result: VehicleActionResult<ClimateState>) : Outcome

    private fun invalid(reason: String): Outcome = Rejected(VehicleActionResult.InvalidArgument(reason))

    private fun mutate(operation: ClimateOperation, block: (ClimateState) -> Outcome): VehicleActionResult<ClimateState> =
        synchronized(lock) {
            val injected = injectedFailure(operation)
            if (injected != null) return injected
            when (val outcome = block(state.value)) {
                is Rejected -> outcome.result
                is Change -> {
                    state.value = outcome.next
                    VehicleActionResult.Success(state.value, outcome.limitReached)
                }
            }
        }

    private fun injectedFailure(operation: ClimateOperation): VehicleActionResult<ClimateState>? {
        val kind = faults.failAlways[operation]
            ?: faults.failNext?.also { faults.failNext = null }
            ?: return null
        val name = operation.name.lowercase()
        return when (kind) {
            InjectedClimateFailure.UNSUPPORTED -> VehicleActionResult.Unsupported(name)
            InjectedClimateFailure.UNAVAILABLE -> VehicleActionResult.Unavailable("simulated: $name unavailable")
            InjectedClimateFailure.PERMISSION_DENIED -> VehicleActionResult.PermissionDenied("simulated: $name not permitted")
            InjectedClimateFailure.EXECUTION_FAILURE -> VehicleActionResult.Failure("simulated: $name execution failed")
        }
    }
}
