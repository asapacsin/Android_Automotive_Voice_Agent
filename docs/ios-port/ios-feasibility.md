# iOS feasibility

**No external documentation was fetched and no iOS build was run while writing this.** Every
conclusion below is PROPOSED or UNKNOWN; none is VERIFIED. Each UNKNOWN has an open question
(open-questions.md) that must be answered from Apple / Amap / Google / iFlytek documentation or a
device spike before the dependent milestone.

## Standalone iPhone app (required)

| Subsystem | Proposed iOS technology | Limitations / risks | Label |
|---|---|---|---|
| UI | Swift + SwiftUI; map view hosted via `UIViewRepresentable` | Amap view is UIKit | PROPOSED |
| Audio session | `AVAudioSession` `.playAndRecord`, mode `.voiceChat`, options `.defaultToSpeaker`, `.allowBluetooth`, `.duckOthers` | Mode choice affects AEC and Bluetooth routing; car Bluetooth HFP lowers quality | PROPOSED |
| Capture + AEC | `AVAudioEngine` input node with `setVoiceProcessingEnabled(true)`; `AVAudioConverter` to 16 kHz | Apple voice processing quality vs WebRTC AEC3 unknown for loud speaker playback | UNKNOWN (OQ-IOS-3) |
| Playback | `AVAudioPlayerNode` on the same engine (so voice processing has the reference), 24 kHz buffers, epoch flush via `stop()`/reschedule | Must keep render reference in the same engine | PROPOSED |
| Interruptions | `AVAudioSession.interruptionNotification`, `routeChangeNotification` → arbiter R4/R5 | Phone calls and Siri interrupt the session | PROPOSED |
| Background | `UIBackgroundModes` `audio` with an active session keeps capture alive in background | App Review may reject continuous background listening without a clear audio feature; mic indicator always shown | UNKNOWN (OQ-IOS-4) |
| Wake word | iFlytek iOS SDK if available; else Picovoice Porcupine custom keyword | Background always-on wake is restricted; plan foreground wake + button | UNKNOWN (OQ-IOS-1) |
| ASR | None needed (Gemini transcription) | `SFSpeechRecognizer` only as an optional fallback | PROPOSED |
| Realtime (Gemini Live) | `URLSessionWebSocketTask` | Behaviour of long-lived sockets when backgrounded; reconnect on resume | PROPOSED |
| TTS | Azure Speech REST (same as Android); not `AVSpeechSynthesizer` (voice parity) | — | PROPOSED |
| Navigation | Amap iOS Navi SDK (`AMapNaviKit`) + Amap Web REST | Need: multi-route calc, route select, guidance text callback, mute built-in TTS, camera/limit info, simulated drive | UNKNOWN (OQ-IOS-2) |
| Location | `CLLocationManager` (when-in-use; always only if background nav) + WGS-84→GCJ-02 | Amap SDK may provide conversion | PROPOSED |
| Vehicle controls | Simulated in-app (as Android) | — | PROPOSED |
| Music | Bundled `AVAudioPlayer`; Apple Music via MusicKit (entitlement, subscription); third-party apps not controllable | Parity for 放周杰伦的歌 only with Apple Music | UNKNOWN (OQ-IOS-7) |
| Phone | `CNContactStore` + `tel://` (system confirm sheet) | Extra system confirmation is added on top of the voice confirmation | PROPOSED |
| Vision | `AVCaptureSession` still frame → Qianfan HTTP | Camera permission string | PROPOSED |
| Secrets | Keychain; settings in UserDefaults | — | PROPOSED |
| Permissions | `NSMicrophoneUsageDescription`, `NSLocationWhenInUseUsageDescription` (+ Always if needed), `NSCameraUsageDescription`, `NSContactsUsageDescription` | Strings in Chinese and English | PROPOSED |

## Optional CarPlay integration

- A CarPlay **navigation** app needs the CarPlay navigation entitlement from Apple and uses the
  CarPlay framework templates (`CPMapTemplate`, etc.). Whether Amap's iOS SDK can render into a
  CarPlay map window is UNKNOWN (OQ-IOS-6).
- CarPlay gives **no** access to climate, seats or windows (PROPOSED; verify OQ-IOS-6). Vehicle
  controls stay simulated.
- Voice in CarPlay: the car's microphone is routed over the CarPlay audio link; behaviour of
  `.voiceChat` processing there is UNKNOWN.
- Treat CarPlay as a later milestone (M7), not part of parity.

## Android-only capabilities that do NOT transfer

| Android capability | Used for | iOS position |
|---|---|---|
| Foreground service (microphone) | Keep session alive in background | No equivalent; background audio mode only (OQ-IOS-4) |
| Media-session control of other apps | play_music handoff + now-playing verification | Not possible for third-party apps |
| `ACTION_PLAY_FROM_SEARCH` intents | Music handoff | Not available |
| iFlytek MSC Android jar | Wake word | iOS SDK separate (OQ-IOS-1) |
| `AudioRecord` VOICE_COMMUNICATION + WebRTC AEC3 JNI | AEC | Voice processing unit or port AEC3 in C++ |
| Debug broadcast receiver | CI control | Replace with XCUITest launch arguments / a debug URL scheme |
