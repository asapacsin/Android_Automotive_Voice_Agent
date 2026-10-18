package com.iflytek.cloud;

import android.content.Context;

/** Compile-only stand-in (tools/vendor-stub/README.md): there is never an engine, so both return null. */
public class SpeechUtility {
    public static SpeechUtility getUtility() {
        return null;
    }

    public static SpeechUtility createUtility(Context context, String params) {
        return null;
    }
}
