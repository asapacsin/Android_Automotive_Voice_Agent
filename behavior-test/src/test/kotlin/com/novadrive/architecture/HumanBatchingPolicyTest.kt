package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the rule that a human is a phase boundary, not an exception handler
 * ([CONSTITUTION.md](../../harness/CONSTITUTION.md) rules 17-19).
 *
 * The failure this prevents is the one the rule was written for: an agent meets a test only a
 * person can run, stops, asks, waits, resumes, and meets the next one. Every human-dependent case
 * became its own interruption, and between them the agent sat idle while work it could have done
 * unaided went untouched.
 *
 * A rule like that decays quietly - the text survives, the mechanism that made it true gets
 * refactored away, and nobody notices until an agent stops early again. So these assert the
 * *mechanism*: the registry, its owner and status vocabularies, and the fact that a queued human
 * item is not counted as work.
 */
class HumanBatchingPolicyTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    private fun text(path: String): String = File(root, path).also {
        assertTrue(it.isFile, "missing file: $path")
    }.readText()

    /** Markdown wraps; a claim that spans a line break is the same claim. */
    private fun flat(path: String): String = text(path).lines().joinToString(" ") { it.trim() }

    @Test
    fun theRegistryExistsAndIsTheSourceOfTruth() {
        val matrix = text("TEST_MATRIX.yaml")
        assertTrue(matrix.contains("harness_version:"), "the registry must declare its version")
        assertTrue(matrix.contains("tests:"), "the registry must hold tests")
        // The generated views must say they are generated, or one of them becomes a second truth.
        listOf("TEST_STATUS.md", "HUMAN_VALIDATION.md").forEach { view ->
            assertTrue(
                text(view).contains("Generated from [TEST_MATRIX.yaml]"),
                "$view must declare that it is generated from the registry",
            )
        }
    }

    @Test
    fun ownerAndStatusAreSpecificRatherThanOneGenericBlocked() {
        // Four different reasons a test is not passing are four different facts, acted on
        // differently. Collapsing them into BLOCKED loses the only information that matters:
        // who can unblock it.
        val matrix = text("TEST_MATRIX.yaml")
        listOf(
            "HUMAN_PHYSICAL", "HUMAN_ACCOUNT", "HUMAN_CREDENTIAL", "HUMAN_DECISION",
            "EXTERNAL_RESOURCE", "AUTONOMOUS",
        ).forEach { owner ->
            assertTrue(matrix.contains(owner), "the owner vocabulary must include $owner")
        }
        listOf("HUMAN_REQUIRED", "BLOCKED_EXTERNAL", "BLOCKED_AUTONOMOUS", "NOT_APPLICABLE")
            .forEach { status ->
                assertTrue(matrix.contains(status), "the status vocabulary must include $status")
            }
    }

    @Test
    fun aQueuedHumanItemIsNotTreatedAsWork() {
        // The core of the change, asserted where it is implemented: the reader that feeds the
        // frontier selects on owner == AUTONOMOUS, so a HUMAN_REQUIRED entry can never become the
        // next action and can never make the run stop.
        val matrix = text("scripts/test_matrix.py")
        assertTrue(
            matrix.contains("def autonomous_work("),
            "the registry must expose what is actually work",
        )
        val body = matrix.substringAfter("def autonomous_work(").substringBefore("\ndef ")
        assertTrue(
            body.contains("AUTONOMOUS"),
            "autonomous_work must select on the AUTONOMOUS owner, not on status alone",
        )
        val discover = text("scripts/discover_work.py")
        assertTrue(
            discover.contains("unsettled_matrix_tests"),
            "the frontier must read the registry",
        )
    }

    @Test
    fun theGateIsMechanicalAndRequiresTwoCleanPasses() {
        val script = text("scripts/test_matrix.py")
        assertTrue(script.contains("HUMAN_VALIDATION_READY"), "the gate must have a name")
        assertTrue(
            script.contains("consecutive clean"),
            "two consecutive clean passes must be part of the gate, or 'backlog empty' returns",
        )
        val constitution = text("harness/CONSTITUTION.md")
        assertTrue(constitution.contains("HUMAN_INTERVENTION_IS_BATCHED"))
        assertTrue(
            flat("harness/CONSTITUTION.md").contains("is no longer the transition"),
            "the constitution must retire AUTONOMOUS_ACTION_AVAILABLE as the phase transition",
        )
        assertTrue(text("harness/PHASES.md").contains("HUMAN_VALIDATION_READY"))
    }

    @Test
    fun aDecisionReachesAHumanQuantified() {
        // "Should we drop armeabi-v7a?" is not a question anyone can answer. The sizes are.
        val script = text("scripts/test_matrix.py")
        assertTrue(
            script.contains("quantified"),
            "a HUMAN_DECISION with nothing measured must fail validation",
        )
        assertTrue(script.contains("reduce it to evidence"))
    }

    @Test
    fun humanRequiredNeedsAConcreteAutomationBlocker() {
        val script = text("scripts/test_matrix.py")
        assertTrue(script.contains("AUTOMATION_BLOCKERS"))
        assertTrue(script.contains("subjective_perception"))
        assertTrue(script.contains("not a legal automation blocker"))
        assertTrue(
            flat("harness/CONSTITUTION.md").contains("Tapping a device, ADB, starting an emulator"),
            "constitution rule 17 must name the illegal human excuses",
        )
    }

    @Test
    fun everyQueuedHumanItemTellsThePersonWhatToDoAndWhatToReport() {
        // A packet that sends someone away without the procedure or the expected result buys one
        // interruption and costs another.
        val packet = text("HUMAN_VALIDATION.md")
        assertTrue(packet.contains("**Why this needs you.**"))
        assertTrue(packet.contains("**What to do:**"))
        assertTrue(packet.contains("**It passes if:**"))
        assertFalse(
            packet.contains("TODO") || packet.contains("TBD"),
            "an unfinished packet is worse than none: it looks ready",
        )
    }
}
