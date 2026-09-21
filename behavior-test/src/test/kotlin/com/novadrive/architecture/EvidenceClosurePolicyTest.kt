package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards CONSTITUTION rule 20: a successful prefix must never be promoted into a broader lifecycle
 * claim without observing that claim's terminal oracle.
 *
 * This exists because a navigation recording showed valid launch/route/guidance observations but
 * never reached arrival; the run was manually stopped and was nevertheless summarized as a stable
 * navigation baseline. The rule therefore guards the mechanism, not just the wording.
 */
class EvidenceClosurePolicyTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    private fun text(path: String): String = File(root, path).also {
        assertTrue(it.isFile, "missing file: $path")
    }.readText()

    @Test
    fun constitutionAndAgentEntryPointCarryTheRule() {
        assertTrue(text("harness/CONSTITUTION.md").contains("CLAIM_SCOPE_MUST_MATCH_EVIDENCE"))
        assertTrue(text("AGENTS.md").contains("terminal oracle"))
    }

    @Test
    fun verificationProcedureInspectsTheWholeClaimWindow() {
        val verify = text("skills/verify.md")
        listOf(
            "start boundary",
            "terminal oracle",
            "abort conditions",
            "prefix evidence only",
            "Manual stop",
        ).forEach { token ->
            assertTrue(verify.contains(token, ignoreCase = true)) {
                "skills/verify.md must preserve evidence-closure concept: $token"
            }
        }
    }

    @Test
    fun specTemplateRequiresLifecycleEvidenceBoundary() {
        val template = text("SPECS/SPEC-TEMPLATE.md")
        listOf(
            "## Evidence boundary",
            "Start boundary",
            "Terminal oracle",
            "Abort conditions",
            "claim_scope: LIFECYCLE",
            "terminal_result",
        ).forEach { token ->
            assertTrue(template.contains(token)) {
                "SPEC template must preserve lifecycle evidence boundary: $token"
            }
        }
    }

    @Test
    fun registryValidatorOwnsLifecycleClosure() {
        val validator = text("scripts/test_matrix.py")
        listOf(
            "CLAIM_SCOPES",
            "LIFECYCLE_HINTS",
            "lifecycle_claim_problems",
            "terminal_oracle",
            "abort_conditions",
            "terminal_result",
            "terminal_evidence",
            "lifecycle PASS requires terminal_result: OBSERVED",
        ).forEach { token ->
            assertTrue(validator.contains(token)) {
                "test_matrix.py must enforce lifecycle closure: $token"
            }
        }
    }

    @Test
    fun navigationArrivalGateCannotBeClosedByManualStop() {
        val matrix = text("TEST_MATRIX.yaml")
        val nav = matrix.substringAfter("id: NAV-DRIVE-001").substringBefore("\n  - id:")
        assertTrue(nav.contains("claim_scope: LIFECYCLE"))
        assertTrue(nav.contains("terminal_result: NOT_OBSERVED"))
        assertTrue(nav.contains("manual nav_stop before natural completion"))
        assertTrue(nav.contains("arrival/completion"))
    }
}
