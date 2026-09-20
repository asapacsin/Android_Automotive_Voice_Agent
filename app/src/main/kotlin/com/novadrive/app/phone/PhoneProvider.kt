package com.novadrive.app.phone

import android.content.Context
import com.novadrive.app.AndroidContacts
import com.novadrive.contracts.PhonePort

/**
 * Selects the phone backend. This is the only production file that should name
 * [AndroidContacts]; everything else takes a [PhonePort].
 *
 * Phone build: the device's address book and dialler. A head-unit OEM adapter replaces this
 * return value — [PhoneCallTool] and the utterance resolver do not change.
 */
object PhoneProvider {
    fun port(context: Context): PhonePort = AndroidContacts(context)
}
