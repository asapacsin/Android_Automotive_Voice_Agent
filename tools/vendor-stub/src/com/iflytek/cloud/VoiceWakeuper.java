package com.iflytek.cloud;

import android.content.Context;

/** Compile-only stand-in (tools/vendor-stub/README.md): createWakeuper returns null, so no engine starts. */
public class VoiceWakeuper {
    public static VoiceWakeuper createWakeuper(Context context, InitListener listener) {
        return null;
    }

    public boolean setParameter(String key, String value) {
        return false;
    }

    public int startListening(WakeuperListener listener) {
        return -1;
    }

    public int writeAudio(byte[] data, int offset, int length) {
        return -1;
    }

    public void stopListening() {
    }

    public boolean isListening() {
        return false;
    }

    public boolean destroy() {
        return true;
    }
}
