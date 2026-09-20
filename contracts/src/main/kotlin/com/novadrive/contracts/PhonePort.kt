package com.novadrive.contracts

/**
 * Phone actions leave this module as [StructuredCommand.PlaceCall] and re-enter through this port.
 *
 * Nothing here knows about Android, a SIM, or `ACTION_CALL`. The production adapter lives in the
 * app (`AndroidContacts`); tests and the JVM orchestrator inject a [FakePhonePort].
 */
interface PhonePort {
    fun telephonyAvailable(): Boolean
    fun resolve(spokenName: String): ContactResolution
    fun dial(contact: ResolvedContact): Boolean
}

/** In-memory phone, for tests. No Android types. */
class FakePhonePort(
    private val contacts: List<ResolvedContact> = emptyList(),
    private val telephony: Boolean = true,
    private val contactsPermitted: Boolean = true,
    private val callPermitted: Boolean = true,
) : PhonePort {
    val dialled = mutableListOf<ResolvedContact>()

    override fun telephonyAvailable(): Boolean = telephony

    override fun resolve(spokenName: String): ContactResolution {
        if (!contactsPermitted) return ContactResolution(ContactMatchKind.PERMISSION_DENIED)
        val matches = contacts.filter { spokenName in it.displayName }
        return when {
            matches.isEmpty() -> ContactResolution(ContactMatchKind.NONE)
            matches.size == 1 -> ContactResolution(ContactMatchKind.UNIQUE, matches)
            else -> ContactResolution(ContactMatchKind.AMBIGUOUS, matches)
        }
    }

    override fun dial(contact: ResolvedContact): Boolean {
        if (!callPermitted) return false
        dialled += contact
        return true
    }
}
