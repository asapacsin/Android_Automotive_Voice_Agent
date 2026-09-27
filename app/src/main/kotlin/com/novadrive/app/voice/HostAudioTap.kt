package com.novadrive.app.voice

/**
 * The seam a debug build's host audio bridge plugs into (see `HostAudioBridge` in the debug source
 * set and docs/EMULATOR_TESTING.md). Both slots are null in every release build and on a phone
 * unless a developer switches the bridge on over ADB, so production capture and playback are
 * unchanged: [PcmAudioCapture] reads from [source] instead of `AudioRecord` while it is set, and
 * [PcmAudioPlayer] hands [sink] a copy of each slice it has just written to the `AudioTrack` (the
 * track keeps running, so drain and barge-in timing still come from the real output clock).
 */
object HostAudioTap {
    interface Source {
        /** Fills [buf] completely and returns its size, 0 when nothing arrived yet, -1 when gone. */
        fun read(buf: ByteArray): Int
    }

    @Volatile var source: Source? = null

    /** Must not block: called on the playback writer under its output lock. */
    @Volatile var sink: ((pcm16le: ByteArray, sampleRateHz: Int) -> Unit)? = null

    /**
     * The text of each Amap guidance prompt as it starts playing, so the PC can speak it: the SDK's
     * inner voice plays only on the emulator's own speaker. Must not block. Never logged.
     */
    @Volatile var guidanceSink: ((text: String) -> Unit)? = null
}
