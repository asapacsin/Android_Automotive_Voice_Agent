#!/usr/bin/env bash
# Prepares a Linux cloud container (Claude Code on the web) to build and test Nova Drive.
#
# Idempotent: every step checks before it downloads. Never prints a credential.
#
# Always installed: JDK 17, Gradle 8.11.1 (~/tools, where ./gradlew looks), Android SDK platform 34,
# build-tools 34.0.0, NDK 27.0.12077973, CMake 3.22.1 (/opt/android-sdk), and the WebRTC AEC3
# sources at the pinned revision (the Linux equivalent of scripts/fetch_webrtc_aec3.ps1).
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
# docs/THIRD_PARTY_NATIVE.md; scripts/fetch_webrtc_aec3.ps1 pins the same revision.
WEBRTC_AEC3_PIN=2cec2f52e26646f93bd2d5498bbabf59cba18da9
MUSIC=app/src/main/res/raw/bach_air_usaf.mp3
MUSIC_URL="https://upload.wikimedia.org/wikipedia/commons/e/ec/Air_-_Air_Force_Strings_-_United_States_Air_Force_Band.mp3"
# Wikimedia answers shared cloud addresses with HTTP 429 (2026-09-24, 2026-10-17). Fallback: another
# public-domain recording of the same piece on archive.org (item Bach-airOnTheGString, licence
# creativecommons.org/licenses/publicdomain). Nothing in the app names the performer.
MUSIC_URL_FALLBACK="https://archive.org/download/Bach-airOnTheGString/LaMusicaClasicaMasRelajanteDelMundo-Bach-AirOnTheGString.mp3"

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
  if [ "$(git -C "$dest" rev-parse HEAD 2>/dev/null)" = "$WEBRTC_AEC3_PIN" ]; then return; fi
  log "fetching WebRTC AEC3 sources at the pinned revision"
  rm -rf "$dest"
  mkdir -p "$dest"
  git -C "$dest" init -q
  git -C "$dest" remote add origin https://github.com/Enaium/webrtc-aec3.git
  retry git -C "$dest" fetch -q --depth 1 origin "$WEBRTC_AEC3_PIN"
  git -C "$dest" checkout -q --detach FETCH_HEAD
  # CMake globs these sources at configure time; a configuration cached without them links nothing.
  rm -rf "$ROOT/app/.cxx"
}

# A stand-in jar (tools/vendor-stub) is not the SDK: with one in place the real zip is still fetched.
real_jar() {
  [ -f "$ROOT/app/libs/Msc.jar" ] && ! unzip -l "$ROOT/app/libs/Msc.jar" 2>/dev/null | grep -q 'META-INF/NOVA_VENDOR_STUB'
}
VENDOR_FAILURE="NOVA_VENDOR_ZIP_URL not set"

fetch_vendor() {
  if real_jar; then return; fi
  if [ -z "${NOVA_VENDOR_ZIP_URL:-}" ]; then
    warn "NOVA_VENDOR_ZIP_URL not set: iFlytek files absent, :app will not compile"
    return
  fi
  log "fetching vendor files"
  local zip url auth_value code host
  zip="$(mktemp --suffix=.zip)"
  url="$(vendor_url "$NOVA_VENDOR_ZIP_URL")"
  host="$(printf '%s' "$url" | sed -E 's#^[a-z]+://([^/]+).*#\1#')"
  local auth=()
  if [ -n "${NOVA_VENDOR_ZIP_AUTH:-}" ]; then
    auth_value="$(vendor_auth "$NOVA_VENDOR_ZIP_AUTH")"
    auth=(-H "Authorization: $auth_value")
  fi
  code=000
  for _ in 1 2 3 4; do
    code="$(curl -sSL "${auth[@]}" -o "$zip" -w '%{http_code}' "$url" 2>/dev/null || true)"
    [ "$code" = 200 ] && break
    sleep 2
  done
  if [ "$code" != 200 ]; then
    vendor_fail "vendor zip download failed: HTTP $code from $host (check NOVA_VENDOR_ZIP_URL / NOVA_VENDOR_ZIP_AUTH)"
    rm -f "$zip"
    return
  fi
  if ! unzip -tq "$zip" >/dev/null 2>&1; then
    vendor_fail "vendor download from $host is not a zip (a sign-in or web page?): not extracted"
    rm -f "$zip"
    return
  fi
  local tmpdir src
  tmpdir="$(mktemp -d)"
  unzip -q -o "$zip" -d "$tmpdir"
  # Accept a zip laid out repo-relative (app/...) or with one top-level folder around it.
  src="$tmpdir"
  if [ ! -d "$tmpdir/app" ]; then
    src="$(dirname "$(find "$tmpdir" -maxdepth 3 -path '*/app/libs' -type d | head -1)" 2>/dev/null)"
    src="$(dirname "$src")"
  fi
  if [ -d "$src/app" ]; then
    cp -R "$src/app/." "$ROOT/app/"
  fi
  rm -rf "$tmpdir" "$zip"
  if real_jar; then rm -f "$ROOT/app/libs/Msc.jar.STUB"; else vendor_fail "vendor zip from $host has no app/libs/Msc.jar"; fi
}

