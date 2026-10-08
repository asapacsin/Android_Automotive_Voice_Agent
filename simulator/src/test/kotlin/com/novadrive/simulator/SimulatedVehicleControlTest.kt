package com.novadrive.simulator

import com.novadrive.vehicle.CabinLimits
import com.novadrive.vehicle.CabinState
import com.novadrive.vehicle.ClimateState
import com.novadrive.vehicle.SeatId
import com.novadrive.vehicle.VehicleActionResult
import com.novadrive.vehicle.WindowId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Level A: the simulated backend behaves like a stateful device, not a success stub. */
class SimulatedVehicleControlTest {
    private fun sim() = SimulatedVehicleControl()

    @Test
    fun defaultStateMatchesSpec() = runBlocking {
        assertEquals(ClimateState(powerOn = false, targetTemperatureCelsius = 24.0, fanLevel = 2), sim().getClimateState())
    }

    @Test
    fun powerOnThenOffMutatesState() = runBlocking {
        val sim = sim()
        val on = sim.setHvacPower(true) as VehicleActionResult.Success
        assertTrue(on.state.powerOn)
        assertTrue(sim.getClimateState().powerOn)
        sim.setHvacPower(false)
        assertFalse(sim.getClimateState().powerOn)
    }

    @Test
    fun setTemperatureMutatesState() = runBlocking {
        val sim = sim()
        val result = sim.setCabinTemperature(22.0) as VehicleActionResult.Success
        assertEquals(22.0, result.state.targetTemperatureCelsius)
        assertEquals(22.0, sim.getClimateState().targetTemperatureCelsius)
        assertFalse(result.limitReached)
    }

    @Test
    fun relativeTemperatureUsesCurrentState() = runBlocking {
        val sim = sim()
        sim.setCabinTemperature(22.0)
        sim.changeCabinTemperature(1.0)
        assertEquals(23.0, sim.getClimateState().targetTemperatureCelsius)
        sim.changeCabinTemperature(1.0)
        assertEquals(24.0, sim.getClimateState().targetTemperatureCelsius)
        sim.changeCabinTemperature(-3.0)
        assertEquals(21.0, sim.getClimateState().targetTemperatureCelsius)
    }

    @Test
    fun fanIncreaseAndBoundedAtMaximum() = runBlocking {
        val sim = sim()
        sim.changeFanLevel(1)
        assertEquals(3, sim.getClimateState().fanLevel)
        sim.setFanLevel(7)
        val atMax = sim.changeFanLevel(1) as VehicleActionResult.Success
        assertEquals(7, atMax.state.fanLevel)
        assertTrue(atMax.limitReached)
        assertEquals(7, sim.getClimateState().fanLevel)
    }

    @Test
    fun fanDecreaseBoundedAtMinimum() = runBlocking {
        val sim = sim()
        val result = sim.changeFanLevel(-5) as VehicleActionResult.Success
        assertEquals(0, result.state.fanLevel)
        assertTrue(result.limitReached)
    }

    @Test
    fun relativeTemperatureClampsAndReportsLimit() = runBlocking {
        val sim = sim()
        sim.setCabinTemperature(31.5)
        val result = sim.changeCabinTemperature(1.0) as VehicleActionResult.Success
        assertEquals(32.0, result.state.targetTemperatureCelsius)
        assertTrue(result.limitReached)
    }

    @Test
    fun outOfRangeAbsoluteValuesAreRejectedAndStateUnchanged() = runBlocking {
        val sim = sim()
        assertTrue(sim.setCabinTemperature(50.0) is VehicleActionResult.InvalidArgument)
        assertTrue(sim.setCabinTemperature(10.0) is VehicleActionResult.InvalidArgument)
        assertTrue(sim.setCabinTemperature(Double.NaN) is VehicleActionResult.InvalidArgument)
        assertTrue(sim.setFanLevel(8) is VehicleActionResult.InvalidArgument)
        assertTrue(sim.setFanLevel(-1) is VehicleActionResult.InvalidArgument)
        assertTrue(sim.changeCabinTemperature(Double.POSITIVE_INFINITY) is VehicleActionResult.InvalidArgument)
        assertEquals(ClimateState.DEFAULT, sim.getClimateState())
    }

