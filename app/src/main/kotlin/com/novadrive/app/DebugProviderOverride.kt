package com.novadrive.app

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Debug-only, in-memory provider override (decided by the decider, 2026-10-15, implementing the
 * owner's 2026-10-13 "Gemini first, Ayano last"). Only the debug `DebugToolReceiver` writes it;
 * [VoiceProviderChoice.resolveForSession] reads it, and only when the app is debuggable. Never
 * persisted (a restart is Qwen again), never set automatically, read at the next session start.
 * Removed with the Gemini stack at ADR-017 Q-5.
 */
object DebugProviderOverride {
    @Volatile
    var geminiRequested: Boolean = false
        private set

    /** Debug receiver only (enforced by ArchitectureRulesTest). */
    fun set(gemini: Boolean) {
        geminiRequested = gemini
    }

    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
