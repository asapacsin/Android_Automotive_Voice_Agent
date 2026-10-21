package com.novadrive.app.tools

import com.novadrive.app.voice.RealtimeToolCatalog
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Characterisation (SPEC-016 B): every tool's name, description and parameter schema, captured from
 * the catalogue before it was split into domains. Per-tool byte identity; declaration order is free.
 */
class ToolDeclarationsGoldenTest {
    @Test
    fun `every tool declaration is byte-identical to the pre-split golden`() {
        val golden = JSONObject(
            requireNotNull(javaClass.classLoader.getResource("tool-declarations-golden.json")).readText(),
        )
        assertEquals(render(RealtimeToolCatalog.tools()), normalise(golden))
    }

    private fun normalise(o: JSONObject): Map<String, Pair<String, String>> =
        o.keySet().associateWith { k ->
            val e = o.getJSONObject(k)
            e.getString("description") to e.getString("parameters")
        }

    companion object {
        fun render(specs: List<RealtimeToolCatalog.ToolSpec>): Map<String, Pair<String, String>> =
            specs.associate { it.name to (it.description to it.parameters.toString()) }
    }
}
