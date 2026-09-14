package com.novadrive.safety

import com.novadrive.contracts.PolicyDecision
import com.novadrive.contracts.StructuredCommand
import com.novadrive.contracts.VehicleSafetySnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BootstrapSafetyPolicyTest {
    private val policy = BootstrapSafetyPolicy()
    private val snapshot = VehicleSafetySnapshot(cabinTemperatureCelsius = 24.0)

    @Test
    fun highVolumeRequiresConfirmation() {
        val verdict = policy.evaluate(StructuredCommand.SetVolume("vol-high", 85), snapshot)
        assertEquals(PolicyDecision.CONFIRM, verdict.decision)
    }

    @Test
    fun moderateVolumeIsAllowed() {
        val verdict = policy.evaluate(StructuredCommand.SetVolume("vol-ok", 40), snapshot)
        assertEquals(PolicyDecision.ALLOW, verdict.decision)
    }
}
