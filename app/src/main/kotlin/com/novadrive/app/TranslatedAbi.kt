package com.novadrive.app

/**
 * The one answer to "is this process running ARM code under binary translation?".
 *
 * The APK ships only ARM native libraries (Amap, the WebRTC AEC). On an x86 emulator the whole
 * process therefore runs through the native bridge, and a real phone never takes the branches
 * that ask this:
 * - translated WebRTC AEC3 costs ~80% of a core on the capture thread (2026-09-26, `top -H`),
 *   which starves the playback writer (~30 underruns/s through a reply) and the capture reads;
 * - the host-mic bridge chops capture (the uplink gate is off there);
 * - no real fixes move the car, so navigation is simulated.
 */
object TranslatedAbi {
    val active: Boolean =
        android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") == true

    /**
     * Whether Amap's native code (GL map and navigation engine) survives the translator.
     * Measured 2026-09-28 (docs/EMULATOR_TESTING.md):
     * - API 30 image (ndk_translation 0.2.2), **arm64**: SIGILL in
     *   `DecodeSimdScalarTwoRegMisc` — the map GL thread at once, the navigation engine 1–7 min
     *   into every drive. Falls back to the text panel.
     * - API 30 image, **armeabi-v7a** install: map and full drives work (the iFlytek wake engine
     *   crashes there instead).
     * - API 34 image (0.2.3), **arm64**: map and full drives work.
     * Always true on a phone.
     */
    val amapNativeSafe: Boolean =
        !active || !android.os.Process.is64Bit() || android.os.Build.VERSION.SDK_INT >= 34
}
