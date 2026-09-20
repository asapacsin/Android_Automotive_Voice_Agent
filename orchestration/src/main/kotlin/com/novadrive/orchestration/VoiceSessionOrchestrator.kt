package com.novadrive.orchestration

import com.novadrive.contracts.AdapterOutcome
import com.novadrive.contracts.ContactMatchKind
import com.novadrive.contracts.ContactResolution
import com.novadrive.contracts.ObservedVehicleState
import com.novadrive.contracts.OrchestrationResult
import com.novadrive.contracts.OrchestrationStatus
import com.novadrive.contracts.PolicyDecision
import com.novadrive.contracts.ResolvedContact
import com.novadrive.contracts.SafetyVerdict
import com.novadrive.contracts.StructuredCommand
import com.novadrive.contracts.VehicleSafetySnapshot
import com.novadrive.contracts.VerificationReport
import com.novadrive.contracts.VerificationStatus
import com.novadrive.feedback.ZhCnFeedbackRenderer
import com.novadrive.ingress.StructuredCommandIngress
import com.novadrive.safety.BootstrapCommandValidator
import com.novadrive.safety.CommandValidator
import com.novadrive.safety.SafetyPolicy
import com.novadrive.vehicle.VehicleCommand
import com.novadrive.vehicle.VehiclePort
import com.novadrive.verification.ObservedStateVerifier

internal class SkillRouter {
    fun toVehicleCommand(command: StructuredCommand, resolved: ResolvedContact?): VehicleCommand =
        when (command) {
            is StructuredCommand.StartNavigation -> VehicleCommand.StartRoute(command.destination)
            is StructuredCommand.CancelNavigation -> VehicleCommand.CancelRoute
            is StructuredCommand.PlayMedia -> VehicleCommand.Play(command.query)
            is StructuredCommand.PauseMedia -> VehicleCommand.Pause
            is StructuredCommand.SetVolume -> VehicleCommand.SetVolume(command.volumePercent)
            is StructuredCommand.PlaceCall -> {
                val contact = requireNotNull(resolved) { "PlaceCall requires a unique resolved contact" }
                VehicleCommand.Dial(contact)
            }
            is StructuredCommand.EndCall -> VehicleCommand.HangUp
            is StructuredCommand.SetCabinTemperature -> VehicleCommand.SetCabinTemperature(command.celsius)
            is StructuredCommand.SetFanLevel -> VehicleCommand.SetFanLevel(command.level)
        }
}

