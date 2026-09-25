package com.novadrive.app.nav.amap

import android.content.Context
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.NaviSetting
import com.amap.api.services.core.ServiceSettings

/**
 * Idempotent Amap privacy-compliance calls that must run before any SDK object is constructed.
 *
 * DEVELOPMENT STAND-IN — this is NOT a consent implementation.
 * A real consent screen is a later phase and a release prerequisite.
 */
object AmapPrivacyCompliance {
    @Volatile
    private var acknowledged = false

    fun ensure(context: Context) {
        if (acknowledged) return
        synchronized(this) {
            if (acknowledged) return
            val app = context.applicationContext
            MapsInitializer.updatePrivacyShow(app, true, true)
            MapsInitializer.updatePrivacyAgree(app, true)
            NaviSetting.updatePrivacyShow(app, true, true)
            NaviSetting.updatePrivacyAgree(app, true)
            // The Search SDK has its own switch; RoutePOISearch (SPEC-011) refuses without it.
            ServiceSettings.updatePrivacyShow(app, true, true)
            ServiceSettings.updatePrivacyAgree(app, true)
            acknowledged = true
        }
    }
}
