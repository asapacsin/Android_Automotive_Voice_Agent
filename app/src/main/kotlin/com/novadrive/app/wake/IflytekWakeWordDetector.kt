package com.novadrive.app.wake

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.iflytek.cloud.ErrorCode
import com.iflytek.cloud.SpeechConstant
import com.iflytek.cloud.SpeechError
import com.iflytek.cloud.SpeechUtility
import com.iflytek.cloud.VoiceWakeuper
import com.iflytek.cloud.WakeuperListener
import com.iflytek.cloud.WakeuperResult
import com.iflytek.cloud.util.ResourceUtil
import com.novadrive.app.DebugVoiceLog
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

enum class WakeWordState { IDLE, AUTHORISING, READY, LISTENING, ERROR }

interface WakeWordDetector {
    fun initialize(credentials: WakeWordCredentials, workDir: File)
    fun startListening()
    fun writeFrame(pcm: ByteArray, first: Boolean, last: Boolean)
    fun stopListening()
    fun release()
    val state: WakeWordState
}

internal data class WakeMatch(val id: String, val score: Int)

internal fun parseWakeResultJson(json: String): WakeMatch? {
    if (json.isBlank()) return null
    return try {
        val obj = JSONObject(json)
        val id = obj.optString("id")
        val score = if (obj.has("score")) obj.optInt("score") else null
        if (id.isBlank() && score == null) null
        else WakeMatch(id, score ?: 0)
    } catch (_: Exception) {
        null
    }
}

