package com.novadrive.simulator

import com.novadrive.vehicle.ClimateState
import com.novadrive.vehicle.VehicleActionResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
}
