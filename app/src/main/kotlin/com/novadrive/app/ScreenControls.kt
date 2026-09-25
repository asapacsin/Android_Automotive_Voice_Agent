package com.novadrive.app

import com.novadrive.app.nav.RecenterOutcome
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.vehicle.ClimateState
import com.novadrive.vehicle.VehicleControlPort
import com.novadrive.app.voice.ClimateToolActions
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screen may do, and what it may watch.
 *
 * [I-6](../../../../../../docs/INVARIANTS.md): a button and a spoken command must reach the same
 * executor by the same route. They used not to — `BottomBarView` called `BundledMusicPlayer` and
 * `VehicleControlPort` straight from its click listeners, so the voice path got argument
 * validation, bounds checking and a result-shaped outcome while the screen path got none of it
 * ([TECH_DEBT.md](../../../../../../docs/TECH_DEBT.md) D-3).
 *
 * The screen now states *intent* and this states the *route*, so a change to how an action is
 * validated cannot silently miss one of them. Reading state is not executing, but the flows are
 * exposed here too: a view that has to reach for a concrete backend to render itself will
 * eventually reach for it to act as well.
 */
interface ScreenControls {
    val musicPlaying: StateFlow<Boolean>
    val climate: StateFlow<ClimateState>

    /** Start the bundled track, or stop it if it is already playing. */
    suspend fun toggleMusic(): Outcome

    /** Play the bundled track from the beginning. */
    suspend fun restartMusic(): Outcome

    suspend fun toggleClimatePower(): Outcome

    suspend fun adjustTemperature(delta: Double): Outcome

    /** 📍 Move the map back to the driver's position. Fails with the [RecenterOutcome] name. */
    suspend fun recenter(): Outcome

    /** 📷 Show the camera window, or hide it if it is showing. */
    suspend fun toggleCamera(): Outcome

    /** Whether the camera window is showing now, so a spoken 「关掉」 can mean off. */
    fun cameraShowing(): Boolean

    /** Deliberately the same shape a tool result has: did it work, and if not, why. */
    data class Outcome(val ok: Boolean, val errorCode: String? = null)
}

/**
 * The production route: the same [ClimateToolHandler] and [AndroidActionExecutor] instances the
 * tool dispatcher holds, so there is one validated path to the ports rather than two.
 */
class ExecutorScreenControls(
    private val executor: AndroidActionExecutor,
    private val climatePort: VehicleControlPort,
    private val climateHandler: ClimateToolHandler,
    override val musicPlaying: StateFlow<Boolean>,
    private val recenterMap: () -> RecenterOutcome,
    private val cameraToggle: () -> ScreenControls.Outcome,
    private val cameraOpen: () -> Boolean = { false },
) : ScreenControls {

    override val climate: StateFlow<ClimateState> get() = climatePort.climateState

    override suspend fun toggleMusic(): ScreenControls.Outcome =
        if (musicPlaying.value) outcome(executor.stopMusic()) else outcome(executor.playMusic())

    override suspend fun restartMusic(): ScreenControls.Outcome {
        // Stop first so a second press restarts rather than doing nothing; both go through the
        // executor, which is the point.
        if (musicPlaying.value) executor.stopMusic()
        return outcome(executor.playMusic())
    }

    override suspend fun toggleClimatePower(): ScreenControls.Outcome {
        val action = if (climatePort.getClimateState().powerOn) {
            ClimateToolActions.POWER_OFF
        } else {
            ClimateToolActions.POWER_ON
        }
        return climate(mapOf("action" to action))
    }

    override suspend fun adjustTemperature(delta: Double): ScreenControls.Outcome = climate(
        mapOf(
            "action" to ClimateToolActions.ADJUST_TEMPERATURE,
            "value" to plain(delta),
        ),
    )

    override suspend fun recenter(): ScreenControls.Outcome {
        val result = recenterMap()
        return if (result == RecenterOutcome.MOVED) {
            ScreenControls.Outcome(true)
        } else {
            ScreenControls.Outcome(false, "${ScreenAffordanceRunner.RECENTER_FAILURE_PREFIX}${result.name}")
        }
    }

    override suspend fun toggleCamera(): ScreenControls.Outcome = cameraToggle()

    override fun cameraShowing(): Boolean = cameraOpen()

    private suspend fun climate(arguments: Map<String, String>): ScreenControls.Outcome {
        val result = climateHandler.handle(arguments)
        return ScreenControls.Outcome(result.ok, result.errorCode)
    }

    private fun outcome(result: AndroidActionResult): ScreenControls.Outcome = when (result) {
        is AndroidActionResult.Accepted -> ScreenControls.Outcome(true)
        is AndroidActionResult.Rejected -> ScreenControls.Outcome(false, result.code)
    }

    private fun plain(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
