package com.novadrive.app

import com.novadrive.contracts.ContactMatchKind
import com.novadrive.contracts.ContactResolution
import com.novadrive.contracts.FakePhonePort
import com.novadrive.contracts.PhonePort
import com.novadrive.contracts.ResolvedContact
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import org.json.JSONObject

/**
 * Calling someone, which is the one tool here that reaches a person who did not ask to be reached.
 *
 * **Why it confirms first.** Measured on device 2026-09-19: 「返屋企啦」 was transcribed 「发诺克拉。」
 * and the model acted confidently on a sentence the driver never said. Every other tool in this
 * product can be undone by saying the opposite — a wrong temperature is a wrong temperature for ten
 * seconds. A wrong call has already rung someone's phone. So resolution and dialling are separate
 * turns: the first says who was found, the second needs the driver to agree.
 *
 * **Why it can refuse outright.** A device with no SIM cannot place a call. Starting the dialler
 * and reporting success would be the exact false claim this product exists to prevent
 * ([I-1](../../../../../../docs/INVARIANTS.md)), so `NO_TELEPHONY` is a real answer.
 */
class PhoneCallTool(
    private val phone: PhonePort,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    constructor(
        lookup: (String) -> ContactResolution,
        telephonyAvailable: () -> Boolean,
        dial: (ResolvedContact) -> Boolean,
        nowMs: () -> Long = System::currentTimeMillis,
    ) : this(LambdaPhonePort(lookup, telephonyAvailable, dial), nowMs)

    private class LambdaPhonePort(
        private val lookupFn: (String) -> ContactResolution,
        private val telephonyFn: () -> Boolean,
        private val placeFn: (ResolvedContact) -> Boolean,
    ) : PhonePort {
        override fun telephonyAvailable(): Boolean = telephonyFn()
        override fun resolve(spokenName: String): ContactResolution = lookupFn(spokenName)
        override fun dial(contact: ResolvedContact): Boolean = placeFn(contact)
    }
    /**
     * Who we last asked the driver about, and when.
     *
     * `confirmed=true` was trusted on its own, which meant the model could ask the driver about
     * one person and then dial another - the driver's "yes" attached to a name they never heard.
     * A confirmation authorises **one contact**, for a short while, **once**.
     */
    private data class Pending(val contactId: String, val atMs: Long)

    private var pending: Pending? = null
    fun call(
        event: DomainVoiceEvent.ToolCall,
        failed: (DomainVoiceEvent.ToolCall, String) -> ToolDispatchResult,
    ): ToolDispatchResult {
        val who = event.arguments["contact"]?.trim().orEmpty()
        if (who.isBlank()) return failed(event, "BLANK_CONTACT")
        if (who.length > 40) return failed(event, "CONTACT_TOO_LONG")
        if (!phone.telephonyAvailable()) return failed(event, NO_TELEPHONY)

        val resolution = phone.resolve(who)
        // Names are personal data: the log says how many matched, never who.
        DebugVoiceLog.log("call_lookup kind=${resolution.kind} matches=${resolution.candidates.size}")
        return when (resolution.kind) {
            ContactMatchKind.NONE -> failed(event, CONTACT_NOT_FOUND)
            ContactMatchKind.PERMISSION_DENIED -> failed(event, CONTACTS_PERMISSION_DENIED)
            ContactMatchKind.AMBIGUOUS -> ambiguous(event, resolution.candidates)
            ContactMatchKind.UNIQUE -> {
                val contact = resolution.candidates.first()
                val confirmed = event.arguments["confirmed"]?.lowercase() == "true"
                if (!confirmed) return confirmationNeeded(event, contact)
                val agreed = pending
                // Consumed either way: a confirmation is spent when it is used, so a repeated
                // call cannot dial twice off one "yes".
                pending = null
                if (agreed == null || agreed.contactId != contact.contactId) {
                    return failed(event, UNCONFIRMED)
                }
                if (nowMs() - agreed.atMs > CONFIRMATION_TTL_MS) return failed(event, CONFIRMATION_STALE)
                if (!phone.dial(contact)) return failed(event, CALL_FAILED)
                DebugVoiceLog.log("call_placed confirmed=true")
                ok(event) {
                    put("status", "calling")
                    put("contact", contact.displayName)
                }
            }
        }
    }

    /**
     * Found exactly one, and said so without dialling. The number is **not** returned: the driver
     * knows their own contacts, the model does not need it, and it would end up in a transcript.
     */
    private fun confirmationNeeded(
        event: DomainVoiceEvent.ToolCall,
        contact: ResolvedContact,
    ): ToolDispatchResult {
        pending = Pending(contact.contactId, nowMs())
        return confirmationPayload(event, contact)
    }

    private fun confirmationPayload(
        event: DomainVoiceEvent.ToolCall,
        contact: ResolvedContact,
    ): ToolDispatchResult = ok(event) {
        put("status", "confirm_required")
        put("contact", contact.displayName)
        put("next", ToolFailureAdvice.CONFIRM_CALL)
    }

    private fun ambiguous(
        event: DomainVoiceEvent.ToolCall,
        candidates: List<ResolvedContact>,
    ): ToolDispatchResult = ok(event) {
        put("status", "ambiguous")
        put("candidates", candidates.take(MAX_CANDIDATES).map { it.displayName })
        put("next", ToolFailureAdvice.CHOOSE_CONTACT)
    }

    private fun ok(event: DomainVoiceEvent.ToolCall, body: JSONObject.() -> Unit): ToolDispatchResult {
        val payload = JSONObject().put("ok", true).put("tool", event.name).apply(body)
        return ToolDispatchResult(null, null, successChip = "✓ ${event.name}", output = payload.toString())
    }

    companion object {
        const val NO_TELEPHONY = "NO_TELEPHONY"
        const val CONTACT_NOT_FOUND = "CONTACT_NOT_FOUND"
        const val CONTACTS_PERMISSION_DENIED = "CONTACTS_PERMISSION_DENIED"
        const val CALL_FAILED = "CALL_FAILED"

        /** confirmed=true for somebody the driver was never asked about. */
        const val UNCONFIRMED = "CALL_NOT_CONFIRMED"

        /** The driver agreed, but long enough ago that it is not this conversation any more. */
        const val CONFIRMATION_STALE = "CONFIRMATION_STALE"

        /** Long enough for a person to answer a question, short enough not to span a journey. */
        const val CONFIRMATION_TTL_MS = 60_000L
        private const val MAX_CANDIDATES = 5

        /** A tool with no driver behind it: nothing resolves, nothing dials. */
        fun none(): PhoneCallTool = PhoneCallTool(FakePhonePort(telephony = false))
    }
}