# A github.com page link (…/blob/<ref>/<path> or …/raw/<ref>/<path>) serves HTML or redirects; use
# the raw file URL instead. Other URLs are left as they are.
vendor_url() {
  printf '%s' "$1" | sed -E 's#^https://github\.com/([^/]+)/([^/]+)/(blob|raw)/(.*)$#https://raw.githubusercontent.com/\1/\2/\4#'
}

# raw.githubusercontent.com needs "token <PAT>" (or "Bearer <PAT>"); a bare token gets a 404.
vendor_auth() {
  case "$1" in
    *" "*) printf '%s' "$1" ;;
    *) printf 'token %s' "$1" ;;
  esac
}

# Visible without the job log: a GitHub annotation (code and host only, never the URL or token).
vendor_fail() {
  VENDOR_FAILURE="$1"
  warn "$1"
  [ -n "${GITHUB_ACTIONS:-}" ] && echo "::error title=vendor files::$1"
  return 0
}

# No real jar after the fetch: compile the stand-in so :app builds and its JVM tests run
# (tools/vendor-stub/README.md). In GitHub Actions only when the workflow opts in with
# NOVA_VENDOR_STUB_OK=yes (the Tier B emulator workflows; decided by the decider 2026-10-19): such a
# run records build:vendor-real BLOCKED, so it is never green. Tier A never sets it.
stub_vendor() {
  real_jar && return
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    [ "${NOVA_VENDOR_STUB_OK:-}" = yes ] || return
    echo "::warning title=STUB_VENDOR::building on the compile-only iFlytek stand-in: $VENDOR_FAILURE"
  fi
  NOVA_STUB_REASON="$VENDOR_FAILURE" "$ROOT/scripts/make_msc_stub.sh" "$ROOT" \
    || warn "stand-in Msc.jar could not be built: :app will not compile"
}

fetch_music() {
  local file="$ROOT/$MUSIC"
  if [ -s "$file" ] && head -c 3 "$file" | grep -q ID3; then return; fi
  log "fetching public-domain music track"
  mkdir -p "$(dirname "$file")"
  local tmp
  tmp="$(mktemp)"
  local url ok=no
  for url in "$MUSIC_URL" "$MUSIC_URL_FALLBACK"; do
    if retry curl -sSfL -A "NovaDriveBuild/1.0 (https://github.com/asapacsin/Android_Automotive_Voice_Agent)" \
        -o "$tmp" "$url" && head -c 3 "$tmp" | grep -q ID3; then
      ok=yes
      break
    fi
  done
  if [ "$ok" = yes ]; then
    mv "$tmp" "$file"
  else
    rm -f "$tmp"
    warn "music track unavailable (Wikimedia rate limit?): :app will not compile until $MUSIC exists"
  fi
}

install_keystore() {
  [ -n "${NOVA_DEBUG_KEYSTORE_B64:-}" ] || return 0
  # AGP reads ANDROID_USER_HOME before ~/.android (GitHub run 38719315653 signed with another key).
  local dir="${ANDROID_USER_HOME:-$HOME/.android}"
  mkdir -p "$dir"
  local target="$dir/debug.keystore"
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
stub_vendor
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
