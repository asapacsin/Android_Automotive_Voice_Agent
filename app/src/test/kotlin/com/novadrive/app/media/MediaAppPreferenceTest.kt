package com.novadrive.app.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MediaAppPreferenceTest {
    @Test fun preferredWins() {
        assertEquals("com.tencent.qqmusic",
            MediaAppPreference.pick(listOf("com.other", "com.tencent.qqmusic", "org.videolan.vlc"), MediaAppPreference.DEFAULT))
        assertEquals("com.netease.cloudmusic",
            MediaAppPreference.pick(listOf("org.videolan.vlc", "com.netease.cloudmusic"), MediaAppPreference.DEFAULT))
    }
    @Test fun fallsBackToFirstOther() {
        assertEquals("com.other", MediaAppPreference.pick(listOf("com.other", "com.x"), MediaAppPreference.DEFAULT))
    }
    @Test fun noneResolves() {
        assertNull(MediaAppPreference.pick(emptyList(), MediaAppPreference.DEFAULT))
    }
}
