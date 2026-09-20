package com.novadrive.app.voice

import com.novadrive.app.PhoneCallTool
import com.novadrive.contracts.CapabilityCatalog
import com.novadrive.contracts.CapabilityIds
import com.novadrive.contracts.FakePhonePort
import com.novadrive.contracts.MapCapabilityCatalog
import com.novadrive.contracts.ProductCapabilities
import com.novadrive.contracts.ResolvedContact
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `电话` / `place_call` clash was not a bad word. It was two owners of the same fact:
 * ActionClaimGuard's keyword list, and the capability registry. These tests pin the invariant
 * the architecture must keep, not the particular string.
 */
class CapabilityResolutionInvariantTest {

    private val callPhrasings = listOf(
        "打电话给张三",
        "给张三打电话",
        "拨打张三电话",
    )

    @Test
    fun aRegisteredPhoneCallCapabilityCannotBeClassifiedUnsupported() {
        assertTrue(ProductCapabilities.isSupported(CapabilityIds.PHONE_PLACE_CALL))
        callPhrasings.forEach { phrase ->
            assertEquals(
                CapabilityIds.PHONE_PLACE_CALL,
                UtteranceIntentResolver.product().resolve(phrase)?.capabilityId,
                phrase,
            )
            assertFalse(ActionClaimGuard.isUnsupportedRequest(phrase), phrase)
            assertEquals(DriverTurn.Kind.ACTION, DriverTurn.classify(phrase), phrase)
        }
    }

    @Test
    fun lexicalFallbackMustNotOverrideARegisteredCapability() {
        // The heuristic still knows 电话: that is how the original defect was written.
        // Structured resolve + the catalog have to win anyway.
        assertTrue(ActionClaimGuard.fallbackUnsupportedHeuristic("电话"))
        assertFalse(ActionClaimGuard.isUnsupportedRequest("打电话给张三"))
        assertEquals(DriverTurn.Kind.ACTION, DriverTurn.classify("打电话给张三"))
    }

    @Test
    fun anUnregisteredCapabilityIsRejectedByTheCatalogNotByKeywords() {
        val empty: CapabilityCatalog = MapCapabilityCatalog(emptyMap())
        callPhrasings.forEach { phrase ->
            val intent = UtteranceIntentResolver.product().resolve(phrase)
            assertEquals(CapabilityIds.PHONE_PLACE_CALL, intent?.capabilityId, phrase)
            assertFalse(empty.isSupported(CapabilityIds.PHONE_PLACE_CALL))
            assertTrue(ActionClaimGuard.isUnsupportedRequest(phrase, empty), phrase)
            assertEquals(DriverTurn.Kind.NO_TOOL_ACTION, DriverTurn.classify(phrase, empty), phrase)
        }
    }

    @Test
    fun availabilityFollowsTheCatalogEvenWhenKeywordsDisagree() {
        val volumeOn = ProductCapabilities.overlay(
            mapOf(CapabilityIds.VOLUME_CONTROL to true),
        )
        assertTrue(ActionClaimGuard.isUnsupportedRequest("把音量调大一点"))
        assertFalse(ActionClaimGuard.isUnsupportedRequest("把音量调大一点", volumeOn))
        assertEquals(DriverTurn.Kind.ACTION, DriverTurn.classify("把音量调大一点", volumeOn))

        val phoneOff = ProductCapabilities.overlay(
            mapOf(CapabilityIds.PHONE_PLACE_CALL to false),
        )
        assertFalse(ActionClaimGuard.isUnsupportedRequest("打电话给张三"))
        assertTrue(ActionClaimGuard.isUnsupportedRequest("打电话给张三", phoneOff))
        assertEquals(DriverTurn.Kind.NO_TOOL_ACTION, DriverTurn.classify("打电话给张三", phoneOff))
    }

    @Test
    fun aFakePhonePortSubstitutesWithoutAndroid() {
        val zhang = ResolvedContact("1", "张三", "13800000000")
        val port = FakePhonePort(contacts = listOf(zhang), telephony = true)
        val tool = PhoneCallTool(port)
        fun failed(event: DomainVoiceEvent.ToolCall, code: String) =
            ToolDispatchResult(null, null, blockedReason = code, output = """{"ok":false,"error":"$code"}""")

        val ask = tool.call(
            DomainVoiceEvent.ToolCall("c1", "place_call", mapOf("contact" to "张三")),
            ::failed,
        )
        assertEquals("confirm_required", JSONObject(ask.output!!).getString("status"))
        assertTrue(port.dialled.isEmpty())

        val go = tool.call(
            DomainVoiceEvent.ToolCall("c2", "place_call", mapOf("contact" to "张三", "confirmed" to "true")),
            ::failed,
        )
        assertEquals("calling", JSONObject(go.output!!).getString("status"))
        assertEquals(listOf(zhang), port.dialled)
    }
}
