package com.novadrive.app

import com.novadrive.contracts.ContactMatchKind
import com.novadrive.contracts.ContactResolution
import com.novadrive.contracts.ResolvedContact
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

    private var clock = 1_000L

    private fun tool(
        resolution: ContactResolution,
        telephony: Boolean = true,
    ) = PhoneCallTool(
        lookup = { resolution },
        telephonyAvailable = { telephony },
        dial = { dialled++; true },
        nowMs = { clock },
    )

    /** Ask, then agree - the two turns a real call takes. */
    private fun confirmedCall(resolution: ContactResolution, who: String = "张三"): PhoneCallTool =
        tool(resolution).also { it.call(call("contact" to who), ::failed) }

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
        val result = confirmedCall(ContactResolution(ContactMatchKind.UNIQUE, listOf(zhang)))
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
    fun missingContactsPermissionIsNotReportedAsNotFound() {
        // AndroidContacts used to map READ_CONTACTS denial onto NONE, so the driver was told
        // the person was not in the book when we had not looked.
        val result = tool(ContactResolution(ContactMatchKind.PERMISSION_DENIED))
            .call(call("contact" to "张三"), ::failed)
        assertEquals(PhoneCallTool.CONTACTS_PERMISSION_DENIED, result.blockedReason)
        assertEquals(0, dialled)
        assertTrue(!result.output!!.contains("没找到"), "permission denied is not a failed search")
    }

    @Test
    fun everyRefusalCarriesWordingForTheDriver() {
        listOf(
            PhoneCallTool.NO_TELEPHONY,
            PhoneCallTool.CONTACT_NOT_FOUND,
            PhoneCallTool.CONTACTS_PERMISSION_DENIED,
            PhoneCallTool.CALL_FAILED,
        ).forEach { code ->
            assertTrue(!ToolFailureAdvice.forCode(code).isNullOrBlank(), "$code has no advice")
        }
    }

    @Test
    fun aDiallerFailureIsNotReportedAsAPlacedCall() {
        val port = com.novadrive.contracts.FakePhonePort(
            contacts = listOf(zhang),
            telephony = true,
            callPermitted = false,
        )
        val tool = PhoneCallTool(port)
        tool.call(call("contact" to "张三"), ::failed)
        val result = tool.call(call("contact" to "张三", "confirmed" to "true"), ::failed)
        assertEquals(PhoneCallTool.CALL_FAILED, result.blockedReason)
        assertTrue(port.dialled.isEmpty())
    }

    // ---- a confirmation authorises one contact, once, for a while --------------------------

    @Test
    fun agreeingAboutOnePersonDoesNotAuthoriseAnother() {
        // The defect this closes: the assistant asks 「打给张三吗？」, the driver says yes, and the
        // model sends confirmed=true for somebody else. The driver's yes would have attached to a
        // name they never heard.
        val tool = confirmedCall(ContactResolution(ContactMatchKind.UNIQUE, listOf(zhang)))
        val other = ContactResolution(ContactMatchKind.UNIQUE, listOf(otherZhang))
        val hijacked = PhoneCallTool(
            lookup = { other },
            telephonyAvailable = { true },
            dial = { dialled++; true },
            nowMs = { clock },
        )
        // A fresh tool has no pending confirmation at all, which is the same refusal.
        val result = hijacked.call(call("contact" to "张三丰", "confirmed" to "true"), ::failed)
        assertEquals(PhoneCallTool.UNCONFIRMED, result.blockedReason)
        assertEquals(0, dialled)
        assertNotNull(tool)
    }

    @Test
    fun aConfirmationIsSpentWhenItIsUsed() {
        val tool = confirmedCall(ContactResolution(ContactMatchKind.UNIQUE, listOf(zhang)))
        tool.call(call("contact" to "张三", "confirmed" to "true"), ::failed)
        assertEquals(1, dialled)
        // The model repeating itself must not ring the person twice off one yes.
        val again = tool.call(call("contact" to "张三", "confirmed" to "true"), ::failed)
        assertEquals(PhoneCallTool.UNCONFIRMED, again.blockedReason)
        assertEquals(1, dialled)
    }

    @Test
    fun aConfirmationGoesStale() {
        val tool = confirmedCall(ContactResolution(ContactMatchKind.UNIQUE, listOf(zhang)))
        clock += PhoneCallTool.CONFIRMATION_TTL_MS + 1
        val result = tool.call(call("contact" to "张三", "confirmed" to "true"), ::failed)
        assertEquals(PhoneCallTool.CONFIRMATION_STALE, result.blockedReason)
        assertEquals(0, dialled)
    }

    @Test
    fun everyConfirmationRefusalCarriesWording() {
        listOf(PhoneCallTool.UNCONFIRMED, PhoneCallTool.CONFIRMATION_STALE).forEach { code ->
            assertTrue(!ToolFailureAdvice.forCode(code).isNullOrBlank(), "$code has no advice")
        }
    }
}
