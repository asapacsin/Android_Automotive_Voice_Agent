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

    val port: VehicleControlPort by lazy { SimulatedVehicleControl() }
}
