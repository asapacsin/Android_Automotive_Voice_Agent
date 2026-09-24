#!/usr/bin/env bash
# Prepares a Linux cloud container (Claude Code on the web) to build and test Nova Drive.
#
# Idempotent: every step checks before it downloads. Never prints a credential.
#
# Always installed: JDK 17, Gradle 8.11.1 (~/tools, where ./gradlew looks), Android SDK platform 34,
# build-tools 34.0.0, NDK 27.0.12077973, CMake 3.22.1 (/opt/android-sdk), and the WebRTC AEC3
# sources (the Linux equivalent of scripts/fetch_webrtc_aec3.ps1).
#
# Optional, from the environment's variables — each one missing only disables what needs it:
#   NOVA_VENDOR_ZIP_URL      zip laid out repo-relative (app/libs/Msc.jar,
#                            app/src/main/jniLibs/<abi>/*.so, app/src/main/assets/ivw/wakeword.jet,
#                            optionally app/src/main/res/raw/bach_air_usaf.mp3). Without it :app
#                            does not compile; the JVM modules still build and test.
#   NOVA_VENDOR_ZIP_AUTH     optional value for an Authorization header when fetching that zip.
#   NOVA_DEBUG_KEYSTORE_B64  base64 of the owner's debug.keystore, so a cloud APK has the SHA1
#                            the Amap key is bound to. Without it Android generates a fresh one.
#   AMAP_API_KEY             written into local.properties (gitignored) for the manifest.
#
# The bundled music is public domain (docs/THIRD_PARTY_AUDIO.md); when the vendor zip does not
# carry it, it is fetched from Wikimedia, which rate-limits shared addresses — a failure is
# reported, not fatal.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
JDK=/usr/lib/jvm/java-17-openjdk-amd64
GRADLE_DIR="$HOME/tools/gradle-8.11.1"
CMDLINE_TOOLS_ZIP=commandlinetools-linux-11076708_latest.zip
SDK_PACKAGES=("platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;27.0.12077973" "cmake;3.22.1")
MUSIC=app/src/main/res/raw/bach_air_usaf.mp3
MUSIC_URL="https://upload.wikimedia.org/wikipedia/commons/e/ec/Air_-_Air_Force_Strings_-_United_States_Air_Force_Band.mp3"

log() { echo "[cloud_setup] $*" >&2; }
warn() { echo "[cloud_setup] WARNING: $*" >&2; }

retry() {
  local attempt delay=2
  for attempt in 1 2 3 4; do
    if "$@"; then return 0; fi
    [ "$attempt" -lt 4 ] && sleep "$delay" && delay=$((delay * 2))
  done
  return 1
}

install_jdk() {
  if [ -x "$JDK/bin/java" ]; then return; fi
  log "installing JDK 17"
  export DEBIAN_FRONTEND=noninteractive
  apt-get install -y -q openjdk-17-jdk-headless >/dev/null 2>&1 ||
    { apt-get update -q >/dev/null 2>&1 && apt-get install -y -q openjdk-17-jdk-headless >/dev/null; }
}

install_gradle() {
  if [ -x "$GRADLE_DIR/bin/gradle" ]; then return; fi
  log "installing Gradle 8.11.1"
  mkdir -p "$HOME/tools"
  local zip
  zip="$(mktemp --suffix=.zip)"
  retry curl -sSfL -o "$zip" https://services.gradle.org/distributions/gradle-8.11.1-bin.zip
  unzip -q -o "$zip" -d "$HOME/tools"
  rm -f "$zip"
}

install_sdk() {
  local sdkmanager="$SDK/cmdline-tools/latest/bin/sdkmanager"
  if [ ! -x "$sdkmanager" ]; then
    log "installing Android command-line tools"
    mkdir -p "$SDK/cmdline-tools"
    local zip
    zip="$(mktemp --suffix=.zip)"
    retry curl -sSfL -o "$zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
    rm -rf "$SDK/cmdline-tools/latest" "$SDK/cmdline-tools/cmdline-tools"
    unzip -q -o "$zip" -d "$SDK/cmdline-tools"
    mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -f "$zip"
  fi
  local missing=0 pkg
  for pkg in "${SDK_PACKAGES[@]}"; do
    [ -e "$SDK/${pkg//;//}" ] || missing=1
  done
  if [ "$missing" -eq 1 ]; then
    log "installing SDK packages: ${SDK_PACKAGES[*]}"
    yes | JAVA_HOME="$JDK" "$sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
    JAVA_HOME="$JDK" "$sdkmanager" --sdk_root="$SDK" "${SDK_PACKAGES[@]}" >/dev/null
  fi
}

