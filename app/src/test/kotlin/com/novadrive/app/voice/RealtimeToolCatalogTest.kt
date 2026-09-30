package com.novadrive.app.voice

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RealtimeToolCatalogTest {
    private fun golden(name: String): String =
        requireNotNull(javaClass.classLoader.getResource("golden/$name")) { "missing golden $name" }.readText()

    /** Characterisation: captured from sessionUpdate before the catalogue was extracted. */
    @Test
    fun `baidu session update is byte-identical to the pre-extraction golden`() {
        assertEquals(toolOrderFree(golden("baidu_session_update.json")), toolOrderFree(BaiduFlexProtocol.sessionUpdate("PERSONA")))
        assertEquals(
            toolOrderFree(golden("baidu_session_update_variant.json")),
            toolOrderFree(BaiduFlexProtocol.sessionUpdate("PERSONA", voice = "v2", speed = 1.25, vadThreshold = 0.75)),
        )
    }

    /** SPEC-016 B: tools are flattened in domain order, so declaration order is not pinned; each tool still is. */
    private fun toolOrderFree(raw: String): String {
        val root = JSONObject(raw)
        val session = root.getJSONObject("session")
        val tools = session.getJSONArray("tools")
        val sorted = (0 until tools.length()).map { tools.getJSONObject(it) }.sortedBy { it.toString() }
        session.put("tools", org.json.JSONArray(sorted))
        return root.toString()
    }

    @Test
    fun `tool names are unique and valid identifiers`() {
        val names = RealtimeToolCatalog.tools().map { it.name }
        assertEquals(12, names.size)
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { BaiduFlexProtocol.validId(it) })
    }

    @Test
    fun `specs are fresh instances with object schemas`() {
        val a = RealtimeToolCatalog.tools()
        val b = RealtimeToolCatalog.tools()
        a.zip(b).forEach { (x, y) ->
            assertNotSame(x.parameters, y.parameters)
            assertEquals("object", x.parameters.getString("type"))
            assertEquals(false, x.parameters.getBoolean("additionalProperties"))
        }
    }

    private fun v(name: String, json: String) = RealtimeToolCatalog.validate(name, JSONObject(json))

    @Test
    fun `validate keeps its codes per tool`() {
        assertNull(v("navigate_to", """{"destination":"机场"}"""))
        assertEquals("BLANK_DESTINATION", v("navigate_to", """{"destination":"  "}"""))
        assertEquals("INVALID_FIELDS", v("navigate_to", """{"destination":"a","x":1}"""))
        assertNull(v("open_app", """{"app":"maps"}"""))
        assertEquals("APP_NOT_ALLOWED", v("open_app", """{"app":"music"}"""))
        assertNull(v("control_music", """{"action":"stop"}"""))
        assertEquals("ACTION_NOT_ALLOWED", v("control_music", """{"action":"pause"}"""))
        assertNull(v("control_climate", """{"action":"set_temperature","value":22}"""))
        assertEquals("MISSING_VALUE", v("control_climate", """{"action":"set_fan"}"""))
        assertEquals("INVALID_FIELD_TYPE", v("control_climate", """{"action":"set_fan","value":"3"}"""))
        assertNull(v("describe_camera_view", """{"question":"前面有什么"}"""))
        assertEquals("QUESTION_TOO_LONG", v("describe_camera_view", """{"question":"${"x".repeat(201)}"}"""))
        assertNull(v("exit_navigation_mode", "{}"))
        assertEquals("INVALID_FIELDS", v("end_conversation", """{"a":"b"}"""))
        assertNull(v("set_speech_output", """{"mode":"silent"}"""))
        assertEquals("MODE_NOT_ALLOWED", v("set_speech_output", """{"mode":"loud"}"""))
        assertNull(v("query_live_info", """{"kind":"weather","where":"here"}"""))
        assertEquals("INVALID_KIND", v("query_live_info", """{"kind":"news"}"""))
        assertNull(v("choose_navigation_option", """{"index":2}"""))
        assertEquals("INVALID_INDEX", v("choose_navigation_option", """{"index":11}"""))
        assertEquals("PREFERENCE_NOT_ALLOWED", v("choose_navigation_option", """{"preference":"scenic"}"""))
        assertEquals("INVALID_FIELDS", v("choose_navigation_option", """{"index":1,"name":"a"}"""))
        assertNull(v("save_place", """{"slot":"home","address":"x"}"""))
        assertNull(v("place_call", """{"contact":"x"}"""))
    }

    @Test
    fun `unknown tool is left to the dispatcher`() {
        assertNull(v("launch_rocket", """{"a":"b"}"""))
    }
}
