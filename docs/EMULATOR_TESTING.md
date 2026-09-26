# Windows emulator testing

On this PC, double-click **Nova Drive Emulator** on the desktop to start the emulator and install/open the current debug APK. The shortcut uses the launcher below.

Use the local Android Emulator when the test phone is unavailable. From the repository root,
run:

```powershell
.\scripts\emulator.ps1
```

The script discovers
the SDK through `ANDROID_SDK_ROOT`, then `ANDROID_HOME`, then
`C:\Users\Administrator\Android\Sdk`. It reuses or creates the `NovaDrive_API_30` AVD with the
API 30 Google APIs x86_64 system image, starts a visible window, waits at most five minutes for
boot, enables host microphone forwarding, and prints the ADB serial. Use that serial on every ADB
command so another connected device cannot receive it accidentally. The new AVD is configured
with 3 GB RAM, four virtual cores, a 1280×720 landscape display, and a 2 GB data partition. If
`JAVA_HOME` is unset, the launcher uses `C:\Users\Administrator\tools\jdk-17` when present.

If the SDK tools or image are not installed, run `.\scripts\emulator.ps1 -SetupSdk` once; Android
SDK licenses must already have been accepted. The SDK setup installs only platform-tools, the
emulator, and the API 30 Google APIs x86_64 image. When `HTTPS_PROXY` or `HTTP_PROXY` is set, the
launcher extracts only its host and port for sdkmanager and does not print the proxy URL. To install
the current debug APK and open the app:

```powershell
.\scripts\emulator.ps1 -InstallApk
```

The default APK path is `C:\Users\Administrator\tools\nova-drive-build\app\outputs\apk\debug\app-debug.apk`.
Pass `-ApkPath <path>` to choose another APK. Setup and APK installation can be combined. The
launcher also accepts `-BootTimeoutSeconds` to change the bounded boot wait.

This emulator is useful for UI, basic lifecycle, and ADB-driven component checks. It cannot certify
real cabin acoustics, speaker echo cancellation, microphone gating under road noise, driver
intelligibility, or physical vehicle behavior. The repository requires physical-device evidence for
audio and navigation behavior in [ACCEPTANCE_TESTS.md](../ACCEPTANCE_TESTS.md). Amap and Baidu
live-service checks also need their real credentials and network access. The API 30 Google APIs x86_64 image includes [ARM native-library translation support](https://android-developers.googleblog.com/2020/03/run-arm-apps-on-android-emulator.html). Still verify that the app's native libraries load on this image before treating emulator results as evidence of ABI compatibility on a physical device.

## Voice on the x86 emulator (measured 2026-09-26, nova_api30, Windows host, WHPX)

- The APK ships only ARM native libraries, so the app runs as `arm64-v8a` under translation
  (`dumpsys package com.novadrive.app` → `primaryCpuAbi=arm64-v8a`). Debug builds therefore skip
  the Amap map surface and WebRTC AEC3 there (`TranslatedAbi`); translated AEC3 took ~80% of a
  core on `nova-pcm-capture` and the reply track underran ~30 times a second.
- The host microphone bridge zero-fills one 15 ms HAL buffer (672 frames at 44.1 kHz) in every
  ~46 ms: about 30% of captured samples are exact zeros, with or without AEC, with `-audio dsound`
  or the default backend. Speech reaches Baidu chopped; the uplink gate is off on this emulator
  because it would reject the chopped bursts as impulses. Live-mic recognition here is a
  best-effort check, not evidence.
- The emulator records from the Windows default recording device. Speakers-to-mic playback of a
  harness clip is not heard when that device is a headset.
- `voice say:<clip>` injection is paced in real time (`test_speech_end frames=207 elapsedMs=2073`
  for a 2.07 s clip) and exercises Baidu recognition, tools and reply playback end to end.
