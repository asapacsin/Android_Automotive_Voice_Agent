package com.novadrive.app.vehicle

import com.novadrive.simulator.SimulatedVehicleControl
import com.novadrive.vehicle.VehicleControlPort

/**
 * Selects the vehicle-control backend. This is the ONLY production file allowed to name a
 * concrete implementation; everything else takes a [VehicleControlPort].
 *
 * Phone build: a SIMULATED climate backend. This app does not control a real vehicle. To target
 * a car, return an Android Automotive / CAN / OEM adapter here — no change to the tool schema,
 * the dispatcher or the UI is required.
 */
object VehicleControlProvider {
    const val BACKEND_LABEL = "simulated"

    private val simulatedBackend by lazy { SimulatedVehicleControl() }

    val port: VehicleControlPort get() = simulatedBackend

    /**
     * The simulated backend for the debug benchmark (state reset, fault injection). Null once a
     * real vehicle backend is returned by [port].
     */
    val simulated: SimulatedVehicleControl? get() = simulatedBackend
}
