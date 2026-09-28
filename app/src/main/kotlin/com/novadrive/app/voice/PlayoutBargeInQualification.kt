package com.novadrive.app.voice

/**
 * Whether assistant playback should flush on [speech_started] while audio is playing (Astra P4).
 * The same answer is used for the playback owner and for echo-candidate turn marking.
 */
fun playoutBargeInQualified(recentSpeech: Boolean): Boolean = recentSpeech
