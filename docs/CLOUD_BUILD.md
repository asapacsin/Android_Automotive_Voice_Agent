# Cloud builds (Claude Code on the web)

A Linux cloud container can build and test this repository. It cannot run the app usefully and
it can never produce acoustic evidence ([ACCEPTANCE_TESTS.md](../ACCEPTANCE_TESTS.md)).

## What happens at session start

`.claude/hooks/session-start.sh` runs [`scripts/cloud_setup.sh`](../scripts/cloud_setup.sh) in
cloud sessions only (`CLAUDE_CODE_REMOTE=true`); local Windows sessions are untouched. The script
is idempotent and never prints a credential. It installs:

| What | Where |
| --- | --- |
| JDK 17 | `/usr/lib/jvm/java-17-openjdk-amd64` (the version `./gradlew` requires) |
| Gradle 8.11.1 | `~/tools/gradle-8.11.1` (where `./gradlew` looks) |
| Android platform 34, build-tools 34.0.0, NDK 27.0.12077973, CMake 3.22.1 | `/opt/android-sdk` |
| WebRTC AEC3 sources, pinned ([THIRD_PARTY_NATIVE.md](THIRD_PARTY_NATIVE.md)) | `app/src/main/cpp/third_party/webrtc-aec3` (as `scripts/fetch_webrtc_aec3.ps1`) |
| `local.properties` | `sdk.dir`, plus `AMAP_API_KEY` when the variable is set |

Measured 2026-09-24: 56 s on a fresh SDK directory; seconds when everything is present.

## What git does not carry

The files in [README "Local binaries"](../README.md) are gitignored on purpose: the iFlytek SDK is
licensed to the owner's App ID and the repository is public. The script takes them from the
cloud environment's variables (environment menu in the session title bar → Edit). Each missing
one only disables what needs it.

| Variable | Effect | Without it |
| --- | --- | --- |
| `NOVA_VENDOR_ZIP_URL` | Zip laid out repo-relative — `app/libs/Msc.jar`, `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/{libmsc,libw_ivw}.so`, `app/src/main/assets/ivw/wakeword.jet`, and `app/src/main/res/raw/bach_air_usaf.mp3` | `:app` does not compile; the JVM modules still build and test |
| `NOVA_VENDOR_ZIP_AUTH` | Value of an `Authorization` header for that URL | Unauthenticated download |
| `NOVA_DEBUG_KEYSTORE_B64` | Base64 of the owner's `debug.keystore` | A fresh keystore per container, whose SHA1 the Amap key does not know |
| `AMAP_API_KEY` | Manifest key for the Android Amap key | The map does not authorise |

Include the mp3 in the zip: its public-domain source (Wikimedia) rate-limits shared cloud
addresses, and the script's fallback download failed with HTTP 429 on 2026-09-24.

The zip URL must serve the zip itself. A private link that answers with a sign-in page is
detected (`unzip -t`) and skipped with a warning. Anything reachable without authentication is
readable by anyone holding the URL; prefer an authenticated URL with `NOVA_VENDOR_ZIP_AUTH`.

The Amap key checks the APK signature: a cloud APK authorises only when it is signed with the
keystore whose SHA1 is registered on the key, which is why the owner's debug keystore is used.

## Build and test

```bash
./gradlew test --rerun-tasks :app:assembleDebug   # outputs under ~/nova-drive-build
```

Read test totals from the JUnit XML under `~/nova-drive-build/*/test-results`. The debug APK is
about 220 MB, almost all of it the Amap navigation SDK's native libraries.

## Emulator: not usable in this container (measured 2026-09-24)

- No `/dev/kvm` (the CPU exposes no vmx/svm), so the emulator runs in software (`-accel off`).
- An API 34 x86_64 image booted headless in about 9.5 minutes; `am start` of Settings then took
  26 s and the display stayed on the boot fallback screen.
- The plain x86_64 image has no ARM translation (`ro.product.cpu.abilist=x86_64`), and the app
  ships only `arm64-v8a`/`armeabi-v7a` native libraries, so the APK cannot install there.

A KVM-capable host (the Windows PC, see [EMULATOR_TESTING.md](EMULATOR_TESTING.md)) with a Google
APIs image that includes ARM translation is the minimum for emulator runs. Audio, echo
cancellation and wake-word behaviour still require the physical device.
