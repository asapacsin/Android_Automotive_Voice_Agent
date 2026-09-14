# Realtime protocol references (inspected, not invented)

Retrieved **2026-09-14**. Adapters port session/audio/event concepts only. Desktop/Web/TUI samples are not copied.

## Qwen Audio Realtime (product default)

- GitHub: https://github.com/QwenAudio/qwen-audio-agent
- Architecture page linked from that repo was HTTP 404 on 2026-09-14; protocol taken from current QwenCloud/DashScope docs.
- WebSocket API: https://docs.qwencloud.com/api-reference/qwen-audio-realtime/websocket-api
- Client events: https://docs.qwencloud.com/api-reference/qwen-audio-realtime/client-events
- Server events: https://docs.qwencloud.com/api-reference/qwen-audio-realtime/server-events
- User guide: https://help.aliyun.com/en/model-studio/qwen-audio-realtime-user-guides
- Endpoint: `wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime?model=<model>`
- Auth: `Authorization: Bearer $DASHSCOPE_API_KEY`
- Product default: `qwen-audio-3.0-realtime-flash` (Qwen Plus `qwen-audio-3.0-realtime-plus` selectable)
- Client events used: `session.update`, `input_audio_buffer.append`, `input_audio_buffer.commit`, `response.cancel`, `conversation.item.create` (`function_call_output`), `response.create`
- Input PCM 16 kHz; output PCM 24 kHz on the official API (Android capture remains 16 kHz; backend does not resample in this checkpoint)
- Tools via `session.update.tools`; function call completes on `response.function_call_arguments.done`

## Baidu E2E (optional compatibility)

- API: https://cloud.baidu.com/doc/SPEECH/s/nmcytnwei (updated 2026-09-04)
  Mirror: https://ai.baidu.com/ai-doc/SPEECH/nmcytnwei
- Auth: https://cloud.baidu.com/doc/SPEECH/s/cm8sn2bii
- Realtime WS: `wss://aip.baidubce.com/ws/2.0/speech/v1/realtime?model=<model>&access_token=<token>`
- Provider-family default when Baidu is selected: `audio-mini-realtime-near` (Lite Near). Also Lite Far / Pro Near / Pro Far.
- Documented client events only: `session.update`, `input_audio_buffer.append`
- Returned `Session` examples include `tool_choice` and `tools: []`. `UpdateSession` and `session.update` do **not** expose those fields, so they are not a documented custom-tool request/result protocol.
- `ConversationItem.type` allows `message` only.
- Interruption: `turn_detection.type=server_vad` with `interrupt_response=true` (currently only true). Server events `input_audio_buffer.speech_started` and `response.done` with `status=cancelled` / `reason=turn_detected` are the documented interrupt evidence. There is **no** documented client cancel/interrupt event.
- Custom Function Calling remains **BLOCKED_BAIDU_FUNCTION_CALLING**

## OpenAI GPT-Live (optional)

- TypeScript Live resource: https://developers.openai.com/api/reference/typescript/resources/live
- WebSockets: https://developers.openai.com/api/docs/guides/voice-websockets
- Getting started: https://developers.openai.com/api/docs/guides/live
- Delegation: https://developers.openai.com/api/docs/guides/live-delegation
- Sessions: https://developers.openai.com/api/docs/guides/live-conversations
- Model: https://developers.openai.com/api/docs/models/gpt-live-1
- Endpoint: `wss://api.openai.com/v1/live/sessions`
- Auth: `Authorization: Bearer $OPENAI_API_KEY`
- First client event: `session.start` with `model=gpt-live-1`, PCM `audio/pcm` rate 16000, `delegation.type=client`
- Audio: `session.input_audio.append` / `session.output_audio.delta`
- Transcripts: `session.input_transcript.delta` / `session.output_transcript.delta`
- Work result: `session.commentary.append` with `delegation_id` + `content`
- Nested Responses tools (when present): `response.event` → `response.output_item.done` then `response.item.create` + `response.create`
- **No documented speech `response.cancel`.** Local playback flush only. Interrupting speech does not cancel backend work.
