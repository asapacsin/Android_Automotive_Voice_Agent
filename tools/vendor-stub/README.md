# Stand-in for the iFlytek MSC jar

`app/libs/Msc.jar` comes from the owner's vendor zip ([docs/CLOUD_BUILD.md](../../docs/CLOUD_BUILD.md)).
Without it Gradle cannot even resolve `:app`'s classpath, so nothing in `:app` compiles or tests.

`src/` holds compile-only Java stand-ins for the eight `com.iflytek.cloud` classes that
`IflytekWakeWordDetector` uses, with the same signatures. They do nothing: `createUtility` and
`createWakeuper` return null, which the detector already treats as "no engine".

`scripts/make_msc_stub.sh [<root>]` compiles them into `app/libs/Msc.jar` and writes the marker
`app/libs/Msc.jar.STUB` (both gitignored). It never replaces a real jar. `scripts/cloud_setup.sh`
and `scripts/new_worktree.sh` call it when no real jar arrived.

**What a stub build is good for:** compiling `:app`, its JVM unit tests and the architecture rules.
**What it is not:** a product build. The wake word cannot work, the native `libmsc.so` /
`libw_ivw.so` and `assets/ivw/wakeword.jet` are absent, and
`FeaturePresenceRegressionTest.wakeWordEngineArtifactsArePackaged` fails on it on purpose. No
device or wake-word evidence may be taken from a stub APK. To tell which you have, look for the
marker file.

Added 2026-10-19 when the vendor zip URL (an expired filebin.net link, HTTP 403) blocked every
`:app` build in cloud sessions and in GitHub Actions (TEST_MATRIX `CI-VENDOR-ZIP-001`).

**Research (2026-10-19, done after ae308d1, whose commit message claimed it before it was run):**
a web search for an iFlytek MSC artifact on Maven Central (`com.iflytek`, `Msc.jar`) found none.
The Android MSC SDK is downloaded per App ID from the iFlytek open-platform console and used as a
local `libs/` jar, so there is no public substitute to fetch. Compile-only stand-ins were chosen
for that reason. Searched, no vendor-documented workaround found.
