package com.novadrive.orchestration

import com.novadrive.contracts.ContactQuery
import com.novadrive.contracts.CoordinateSystem
import com.novadrive.contracts.Destination
import com.novadrive.contracts.GeoCoordinate
import com.novadrive.contracts.OrchestrationStatus
import com.novadrive.contracts.PolicyDecision
import com.novadrive.contracts.StructuredCommand
import com.novadrive.safety.BootstrapSafetyPolicy
import com.novadrive.simulator.InMemoryVehicleSimulator
import com.novadrive.simulator.SimulatorFaults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceSessionOrchestratorBehaviorTest {
    private val plaza = Destination(
        label = "人民广场",
        poiName = "人民广场",
        coordinate = GeoCoordinate(31.2304, 121.4737, CoordinateSystem.GCJ02),
    )

    @Test
    fun allowHappyPathVerifiesNavigationAndReturnsZhCnFeedback() {
        val sim = InMemoryVehicleSimulator()
        val session = session(sim)
        val result = session.submit(StructuredCommand.StartNavigation("nav-1", plaza))
        assertEquals(OrchestrationStatus.VERIFIED, result.status)
        assertEquals(PolicyDecision.ALLOW, result.policy?.decision)
        assertTrue(result.executed)
        assertTrue(result.verifiedSuccess)
        assertEquals("已开始前往人民广场。", result.feedbackZhCn)
        assertTrue(sim.observeNavigation().active)
        assertEquals("人民广场", sim.observeNavigation().destinationLabel)
        assertEquals(CoordinateSystem.GCJ02, sim.observeNavigation().destinationCoordinate?.coordinateSystem)
    }

    @Test
    fun denyDoesNotExecuteRestrictedNavigation() {
        val sim = InMemoryVehicleSimulator()
        val session = session(sim)
        val result = session.submit(
            StructuredCommand.StartNavigation(
                "nav-deny",
                plaza.copy(restricted = true, label = "禁行区域"),
            ),
        )
        assertEquals(OrchestrationStatus.DENIED, result.status)
        assertEquals(PolicyDecision.DENY, result.policy?.decision)
        assertFalse(result.executed)
        assertFalse(sim.observeNavigation().active)
        assertEquals("目的地受限，无法开始导航。", result.feedbackZhCn)
    }

    @Test
    fun confirmStaysPendingUntilExplicitApprovalThenExecutesCall() {
        val sim = InMemoryVehicleSimulator()
        val session = session(sim)
        val pending = session.submit(
            StructuredCommand.PlaceCall("call-1", ContactQuery(spokenName = "李娜")),
        )
        assertEquals(OrchestrationStatus.PENDING_CONFIRMATION, pending.status)
        assertFalse(pending.executed)
        assertFalse(sim.observePhone().inCall)
        assertEquals("即将拨打电话，请确认。", pending.feedbackZhCn)

        val approved = session.confirm("call-1")
        assertEquals(OrchestrationStatus.VERIFIED, approved.status)
        assertTrue(approved.executed)
        assertTrue(sim.observePhone().inCall)
        assertEquals("13900003333", sim.observePhone().remoteNumber)
        assertEquals("正在呼叫。", approved.feedbackZhCn)
    }

    @Test
    fun invalidHvacBoundsDoNotExecute() {
        val sim = InMemoryVehicleSimulator()
        val session = session(sim)
        val result = session.submit(StructuredCommand.SetCabinTemperature("hvac-bad", 5.0))
        assertEquals(OrchestrationStatus.INVALID, result.status)
        assertFalse(result.executed)
        assertEquals(24.0, sim.observeHvac().cabinTemperatureCelsius)
        assertEquals("温度超出可调范围。", result.feedbackZhCn)
    }

    @Test
    fun executionFailureDoesNotClaimSuccess() {
        val faults = SimulatorFaults(failNextExecution = true)
        val sim = InMemoryVehicleSimulator(faults = faults)
        val session = session(sim)
        val result = session.submit(StructuredCommand.StartNavigation("nav-fail", plaza))
        assertEquals(OrchestrationStatus.EXECUTION_FAILED, result.status)
        assertFalse(result.executed)
        assertFalse(result.verifiedSuccess)
        assertFalse(sim.observeNavigation().active)
        assertEquals("执行失败，未完成操作。", result.feedbackZhCn)
    }

    @Test
    fun verificationMismatchDoesNotClaimSuccess() {
        val faults = SimulatorFaults(desyncNextObservation = true)
        val sim = InMemoryVehicleSimulator(faults = faults)
        val session = session(sim)
        val result = session.submit(StructuredCommand.StartNavigation("nav-desync", plaza))
        assertEquals(OrchestrationStatus.VERIFICATION_FAILED, result.status)
        assertTrue(result.executed)
        assertFalse(result.verifiedSuccess)
        assertFalse(sim.observeNavigation().active)
        assertEquals("未确认到车辆状态变化，不能视为成功。", result.feedbackZhCn)
    }

    @Test
    fun cancelPendingConfirmPreventsLaterApprovalAndExecution() {
        val sim = InMemoryVehicleSimulator()
        val session = session(sim)
        session.submit(StructuredCommand.PlaceCall("call-cancel", ContactQuery(spokenName = "李娜")))
        val cancelled = session.cancel("call-cancel")
        assertEquals(OrchestrationStatus.CANCELLED, cancelled.status)
        assertFalse(cancelled.executed)
        val after = session.confirm("call-cancel")
        assertEquals(OrchestrationStatus.CANCELLED, after.status)
        assertFalse(sim.observePhone().inCall)
    }

    @Test
    fun interruptCancelsPendingAndRunsNewAllowCommand() {
        val sim = InMemoryVehicleSimulator()
        val session = session(sim)
        session.submit(StructuredCommand.PlaceCall("call-interrupt", ContactQuery(spokenName = "李娜")))
        val interrupted = session.interruptWith(StructuredCommand.StartNavigation("nav-interrupt", plaza))
        assertEquals(OrchestrationStatus.VERIFIED, interrupted.status)
        assertTrue(sim.observeNavigation().active)
        assertFalse(sim.observePhone().inCall)
        assertTrue(session.pendingCorrelationIds().isEmpty())
        val lateConfirm = session.confirm("call-interrupt")
        assertEquals(OrchestrationStatus.CANCELLED, lateConfirm.status)
    }

    @Test
    fun ambiguousChineseNameRequiresClarificationWithoutDialing() {
        val sim = InMemoryVehicleSimulator()
        val session = session(sim)
        val result = session.submit(
            StructuredCommand.PlaceCall("call-zhang", ContactQuery(spokenName = "张伟")),
        )
        assertEquals(OrchestrationStatus.CLARIFICATION_NEEDED, result.status)
        assertEquals(2, result.clarificationCandidates.size)
        assertFalse(result.executed)
        assertFalse(sim.observePhone().inCall)
        assertTrue(result.feedbackZhCn.contains("请说明要打给哪一位"))
    }

    @Test
    fun policyCannotBeBypassedBySubmittingDirectlyThroughIngress() {
        val sim = InMemoryVehicleSimulator(callsBlocked = true)
        val session = session(sim)
        val result = session.submit(
            StructuredCommand.PlaceCall("call-blocked", ContactQuery(spokenName = "李娜")),
        )
        assertEquals(OrchestrationStatus.DENIED, result.status)
        assertFalse(result.executed)
        assertFalse(sim.observePhone().inCall)
    }

    @Test
    fun allowMediaPlayThenPause() {
        val sim = InMemoryVehicleSimulator()
        val session = session(sim)
        val played = session.submit(StructuredCommand.PlayMedia("media-1", "周杰伦"))
        assertEquals(OrchestrationStatus.VERIFIED, played.status)
        assertEquals("正在播放周杰伦。", played.feedbackZhCn)
        val paused = session.submit(StructuredCommand.PauseMedia("media-2"))
        assertEquals(OrchestrationStatus.VERIFIED, paused.status)
        assertFalse(sim.observeMedia().playing)
    }

    @Test
    fun confirmHighVolumeThenApply() {
        val sim = InMemoryVehicleSimulator()
        val session = session(sim)
        val pending = session.submit(StructuredCommand.SetVolume("vol-1", 85))
        assertEquals(OrchestrationStatus.PENDING_CONFIRMATION, pending.status)
        assertEquals(40, sim.observeMedia().volumePercent)
        val approved = session.confirm("vol-1")
        assertEquals(OrchestrationStatus.VERIFIED, approved.status)
        assertEquals(85, sim.observeMedia().volumePercent)
        assertEquals("音量已设为85。", approved.feedbackZhCn)
    }

    @Test
    fun metricHvacAllowPathUsesCelsius() {
        val sim = InMemoryVehicleSimulator()
        val session = session(sim)
        val result = session.submit(StructuredCommand.SetCabinTemperature("hvac-ok", 22.0))
        assertEquals(OrchestrationStatus.VERIFIED, result.status)
        assertEquals(22.0, sim.observeHvac().cabinTemperatureCelsius)
        assertEquals("温度已设为22度。", result.feedbackZhCn)
    }

    private fun session(sim: InMemoryVehicleSimulator) =
        VoiceSessionOrchestrator(BootstrapSafetyPolicy(), sim)
}
