# Windows emulator testing

On this PC, double-click **Nova Drive Emulator** on the desktop. It runs, from the repository root:

```powershell
.\scripts\emulator.ps1 -InstallApk
```

The launcher boots the `nova_api34` AVD (API 34 Google APIs x86_64, on `D:\android-avd`)
detached with `-no-snapshot-save -allow-host-audio -gpu host -feature GLESDynamicVersion
-camera-back webcam0`, waits at most five minutes for boot, then applies `adb root`, host-mic
forwarding, the PC proxy (`settings put global http_proxy 10.0.2.2:7897`, needed for Baidu TLS)
and a Hengqin GPS fix; installs the debug APK (`-r -g --abi arm64-v8a`, keeping the app's data);
opens the app; moves the emulator window to (100, 0); and starts the host audio bridge detached
(`--mic "Microphone Array (适用于数字麦克风的英特尔® 智音技术)"`, log
`C:\Users\Administrator\tools\nova-drive-build\host_audio_bridge.log`), first muting the
emulator's own speaker (`cmd media_session volume --stream <n> --set 0` for streams 1-3, 5, 8, 9, 11;
streams 0, 4, 10 to their minimum 1) so voices are not heard twice. Options: `-NoBridge` (no mute),
`-Mic`, `-AvdName nova_api30 -Abi armeabi-v7a` (the older AVD, see the table at the end),
`-ApkPath`, `-SetupSdk` (installs platform-tools, the emulator and `-ImagePackage`),
`-BootTimeoutSeconds`. It finds the SDK through `ANDROID_SDK_ROOT`, `ANDROID_HOME`, then
`C:\Users\Administrator\Android\Sdk`; a missing AVD is created under `-AvdDir` (default
`D:\android-avd`, C: is nearly full) with nova_api30's hardware (Pixel 5, 4 GB RAM, host GPU,
6 GB data). On this PC the API 34 image lives in `D:\android-sdk-extra` behind a directory
junction at `Sdk\system-images\android-34`.

**A fresh AVD has no keys.** Enter the Baidu Bearer key, the iFlytek APPID and the Amap Web key
once in 开发者设置 on `nova_api34`. The map and navigation need none of them (the Android Amap key
is in the APK); voice, the wake word and POI search do. `DEBUG_TOOL nav_route` also accepts
`@lat,lon` (GCJ-02) to route without the Web key; that argument is redacted in the log.

