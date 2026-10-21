package com.iflytek.cloud;

/** Compile-only stand-in (tools/vendor-stub/README.md). Not the iFlytek SDK. */
public class SpeechError extends Exception {
    private final int code;

    public SpeechError(int code, String description) {
        super(description);
        this.code = code;
    }

    public int getErrorCode() {
        return code;
    }

    public String getErrorDescription() {
        return getMessage();
    }
}
