#!/usr/bin/env bash
# Pre-install the API 34 emulator system image with retries (runs 39452886908, 39461059725 died on
# "Error on ZipFile unknown archive" inside android-emulator-runner's own sdkmanager call).
set -u
PKG='system-images;android-34;google_apis;x86_64'
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
DIR="$SDK/system-images/android-34/google_apis/x86_64"
SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"

verified() { [ -f "$DIR/system.img" ] || [ -f "$DIR/package.xml" ]; }

if verified; then echo "system image already present"; exit 0; fi
for attempt in 1 2 3 4; do
  echo "sdkmanager install attempt $attempt/4"
  if yes | "$SDKM" --install "$PKG" --channel=0 && verified; then
    echo "system image installed"; exit 0
  fi
  rm -rf "$DIR" "$SDK"/.temp/* "${TMPDIR:-/tmp}"/PackageOperation* 2>/dev/null
  sleep $((attempt * 15))
done
echo "::error title=system image download failed::$PKG could not be installed after 4 attempts"
exit 1
