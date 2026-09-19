package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The provider boundary: vendor protocol vocabulary lives in adapters, and nowhere else.
 *
 * This exists because it was breached and nothing noticed. `ActionClaimGuard` and
 * `ConversationResetPolicy` — per-turn *policy*, not adapters — took `outputKinds: List<String>`
 * and asked `"function_call" in outputKinds`, where that string came straight out of Baidu's
 * `output[].type`. A second provider would have had to fabricate the literal word `"function_call"`
 * to reuse policy that has nothing to do with Baidu.
 *
 * The rule ([ADR-009](../../DECISIONS/ADR-009-provider-neutral-realtime-contract.md)): core and
 * policy consume `DomainVoiceEvent`, `ResponseOutcome` and `ProviderCapabilities`. Which wire
 * format said what is the adapter's business.
 */
class ProviderBoundaryTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    private fun kotlinFiles(vararg dirs: String): List<File> =
        dirs.flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }

    private fun relative(file: File) = file.relativeTo(root).path.replace('\\', '/')

    /** Source with block and line comments removed, so prose about the rule cannot trip it. */
    private fun withoutComments(source: String): String =
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
            .replace(source, "")
            .lineSequence()
            .joinToString("\n") { line -> line.substringBefore("//") }

    @Test
    fun coreAndPolicyDoNotSpeakAVendorWireFormat() {
        val offenders = kotlinFiles(*CORE_AND_POLICY)
            .filterNot { relative(it) in ADAPTERS }
            .mapNotNull { file ->
                // Comments are where this rule gets *explained*, so checking them would make the
                // guard fire on its own documentation. Only code counts.
                val code = withoutComments(file.readText())
                val found = WIRE_VOCABULARY.filter { code.contains("\"$it\"") }
                if (found.isEmpty()) null else "${relative(file)} -> $found"
            }
        assertTrue(offenders.isEmpty()) {
            "Provider wire vocabulary outside an adapter (ADR-009). Translate it at the adapter " +
                "boundary into DomainVoiceEvent / ResponseOutcome instead: $offenders"
        }
    }

    @Test
    fun theProviderNeutralCoreNamesNoVendorSdk() {
        // `ingress` is the provider-neutral core: it must compile without knowing any vendor
        // exists. Importing a vendor SDK there would make "swap the provider" a rewrite.
        val offenders = kotlinFiles("ingress/src/main")
            .mapNotNull { file ->
                val imports = file.readText().lineSequence().filter { it.startsWith("import ") }
                val bad = imports.filter { line -> VENDOR_PACKAGES.any { line.contains(it) } }.toList()
                if (bad.isEmpty()) null else "${relative(file)} -> $bad"
            }
        assertTrue(offenders.isEmpty()) {
            "the provider-neutral core imported a vendor SDK: $offenders"
        }
    }

    @Test
    fun theAdapterBoundaryStillExists() {
        // If these disappear, the boundary has been refactored away and the tests above would
        // pass vacuously — they only assert an absence.
        val contract = File(root, "ingress/src/main/kotlin/com/novadrive/ingress/realtime/RealtimeVoiceProvider.kt")
        assertTrue(contract.isFile) { "RealtimeVoiceProvider is the seam; it must exist" }
        val text = contract.readText()
        listOf("capabilities", "connect", "disconnect", "sendAudio", "sendToolResult")
            .forEach { assertTrue(text.contains(it)) { "the provider contract must still declare $it" } }

        val outcome = File(root, "ingress/src/main/kotlin/com/novadrive/ingress/realtime/ResponseOutcome.kt")
        assertTrue(outcome.isFile) {
            "ResponseOutcome is how a completed response crosses the boundary without a wire format"
        }
    }

    private companion object {
        /** Everything that must stay provider-neutral: the core, and the per-turn policy. */
        val CORE_AND_POLICY = arrayOf(
            "ingress/src/main",
            "contracts/src/main",
            "orchestration/src/main",
            "app/src/main/kotlin/com/novadrive/app/voice",
            "app/src/main/kotlin/com/novadrive/app/nav",
        )

        /**
         * The files allowed to know a wire format, because translating it is their job. Adding to
         * this list is a real decision: it means a new file now speaks a vendor's protocol.
         */
        val ADAPTERS = setOf(
            "app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexClient.kt",
            "app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexProtocol.kt",
            "app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexProvider.kt",
            "app/src/main/kotlin/com/novadrive/app/voice/BaiduRealtimeClient.kt",
            "app/src/main/kotlin/com/novadrive/app/voice/BaiduProtocol.kt",
            "app/src/main/kotlin/com/novadrive/app/voice/BaiduDirectRealtimeProvider.kt",
            "app/src/main/kotlin/com/novadrive/app/voice/FlexFunctionCallAssembler.kt",
        )

        /**
         * Realtime-protocol event and field names. Deliberately not every vendor word — only the
         * ones that decide behaviour if they leak, which is what made the original breach matter.
         */
        val WIRE_VOCABULARY = listOf(
            "function_call",
            "input_audio_buffer",
            "response.created",
            "response.done",
            "session.updated",
            "conversation.item",
            "input_audio_buffer.speech_started",
        )

        val VENDOR_PACKAGES = listOf(
            "com.baidu", "com.iflytek", "com.amap", "com.alibaba.dashscope", "com.openai",
        )
    }
}
