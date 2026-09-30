package com.novadrive.app.tools

import com.novadrive.app.voice.RealtimeToolCatalog.ToolSpec
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolRegistryTest {
    private fun domain(id: String, vararg names: String) = object : ToolDomain {
        override val id = id
        override fun specs() = names.map { ToolSpec(it, "d", JSONObject()) }
        override fun validate(name: String, args: JSONObject): String? = null
    }

    @Test
    fun `a tool declared by two domains is rejected`() {
        assertThrows<IllegalArgumentException> { ToolRegistry(listOf(domain("a", "x"), domain("b", "x"))) }
    }

    @Test
    fun `a repeated domain id is rejected`() {
        assertThrows<IllegalArgumentException> { ToolRegistry(listOf(domain("a", "x"), domain("a", "y"))) }
    }

    @Test
    fun `every product tool has one domain and a spec`() {
        val expected = mapOf(
            "navigate_to" to "navigation", "choose_navigation_option" to "navigation",
            "exit_navigation_mode" to "navigation", "save_place" to "navigation",
            "open_app" to "apps", "control_music" to "media", "control_climate" to "climate",
            "control_window" to "body", "control_seat" to "body",
            "describe_camera_view" to "vision", "place_call" to "phone", "query_live_info" to "live_info",
            "end_conversation" to "speech", "set_speech_output" to "speech", "set_speaking_style" to "speech",
        )
        val registry = ToolRegistry.PRODUCT
        assertEquals(expected.keys, registry.tools().map { it.name }.toSet())
        expected.forEach { (name, id) ->
            assertEquals(id, registry.domainOf(name)?.id, name)
            assertEquals(name, registry.spec(name)?.name)
            assertSame(registry.domainOf(name), registry.domainOf(name))
        }
    }

    @Test
    fun `unknown tool has no domain, spec or validation code`() {
        val registry = ToolRegistry.PRODUCT
        assertNull(registry.domainOf("open_window"))
        assertNull(registry.spec("open_window"))
        assertNull(registry.validate("open_window", JSONObject().put("a", 1)))
    }

    @Test
    fun `repeat-sensitive tools are exactly the world-acting eleven`() {
        assertEquals(
            setOf(
                "control_climate", "control_music", "query_live_info", "place_call",
                "navigate_to", "open_app", "save_place", "exit_navigation_mode",
                "control_window", "control_seat", "set_speaking_style",
            ),
            ToolRegistry.PRODUCT.tools().filter { it.repeatSensitive }.map { it.name }.toSet(),
        )
        assertEquals(
            ToolRegistry.PRODUCT.tools().filter { it.repeatSensitive }.map { it.name }.toSet(),
            ToolRegistry.PRODUCT.repeatSensitiveNames,
        )
    }
}
