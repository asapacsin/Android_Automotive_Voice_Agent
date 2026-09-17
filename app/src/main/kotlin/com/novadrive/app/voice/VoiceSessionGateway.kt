package com.novadrive.app.voice

import com.novadrive.app.BaiduApiConfig

/**
 * Single application-scoped start/stop seam for the voice session.
 * MainActivity owns the controller; DeveloperSettings and the future wake-word
 * handler call [start]/[stop] on this gateway.
 */
object VoiceSessionGateway {
    @Volatile
    private var attachedIdentity: Any? = null

    @Volatile
    private var starter: GatewaySession? = null

    @Volatile
    private var service: SessionServiceControl? = null

    fun attach(controller: VoiceSessionController, service: SessionServiceControl) {
        attachInternal(controller, ControllerGatewaySession(controller, service), service)
    }

    fun detach(controller: VoiceSessionController) {
        detachInternal(controller)
    }

    val isActive: Boolean
        get() = starter?.isActive == true

    /**
     * Wake word / UI / app entry. A running session in STANDBY resumes listening; one in ACTIVE
     * restarts its inactivity countdown; with no session (DEEP_IDLE) a new one is opened.
     */
    fun start(reason: String = "start"): StartResult {
        val session = starter ?: return StartResult.NotAttached
        if (session.isActive) {
            session.activate(reason)
            return StartResult.AlreadyActive
        }
        if (!session.hasMicPermission()) return StartResult.MicPermissionMissing
        return try {
            session.startBaidu(reason)
            service?.start()
            StartResult.Started
        } catch (failure: IllegalArgumentException) {
            StartResult.ConfigInvalid(failure.message ?: "INVALID_CONFIGURATION")
        }
    }

    fun stop() {
        starter?.stop()
        service?.stop()
    }

    /**
     * Has the assistant say something in its own voice: starts the session if it is not running,
     * then sends [prompt] as a text turn (queued until connected). Returns why it could not, if so.
     */
    /** Debug speech harness entry; no-op unless a session is active on a debuggable build. */
    fun injectTestSpeech(pcm16le: ByteArray): Boolean {
        val session = starter ?: return false
        if (!session.isActive) return false
        session.injectTestSpeech(pcm16le)
        return true
    }

    /** Debug A/B switch for the microphone input gain. */
    fun setInputGainEnabled(enabled: Boolean) {
        starter?.setInputGainEnabled(enabled)
    }

    fun speak(prompt: String): StartResult {
        val session = starter ?: return StartResult.NotAttached
        val started = start("app_prompt")
        if (started !is StartResult.Started && started !is StartResult.AlreadyActive) return started
        session.sendText(prompt)
        return started
    }

    /** UI / voice: stop listening now; the session and the wake word stay available. */
    fun standby(reason: String) {
        starter?.standby(reason)
    }

    /** The model's end_conversation tool: stop listening after the goodbye. */
    fun standbyAfterReply(reason: String): Boolean {
        val session = starter ?: return false
        if (!session.isActive) return false
        session.standbyAfterReply(reason)
        return true
    }

    val listeningState: ListeningState
        get() = starter?.takeIf { it.isActive }?.listeningState ?: ListeningState.DEEP_IDLE

    internal fun attachInternal(identity: Any, session: GatewaySession, service: SessionServiceControl) {
        attachedIdentity = identity
        starter = session
        this.service = service
    }

    internal fun detachInternal(identity: Any) {
        if (attachedIdentity === identity) {
            attachedIdentity = null
            starter = null
            service = null
        }
    }

    internal fun isAttached(identity: Any): Boolean = attachedIdentity === identity
}

interface SessionServiceControl {
    fun start()
    fun stop()
    fun hasMicPermission(): Boolean
    fun baiduConfig(): BaiduApiConfig
}

sealed interface StartResult {
    data object Started : StartResult
    data object AlreadyActive : StartResult
    data object MicPermissionMissing : StartResult
    data object NotAttached : StartResult
    data class ConfigInvalid(val message: String) : StartResult
}

internal interface GatewaySession {
    val isActive: Boolean
    fun hasMicPermission(): Boolean
    fun startBaidu(reason: String = "start")
    fun stop()
    fun activate(reason: String) {}
    fun standby(reason: String) {}
    fun standbyAfterReply(reason: String) {}
    val listeningState: ListeningState get() = if (isActive) ListeningState.ACTIVE else ListeningState.DEEP_IDLE
    fun sendText(text: String) {}
    fun injectTestSpeech(pcm16le: ByteArray) {}
    fun setInputGainEnabled(enabled: Boolean) {}
}

private class ControllerGatewaySession(
    private val controller: VoiceSessionController,
    private val service: SessionServiceControl,
) : GatewaySession {
    override val isActive: Boolean
        get() = controller.sessionActiveNow

    override fun hasMicPermission(): Boolean = service.hasMicPermission()

    override fun startBaidu(reason: String) {
        controller.startBaidu(service.baiduConfig(), reason)
    }

    override fun activate(reason: String) {
        controller.activateListening(reason)
    }

    override fun standby(reason: String) {
        controller.standby(reason)
    }

    override fun standbyAfterReply(reason: String) {
        controller.standbyAfterReply(reason)
    }

    override val listeningState: ListeningState
        get() = controller.listeningState

    override fun stop() {
        controller.stop()
    }

    override fun sendText(text: String) {
        controller.sendText(text)
    }

    override fun injectTestSpeech(pcm16le: ByteArray) {
        controller.injectTestSpeech(pcm16le)
    }

    override fun setInputGainEnabled(enabled: Boolean) {
        controller.setInputGainEnabled(enabled)
    }
}
