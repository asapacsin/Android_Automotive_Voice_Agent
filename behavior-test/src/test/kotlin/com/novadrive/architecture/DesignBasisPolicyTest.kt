package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the hard-gate design-basis checker
 * ([scripts/design_basis.py](../../scripts/design_basis.py) plus
 * [.cursor/rules/design-basis-gate.mdc](../../.cursor/rules/design-basis-gate.mdc)).
 *
 * The failure this prevents: an agent invents a locally plausible mechanism for a difficult
 * problem, implements it, and passes some tests before discovering the architecture was wrong.
 */
class DesignBasisPolicyTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    private fun text(path: String): String = File(root, path).also {
        assertTrue(it.isFile, "missing file: $path")
    }.readText()

    private fun python(): String = System.getenv("PYTHON") ?: "python"

    private fun runDesignBasis(vararg args: String): Pair<Int, String> {
        val script = File(root, "scripts/design_basis.py")
        assertTrue(script.isFile, "missing scripts/design_basis.py")
        val command = mutableListOf(python(), script.absolutePath)
        command.addAll(args)
        val proc = ProcessBuilder(command)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        return proc.waitFor() to out
    }

    @Test
    fun registryAndProposalExist() {
        assertTrue(File(root, "harness/design_basis.yaml").isFile)
        assertTrue(File(root, "harness/proposals/HARNESS_PROPOSAL_004-design-basis-gate.md").isFile)
    }

    @Test
    fun harnessCheckWiresDesignBasisSelftestAndValidate() {
        val check = text("scripts/harness_check.py")
        assertTrue(check.contains("design_basis.py"), "harness_check must run design_basis --selftest")
        assertTrue(check.contains("harness/design_basis.yaml"), "harness_check must require the registry")
    }

    @Test
    fun discoverWorkSurfacesUnclearedDesignBasis() {
        val discover = text("scripts/discover_work.py")
        assertTrue(discover.contains("def design_basis_gaps("))
        assertTrue(discover.contains("design_basis_gaps"))
        val body = discover.substringAfter("def design_basis_gaps(").substringBefore("\ndef ")
        assertTrue(body.contains("needs_compilation=True"))
        assertTrue(body.contains("design_basis.yaml"))
    }

    @Test
    fun constitutionNamesTheGate() {
        val constitution = text("harness/CONSTITUTION.md")
        assertTrue(constitution.contains("DESIGN_BASIS_BEFORE_DIFFICULT_IMPLEMENTATION"))
        assertTrue(constitution.contains("scripts/design_basis.py"))
    }

    @Test
    fun selftestAndValidatePassOnEmptyRegistry() {
        val (selftestCode, selftestOut) = runDesignBasis("--selftest")
        assertEquals(0, selftestCode, selftestOut)
        val (validateCode, validateOut) = runDesignBasis("--validate")
        assertEquals(0, validateCode, validateOut)
    }
}
