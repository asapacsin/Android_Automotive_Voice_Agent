package com.novadrive.app.tools

import com.novadrive.app.AllowedApp
import com.novadrive.app.AndroidActionExecutor
import com.novadrive.app.AndroidActionResult
import com.novadrive.app.PersonaProfiles
import com.novadrive.app.SpeakingStyle
import com.novadrive.app.SpeakingStyleState
import com.novadrive.app.SpeakingStyleStore
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SPEC-015 FZ-11/FZ-12/B6 (STYLE-UNIT-001): set_speaking_style validates, applies, persists and composes. */
class SpeakingStyleToolTest {
    private val env = ToolCallEnv(
        driverContext = { null },
        failedFormat = { _, code, _ -> ToolDispatchResult(null, null, blockedReason = code) },
        resultFormat = { _, _ -> ToolDispatchResult(null, null) },
    )

    private object NoExecutor : AndroidActionExecutor {
        private val no = AndroidActionResult.Rejected("UNUSED")
        override fun navigate(destination: String) = no
        override fun openApp(app: AllowedApp) = no
        override fun playMusic() = no
        override fun stopMusic() = no
        override fun exitNavigationMode() = no
    }

    private val server = SpeechServer(NoExecutor)

    private fun call(style: String) =
        DomainVoiceEvent.ToolCall("call_1", SpeechDomain.SET_SPEAKING_STYLE, mapOf("style" to style))

    @AfterEach
    fun reset() {
        SpeakingStyleState.persist = {}
        SpeakingStyleState.restore(SpeakingStyle.DEFAULT)
    }

    @Test
    fun `validation accepts only the style enum`() {
        val name = SpeechDomain.SET_SPEAKING_STYLE
        assertNull(SpeechDomain.validate(name, JSONObject().put("style", "sweet")))
        assertNull(SpeechDomain.validate(name, JSONObject().put("style", "default")))
        assertEquals("INVALID_FIELDS", SpeechDomain.validate(name, JSONObject()))
        assertEquals("INVALID_FIELDS", SpeechDomain.validate(name, JSONObject().put("style", "sweet").put("x", 1)))
        assertEquals("INVALID_FIELD_TYPE", SpeechDomain.validate(name, JSONObject().put("style", 1)))
        assertEquals("STYLE_NOT_ALLOWED", SpeechDomain.validate(name, JSONObject().put("style", "angry")))
    }

    @Test
    fun `the tool is declared in the speech domain and repeat sensitive`() {
        assertEquals("speech", ToolRegistry.PRODUCT.domainOf(SpeechDomain.SET_SPEAKING_STYLE)?.id)
        assertTrue(SpeechDomain.SET_SPEAKING_STYLE in ToolRegistry.PRODUCT.repeatSensitiveNames)
    }

    @Test
    fun `sweet sets and persists the state and returns the instruction and chip`() {
        val saved = mutableListOf<SpeakingStyle>()
        SpeakingStyleState.persist = { saved += it }
        val result = server.call(call("sweet"), env)
        assertEquals(SpeakingStyle.SWEET, SpeakingStyleState.current)
        assertEquals(listOf(SpeakingStyle.SWEET), saved)
        assertNull(result.blockedReason)
        assertEquals("✓ 语气：甜", result.successChip)
        val json = JSONObject(result.output!!)
        assertTrue(json.getBoolean("ok"))
        assertEquals("set_speaking_style", json.getString("tool"))
        assertEquals("sweet", json.getString("style"))
        assertEquals(SpeechServer.SWEET_INSTRUCTION, json.getString("instruction"))
        assertFalse(json.has("persisted"))
    }

    @Test
    fun `default restores the normal tone`() {
        SpeakingStyleState.restore(SpeakingStyle.SWEET)
        val result = server.call(call("default"), env)
        assertEquals(SpeakingStyle.DEFAULT, SpeakingStyleState.current)
        assertEquals("✓ 语气：默认", result.successChip)
        assertEquals(SpeechServer.DEFAULT_INSTRUCTION, JSONObject(result.output!!).getString("instruction"))
    }

    @Test
    fun `a failed save still applies the style but does not claim persistence`() {
        SpeakingStyleState.persist = { error("disk") }
        val json = JSONObject(server.call(call("sweet"), env).output!!)
        assertEquals(SpeakingStyle.SWEET, SpeakingStyleState.current)
        assertTrue(json.getBoolean("ok"))
        assertFalse(json.getBoolean("persisted"))
    }

    @Test
    fun `an unknown style is refused without changing state`() {
        val result = server.call(call("angry"), env)
        assertEquals("STYLE_NOT_ALLOWED", result.blockedReason)
        assertEquals(SpeakingStyle.DEFAULT, SpeakingStyleState.current)
    }

    @Test
    fun `store mapping round-trips wire names and defaults unknown values`() {
        SpeakingStyle.entries.forEach { assertEquals(it, SpeakingStyleStore.decode(SpeakingStyleStore.encode(it))) }
        assertEquals(SpeakingStyle.DEFAULT, SpeakingStyleStore.decode(null))
        assertEquals(SpeakingStyle.DEFAULT, SpeakingStyleStore.decode("loud"))
    }

    @Test
    fun `every offered style applies, persists and returns its own instruction and chip`() {
        for (style in SpeakingStyle.entries) {
            val result = server.call(call(style.wireName), env)
            assertEquals(style, SpeakingStyleState.current)
            val json = JSONObject(result.output!!)
            assertEquals(style.wireName, json.getString("style"))
            assertEquals(SpeechServer.instructionFor(style), json.getString("instruction"))
            assertEquals("✓ 语气：${style.chipLabel}", result.successChip)
        }
        assertNull(SpeechDomain.validate(SpeechDomain.SET_SPEAKING_STYLE, JSONObject().put("style", "tsundere")))
    }

    @Test
    fun `after the tool the composed persona carries the sweet tone`() {
        assertFalse(PersonaProfiles.compose("PERSONA", SpeakingStyleState.current).contains(PersonaProfiles.SWEET_TONE))
        server.call(call("sweet"), env)
        assertTrue(PersonaProfiles.compose("PERSONA", SpeakingStyleState.current).contains(PersonaProfiles.SWEET_TONE))
    }
}
