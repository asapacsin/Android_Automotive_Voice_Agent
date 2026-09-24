package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The echo canceller is fetched, not committed, so the only record of which code the APK was built
 * from is the pinned revision. Both fetch paths must pin the same one, and the provenance document
 * must name it: a moved pin is a deliberate, documented change (docs/THIRD_PARTY_NATIVE.md).
 */
class NativeProvenanceTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))
    private val sha = Regex("[0-9a-f]{40}")

    private fun pinIn(path: String, marker: String): String {
        val line = File(root, path).readLines().firstOrNull { marker in it } ?: error("$path has no `$marker`")
        return sha.find(line)?.value ?: error("$path: `$marker` carries no full commit id")
    }

    @Test
    fun bothFetchPathsPinTheDocumentedWebRtcRevision() {
        val windows = pinIn("scripts/fetch_webrtc_aec3.ps1", "\$pin =")
        val cloud = pinIn("scripts/cloud_setup.sh", "WEBRTC_AEC3_PIN=")
        assertEquals(windows, cloud, "the Windows and cloud builds must fetch the same echo canceller")
        val doc = File(root, "docs/THIRD_PARTY_NATIVE.md").readText()
        assertTrue(doc.contains(windows), "docs/THIRD_PARTY_NATIVE.md must record the pinned revision $windows")
    }

    @Test
    fun theFetchIsByRevisionNotLatest() {
        listOf("scripts/fetch_webrtc_aec3.ps1", "scripts/cloud_setup.sh").forEach { path ->
            val text = File(root, path).readText()
            assertTrue(!text.contains("git clone"), "$path must fetch the pinned revision, not clone the latest")
            assertTrue(text.contains("FETCH_HEAD"), "$path must check out the fetched pin")
        }
    }
}
