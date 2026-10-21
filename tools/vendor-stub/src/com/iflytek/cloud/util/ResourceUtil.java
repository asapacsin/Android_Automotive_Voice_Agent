package com.iflytek.cloud.util;

import android.content.Context;

/** Compile-only stand-in (tools/vendor-stub/README.md). Not the iFlytek SDK. */
public class ResourceUtil {
    public enum RESOURCE_TYPE { assets, res, path }

    public static String generateResourcePath(Context context, RESOURCE_TYPE type, String path) {
        return "";
    }
}
