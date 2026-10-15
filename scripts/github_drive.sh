#!/bin/bash
# Record one emulator drive of the installed debug app.
# Never prints coordinates, addresses, or transcripts.
set -eu
apk="${1:?apk path}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

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
    exit 1
  fi
  if printf '%s\n' "$logs" | grep -Eq "map_loaded|map_recenter ok=true|map_recenter_check|amap_gps startGPS="; then
    ready=1
    break
  fi
  if ! adb shell pidof com.novadrive.app >/dev/null 2>&1; then
    echo "app process exited during startup"
    printf '%s\n' "$logs" | tail -n 40
    exit 1
  fi
  echo "waiting for map startup ($i)"
  i=$((i + 1))
  sleep 5
done
if [ "$ready" != 1 ]; then
  echo "map startup timed out"
  adb logcat -d -s NovaVoice:D | tail -n 40 || true
  exit 1
fi
echo "map startup seen"

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
  adb logcat -d -s NovaVoice:D | tail -n 40 || true
  exit 1
fi
echo "route ready"

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
  adb logcat -d -s NovaVoice:D | tail -n 40 || true
  exit 1
fi
echo "navigation started"

adb shell screenrecord --time-limit 24 --bit-rate 4000000 /data/local/tmp/drive.mp4
adb pull /data/local/tmp/drive.mp4 "$root/drive-phone.mp4" >/dev/null

sudo apt-get update -q >/dev/null
sudo apt-get install -y -q ffmpeg fonts-noto-cjk >/dev/null
font="$(fc-list :lang=zh file | head -1 | cut -d: -f1)"
test -n "$font"
dur="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$root/drive-phone.mp4")"
ffmpeg -y -f lavfi -i "color=c=0x111111:s=640x960:d=${dur}" \
  -vf "drawtext=fontfile=${font}:text='模拟用户':fontsize=52:fontcolor=white:x=48:y=80,drawtext=fontfile=${font}:text='帮我导航':fontsize=40:fontcolor=white:x=48:y=220,drawtext=fontfile=${font}:text='开始导航':fontsize=40:fontcolor=white:x=48:y=320,drawtext=fontfile=${font}:text='小诺正在这条路上开':fontsize=32:fontcolor=0xdddddd:x=48:y=460" \
  -c:v libx264 -pix_fmt yuv420p "$root/drive-panel.mp4" >/dev/null
ffmpeg -y -i "$root/drive-phone.mp4" -i "$root/drive-panel.mp4" \
  -filter_complex "[0:v]scale=540:-2,pad=540:960:(ow-iw)/2:(oh-ih)/2:black[phone];[phone][1:v]hstack=inputs=2" \
  -c:v libx264 -pix_fmt yuv420p -movflags +faststart "$root/drive.mp4" >/dev/null
echo "drive video ready"
