package com.novadrive.app

import com.novadrive.contracts.ContactMatchKind
import com.novadrive.contracts.ContactResolution
import com.novadrive.contracts.ResolvedContact
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A call is the one action in this product that reaches a person who did not ask to be reached,
 * so the tests are about what it refuses to do.
 */
class PhoneCallToolTest {
    private val zhang = ResolvedContact("1", "张三", "13800000000")
    private val otherZhang = ResolvedContact("2", "张三丰", "13900000000")
    private var dialled = 0

    private fun tool(
        resolution: ContactResolution,
        telephony: Boolean = true,
    ) = PhoneCallTool(
        lookup = { resolution },
        telephonyAvailable = { telephony },
        dial = { dialled++; true },
    )

    private fun call(vararg args: Pair<String, String>) =
        DomainVoiceEvent.ToolCall("c1", "place_call", args.toMap())

    private fun failed(event: DomainVoiceEvent.ToolCall, code: String) =
        ToolDispatchResult(null, null, blockedReason = code, output = """{"ok":false,"error":"$code"}""")

    @Test
    fun oneMatchIsNotDialledUntilTheDriverAgrees() {
        val result = tool(ContactResolution(ContactMatchKind.UNIQUE, listOf(zhang)))
            .call(call("contact" to "张三"), ::failed)
        val json = JSONObject(result.output!!)
        assertEquals("confirm_required", json.getString("status"))
        assertEquals(0, dialled, "a call must never be placed on the first turn")
    }

    @Test
    fun theNumberIsNeverHandedToTheModel() {
        // It would end up in a transcript, and the driver already knows their own contacts.
        val result = tool(ContactResolution(ContactMatchKind.UNIQUE, listOf(zhang)))
            .call(call("contact" to "张三"), ::failed)
        assertFalse(result.output!!.contains("13800000000"))
    }

    @Test
    fun aConfirmedCallIsPlacedOnce() {
        val result = tool(ContactResolution(ContactMatchKind.UNIQUE, listOf(zhang)))
            .call(call("contact" to "张三", "confirmed" to "true"), ::failed)
        assertEquals("calling", JSONObject(result.output!!).getString("status"))
        assertEquals(1, dialled)
    }

    @Test
    fun twoMatchesAreOfferedRatherThanGuessedBetween() {
        val result = tool(ContactResolution(ContactMatchKind.AMBIGUOUS, listOf(zhang, otherZhang)))
            .call(call("contact" to "张三", "confirmed" to "true"), ::failed)
        val json = JSONObject(result.output!!)
        assertEquals("ambiguous", json.getString("status"))
        // Confirmed or not: nobody said WHICH 张三, so there is nothing to confirm.
        assertEquals(0, dialled)
    }

    @Test
    fun aCarWithNoSimSaysSoInsteadOfDialling() {
        val result = tool(ContactResolution(ContactMatchKind.UNIQUE, listOf(zhang)), telephony = false)
            .call(call("contact" to "张三", "confirmed" to "true"), ::failed)
        assertEquals(PhoneCallTool.NO_TELEPHONY, result.blockedReason)
        assertEquals(0, dialled)
    }

    @Test
    fun anUnknownNameIsNotSubstitutedForARealOne() {
        val result = tool(ContactResolution(ContactMatchKind.NONE))
            .call(call("contact" to "谁谁谁", "confirmed" to "true"), ::failed)
        assertEquals(PhoneCallTool.CONTACT_NOT_FOUND, result.blockedReason)
        assertEquals(0, dialled)
    }

    @Test
    fun everyRefusalCarriesWordingForTheDriver() {
        listOf(PhoneCallTool.NO_TELEPHONY, PhoneCallTool.CONTACT_NOT_FOUND).forEach { code ->
            assertTrue(!ToolFailureAdvice.forCode(code).isNullOrBlank(), "$code has no advice")
        }
    }
}
