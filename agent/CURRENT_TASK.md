# Current Task

## Direct on-device Baidu Flex checkpoint

Date: 2026-09-15

Baidu Flex Function Calling is implemented and live-verified on-device. Default runtime is `qianfan-realtime-flex-v1`. Lite Near / Lite Far / Pro Near / Pro Far remain selectable as conversation-only Pro/Lite.

Verified on Xiaomi 24069RA21C (serial 2391ff70, Android SDK 36): Test Connection with Keystore credentials; a live voice session with transcripts and a `navigate_to` function call; hands-free Amap turn-by-turn via the real tool path with an Amap Web-service key; foreground-service keep-alive; `open_app` settings and maps intents.

Remaining manual items: user ear-test of reply audio (flip the sample-rate switch if wrong); entering an Amap Web-service key for hands-free navigation; a full drive test. No commit or push was performed. No credentials exist in the repository.

Reuse the provider-neutral session controller, audio ports, domain events, work coordinator, and safety/orchestration interfaces. Keep Qwen/GPT compatibility frozen; do not add provider-specific behavior or tests for them.

Do not parse assistant prose as commands and do not invent protocol messages. Flex uses only the documented client events (`session.update`, `input_audio_buffer.append`, `response.cancel`, `conversation.item.create` / `function_call_output`, `response.create`) and validated tools (`navigate_to`, `open_app`) through `AndroidToolDispatcher`. Pro/Lite has no Function Calling.

Do not commit or push. Never hardcode, print, log, package, or expose credentials. Do not claim physical-device or live-provider success without the corresponding evidence.