    @Test
    fun offKeepsSetpoints() = runBlocking {
        val sim = sim()
        sim.setHvacPower(true)
        sim.setCabinTemperature(22.0)
        sim.setHvacPower(false)
        assertEquals(ClimateState(powerOn = false, targetTemperatureCelsius = 22.0, fanLevel = 2), sim.getClimateState())
    }

    @Test
    fun eachInjectedFailureKindIsReportedAndChangesNothing() = runBlocking {
        val expected = mapOf(
            InjectedClimateFailure.UNSUPPORTED to VehicleActionResult.Unsupported::class,
            InjectedClimateFailure.UNAVAILABLE to VehicleActionResult.Unavailable::class,
            InjectedClimateFailure.PERMISSION_DENIED to VehicleActionResult.PermissionDenied::class,
            InjectedClimateFailure.EXECUTION_FAILURE to VehicleActionResult.Failure::class,
        )
        for ((kind, type) in expected) {
            val sim = sim()
            sim.faults.failNext = kind
            val result = sim.setHvacPower(true)
            assertTrue(type.isInstance(result), "$kind -> $result")
            assertFalse(sim.getClimateState().powerOn)
            // failNext is one-shot
            assertTrue(sim.setHvacPower(true) is VehicleActionResult.Success)
        }
    }

    @Test
    fun persistentFailureAffectsOnlyTheListedOperation() = runBlocking {
        val sim = sim()
        sim.faults.failAlways[ClimateOperation.SET_TEMPERATURE] = InjectedClimateFailure.UNAVAILABLE
        assertTrue(sim.setCabinTemperature(22.0) is VehicleActionResult.Unavailable)
        assertTrue(sim.setCabinTemperature(22.0) is VehicleActionResult.Unavailable)
        assertEquals(24.0, sim.getClimateState().targetTemperatureCelsius)
        assertTrue(sim.setFanLevel(4) is VehicleActionResult.Success)
    }

    @Test
    fun stateFlowReflectsMutations() = runBlocking {
        val sim = sim()
        sim.setHvacPower(true)
        assertTrue(sim.climateState.value.powerOn)
    }

    @Test
    fun legacySimulatorSharesTheSameClimateState() {
        val legacy = InMemoryVehicleSimulator()
        legacy.execute(com.novadrive.vehicle.VehicleCommand.SetCabinTemperature(21.0))
        assertEquals(21.0, legacy.climate.climateState.value.targetTemperatureCelsius)
        assertEquals(21.0, legacy.observeHvac().cabinTemperatureCelsius)
    }

    // --- cabin: windows and seats ---

    private val allWindows = WindowId.entries.toSet()

    @Test
    fun cabinDefaultIsClosedWindowsAndMiddleSeats() = runBlocking {
        val cabin = sim().getCabinState()
        assertTrue(cabin.windows.values.all { it == 0 })
        assertTrue(cabin.seatHeights.values.all { it == CabinLimits.SEAT_DEFAULT })
    }

    @Test
    fun setWindowsAbsoluteAcceptsAnyIntegerPercent() = runBlocking {
        val sim = sim()
        val r = sim.setWindows(setOf(WindowId.FRONT_LEFT, WindowId.FRONT_RIGHT), 37) as VehicleActionResult.Success
        assertEquals(37, r.state.windows[WindowId.FRONT_LEFT])
        assertEquals(37, r.state.windows[WindowId.FRONT_RIGHT])
        assertEquals(0, r.state.windows[WindowId.REAR_LEFT])
        assertFalse(r.limitReached)
        assertEquals(r.state, sim.cabinState.value)
    }

    @Test
    fun setWindowsOutOfRangeIsAtomicInvalid() = runBlocking {
        val sim = sim()
        sim.setWindows(allWindows, 40)
        assertTrue(sim.setWindows(allWindows, 101) is VehicleActionResult.InvalidArgument)
        assertTrue(sim.setWindows(setOf(WindowId.REAR_LEFT), -1) is VehicleActionResult.InvalidArgument)
        assertTrue(sim.getCabinState().windows.values.all { it == 40 })
    }

