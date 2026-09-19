package com.novadrive.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import com.novadrive.contracts.ContactMatchKind
import com.novadrive.contracts.ContactResolution
import com.novadrive.contracts.ResolvedContact

/**
 * The device side of [PhoneCallTool]: who is in the address book, and whether this car can call.
 *
 * Nothing here logs a name or a number. The driver's contacts are theirs; the app needs to know
 * *how many* matched, and the model needs a display name to say out loud — no more than that.
 */
class AndroidContacts(context: Context) {
    private val appContext = context.applicationContext

    /**
     * True when a call could actually be placed. The test device has no SIM
     * (`gsm.sim.state=ABSENT`), and on such a phone starting the dialler and reporting success
     * would be a false claim — so this is checked before anything else.
     */
    fun telephonyAvailable(): Boolean {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return false
        val manager = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return false
        return manager.simState == TelephonyManager.SIM_STATE_READY
    }

    fun resolve(spokenName: String): ContactResolution {
        if (appContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            DebugVoiceLog.log("call_lookup_denied reason=no_contacts_permission")
            return ContactResolution(ContactMatchKind.NONE)
        }
        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(spokenName),
        )
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val matches = mutableListOf<ResolvedContact>()
        runCatching {
            appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && matches.size < MAX_MATCHES) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val number = cursor.getString(2) ?: continue
                    // One row per contact: a person with three numbers is one person to the
                    // driver, and offering the same name three times is not a choice.
                    if (matches.none { it.contactId == id }) {
                        matches += ResolvedContact(id, name, number)
                    }
                }
            }
        }.onFailure { DebugVoiceLog.log("call_lookup_failed") }
        return when {
            matches.isEmpty() -> ContactResolution(ContactMatchKind.NONE)
            matches.size == 1 -> ContactResolution(ContactMatchKind.UNIQUE, matches)
            else -> ContactResolution(ContactMatchKind.AMBIGUOUS, matches)
        }
    }

    /** Places the call. Only ever reached with the driver's spoken confirmation. */
    fun dial(contact: ResolvedContact): Boolean {
        if (appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            DebugVoiceLog.log("call_denied reason=no_call_permission")
            return false
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", contact.phoneNumber, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { appContext.startActivity(intent); true }.getOrDefault(false)
    }

    private companion object {
        const val MAX_MATCHES = 10
    }
}
