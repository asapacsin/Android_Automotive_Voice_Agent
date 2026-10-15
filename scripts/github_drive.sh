#!/bin/bash
# Record one emulator drive of the installed debug app.
# Never prints coordinates, addresses, or transcripts.
set -eu
apk="${1:?apk path}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

# Report what the agent cannot read from the job log, as annotations. Only tokens and codes:
# never the key, a coordinate, an address or a transcript.
diagnose() {
  local stage="$1" level="${2:-error}"
  local all nova
  all="$(adb logcat -d 2>/dev/null || true)"
  nova="$(adb logcat -d -s NovaVoice:D 2>/dev/null || true)"
  local auth sha calc surface fatal
  auth="$(printf '%s\n' "$all" | grep -oE 'INVALID_USER_[A-Z_]+|USERKEY_PLAT_NOMATCH|USER_KEY_RECYCLED|INSUFFICIENT_PRIVILEGES|SERVICE_NOT_AVAILBALE|DAILY_QUERY_OVER_LIMIT|ACCESS_TOO_FREQUENT' | sort | uniq -c | tr '\n' ' ')"
  sha="$(printf '%s\n' "$all" | grep -oE 'SHA1Package:[0-9A-Fa-f:]+;[A-Za-z0-9_.]+' | head -1)"
  calc="$(printf '%s\n' "$nova" | grep -oE 'nav_calc_failure code=-?[0-9]+|nav_calc_success|nav_started|map_loaded|amap_gps startGPS=[a-z]+|nav_guidance_tts_init code=-?[0-9]+' | sort | uniq -c | tr '\n' ' ')"
  surface="$(printf '%s\n' "$nova" | grep -oE 'map_surface skipped=[a-z_]+' | head -1)"
  fatal="$(printf '%s\n' "$all" | grep -oE 'FATAL EXCEPTION|Fatal signal [0-9]+ \([A-Z]+\)|SIGILL|UnsatisfiedLinkError' | sort | uniq -c | tr '\n' ' ')"
  echo "::${level} title=drive ${stage}::amap_auth=[${auth:-none}] ${sha:-no SHA1Package line} app=[${calc:-none}] ${surface:-map surface attached} crash=[${fatal:-none}]"
}
stage() { echo "::notice title=drive stage::$1"; }
trap 'echo "::error title=drive script failed::line ${LINENO} exit $?"' ERR

adb install -r -g --abi arm64-v8a "$apk"

origin="$(sed -n 's/.*GeoFix *= *"\([0-9.][0-9.]*\) \([0-9.][0-9.]*\)".*/\1 \2/p' scripts/emulator.ps1 | head -1)"
test -n "$origin"
# shellcheck disable=SC2086
set -- $origin
lon="$1"
lat="$2"
adb emu geo fix "$lon" "$lat" >/dev/null
adb shell am start -W -n com.novadrive.app/.MainActivity >/dev/null
python3 - "$lat" "$lon" <<'PY' > /tmp/route-arg
import sys
lat = float(sys.argv[1]) + 0.02
lon = float(sys.argv[2]) + 0.015
sys.stdout.write(f"@{lat:.4f},{lon:.4f}")
PY

# map_loaded is only one camera-reapply line. The host also logs when GPS starts
# and when the camera is checked. Any of those means the map screen is up.
ready=0
i=0
while [ "$i" -lt 36 ]; do
  logs="$(adb logcat -d -s NovaVoice:D || true)"
  if printf '%s\n' "$logs" | grep -q "map_surface skipped=translator_unsafe"; then
    echo "map surface skipped: translator unsafe"
    diagnose "stopped at map surface"
    exit 1
  fi
  if printf '%s\n' "$logs" | grep -Eq "map_loaded|map_recenter ok=true|map_recenter_check|amap_gps startGPS="; then
    ready=1
    break
  fi
  if ! adb shell pidof com.novadrive.app >/dev/null 2>&1; then
    echo "app process exited during startup"
    diagnose "stopped at app startup (process exited)"
    exit 1
  fi
  echo "waiting for map startup ($i)"
  i=$((i + 1))
  sleep 5
done
if [ "$ready" != 1 ]; then
  echo "map startup timed out"
  diagnose "stopped at map startup"
  exit 1
fi
echo "map startup seen"
stage "map up"

# Record the real screen from here: route planning, the start, then the simulated drive.
# screenrecord stops itself at its limit (180 s is its maximum).
adb shell screenrecord --time-limit 180 --bit-rate 6000000 /sdcard/drive.mp4 &
recorder=$!
sleep 4

adb shell am broadcast -a com.novadrive.app.DEBUG_TOOL -p com.novadrive.app \
  --es tool nav_route --es arg "$(cat /tmp/route-arg)" >/dev/null
rm -f /tmp/route-arg

routed=0
i=0
while [ "$i" -lt 36 ]; do
  if adb logcat -d -s NovaVoice:D | grep -q "nav_calc_success"; then
    routed=1
    break
  fi
  echo "waiting for route ($i)"
  i=$((i + 1))
  sleep 5
done
if [ "$routed" != 1 ]; then
  echo "route timed out"
  diagnose "stopped at route"
  exit 1
fi
echo "route ready"
stage "route calculated"

adb shell am broadcast -a com.novadrive.app.DEBUG_TOOL -p com.novadrive.app \
  --es tool nav_start --es arg emulator:80 >/dev/null

started=0
i=0
while [ "$i" -lt 12 ]; do
  if adb logcat -d -s NovaVoice:D | grep -q "nav_started"; then
    started=1
    break
  fi
  echo "waiting for navigation start ($i)"
  i=$((i + 1))
  sleep 2
done
if [ "$started" != 1 ]; then
  echo "navigation start timed out"
  diagnose "stopped at navigation start"
  exit 1
fi
echo "navigation started"
stage "navigation started"

wait "$recorder" || true
sleep 3
adb pull /sdcard/drive.mp4 "$root/drive-phone.mp4" >/dev/null
test -s "$root/drive-phone.mp4"

# Run 38721062441: the old side-panel step died in ffmpeg (exit 234 = -22, invalid argument).
# The phone screen is the demo; only remux it so it plays in a browser.
sudo apt-get update -q >/dev/null
sudo apt-get install -y -q ffmpeg >/dev/null
ffmpeg -v error -y -i "$root/drive-phone.mp4" -c copy -movflags +faststart "$root/drive.mp4" \
  || cp "$root/drive-phone.mp4" "$root/drive.mp4"
echo "drive video ready"
diagnose "finished, video recorded" notice
