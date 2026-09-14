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
}
