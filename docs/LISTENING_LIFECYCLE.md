# Listening lifecycle (ACTIVE / STANDBY / DEEP_IDLE)

Implemented 2026-09-17. The assistant no longer streams the microphone to Baidu for as long as a
session exists.

## States

| State | Microphone capture | Upload to Baidu | Realtime socket | Leaves by |
| --- | --- | --- | --- | --- |
| **ACTIVE** | on | on (except temporary suppression, below) | open | inactivity, 「关闭小诺」-type phrase, `end_conversation`, UI tap |
| **STANDBY** | **released** (AudioRecord stopped) | none; held audio dropped | open while the server keeps it | wake word, UI tap, app prompt (camera look) → ACTIVE; 5 min → DEEP_IDLE; socket loss → DEEP_IDLE |
| **DEEP_IDLE** | off | none | **closed**, no reconnect | wake word, UI tap → new session → ACTIVE |

`ListeningLifecycle` (app/voice) is the only authority. It drives a single switch in the core
session, `VoiceSessionController.setCaptureSuspended` (ingress), which stops capture, drops queued
audio and blocks every path that would re-arm capture (including reconnect `SessionReady`).

**Temporary suppression is a different thing** and is unchanged: while 小诺's reply plays
(`gated`) or navigation guidance plays (`guidanceGated`, P13) frames are dropped, but the state stays
ACTIVE and upload resumes when the audio ends. Neither gate touches the lifecycle, and the lifecycle
does not touch them.

## Timeouts (`ListeningTimeouts`)

| Constant | Value | Meaning |
| --- | --- | --- |
| `STANDBY_AFTER_MS` | **30 s** | no meaningful user turn since the assistant finished → STANDBY |
| `DEEP_IDLE_AFTER_STANDBY_MS` | **5 min** | in STANDBY that long → DEEP_IDLE (socket closed) |
| `IDLE_GRACE_MS` | 1.5 s | a deadline that passed while busy fires this long after becoming idle |

What counts:
- The countdown restarts on session start / resume, and when the answer to a **meaningful** user
  turn has finished (reply done, reply audio played, no tool result owed).
- It never fires while busy: user speaking, model thinking or talking, reply audio playing, tool
  work pending, connecting / reconnecting.
- It is **not** extended by: assistant speech on its own, navigation guidance, navigation progress,
  tool execution, UI, vehicle state. A VAD false trigger that yields 「嗯」 or an empty transcript
  (`ListeningIntent.isMeaningful`) does not restart it, so room noise cannot keep streaming alive.
- Every timer is epoch-guarded: a timer from an earlier ACTIVE stretch cannot end a newer one.

## Ending listening by voice (TERMINATE_LISTENING)

Handled locally in `VoiceSessionController.onUserUtterance` as soon as the final transcript
arrives; the model's reply to the phrase is cancelled (`response.cancel` even before its audio
starts), its audio is not played, and the client sends no follow-up turns of its own while in
STANDBY. The app is not closed and the wake word keeps running.

Phrases (whole utterance only, after removing punctuation, a leading 「小诺/你好小诺/好的」 and a
trailing 「吧/了/啊/谢谢」):

| Always (they name the assistant or listening) | Only when no list is open and no tool is pending |
| --- | --- |
| stop listening · go to sleep · that's all · that is all · 关闭小诺 · 小诺关闭 · 关掉小诺 · 别听了 · 不要听了 · 停止监听 · 停止聆听 · 休息吧 · 小诺休息(吧) · 你休息吧 · 去休息吧 | close · never mind · 不用了 · 不需要了 · 没事了 |

Paraphrases go to the model, which can call the new tool **`end_conversation`**; listening then
stops after its one-sentence goodbye has played.

### Precedence

1. **Specific task commands** — anything else, including 「关闭导航」「关闭空调」「close the window」
   「stop the music」「cancel navigation」 → model and tools.
2. **Contextual cancellation** — with a destination/route list on screen or tool work pending,
   「不用了」「never mind」「close」 go to the model (→ `exit_navigation_mode`). 「算了」「取消」
   「返回」「cancel」「stop」 are never intercepted and keep their existing task meaning.
3. **Assistant-session termination** — the table above → STANDBY.
4. **Generic** → model.

While merely navigating (guidance running, no list open) 「不用了」 ends listening; it does not stop
navigation. 「结束导航」 still does.

## Wake word and UI

- Local wake word **exists** (iFlytek MSC, offline) and keeps listening in every state.
  A detection calls `VoiceSessionGateway.start("wake_word")`: STANDBY → ACTIVE on the same
  connection with a fresh conversation, ACTIVE → countdown restarted, DEEP_IDLE → new session.
