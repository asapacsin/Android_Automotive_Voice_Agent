package com.novadrive.app

/**
 * The one answer to "is this process running ARM code under binary translation?".
 *
 * The APK ships only ARM native libraries (Amap, the WebRTC AEC). On an x86 emulator the whole
 * process therefore runs as arm64 through the native bridge. Two things break there and nowhere
 * else, so each workaround asks this, and a real phone never takes those branches:
 * - the Amap map GL thread dies with SIGILL (map surface skipped);
 * - translated WebRTC AEC3 costs ~80% of a core on the capture thread (2026-09-26, `top -H`),
 *   which starves the playback writer (~30 underruns/s through a reply) and the capture reads.
 */
object TranslatedAbi {
    val active: Boolean =
        android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") == true
}
