package com.novadrive.orchestration

import com.novadrive.contracts.OrchestrationStatus
import com.novadrive.ingress.realtime.BaiduRealtimeCapabilities
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.MockRealtimeVoiceProvider
import com.novadrive.ingress.realtime.RealtimeToolDispatcher
import com.novadrive.ingress.realtime.hvacSuccessChip
import com.novadrive.safety.BootstrapSafetyPolicy
import com.novadrive.simulator.InMemoryVehicleSimulator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RealtimeToolDispatchBehaviorTest {
    @Test
    fun mockStructuredSetTemperatureVerifiesSimulatorState() {
        val sim = InMemoryVehicleSimulator()
        val dispatcher = RealtimeToolDispatcher(VoiceSessionOrchestrator(BootstrapSafetyPolicy(), sim))
        val provider = MockRealtimeVoiceProvider(autoReply = true, emitToolCall = true)
        provider.connect()
        provider.sendAudio(ByteArray(2000))
        val call = provider.receiveEvents().filterIsInstance<DomainVoiceEvent.ToolCall>().single()
        val dispatched = dispatcher.dispatch(call)
        assertEquals(OrchestrationStatus.VERIFIED, dispatched.orchestration?.status)
        assertEquals(22.0, sim.observeHvac().cabinTemperatureCelsius)
        assertEquals("温度已设为22度。", dispatched.orchestration?.feedbackZhCn)
        assertEquals("✓ 主驾 22°C", dispatched.successChip)
        assertTrue(dispatched.orchestration!!.verifiedSuccess)
    }

    @Test
    fun proseIsNotParsedAsAToolCall() {
        val sim = InMemoryVehicleSimulator()
        val dispatcher = RealtimeToolDispatcher(VoiceSessionOrchestrator(BootstrapSafetyPolicy(), sim))
        val unknown =
            dispatcher.dispatch(
                DomainVoiceEvent.ToolCall("x", "please set the AC to twenty two", emptyMap()),
            )
        assertNull(unknown.orchestration)
        assertEquals("UNKNOWN_TOOL", unknown.blockedReason)
        assertEquals(24.0, sim.observeHvac().cabinTemperatureCelsius)
        assertFalse(BaiduRealtimeCapabilities.CUSTOM_FUNCTION_CALLING)
        assertEquals("✓ 主驾 22°C", hvacSuccessChip("driver", 22.0))
    }
}