    @Test
    fun emptyWindowSetIsInvalid() = runBlocking {
        val sim = sim()
        assertTrue(sim.setWindows(emptySet(), 50) is VehicleActionResult.InvalidArgument)
        assertTrue(sim.changeWindows(emptySet(), 20) is VehicleActionResult.InvalidArgument)
        assertEquals(CabinState.DEFAULT, sim.getCabinState())
    }

    @Test
    fun changeWindowsIsRelativeAndClampsPerWindow() = runBlocking {
        val sim = sim()
        sim.setWindows(setOf(WindowId.FRONT_LEFT), 90)
        val r = sim.changeWindows(setOf(WindowId.FRONT_LEFT, WindowId.FRONT_RIGHT), 20) as VehicleActionResult.Success
        assertEquals(100, r.state.windows[WindowId.FRONT_LEFT])
        assertEquals(20, r.state.windows[WindowId.FRONT_RIGHT])
        assertTrue(r.limitReached)
        val ok = sim.changeWindows(setOf(WindowId.FRONT_RIGHT), -10) as VehicleActionResult.Success
        assertEquals(10, ok.state.windows[WindowId.FRONT_RIGHT])
        assertFalse(ok.limitReached)
    }

    @Test
    fun changeWindowsAlreadyAtLimitReportsLimit() = runBlocking {
        val r = sim().changeWindows(allWindows, -CabinLimits.DEFAULT_WINDOW_STEP) as VehicleActionResult.Success
        assertTrue(r.limitReached)
        assertTrue(r.state.windows.values.all { it == 0 })
    }

    @Test
    fun seatAbsoluteBoundsAreRejected() = runBlocking {
        val sim = sim()
        assertTrue(sim.setSeatHeight(SeatId.DRIVER, 11) is VehicleActionResult.InvalidArgument)
        assertTrue(sim.setSeatHeight(SeatId.DRIVER, -1) is VehicleActionResult.InvalidArgument)
        val r = sim.setSeatHeight(SeatId.DRIVER, 10) as VehicleActionResult.Success
        assertEquals(10, r.state.seatHeights[SeatId.DRIVER])
        assertEquals(CabinLimits.SEAT_DEFAULT, r.state.seatHeights[SeatId.PASSENGER])
    }

    @Test
    fun seatRelativeClampsWithLimitReached() = runBlocking {
        val sim = sim()
        val down = sim.changeSeatHeight(SeatId.DRIVER, -1) as VehicleActionResult.Success
        assertEquals(4, down.state.seatHeights[SeatId.DRIVER])
        assertFalse(down.limitReached)
        val floor = sim.changeSeatHeight(SeatId.DRIVER, -9) as VehicleActionResult.Success
        assertEquals(0, floor.state.seatHeights[SeatId.DRIVER])
        assertTrue(floor.limitReached)
        val again = sim.changeSeatHeight(SeatId.DRIVER, -1) as VehicleActionResult.Success
        assertTrue(again.limitReached)
    }

    @Test
    fun cabinStateFlowEmitsChanges() = runBlocking {
        val sim = sim()
        val before = sim.cabinState.value
        sim.setSeatHeight(SeatId.PASSENGER, 2)
        assertEquals(2, sim.cabinState.value.seatHeights[SeatId.PASSENGER])
        assertTrue(before !== sim.cabinState.value)
        assertEquals(sim.getCabinState(), sim.cabinState.value)
    }

    @Test
    fun cabinStateValidatesItsInvariants() {
        val windows = WindowId.entries.associateWith { 0 }
        val seats = SeatId.entries.associateWith { 5 }
        assertThrows(IllegalArgumentException::class.java) { CabinState(windows - WindowId.REAR_LEFT, seats) }
        assertThrows(IllegalArgumentException::class.java) { CabinState(windows, seats - SeatId.DRIVER) }
        assertThrows(IllegalArgumentException::class.java) { CabinState(windows + (WindowId.FRONT_LEFT to 101), seats) }
        assertThrows(IllegalArgumentException::class.java) { CabinState(windows, seats + (SeatId.DRIVER to 11)) }
        assertEquals(CabinState(windows, seats), CabinState.DEFAULT)
    }
}
