# Decisions — Checkpoint 1

## D1. JVM-first core, optional Android app module

Core logic is Kotlin/JVM so `gradle test` does not need an emulator. `app` uses the Android Gradle Plugin but is `include`d only when `platforms/android-34` is installed. Reason: this machine's SDK started as cmdline-tools 22.0 only; missing platforms must not block JVM verification.

## D2. Pinned toolchain from inspection, not latest-by-default

| Tool | Pin | Why |
| --- | --- | --- |
| JDK | Temurin 17.0.20.1+1 | Only complete JDK on the machine |
| Gradle | 8.11.1 | Local zip already downloaded |
| Kotlin | 2.0.21 | Compatible with Gradle 8.11 |
| AGP | 8.7.3 | Compatible with Gradle 8.9+ / JDK 17 |
| compileSdk | 34 | Matches planned `sdkmanager "platforms;android-34"` |

`org.gradle.java.home` points at the portable JDK so Gradle does not require a global `PATH` entry.

## D3. Structured commands only

No NL parser. Replay S2S (`ReplaySpeechToSpeechPort`) forwards already-typed `StructuredCommand` values. This keeps Checkpoint 1 honest: later NLU cannot skip the contract.

## D4. Policy is a pure gate in front of an internal router

`SkillRouter` is `internal`. Public API is `submit` / `confirm` / `cancel` / `interruptWith`. That is how "policy cannot be bypassed" is made visible without pretending adapters are unreachable from tests.

## D5. Success requires independent observation

`AdapterOutcome.Applied` is not success. `ObservedStateVerifier` reads `VehiclePort.observe()` after execute. The simulator can apply internally and leave the published snapshot stale (`desyncNextObservation`) so verification failure is testable.

## D6. Coordinate systems are data, conversions are adapter-only

Core never implements WGS84 ↔ GCJ-02. `PROVIDER_DEFINED` requires an id string so OEM frames do not look like WGS-84.

## D7. China-first defaults live in `ChinaFirstDefaults`

`zh-CN`, 小诺, 你好小诺, metric, preferred GCJ-02. Navigation provider kinds are Fake / AMap / Baidu / OEM. No GMS.

## D8. Gradle wrapper script uses the local distribution zip

A binary `gradle-wrapper.jar` is not vendored (it is not on disk as a generated wrapper). `gradlew.bat` extracts `gradle-8.11.1-bin.zip` and invokes that Gradle. Documented so CI/humans do not assume a downloaded wrapper jar.

## D9. Confirm re-evaluates policy

`confirm()` re-runs `SafetyPolicy` against a fresh snapshot. If the world became `DENY` (privacy calls blocked), approval does not execute.

## D10. Do not expand domain surface

No windows, sunroof, charging, parking, cameras, seats, or scenes in contracts.

## D11. ASCII Gradle build directory on this Windows machine

The workspace lives under `D:\桌面\...`. Gradle Test Executor launches with an `@argfile` classpath. Java 17 reads that file using the OEM code page (GBK) before `-Dfile.encoding=UTF-8` applies, so UTF-8 `桌面` entries become unloadable and tests fail with `ClassNotFoundException`. Compiled outputs go to `C:\Users\Administrator\tools\nova-drive-build\` so worker classpaths stay ASCII. Source remains in the workspace. `android.overridePathCheck=true` stays required because AGP still sees the source path. Checkpoint 2 also sets `kotlin.incremental=false` and `kotlin.compiler.execution.strategy=in-process` because a corrupted Kotlin incremental cache fell back to a CLI kotlinc that received `\u684C\u9762` escaped source paths.

## D12. Baidu E2E credentials and protocol stay behind the backend

The official Baidu realtime endpoint is `wss://aip.baidubce.com/ws/2.0/speech/v1/realtime` with `model` plus `access_token` from OAuth `client_credentials` ([docs](https://cloud.baidu.com/doc/SPEECH/s/nmcytnwei), [auth](https://cloud.baidu.com/doc/SPEECH/s/cm8sn2bii)). Android never receives AK/SK/token. Custom Function Calling is not documented on this exact API; interruption is `turn_detection.interrupt_response` (currently only `true`) via server VAD.

## D13. Qwen Flash default; GPT-Live, Baidu, and Fake optional

Default realtime provider is Qwen Flash (`qwen-audio-3.0-realtime-flash`). Qwen Plus (`qwen-audio-3.0-realtime-plus`) is selectable. GPT-Live (`gpt-live-1`) is an optional adapter. Baidu is an optional compatibility adapter; when Baidu is explicitly selected, Lite Near (`audio-mini-realtime-near`) is that provider family's default. Fake is the quota-free local test provider and is never the product default. Missing GPT-Live or Baidu credentials must never be required for Qwen/Fake startup. Vendor JSON is translated on the backend; the Kotlin `VoiceSessionController` is provider-independent and JVM-tested. Official Baidu protocol (when that adapter is selected): https://cloud.baidu.com/doc/SPEECH/s/nmcytnwei (updated 2026-09-04, retrieved 2026-09-14). Baidu interruption is server VAD `interrupt_response=true`; there is no documented client cancel. Custom Function Calling remains `BLOCKED_BAIDU_FUNCTION_CALLING` because Session example `tools`/`tool_choice` fields are not present on UpdateSession or client events.
