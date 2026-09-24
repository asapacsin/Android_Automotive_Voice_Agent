# Third-party native code

## WebRTC AEC3

| | |
| --- | --- |
| Source | <https://github.com/Enaium/webrtc-aec3> — a standalone extraction of WebRTC's Acoustic Echo Canceller 3 |
| Pinned revision | `2cec2f52e26646f93bd2d5498bbabf59cba18da9` (2026-08-08, "Disable absl_nonnull annotation on all platforms") |
| Upstream | WebRTC `modules/audio_processing/aec3` and its dependencies; the extraction does not name the upstream WebRTC revision it was cut from |
| Location | `app/src/main/cpp/third_party/webrtc-aec3/` (gitignored; fetched, never committed) |
| Fetched by | `scripts/fetch_webrtc_aec3.ps1` (Windows) and `scripts/cloud_setup.sh` (cloud); both pin the revision above |
| Built by | `app/src/main/cpp/CMakeLists.txt` into `libwebrtc_aec3.a`, linked into `libnova_aec.so` for `arm64-v8a` and `armeabi-v7a` |
| Not built | `*_avx2.cc`, `*_sse2.cc`, `*_sse.cc`, `cpu_features_linux.c`, `cpu_features_android.c`; the extraction's own `CMakeLists.txt`, `tests/`, `pixi.*` |

### Licences

| Component in the tree | Licence |
| --- | --- |
| WebRTC code (`src/api`, `src/audio_processing`, `src/common_audio`, `src/base/rtc_base`, `src/base/system_wrappers`) | BSD 3-Clause, "Copyright (c) 2011, The WebRTC project authors" — the extraction's `LICENSE` |
| Abseil (`src/absl`) | Apache License 2.0 (Abseil authors) |
| JsonCpp (`src/base/jsoncpp`) | MIT (or public domain, at the user's option) |

WebRTC is also covered by its upstream `PATENTS` grant, which the extraction does not carry.

These licences require the copyright notices to be reproduced when the binary is distributed.
The APK does not yet ship a notices screen or file: nothing is distributed today ("Not
published", README), but a release build needs one before it leaves the owner's devices.

### Why pinned

Until 2026-09-24 the fetch cloned the extraction's latest commit, so two checkouts a week apart
could build different echo-cancellation code with nothing recording which. The pin is the
revision every automated result since then was built from (cloud build 2026-09-24: 1618 tests,
0 failures; `libnova_aec.so` for both ABIs). Moving it is a deliberate change: update both
scripts and this table in one commit, and re-run the echo device rows
(`ASTRA-ECHO-PLAYBACK-001`, `ASTRA-DOUBLE-TALK-001`).

A checkout made earlier (for example on the Windows build machine) may be at a different
revision; `scripts/fetch_webrtc_aec3.ps1` now reports that and moves it to the pin.
