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

    fun start(): StartResult {
        val session = starter ?: return StartResult.NotAttached
        if (session.isActive) return StartResult.AlreadyActive
        if (!session.hasMicPermission()) return StartResult.MicPermissionMissing
        return try {
            session.startBaidu()
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
        val started = if (session.isActive) StartResult.AlreadyActive else start()
        if (started !is StartResult.Started && started !is StartResult.AlreadyActive) return started
        session.sendText(prompt)
        return started
    }

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
    fun startBaidu()
    fun stop()
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

    override fun startBaidu() {
        controller.startBaidu(service.baiduConfig())
    }

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
