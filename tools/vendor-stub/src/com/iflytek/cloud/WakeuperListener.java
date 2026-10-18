package com.iflytek.cloud;

import android.os.Bundle;

/** Compile-only stand-in (tools/vendor-stub/README.md). Not the iFlytek SDK. */
public interface WakeuperListener {
    void onBeginOfSpeech();

    void onResult(WakeuperResult result);

    void onError(SpeechError error);

    void onEvent(int eventType, int arg1, int arg2, Bundle obj);

    void onVolumeChanged(int volume);
}