- The status row under 小诺's avatar is the UI control and indicator: 🎙 聆听中 (streaming),
  小诺已待命 (standby), 休眠中 (deep idle). Tapping it stops listening when active, otherwise starts.
- App prompts (camera auto-look) resume listening first, so their spoken answer is heard.

## Instrumentation

Telemetry events (`com.novadrive.evaluation.Telemetry`, monotonic time): `LISTENING_ACTIVE`,
`LISTENING_STANDBY`, `LISTENING_DEEP_IDLE` (detail: from, reason, cumulative cloud-streaming ms),
`INACTIVITY_TIMEOUT`, `TERMINATE_LISTENING`, `SPEECH_START`, `RESPONSE_COMPLETED`,
`SOCKET_CONNECTED`, `SOCKET_DISCONNECTED`, `WAKE_DETECTED`. The debug log mirrors transitions as
`listening FROM->TO reason=… cloudStreamingMs=…`, and `session_diag` shows `listening=` and
`captureSuspended=` every 5 s.

## Device evidence (2026-09-17, synthetic speech, debug build)

- Start → STANDBY after **30.07 s** of silence; captured-frame counter frozen; injected speech in
  STANDBY produced no speech event and no transcript.
- 「关闭小诺」 → STANDBY 68 ms after the transcript; its reply was not played.
- 「关闭空调」 → `control_climate power_off`, still ACTIVE.
- 「不用了」 with the destination list open → list cancelled (`exit_navigation_mode`, after the
  false-claim guard caught a spoken-only cancel); 「不用了」 with nothing open → STANDBY in 83 ms.
- Emulator navigation with guidance talking continuously → STANDBY 30.08 s after activation;
  guidance gate kept toggling, capture stayed off.
- STANDBY → DEEP_IDLE exactly 5 min later (socket closed, no reconnect); `voice start` → new
  session, commands work.

## Not yet verified

- Real wake word spoken by a person in each state, and the UI tap on the status row (both code
  paths are the same `VoiceSessionGateway.start`, exercised here by ADB).
- Whether Baidu closes an idle STANDBY socket before 5 min (handled: → DEEP_IDLE, but the timing is
  unmeasured).
- Very long drives: battery/thermal effect of the wake-word engine alone.

## Silent mode (「闭嘴」) — added 2026-09-17

Separate from the listening states: 小诺 **keeps listening and carrying out commands**, but its
replies are shown as text only (`SpeechOutput.silent`, checked by the playback port together with
ACTIVE). The reply in progress is cut off when silent mode starts. Process-wide, not persisted
(an app restart speaks again). Navigation guidance is not affected.

| Turns voice off (whole utterance, handled locally) | Turns voice back on |
| --- | --- |
| 闭嘴 · 小诺闭嘴 · 安静(点/一点) · 保持安静 · 别说话 · 不要说话 · 别出声 · 不要出声 · 别吵(了) · 少说话 · 静音 · 小诺静音 · 别说了 · 不要说了 · shut up · be quiet · keep quiet · keep silent · stay silent · stop talking · silence · mute | 可以说话了 · 你可以说话了 · 说话吧 · 恢复语音 · 恢复说话 · 取消静音 · 开口吧 · 出声吧 · 可以出声了 · you can talk (now) · you can speak · speak again · unmute · talk to me |

Other wordings go to the model's `set_speech_output` tool (`silent` / `spoken`). The UI shows
「🔇 静音」 on the status row; tapping it while silent restores voice. Precedence: stop-listening
phrases first, then silence / restore, then contextual cancellation, then the model. Phrases that
only contain these words (「导航到安静的咖啡馆」「关闭音乐」) go to the model.

**Interrupting a reply.** While 小诺 speaks the microphone is closed to its own voice (echo
protection), so 「闭嘴」 said over a reply is not heard. The wake word is: 「你好小诺」 during a reply
now cuts the reply off, and the following 「闭嘴」 is heard.

Device (synthetic speech, 2026-09-17): 「闭嘴」 → silent 2 ms after the transcript, its reply cancelled
before any audio; 「温度调高一点」 while silent → executed, text only (`reply_audio_not_played
reason=silent`); 「可以说话了」 → spoken again; "keep quiet" → silent; wake path during a ~20 s reply →
playback stopped and the microphone reopened 0.35 s later (the real spoken wake word was not used:
the MSC engine cannot be fed injected audio).
