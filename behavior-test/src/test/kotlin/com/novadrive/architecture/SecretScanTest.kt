package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SecretScanTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    @Test
    fun androidSourcesDoNotContainBaiduSecrets() {
        val roots = listOf("app/src", "ingress/src", "contracts/src", "orchestration/src")
        val hits = mutableListOf<String>()
        roots.forEach { rel ->
            File(root, rel).walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "xml", "properties", "kts", "json") }
                .forEach { file ->
                    val text = file.readText()
                    if (text.contains("BAIDU_SECRET_KEY=") || text.contains("BAIDU_API_KEY=") ||
                        text.contains("DASHSCOPE_API_KEY=") || text.contains("OPENAI_API_KEY=")
                    ) {
                        hits += file.relativeTo(root).path
                    }
                    if (text.contains("wss://aip.baidubce.com") && text.contains("access_token=")) {
                        hits += file.relativeTo(root).path
                    }
                }
        }
        assertTrue(hits.isEmpty()) { hits.joinToString("\n") }
    }

    @Test
    fun gitignoreCoversBackendEnv() {
        val gitignore = File(root, ".gitignore").readText()
        assertTrue(gitignore.contains("backend/.env"))
        val example = File(root, "backend/.env.example").readText()
        assertTrue(example.contains("YOUR_API_KEY"))
        assertTrue(example.contains("audio-mini-realtime-near"))
        assertTrue(example.contains("qwen-audio-3.0-realtime-flash"))
        assertFalse(example.contains("BAIDU_API_KEY=ak"))
    }
}
