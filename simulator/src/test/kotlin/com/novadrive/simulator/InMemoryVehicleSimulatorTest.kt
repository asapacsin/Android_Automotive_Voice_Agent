package com.novadrive.simulator

import com.novadrive.contracts.AdapterOutcome
import com.novadrive.contracts.CoordinateSystem
import com.novadrive.contracts.Destination
import com.novadrive.contracts.GeoCoordinate
import com.novadrive.vehicle.VehicleCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InMemoryVehicleSimulatorTest {
    @Test
    fun mutatesAndIndependentlyExposesAllFourDomains() {
        val sim = InMemoryVehicleSimulator()
        val dest = Destination(
            label = "外滩",
            coordinate = GeoCoordinate(31.240, 121.490, CoordinateSystem.GCJ02),
        )
        assertTrue(sim.execute(VehicleCommand.StartRoute(dest)) is AdapterOutcome.Applied)
        assertTrue(sim.execute(VehicleCommand.Play("周杰伦")) is AdapterOutcome.Applied)
        assertTrue(sim.execute(VehicleCommand.SetVolume(30)) is AdapterOutcome.Applied)
        val liNa = InMemoryVehicleSimulator.defaultContacts().first { it.displayName == "李娜" }
        assertTrue(sim.execute(VehicleCommand.Dial(liNa)) is AdapterOutcome.Applied)
        assertTrue(sim.execute(VehicleCommand.SetCabinTemperature(23.0)) is AdapterOutcome.Applied)
        assertTrue(sim.execute(VehicleCommand.SetFanLevel(4)) is AdapterOutcome.Applied)

        assertEquals("外滩", sim.observeNavigation().destinationLabel)
        assertEquals(CoordinateSystem.GCJ02, sim.observeNavigation().destinationCoordinate?.coordinateSystem)
        assertTrue(sim.observeMedia().playing)
        assertEquals("周杰伦", sim.observeMedia().title)
        assertEquals(30, sim.observeMedia().volumePercent)
        assertTrue(sim.observePhone().inCall)
        assertEquals("13900003333", sim.observePhone().remoteNumber)
        assertEquals(23.0, sim.observeHvac().cabinTemperatureCelsius)
        assertEquals(4, sim.observeHvac().fanLevel)

        val observed = sim.observe()
        assertEquals(sim.observeNavigation(), observed.navigation)
        assertEquals(sim.observeMedia(), observed.media)
        assertEquals(sim.observePhone(), observed.phone)
        assertEquals(sim.observeHvac(), observed.hvac)
    }

    @Test
    fun injectedFailuresArePredictable() {
        val faults = SimulatorFaults(failNextExecution = true)
        val sim = InMemoryVehicleSimulator(faults = faults)
        val failed = sim.execute(VehicleCommand.Pause)
        assertTrue(failed is AdapterOutcome.Failed)
        assertFalse(sim.observeMedia().playing)
        assertTrue(sim.execute(VehicleCommand.Pause) is AdapterOutcome.Applied)
    }
}
