#!/bin/bash
# Builds the debug APK on a GitHub runner (nav demo, device checks, test worker). Needs AMAP_API_KEY,
# NOVA_VENDOR_ZIP_URL, NOVA_DEBUG_KEYSTORE_B64 (and optionally NOVA_VENDOR_ZIP_AUTH) in the env.
# Prints only shapes and certificate digests, never a key. Shared so the workflows cannot drift.
set -e  # as the workflow step ran it (bash -e)
sudo apt-get update -q
sudo apt-get install -y -q openjdk-17-jdk-headless unzip
sudo mkdir -p /opt/android-sdk
sudo chown -R "$USER" /opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
# Run 38719315653 signed the APK with c5330c7c..., not the keystore installed in
# ~/.android: AGP looks in ANDROID_USER_HOME / ANDROID_PREFS_ROOT / ANDROID_SDK_HOME first.
echo "::notice title=android location env::$(env | grep -E '^ANDROID_(USER_HOME|PREFS_ROOT|SDK_HOME|EMULATOR_HOME|AVD_HOME)=' | tr '\n' ' ')"
unset ANDROID_PREFS_ROOT ANDROID_SDK_HOME
export ANDROID_USER_HOME="$HOME/.android"
bash scripts/cloud_setup.sh
sha1=$(keytool -exportcert -alias androiddebugkey -keystore "$HOME/.android/debug.keystore" -storepass android | openssl dgst -sha1 | awk '{print $2}')
md5=$(keytool -exportcert -alias androiddebugkey -keystore "$HOME/.android/debug.keystore" -storepass android | openssl dgst -md5 | awk '{print $2}')
echo "debug cert sha1=$sha1"
echo "debug cert md5=$md5"
if [ "$sha1" != "322fe8e733d503aafa6f0037df665452cbd4daeb" ]; then
  echo "The debug keystore secret is not the certificate registered on the Amap Android key."
  exit 1
fi
chmod +x ./gradlew
./gradlew :app:assembleDebug
apk="$HOME/nova-drive-build/app/outputs/apk/debug/app-debug.apk"
test -f "$apk"
# What Amap will see: the certificate the APK is really signed with, and the shape of the
# key the manifest got (length and character class only - never the key).
signer="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)apksigner"
apk_sha1="$("$signer" verify --print-certs "$apk" | sed -n 's/.*certificate SHA-1 digest: //p' | head -1)"
pkg="$("$(dirname "$signer")/aapt2" dump packagename "$apk" 2>/dev/null || echo unknown)"
key="$(sed -n 's/^AMAP_API_KEY=//p' local.properties | tr -d '[:space:]')"
shape="length=${#key}"
if printf '%s' "$key" | grep -Eq '^[0-9a-f]+$'; then shape="$shape lowercase-hex"; else shape="$shape not-lowercase-hex"; fi
echo "::notice title=amap binding the app presents::package=${pkg} apk_sha1=${apk_sha1} key ${shape}"
stores="$(find / -name debug.keystore -not -path '/proc/*' 2>/dev/null | while read -r f; do printf '%s=%s ' "$f" "$(keytool -exportcert -alias androiddebugkey -keystore "$f" -storepass android 2>/dev/null | openssl dgst -sha1 | awk '{print substr($2,1,8)}')"; done)"
echo "::notice title=debug keystores on the runner::${stores}"
if [ "$apk_sha1" != "$sha1" ]; then
  echo "::error title=apk not signed with the Amap-registered certificate::apk_sha1=${apk_sha1} expected=${sha1}"
  exit 1
fi
