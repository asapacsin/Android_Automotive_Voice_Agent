package com.novadrive.contracts

enum class OrchestrationStatus {
    VERIFIED,
    DENIED,
    PENDING_CONFIRMATION,
    INVALID,
    EXECUTION_FAILED,
    VERIFICATION_FAILED,
    CANCELLED,
    CLARIFICATION_NEEDED,
}

data class OrchestrationResult(
    val correlationId: String,
    val status: OrchestrationStatus,
    val policy: SafetyVerdict?,
    val verification: VerificationReport?,
    val observedState: ObservedVehicleState?,
    val feedbackZhCn: String,
    val clarificationCandidates: List<ResolvedContact> = emptyList(),
    val executed: Boolean,
) {
    val verifiedSuccess: Boolean get() = status == OrchestrationStatus.VERIFIED
}

sealed interface AdapterOutcome {
    data object Applied : AdapterOutcome
    data class Failed(val reasonCode: String) : AdapterOutcome
}
