# Voice provider setup — Nova Drive / 小诺

Nova Drive connects to Baidu Qianfan directly from the Android app. There is no PC backend in the current runtime. `VoiceSessionController` selects the configured Baidu runtime; a fresh install defaults to **Baidu Flex** (`qianfan-realtime-flex-v1`), which provides end-to-end speech-to-speech and function calling.

## Configure local Android build settings

Create or edit the ignored `local.properties` file at the repository root. It is used by Gradle for the Amap Navigation SDK key, not Baidu voice credentials:

```properties
AMAP_API_KEY=your_amap_navigation_sdk_key
```

`local.properties` is excluded by `.gitignore`. Do not commit it, copy credentials into source code, or paste secret values into chat or logs. The Amap Web-service key used for place search is entered separately in the app's Developer Settings and stored with Android Keystore.

## Build, install, and configure voice

Build the debug APK from the repository root:

```powershell
.\gradlew.bat :app:assembleDebug
```

On this Windows setup, the APK is written to:

```text
C:\Users\Administrator\tools\nova-drive-build\app\outputs\apk\debug\app-debug.apk
```

Install to a connected Android device with USB debugging enabled:

```powershell
adb install -r C:\Users\Administrator\tools\nova-drive-build\app\outputs\apk\debug\app-debug.apk
```

Launch Nova Drive, open **开发者设置**, and enter the Baidu credentials there. The app stores App ID, API Key, and Secret Key encrypted with Android Keystore; credential values are not echoed back. With the default **App ID + API Key + Secret Key** authentication mode, all three values are required. The optional **Bearer API Key** mode uses the API Key only.

Use **测试连接** to verify the selected realtime session. This opens and closes the provider session without starting microphone capture. Then start a voice session from the main screen and grant microphone permission when Android requests it.

## Runtime and model choices

| Runtime | Model | Behavior |
| --- | --- | --- |
| Baidu Flex (default) | `qianfan-realtime-flex-v1` | End-to-end audio with the app's supported function-calling tools. |
| Baidu Lite / Pro (compatibility) | Lite Near, Lite Far, Pro Near, or Pro Far | Direct Baidu realtime audio conversation; this documented API path does not support the app's function calling. |

The Developer Settings model choice selects Flex or the Lite/Pro compatibility runtime. Existing installations with an older Lite/Pro selection may keep that selection after settings migration; select Flex there to use the current default. Credentials are never required from a PC service, and there is no `VOICE_PROVIDER`, `.env`, backend URL, emulator alias, or backend startup step in the Android voice path.

See [BAIDU_DIRECT_ANDROID.md](BAIDU_DIRECT_ANDROID.md) for runtime details and [BAIDU_E2E_SETUP.md](BAIDU_E2E_SETUP.md) for the device setup and troubleshooting procedure.