class VoiceSessionOrchestrator(
    private val policy: SafetyPolicy,
    private val vehicle: VehiclePort,
    private val verifier: ObservedStateVerifier = ObservedStateVerifier(),
    private val feedback: ZhCnFeedbackRenderer = ZhCnFeedbackRenderer(),
    private val validator: CommandValidator = BootstrapCommandValidator(),
    private val safetySnapshot: () -> VehicleSafetySnapshot = { vehicle.safetySnapshot() },
) : StructuredCommandIngress {
    private val router = SkillRouter()
    private val pending = linkedMapOf<String, PendingConfirmation>()
    private val cancelled = linkedSetOf<String>()

    override fun submit(command: StructuredCommand): OrchestrationResult {
        cancelOtherPending(command.correlationId)
        if (command.correlationId in cancelled) {
            return finish(command, OrchestrationStatus.CANCELLED, executed = false)
        }
        val invalid = validator.validate(command)
        if (invalid != null) {
            return finish(command, OrchestrationStatus.INVALID, executed = false)
        }
        val resolved = resolveIfNeeded(command)
        if (command is StructuredCommand.PlaceCall) {
            when (resolved.kind) {
                ContactMatchKind.NONE ->
                    return finish(command, OrchestrationStatus.INVALID, executed = false)
                ContactMatchKind.PERMISSION_DENIED ->
                    return finish(command, OrchestrationStatus.INVALID, executed = false)
                ContactMatchKind.AMBIGUOUS ->
                    return finish(
                        command,
                        OrchestrationStatus.CLARIFICATION_NEEDED,
                        executed = false,
                        clarification = resolved.candidates,
                    )
                ContactMatchKind.UNIQUE -> Unit
            }
        }
        val uniqueContact = resolved.candidates.singleOrNull()
        val verdict = policy.evaluate(command, safetySnapshot())
        return when (verdict.decision) {
            PolicyDecision.DENY ->
                finish(command, OrchestrationStatus.DENIED, policy = verdict, executed = false)
            PolicyDecision.CONFIRM -> {
                pending[command.correlationId] = PendingConfirmation(command, uniqueContact, verdict)
                finish(command, OrchestrationStatus.PENDING_CONFIRMATION, policy = verdict, executed = false)
            }
            PolicyDecision.ALLOW -> executeVerified(command, uniqueContact, verdict)
        }
    }

    fun confirm(correlationId: String): OrchestrationResult {
        if (correlationId in cancelled) {
            return orphanCancelled(correlationId)
        }
        val pendingCommand = pending.remove(correlationId)
            ?: return OrchestrationResult(
                correlationId = correlationId,
                status = OrchestrationStatus.INVALID,
                policy = null,
                verification = null,
                observedState = vehicle.observe(),
                feedbackZhCn = "没有待确认的请求。",
                executed = false,
            )
        val latest = policy.evaluate(pendingCommand.command, safetySnapshot())
        if (latest.decision == PolicyDecision.DENY) {
            return finish(pendingCommand.command, OrchestrationStatus.DENIED, policy = latest, executed = false)
        }
        return executeVerified(pendingCommand.command, pendingCommand.resolvedContact, latest)
    }

    fun cancel(correlationId: String): OrchestrationResult {
        pending.remove(correlationId)
        cancelled += correlationId
        return OrchestrationResult(
            correlationId = correlationId,
            status = OrchestrationStatus.CANCELLED,
            policy = null,
            verification = null,
            observedState = vehicle.observe(),
            feedbackZhCn = feedback.render(
                command = StructuredCommand.CancelNavigation(correlationId),
                status = OrchestrationStatus.CANCELLED,
                policy = null,
                verification = null,
            ),
            executed = false,
        )
    }

    fun interruptWith(command: StructuredCommand): OrchestrationResult {
        pending.keys.toList().forEach { cancel(it) }
        return submit(command)
    }

    fun pendingCorrelationIds(): Set<String> = pending.keys.toSet()

    private fun executeVerified(
        command: StructuredCommand,
        resolved: ResolvedContact?,
        verdict: SafetyVerdict,
    ): OrchestrationResult {
        val vehicleCommand = router.toVehicleCommand(command, resolved)
        val outcome = vehicle.execute(vehicleCommand)
        if (outcome is AdapterOutcome.Failed) {
            return finish(command, OrchestrationStatus.EXECUTION_FAILED, policy = verdict, executed = false)
        }
        val observed = vehicle.observe()
        val report = verifier.verify(command, vehicleCommand, observed)
        val status = if (report.status == VerificationStatus.MATCHED) {
            OrchestrationStatus.VERIFIED
        } else {
            OrchestrationStatus.VERIFICATION_FAILED
        }
        return finish(
            command,
            status,
            policy = verdict,
            verification = report,
            observed = observed,
            executed = true,
        )
    }

    private fun resolveIfNeeded(command: StructuredCommand): ContactResolution =
        if (command is StructuredCommand.PlaceCall) {
            vehicle.resolveContact(command.query)
        } else {
            ContactResolution(ContactMatchKind.NONE)
        }

    private fun cancelOtherPending(keep: String) {
        val stale = pending.keys.filter { it != keep }
        stale.forEach { id ->
            pending.remove(id)
            cancelled += id
        }
    }

    private fun finish(
        command: StructuredCommand,
        status: OrchestrationStatus,
        policy: SafetyVerdict? = null,
        verification: VerificationReport? = null,
        observed: ObservedVehicleState? = vehicle.observe(),
        executed: Boolean,
        clarification: List<ResolvedContact> = emptyList(),
    ): OrchestrationResult =
        OrchestrationResult(
            correlationId = command.correlationId,
            status = status,
            policy = policy,
            verification = verification,
            observedState = observed,
            feedbackZhCn = feedback.render(command, status, policy, verification, clarification),
            clarificationCandidates = clarification,
            executed = executed,
        )

    private fun orphanCancelled(correlationId: String): OrchestrationResult =
        OrchestrationResult(
            correlationId = correlationId,
            status = OrchestrationStatus.CANCELLED,
            policy = null,
            verification = null,
            observedState = vehicle.observe(),
            feedbackZhCn = "已取消该请求。",
            executed = false,
        )

    private data class PendingConfirmation(
        val command: StructuredCommand,
        val resolvedContact: ResolvedContact?,
        val verdict: SafetyVerdict,
    )
}
