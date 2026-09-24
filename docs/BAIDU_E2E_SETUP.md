# Baidu realtime Android setup

Nova Drive uses a direct Android connection to Baidu Qianfan. The app's default is **Baidu Flex**, model `qianfan-realtime-flex-v1`, for end-to-end speech-to-speech with function calling. Lite Near, Lite Far, Pro Near, and Pro Far remain selectable compatibility models; that Lite/Pro API path does not support the app's function calling. See [BAIDU_DIRECT_ANDROID.md](BAIDU_DIRECT_ANDROID.md) and [PROVIDER_SETUP.md](PROVIDER_SETUP.md).

## 1. Set up local Android build configuration

At the repository root, create or edit the git-ignored `local.properties` file:

```properties
sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
AMAP_API_KEY=your_amap_navigation_sdk_key
```

This property configures the Amap Navigation SDK in the Android build. It is not a Baidu credential. Do not commit `local.properties` or put Baidu credentials in it, in source code, or in chat.

## 2. Build and install the app

From the repository root in PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r C:\Users\Administrator\tools\nova-drive-build\app\outputs\apk\debug\app-debug.apk
```

The install command assumes the configured Windows build output path and a connected device with USB debugging enabled. Alternatively, open this repository in Android Studio, sync Gradle, and run the `app` debug configuration on the device.

## 3. Enter Baidu credentials in the app

Open **开发者设置** and enter credentials in the Baidu voice settings. The default authentication mode, **App ID + API Key + Secret Key**, requires all three values from the Baidu console. The optional **Bearer API Key** mode requires only the API Key. The app encrypts stored credentials with Android Keystore; blank credential fields retain saved values, and values are not shown again. Use the explicit clear action to remove saved credentials.

The default runtime and model are Baidu Flex / `qianfan-realtime-flex-v1`. The four Lite/Pro models are compatibility choices and do not have function calling. Older installations can retain a previous Lite/Pro choice after settings migration; choose Flex in Developer Settings to switch. Flex access is in public beta and may require account enablement by Baidu.

Tap **测试连接** to open and close a provider session without activating the microphone. Then return to the main screen, start a voice session, grant Android microphone permission, and speak a short test phrase. End the session using the microphone control. The separate **麦克风测试** checks local capture only and does not connect to Baidu.

## 4. Expected behavior and troubleshooting

With Flex, a successful test connection reaches a ready session, and an active voice session can receive and play a spoken reply. The app connects to Baidu's realtime WebSocket directly; no backend process, backend URL, emulator host alias, LAN address, or PC credential file is involved.

| Symptom | Meaning / action |
| --- | --- |
| `BAIDU_API_KEY_MISSING` | Add an API Key in Developer Settings. |
| `BAIDU_APP_ID_MISSING` or `BAIDU_SECRET_KEY_MISSING` | In the default legacy access-token mode, fill the missing credential; alternatively select Bearer API Key mode if that is how the account is configured. |
| `BAIDU_AUTH_FAILED` | Check the selected authentication mode and credentials in the Baidu console. |
| `BAIDU_FLEX_ACCESS_DENIED` | Flex public-beta/model access was denied; check Baidu account enablement. |
| `MIC_PERMISSION_DENIED` | Grant microphone permission in Android settings and retry. |
| Connection, DNS, TLS, or timeout error | Check the device's internet connection and retry **测试连接**. |
| Invalid model error | Select Flex (`qianfan-realtime-flex-v1`) or one of the supported Lite/Pro choices in Developer Settings. |

Normal local tests use fake or scripted providers and do not require Baidu credentials or send live audio. Live device use requires Baidu credentials and network access.
