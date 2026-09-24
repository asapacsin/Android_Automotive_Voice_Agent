# Baidu direct Android runtime

The Android app connects to Baidu Qianfan realtime directly. There is no PC backend in the voice path. The canonical ownership and event flow are documented in [ARCHITECTURE.md](ARCHITECTURE.md); this page records provider-specific setup facts.

## Runtime selection

`VoiceSessionController` creates the configured provider. A fresh installation defaults to Baidu Flex, model `qianfan-realtime-flex-v1`; Flex carries the app's function-calling tools. The Lite/Pro compatibility choices use Baidu's direct realtime conversation API and do not expose function calling. Existing installs may retain a saved Lite/Pro selection.

Flex uses `BaiduFlexClient` and `BaiduFlexProtocol`. Provider events are adapted to the provider-neutral ingress event contract. Function-call arguments pass through the existing assembler/dispatcher boundary; device actions are never inferred from assistant prose. The Lite/Pro adapter is conversation-only.

The app sends and receives PCM through its existing capture and playback ports. Flex output defaults to 24 kHz; Lite/Pro output defaults to 16 kHz. Do not interpret those settings as evidence that the current audio route is acoustically verified.

## Credentials and connection check

In **开发者设置**, enter Baidu credentials. The default App ID authentication mode requires App ID, API Key and Secret Key; the optional Bearer API Key mode uses the API Key. `BaiduSettingsRepository` stores credentials encrypted with Android Keystore. A blank field keeps the saved value; use the explicit clear action to remove credentials.

The **测试连接** action opens the selected provider session and closes it without starting microphone capture. A normal voice session streams directly from the Android device over the provider WebSocket. It does not use `.env`, a backend URL, localhost, emulator host aliases, a PC LAN address, or ADB for runtime transport.

See [PROVIDER_SETUP.md](PROVIDER_SETUP.md) for local build/install steps and [BAIDU_E2E_SETUP.md](BAIDU_E2E_SETUP.md) for troubleshooting. The realtime Flex service may require public-beta account enablement. Device evidence is tracked in [ACCEPTANCE_TESTS.md](../ACCEPTANCE_TESTS.md), not inferred from a successful connection handshake.