fetch_webrtc() {
  local dest="$ROOT/app/src/main/cpp/third_party/webrtc-aec3"
  if [ -d "$dest/src" ]; then return; fi
  log "cloning WebRTC AEC3 sources"
  rm -rf "$dest"
  retry git clone -q --depth 1 https://github.com/Enaium/webrtc-aec3.git "$dest"
  # CMake globs these sources at configure time; a configuration cached without them links nothing.
  rm -rf "$ROOT/app/.cxx"
}

fetch_vendor() {
  if [ -f "$ROOT/app/libs/Msc.jar" ]; then return; fi
  if [ -z "${NOVA_VENDOR_ZIP_URL:-}" ]; then
    warn "NOVA_VENDOR_ZIP_URL not set: iFlytek files absent, :app will not compile"
    return
  fi
  log "fetching vendor files"
  local zip
  zip="$(mktemp --suffix=.zip)"
  local auth=()
  [ -n "${NOVA_VENDOR_ZIP_AUTH:-}" ] && auth=(-H "Authorization: $NOVA_VENDOR_ZIP_AUTH")
  if ! retry curl -sSfL "${auth[@]}" -o "$zip" "$NOVA_VENDOR_ZIP_URL"; then
    warn "vendor zip download failed"
    rm -f "$zip"
    return
  fi
  if ! unzip -tq "$zip" >/dev/null 2>&1; then
    warn "vendor download is not a zip (a sign-in page?): not extracted"
    rm -f "$zip"
    return
  fi
  unzip -q -o "$zip" -d "$ROOT"
  rm -f "$zip"
}

fetch_music() {
  local file="$ROOT/$MUSIC"
  if [ -s "$file" ] && head -c 3 "$file" | grep -q ID3; then return; fi
  log "fetching public-domain music track"
  mkdir -p "$(dirname "$file")"
  local tmp
  tmp="$(mktemp)"
  if retry curl -sSfL -A "NovaDriveBuild/1.0 (https://github.com/asapacsin/Android_Automotive_Voice_Agent)" \
      -o "$tmp" "$MUSIC_URL" && head -c 3 "$tmp" | grep -q ID3; then
    mv "$tmp" "$file"
  else
    rm -f "$tmp"
    warn "music track unavailable (Wikimedia rate limit?): :app will not compile until $MUSIC exists"
  fi
}

install_keystore() {
  [ -n "${NOVA_DEBUG_KEYSTORE_B64:-}" ] || return 0
  mkdir -p "$HOME/.android"
  local target="$HOME/.android/debug.keystore"
  local tmp
  tmp="$(mktemp)"
  if echo "$NOVA_DEBUG_KEYSTORE_B64" | base64 -d >"$tmp" 2>/dev/null && [ -s "$tmp" ]; then
    mv "$tmp" "$target"
    log "debug keystore installed"
  else
    rm -f "$tmp"
    warn "NOVA_DEBUG_KEYSTORE_B64 is not valid base64: keystore not installed"
  fi
}

write_local_properties() {
  local props="$ROOT/local.properties"
  {
    echo "sdk.dir=$SDK"
    [ -n "${AMAP_API_KEY:-}" ] && echo "AMAP_API_KEY=$AMAP_API_KEY"
  } >"$props"
  [ -n "${AMAP_API_KEY:-}" ] || warn "AMAP_API_KEY not set: the map will not authorise in a cloud APK"
}

install_jdk
install_gradle
install_sdk
fetch_webrtc
fetch_vendor
fetch_music
install_keystore
write_local_properties

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export JAVA_HOME=$JDK"
    echo "export ANDROID_HOME=$SDK"
  } >>"$CLAUDE_ENV_FILE"
fi
log "done: ./gradlew test :app:assembleDebug (JAVA_HOME=$JDK ANDROID_HOME=$SDK)"