class IflytekWakeWordDetector(
    context: Context,
) : WakeWordDetector {
    var onWake: ((String) -> Unit)? = null

    private val appContext = context.applicationContext
    private val lock = Any()
    private val initExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "iflytek-wake-init").apply { isDaemon = true }
    }

    @Volatile
    override var state: WakeWordState = WakeWordState.IDLE
        private set

    private var wakeuper: VoiceWakeuper? = null
    private var startWhenReady = false
    private var wantListening = false
    private var engineReady = false
    private var tornDown = false

    private val listener = object : WakeuperListener {
        override fun onBeginOfSpeech() = Unit

        override fun onResult(result: WakeuperResult?) {
            val phrase = synchronized(lock) {
                parseWakeResultJson(result?.resultString.orEmpty())?.let { "${it.id}:${it.score}" }
            } ?: return
            DebugVoiceLog.log("wake_detection")
            onWake?.invoke(phrase)
            resumeListeningIfNeeded()
        }

        override fun onError(error: SpeechError?) {
            // The engine's own failures were invisible here: MSC reported 200061 only in its
            // native log, so a wake word that never fired looked identical to one nobody said.
            // The code is the whole diagnosis (200061 is reported as a network error even when
            // the network is provably fine), so it is worth a line of its own.
            DebugVoiceLog.log("wake_session_error code=${error?.errorCode ?: -1}")
            resumeListeningIfNeeded()
        }

        override fun onEvent(eventType: Int, arg1: Int, arg2: Int, obj: Bundle?) = Unit

        override fun onVolumeChanged(volume: Int) = Unit
    }

    override fun initialize(credentials: WakeWordCredentials, workDir: File) {
        // workDir is unused: MSC reads ivw/wakeword.jet from assets via ResourceUtil.
        synchronized(lock) {
            if (tornDown) return
            if (state == WakeWordState.AUTHORISING || state == WakeWordState.READY || state == WakeWordState.LISTENING) return
            if (!credentials.isComplete()) {
                // Distinct from a genuine engine failure: the APPID has never been entered.
                // One shared failure string previously made this indistinguishable in the field.
                state = WakeWordState.ERROR
                DebugVoiceLog.log("wake_init_failure reason=no_appid")
                return
            }
            state = WakeWordState.AUTHORISING
        }
        val appId = credentials.appId
        initExecutor.execute {
            try {
                val params = "appid=" + appId + "," + SpeechConstant.ENGINE_MODE + "=" + SpeechConstant.MODE_MSC
                val utility = SpeechUtility.getUtility() ?: SpeechUtility.createUtility(appContext, params)
                val created = VoiceWakeuper.createWakeuper(appContext, null)
                synchronized(lock) {
                    if (tornDown) {
                        runCatching { created?.destroy() }
                        return@execute
                    }
                    if (utility == null || created == null) {
                        engineReady = false
                        state = WakeWordState.ERROR
                        val reason = if (utility == null) "utility_null" else "wakeuper_null"
                        DebugVoiceLog.log("wake_init_failure reason=$reason")
                        return@execute
                    }
                    wakeuper = created
                    engineReady = true
                    state = WakeWordState.READY
                    if (startWhenReady) {
                        startWhenReady = false
                        openSessionLocked()
                    }
                }
            } catch (_: Exception) {
                synchronized(lock) {
                    engineReady = false
                    state = WakeWordState.ERROR
                }
                DebugVoiceLog.log("wake_init_failure reason=exception")
            }
        }
    }

    override fun startListening() {
        synchronized(lock) {
            wantListening = true
            when (state) {
                WakeWordState.LISTENING -> return
                WakeWordState.AUTHORISING -> {
                    startWhenReady = true
                    return
                }
                WakeWordState.READY -> Unit
                WakeWordState.IDLE, WakeWordState.ERROR -> {
                    if (!engineReady) {
                        state = WakeWordState.ERROR
                        DebugVoiceLog.log("wake_init_failure reason=start_before_ready")
                        return
                    }
                }
            }
            startWhenReady = false
            openSessionLocked()
        }
    }

    override fun writeFrame(pcm: ByteArray, first: Boolean, last: Boolean) {
        // MSC writeAudio has no BEGIN/CONTINUE/END status; first/last are ignored rather than faked.
        val current = synchronized(lock) {
            if (state != WakeWordState.LISTENING) return
            wakeuper
        } ?: return
        runCatching { current.writeAudio(pcm, 0, pcm.size) }
    }

    override fun stopListening() {
        synchronized(lock) {
            wantListening = false
            startWhenReady = false
            val current = wakeuper
            if (current != null) {
                runCatching { current.stopListening() }
            }
            if (state == WakeWordState.LISTENING) {
                state = if (engineReady) WakeWordState.READY else WakeWordState.IDLE
            }
        }
    }

    override fun release() {
        synchronized(lock) {
            wantListening = false
            startWhenReady = false
            engineReady = false
            tornDown = true
            val current = wakeuper
            wakeuper = null
            if (current != null) {
                runCatching { current.stopListening() }
                runCatching { current.destroy() }
                DebugVoiceLog.log("wake_teardown")
            }
            state = WakeWordState.IDLE
        }
    }

    private fun resumeListeningIfNeeded() {
        val shouldRestart = synchronized(lock) {
            if (!wantListening || tornDown) return
            val current = wakeuper ?: return
            if (current.isListening) {
                state = WakeWordState.LISTENING
                return
            }
            true
        }
        if (!shouldRestart) return
        DebugVoiceLog.log("wake_restart")
        startListening()
    }

    private fun openSessionLocked() {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state = WakeWordState.ERROR
            DebugVoiceLog.log("wake_permission_failure")
            return
        }
        val current = wakeuper
        if (current == null) {
            state = WakeWordState.ERROR
            DebugVoiceLog.log("wake_init_failure reason=not_initialised")
            return
        }
        val assetPresent = runCatching {
            appContext.assets.open(IVW_ASSET_PATH).close()
            true
        }.getOrDefault(false)
        if (!assetPresent) {
            state = WakeWordState.ERROR
            DebugVoiceLog.log("wake_resource_failure")
            return
        }
        val resPath = try {
            ResourceUtil.generateResourcePath(appContext, ResourceUtil.RESOURCE_TYPE.assets, IVW_ASSET_PATH)
        } catch (_: Exception) {
            null
        }
        if (resPath.isNullOrBlank()) {
            state = WakeWordState.ERROR
            DebugVoiceLog.log("wake_resource_failure")
            return
        }
        current.setParameter(SpeechConstant.PARAMS, null)
        current.setParameter(SpeechConstant.IVW_THRESHOLD, IVW_THRESHOLD_VALUE)
        current.setParameter(SpeechConstant.IVW_SST, IVW_SST_VALUE)
        current.setParameter(SpeechConstant.KEEP_ALIVE, KEEP_ALIVE_VALUE)
        current.setParameter(SpeechConstant.IVW_RES_PATH, resPath)
        current.setParameter(SpeechConstant.IVW_NET_MODE, IVW_NET_MODE_OFFLINE)
        // The app owns the microphone and hands frames to the engine ([ADR-006] shared capture).
        // Without this the engine opens a second recorder of its own, and on 2026-09-19 that one
        // died a second in with `cannot get record permission, get invalid audio data` and ended
        // the session as `error:200061` - a network code for a microphone problem, which cost
        // three wrong diagnoses. The vendor demo has this line, commented out.
        current.setParameter(SpeechConstant.AUDIO_SOURCE, AUDIO_SOURCE_APP_FED)
        // IVW_AUDIO_PATH and AUDIO_FORMAT are intentionally unset: the vendor demo
        // writes the last minute of microphone audio to disk. We do not.
        val code = current.startListening(listener)
        if (code != ErrorCode.SUCCESS) {
            state = WakeWordState.ERROR
            // The SDK's own code is the only thing that distinguishes, e.g., 10407
            // (APPID does not match this SDK download) from a resource or engine fault.
            // It is a vendor error number, not a credential, so it is safe to log.
            DebugVoiceLog.log("wake_init_failure reason=start_listening code=$code")
            return
        }
        state = WakeWordState.LISTENING
    }

    private companion object {
        const val IVW_ASSET_PATH = "ivw/wakeword.jet"
        const val IVW_THRESHOLD_VALUE = "0:1450"
        const val IVW_SST_VALUE = "wakeup"
        const val KEEP_ALIVE_VALUE = "1"
        const val IVW_NET_MODE_OFFLINE = "0"

        /** `-1`: no recorder of its own; audio arrives through [writeFrame]. */
        const val AUDIO_SOURCE_APP_FED = "-1"
    }
}