This emulator is useful for UI, basic lifecycle, and ADB-driven component checks. It cannot certify
real cabin acoustics, speaker echo cancellation, microphone gating under road noise, driver
intelligibility, or physical vehicle behavior. The repository requires physical-device evidence for
audio and navigation behavior in [ACCEPTANCE_TESTS.md](../ACCEPTANCE_TESTS.md). Amap and Baidu
live-service checks also need their real credentials and network access. The Google APIs x86_64 images include [ARM native-library translation support](https://android-developers.googleblog.com/2020/03/run-arm-apps-on-android-emulator.html). Still verify that the app's native libraries load on this image before treating emulator results as evidence of ABI compatibility on a physical device.

## Voice on the x86 emulator (measured 2026-09-26, nova_api30, Windows host, WHPX)

- The APK ships only ARM native libraries, so the app runs as `arm64-v8a` under translation
  (`dumpsys package com.novadrive.app` → `primaryCpuAbi=arm64-v8a`). Debug builds therefore skip
  WebRTC AEC3 there (`TranslatedAbi`; the map skip is now API 30 arm64 only, see the last
  section); translated AEC3 took ~80% of a
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

## Talking to 小诺 on the emulator: the host audio bridge (debug builds)

Because of the chopped host-mic bridge above, live voice on the emulator goes through the PC
instead. With the emulator running and the debug app open, run from the repository root:

```powershell
python tools/speech-harness/host_audio_bridge.py
```

It runs `adb reverse tcp:7790 tcp:7790`, switches the app's bridge on (`DEBUG_TOOL tool=bridge
arg=on:7790`), starts a session, streams the PC microphone (ffmpeg dshow, 16 kHz mono; the USB
headset first unless it reads digital silence, else the next device; `--mic "<name>"`, `--list`)
and plays every reply slice with ffplay at the reply rate (Flex: 24 kHz). It prints what was heard
(`transcript=你:`), tool calls and replies from the NovaVoice log. Enter starts a turn (the
`voice wake` path); Ctrl+C switches the bridge off.

Pacing and robustness (P37, 2026-09-28). The PC sends one 20 ms frame per clock tick
(`[uplink] fps=50.0` every 5 s; `dropped`/`stalls` count frames the app did not take), never blocks
on a full socket, and reconnects by itself when the app restarts or switches the bridge off. The app
drains the socket on its own thread into a 120 ms buffer, so a paused capture (settings screen)
never stalls the PC and never replays old audio as a burst (`host_bridge uplink_frames=
dropped_ms= max_gap_ms=`). `--from-file a.pcm,b.pcm --gap 1.2` plays clips back to back.

How it works: `HostAudioTap` (main source set, both slots null unless a debug build's
`HostAudioBridge` sets them) makes `PcmAudioCapture` read frames from the socket instead of
`AudioRecord`, so gain, gate, mute and turn handling are those of the live microphone, and makes
`PcmAudioPlayer` copy each slice it has written to the `AudioTrack` to the PC. The emulator track
keeps playing, so drain, SPEAKING→LISTENING and barge-in timing still come from its clock. A
barge-in flush does not cut audio already sent to ffplay (up to one slice queue on the PC).

Measured 2026-09-26 (`--from-file tools/speech-harness/speech/temp24.pcm`):
`transcript=你: 调到二十四度。` → `tool=control_climate … 24°C` →
`transcript=小诺: 空调温度已设为24度…` and `[reply audio] bytes=207840 rate=24000 seconds=4.33`.

**Wake word works on the bridge.** The iFlytek MSC engine loads and runs under ARM translation
(`MscSpeechLog onVolumeChanged` while armed); it was simply disabled in settings
(`wake status` → `enabled=false`). Its idle capture is also a `PcmAudioCapture`, so it hears the
bridged audio: with the session asleep, `--no-start --from-file …/wake_xiaoxiao.pcm` gave
`wake_detection` 2.1 s after `host_bridge on` (1 s lead silence + the clip) and
`listening SLEEP->ACTIVE reason=wake_word`. Enable it with `tool=wake arg=on`.

## Navigation on the emulator: text panel and guidance on the PC (2026-09-27)

Where the map cannot be drawn (API 30 + arm64; see the last section), `AmapNaviViewHost` puts a plain-View
`TextNavigationPanel` where the map would be. It renders `TextNavigationPanelModel` from the
controller's existing flows (`AssistantNavigationScreen` forwards phase, destination and route
candidates) and the SDK listener's `NaviInfo` progress: idle, searching, destination list, route
preview (index, km, minutes, label), navigating (manoeuvre arrow + name, distance to it, next road,
remaining km and time, speed limit if known, last guidance sentence), arrived / stopped. A real
phone never constructs it. The choice overlay still sits on top for taps.

Amap's own spoken guidance (`setUseInnerVoice`) plays only on the emulator speaker. With the host
bridge on, `AmapGuidanceVoice.onPlayStart` also hands the guidance text to
`HostAudioTap.guidanceSink`; the debug `HostAudioBridge` sends it down the same socket as a tagged
frame (`[u32 0][u32 len][UTF-8]`, rate 0 = text), and `host_audio_bridge.py` speaks it with
edge-tts (`zh-CN-XiaoxiaoNeural`, online, TLS through `truststore` because the PC's antivirus
scans HTTPS; SAPI fallback, which has no Chinese voice on this PC). The PC prints only
`[guidance] chars=N`. The mic gate (`nav_guidance_mic_gate`) is unchanged: it still follows the
SDK's play start/end.

Measured 2026-09-27 (S5 + S5b voice, then a tap on route 1): panel showed the destination list,
the three-route preview and `导航中 → …  ← 左转 343 m 进入 …  剩余 8.6 km · 33 分钟` with the guidance
sentence; the bridge printed `[guidance] chars=31`. Screenshots: android_doc
`emulator_nav_panel_2026-09-27/`. **Open:** the emulator-mode vehicle did not advance after the
first manoeuvre (no further `nav_maneuver` / `onNaviInfoUpdate` for 6 minutes), so later
manoeuvres, arrival and repeated guidance on the PC are not yet observed on x86.

## Map picture and a moving car on the emulator (2026-09-28)

**Stall root cause.** The voice/controller path always starts `NaviType.GPS`
(`EmbeddedNavigationController` → `startNavigation(emulator = false)`); the owner's drive logged
`nav_start accepted=true mode=1`. On x86 nothing moves the GPS position, so the SDK produced one
`NaviInfo` and then only traffic updates. A `DEBUG_TOOL nav_start emulator:120` run on the same
build (mode=2) advanced normally. Fix, in the owner (`AmapNaviViewHost.startNavigation`): when the
map cannot be drawn (`TranslatedAbi`), navigation always runs as `NaviType.EMULATOR` at the
emulator speed (default 50 km/h). A real phone is unchanged.

**Map picture.** `TextNavigationPanel` now shows the Amap Web Service static map
(`/v3/staticmap`) next to the text (below it in portrait), using the Web key saved in settings.
`StaticMapModel` (pure, unit-tested) builds the request: car marker `C`, destination `D`,
numbered destination candidates, route polylines (all routes in preview, the active one while
navigating, Douglas–Peucker to ≤80 points each, the stretch 300 m behind / 3 km ahead of the car
at zoom 15 when navigating). Refresh on any layout change at once, on car movement only after 4 s
and 40 m (200 m when idle). `StaticMapFetcher` keeps one request in flight plus the newest pending
one and logs only `static_map ok bytes=N` / `static_map error=…`; the URL (key, coordinates) is
never logged. Without a key the text shows a one-line hint. Route shapes come from
`AmapRouteGeometry` (in `AmapRouteLiveInfo.kt`).

Measured: voice S5 → candidates, S5b → 3-route preview, tap route 1 → `nav_start mode=2`, panel
remaining 9.2 km → 8.3 km a minute later with the map following. Screenshots in android_doc
`emulator_static_map_2026-09-28/`. **Open: arrival not reached on x86.** Three of three drives died
with SIGILL in a translated native thread (ndk_translation interpreter, unnamed `Thread-N`) 1–2 s
after a manoeuvre change: at 8.2 km remaining after ~1 min, at 4.5 km after 7 min (six manoeuvres
passed), and at 1.0 km (debug `nav_start emulator:120`, 8.6 → 1.0 km in 4 min). Disabling camera
updates (`setCameraInfoUpdateEnabled(false)`) did not help and was reverted. It is Amap ARM code
under translation; the owner's app restarts when it happens. Not seen on the ARM phone.

## The real Amap map on the emulator (2026-09-28)

The SIGILL above is the **API 30** translator's arm64 interpreter (`ro.ndk_translation.version`
0.2.2): the tombstone ends in `Decoder<…Interpreter>::DecodeSimdScalarTwoRegMisc()` ←
`DecodeDataProcessingSimdAndFp`, an AdvSIMD scalar instruction it does not implement, reached by
Amap's GL thread at once and by its navigation engine within minutes. Measured one emulator at a
time, simulated drives at 120 km/h from Hengqin (`nav_route`, `nav_start emulator:120`):

| Configuration | Real map (AMapNaviView) | Drives to arrival | Crash |
| --- | --- | --- | --- |
| nova_api30 (API 30, translator 0.2.2), arm64 install | no: SIGILL in the GLThread at map start | 0/3 (text-panel mode) | SIGILL `DecodeSimdScalarTwoRegMisc`, 1–7 min into each drive |
| nova_api30, `install --abi armeabi-v7a` | **yes** (tiles, car-up 3D view, lanes, cameras) | 1/1: 17.9 km, 19 manoeuvres, 70 guidance sentences, `nav_stopped reached=true reason=emulator_end` | none in navigation; **the iFlytek wake engine crashes** (SIGSEGV in translated code on the `iflytek-wake-init` thread, 2/2 as soon as the wake word is enabled) |
| **nova_api34** (API 34 image r14, translator 0.2.3), arm64 install (the image translates arm64 only) | **yes** | **3/3**: 8.7 km (4.5 min), 25.8 km (13.4 min, 22+ manoeuvres; the host bridge spoke 35 guidance sentences on the PC) and a third on the final build (see below) | none |

So `TranslatedAbi.amapNativeSafe` is false only for a 64-bit translated process below API 34, and
only there does `AmapNaviViewHost` put up the text panel and static map picture (kept for that
case: `nova_api30` with an arm64 install). Everything else on an emulator draws the real
`AMapNaviView`. Navigation on any emulator (`TranslatedAbi.active`) still runs as
`NaviType.EMULATOR`, because no real fix moves the car. The debug-build AEC skip and uplink-gate
switch still key off `TranslatedAbi.active`: they answer the host-microphone path, not the map.

**Not yet measured on nova_api34:** Baidu voice, the wake word and POI search, because a fresh
AVD has no keys and agents do not enter credentials. After the owner enters them once, check
`wake status`, a wake from `host_audio_bridge.py --no-start --from-file
tools/speech-harness/speech/wake_xiaoxiao.pcm`, and a voice drive (S5/S5b). If the iFlytek engine
fails there too, the fallback is nova_api30 with the armeabi-v7a install and the wake word off.
Screenshots: android_doc `emulator_real_map_2026-09-28/`.

**Demo and owner checklist** (android_doc `emulator_demo_2026-09-28/`, `DEMO_README.md`): recorded
on nova_api30 + armeabi-v7a (the AVD with keys), voice through `host_audio_bridge.py --no-start
--from-file` after `DEBUG_TOOL voice wake`: 导航去珠海站 → 5 candidates → 第二个 → 3 routes →
选最快的那条 → real 3D navigation (turn card, lanes, cameras, overspeed) → arrival; 调到二十四度 →
`control_climate … 24°C`; 播放音乐 → music plays but the reply says 没成功 (`control_music`
`MALFORMED_JSON` after `screen_action=voice_music_play ok=true`, an app defect fixed as P34 in
OPEN_PROBLEMS, re-test pending); 闭嘴 → quiet (the `error` event after it: P35).
`DEBUG_TOOL nav_speed <kmh>` changes the simulated car's speed mid-drive (a voice-started
emulator drive runs at 50 km/h). `voice say:` could not read clips pushed by a rooted adbd on
this AVD (`missing <clip>.pcm`: root-owned files are invisible to the app through FUSE), hence
the bridge.
