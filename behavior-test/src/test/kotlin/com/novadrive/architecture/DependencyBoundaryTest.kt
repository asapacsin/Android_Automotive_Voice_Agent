package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DependencyBoundaryTest {
    private val coreModules = listOf(
        "contracts",
        "ingress",
        "safety",
        "vehicle",
        "verification",
        "feedback",
        "orchestration",
    )

    private val forbiddenImports = listOf(
        "com.novadrive.simulator",
        "android.car",
        "com.amap",
        "com.baidu",
        "com.google.android.gms",
        "com.openai",
        "com.alibaba.dashscope",
        "com.dashscope",
    )

    @Test
    fun speechAndCoreModulesDoNotImportSimulatorProviderSdkOrVhal() {
        val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))
        val violations = mutableListOf<String>()
        coreModules.forEach { module ->
            val src = File(root, "$module/src/main")
            src.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("import ")) {
                            forbiddenImports.forEach { forbidden ->
                                if (trimmed.contains(forbidden)) {
                                    violations += "${file.relativeTo(root)}:${index + 1} $trimmed"
                                }
                            }
                        }
                    }
                }
        }
        assertTrue(violations.isEmpty()) { violations.joinToString("\n") }
    }

    @Test
    fun gradleKeepsSimulatorOffCoreImplementationClasspaths() {
        val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))
        coreModules.forEach { module ->
            val gradle = File(root, "$module/build.gradle.kts").readText()
            assertTrue(!gradle.contains("project(\":simulator\")")) {
                "$module implementation must not depend on :simulator"
            }
        }
        val ingress = File(root, "ingress/build.gradle.kts").readText()
        assertTrue(ingress.contains("api(project(\":contracts\"))"))
        assertTrue(!ingress.contains("project(\":vehicle\")"))
        assertTrue(!ingress.contains("project(\":orchestration\")"))
    }

    /**
     * Vehicle control: only the provider may name a concrete backend. Dispatcher, tool handler,
     * voice and UI code must depend on VehicleControlPort, so a real-vehicle adapter can replace
     * the simulator by changing one file.
     */
    @Test
    fun onlyTheVehicleProviderNamesAConcreteVehicleBackend() {
        val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))
        val allowed = "app/src/main/kotlin/com/novadrive/app/vehicle/VehicleControlProvider.kt"
        val backends = listOf("SimulatedVehicleControl", "InMemoryVehicleSimulator")
        val violations = File(root, "app/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.relativeTo(root).invariantSeparatorsPath != allowed }
            .filter { file -> backends.any { file.readText().contains(it) } }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        // DeveloperSettingsActivity's legacy orchestration demo predates the port and is
        // navigation-only; it is the single tolerated exception and must not grow.
        assertTrue(
            violations == listOf("app/src/main/kotlin/com/novadrive/app/DeveloperSettingsActivity.kt") || violations.isEmpty(),
            "concrete vehicle backend referenced outside the provider: $violations",
        )
        val provider = File(root, allowed).readText()
        assertTrue(provider.contains("SimulatedVehicleControl"), "provider must select the simulated backend on the phone build")
    }
}
