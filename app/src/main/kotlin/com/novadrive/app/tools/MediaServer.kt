package com.novadrive.app.tools

import com.novadrive.app.AndroidActionExecutor
import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.media.HandoffResult
import com.novadrive.app.media.MusicHandoffTool
import com.novadrive.app.media.MusicRequest
import com.novadrive.app.media.NowPlaying
import com.novadrive.app.media.NowPlayingVerifier
import com.novadrive.app.media.PlaybackCheck
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Executes [MediaDomain]: the bundled track (play or stop), and `play_music` through the driver's
 * music app (SPEC-017). Only the MediaSession readback may be claimed (I-1).
 */
class MediaServer(
    private val executor: AndroidActionExecutor,
    private val music: MusicHandoffTool? = null,
) : ToolServer {
    override val domain: ToolDomain = MediaDomain

    override fun call(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult =
        when (call.name) {
            "control_music" -> {
                when (call.arguments["action"]) {
                    // A named or described request never reaches here: ToolCallGuards.unsupportedMedia
                    // refuses it and points the model at play_music.
                    "play" -> env.result(call, executor.playMusic())
                    "stop" -> env.result(call, executor.stopMusic())
                    else -> env.failed(call, "ACTION_NOT_ALLOWED")
                }
            }
            MediaDomain.PLAY_MUSIC -> playMusic(call, env)
            else -> env.failed(call, "UNKNOWN_TOOL")
        }

    private fun playMusic(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult {
        val tool = music ?: return env.failed(call, MUSIC_HANDOFF_UNAVAILABLE)
        val a = call.arguments
        val request = MusicRequest(a["title"], a["artist"], a["album_or_work"], a["mood"], a["query"], a["exclude_title"])
        // Before the hand-off: a track that was already playing is not evidence of this request.
        val previous = tool.snapshot()
        when (val sent = tool.handOff(request)) {
            is HandoffResult.Sent -> Unit
            HandoffResult.NoApp -> return env.failed(call, NO_MUSIC_APP)
            is HandoffResult.Rejected -> return env.failed(call, HANDOFF_REJECTED)
        }
        return ToolDispatchResult(
            null,
            null,
            successChip = "🎵 已交给音乐 app",
            deferredOutput = {
                val check = tool.await(request, previous)
                DebugVoiceLog.log(NowPlayingVerifier.logLine(check, tool.lastWaitedMs))
                com.novadrive.app.voice.SpeechAuthority.arbiter.onConfirmation()
                output(call, check, env)
            },
        )
    }

    private fun output(call: DomainVoiceEvent.ToolCall, check: PlaybackCheck, env: ToolCallEnv): String =
        when (check) {
            is PlaybackCheck.Playing -> {
                val np = check.nowPlaying
                JSONObject().put("ok", true).put("tool", call.name).put("status", "playing")
                    .put(
                        "now_playing",
                        JSONObject().put("title", np.title ?: JSONObject.NULL)
                            .put("artist", np.artist ?: JSONObject.NULL)
                            .put("app", np.packageName ?: JSONObject.NULL),
                    )
                    .put("matches_request", check.matchesRequest)
                    .put("announce", announce(np, check.matchesRequest))
                    .put("instruction", "只根据 now_playing 说正在放什么，不要说别的歌名")
                    .put("chip", "🎵 ${np.title ?: "音乐"}")
                    .toString()
            }
            PlaybackCheck.Unverified -> JSONObject().put("ok", true).put("tool", call.name)
                .put("status", "requested_unverified")
                .put("instruction", "只能说已经让音乐 app 去找了，不能说正在放哪首歌")
                .put("chip", "🎵 已交给音乐 app")
                .apply { if (hintShown.compareAndSet(false, true)) put("hint", ACCESS_HINT) }
                .toString()
            PlaybackCheck.NotPlaying -> env.failed(call, NOT_PLAYING).output!!
        }

    companion object {
        const val NOT_PLAYING = "NOT_PLAYING"
        const val NO_MUSIC_APP = "NO_MUSIC_APP"
        const val HANDOFF_REJECTED = "HANDOFF_REJECTED"
        const val MUSIC_HANDOFF_UNAVAILABLE = "MUSIC_HANDOFF_UNAVAILABLE"
        const val ACCESS_HINT = "在系统设置里给小诺打开「通知使用权」，小诺就能确认正在放哪首歌。"

        /** Once per process. */
        private val hintShown = AtomicBoolean(false)

        internal fun resetHintForTest() = hintShown.set(false)

        fun announce(np: NowPlaying, matches: Boolean): String {
            val who = np.artist?.takeIf { it.isNotBlank() }?.let { "${it}的" }.orEmpty()
            val title = np.title?.takeIf { it.isNotBlank() }
            val song = if (title != null) "$who《$title》" else if (who.isNotEmpty()) "${who}歌" else "一首歌"
            return if (matches) "在放$song" else "现在放的是$song，不确定是不是你要的那首，要换吗？"
        }
    }
}
