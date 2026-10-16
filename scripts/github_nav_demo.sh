#!/bin/bash
# The review demo: the real app used as a driver would, on the GitHub emulator - ask for a
# destination by voice, pick it, start navigation, drive the simulated route to arrival with Amap
# guidance, and talk to her on the way. Her voice is Gemini's (debug override, quota guard: the
# bridge stops unless the session is gemini_live). Driver lines are synthetic (edge-tts). Output:
# evidence/nav_demo.mp4 (screen + driver + her replies + guidance voiced with edge-tts) and summary.txt
# (counts and codes only - no transcript, address or coordinate).
set -eu
apk="${1:?apk path}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
ev="$root/evidence"
mkdir -p "$ev"
summary="$ev/summary.txt"
: >"$summary"
trap 'echo "::error title=nav demo failed::line ${LINENO} exit $?"' ERR

note() { echo "$1" | tee -a "$summary"; echo "::notice title=${1%%:*}::${1#*: }"; }
nova() { adb logcat -d -s NovaVoice:D 2>/dev/null || true; }
mark() { adb logcat -c || true; }
count() { nova | grep -cE "$1" || true; }
tool() { adb shell am broadcast -a com.novadrive.app.DEBUG_TOOL -p com.novadrive.app --es tool "$1" --es arg "$2" >/dev/null; }
# Result of the last debug_tool line for a tool: only ok/error/result codes.
result_of() {
  nova | grep "debug_tool tool=$1 " | tail -1 | grep -oE '"ok":(true|false)|"error":"[A-Z_]+"|result=(Accepted|Rejected\([A-Z_]+\)|[a-z_]+=[a-z0-9]+|[A-Za-z]+(\([A-Z_]+\))?)' | tr '\n' ' '
}
shot() {
  adb exec-out screencap -p >"$ev/$1.png"
  if command -v ffmpeg >/dev/null; then
    ffmpeg -v error -y -i "$ev/$1.png" -vf scale=540:-2 -q:v 4 "$ev/$1.jpg" && rm -f "$ev/$1.png"
  fi
}
wait_for() { # pattern seconds
  local i=0
  while [ "$i" -lt "$2" ]; do
    if nova | grep -qE "$1"; then return 0; fi
    i=$((i + 1)); sleep 1
  done
  return 1
}
app_up() {
  adb shell am start -W -n com.novadrive.app/.MainActivity >/dev/null
  wait_for "map_loaded|map_recenter ok=true|map_recenter_check|amap_gps startGPS=" 120
}

sudo apt-get update -q >/dev/null
sudo apt-get install -y -q ffmpeg >/dev/null

adb install -r -g --abi arm64-v8a "$apk"
# The summary greps the whole drive from logcat: keep it from rolling over.
adb logcat -G 16M || true
sleep 30
adb shell settings put global hide_error_dialogs 1 || true
adb shell am force-stop com.google.android.apps.nexuslauncher || true
adb shell input keyevent KEYCODE_HOME || true

origin="$(sed -n 's/.*GeoFix *= *"\([0-9.][0-9.]*\) \([0-9.][0-9.]*\)".*/\1 \2/p' scripts/emulator.ps1 | head -1)"
set -- $origin
adb emu geo fix "$1" "$2" >/dev/null
dest="$(python3 - "$2" "$1" <<'PY'
import sys
print(f"@{float(sys.argv[1]) + 0.02:.4f},{float(sys.argv[2]) + 0.015:.4f}")
PY
)"

# Optional keys from Actions secrets (absent -> those checks are reported as skipped). Each is
# written to a file, pushed, copied into the app's files dir and deleted; never printed.
setup_key() { # env-name tool file
  local value="${!1:-}"
  if [ -z "$value" ]; then note "secret $1: not set"; return 1; fi
  local tmp; tmp="$(mktemp)"; printf '%s\n' "$value" >"$tmp"
  adb push "$tmp" "/data/local/tmp/$3" >/dev/null; rm -f "$tmp"
  adb shell chmod 644 "/data/local/tmp/$3"
  adb shell run-as com.novadrive.app cp "/data/local/tmp/$3" "files/$3"
  adb shell rm -f "/data/local/tmp/$3"
  tool "$2" x; sleep 2
  note "secret $1: set, $2 -> $(result_of "$2")"
}


app_up && note "app: map up" || note "app: map did not come up"
setup_key AMAP_WEB_KEY amap_web_setup amap_web_setup.txt || true
if ! setup_key GEMINI_API_KEY gemini_setup gemini_setup.txt; then note "nav demo: skipped - no GEMINI_API_KEY"; exit 0; fi
# P62 / GUIDANCE-EMU-001: guidance in her voice (SPEC-018 relay, ADR-014), Amap only as the fallback.
# 3000 ms is a measurement deadline (SPEC-018's default 1200 ms is set from G-1, never measured).
tool guidance_relay on:3000; sleep 1
note "guidance relay: $(result_of guidance_relay)"
adb shell am force-stop com.novadrive.app
app_up || true
sleep 3

# Screen: three back-to-back 180 s recordings (screenrecord's limit), stopped once the drive ends.
rec_start="$(date +%s.%N)"
(
  for i in 1 2 3; do
    echo "$(date +%s.%N)" >"$ev/seg${i}_start.txt"
    # P64: half resolution, so the guest encoder takes less of the CPU the map renders on.
    adb shell screenrecord --time-limit 180 --size 576x1248 --bit-rate 3000000 "/sdcard/nav_seg${i}.mp4" || true
    [ -f "$ev/stop_recording" ] && break
  done
) &
recorder=$!

# Once navigation starts, speed the simulated car up so the whole drive fits in the video.
(
  for _ in $(seq 1 120); do
    if nova | grep -q "nav_started"; then sleep 8; tool nav_speed 90; break; fi
    sleep 3
  done
) &

S="tools/speech-harness/speech"
# P63: 十字门, a route with a 40 km/h speed camera (NAV-MID-ROUTE-001, 2026-09-21), driven at 90.
clips="$S/nav_shizimen.pcm,$S/pick_first.pcm,$S/start_nav.pcm,$S/ctx_too_hot.pcm,$S/music_on.pcm,$S/eta_q.pcm,$S/gas_nearby.pcm,$S/music_off.pcm"
python3 tools/speech-harness/host_audio_bridge.py --from-file "$clips" --lead 8 --gap 20 --duration 420 \
  --no-play --provider gemini --record "$ev/demo_audio" >/dev/null 2>"$ev/bridge_err.txt" || true
note "nav demo: provider=$(nova | grep -oE 'session_provider choice=[a-z_]+' | tail -1) started=$(count 'nav_started') stopped=$(nova | grep -oE 'nav_stopped reached=[a-z]+ reason=[a-z_]+' | tail -1) guidance=$(count 'nav_guidance_play_start') tools=$(nova | grep -oE '^tool=[a-z_]+|tool=[a-z_]+ args' | grep -oE 'tool=[a-z_]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ') errors=$(count 'state=ERROR|_PROTOCOL_ERROR')"
# Let the drive reach the destination if it has not yet.
for _ in $(seq 1 40); do nova | grep -q "nav_stopped" && break; sleep 5; done
# P62 relay: who spoke each guidance sentence, and how long until her audio (codes and ms only).
note "relay: $(nova | grep -oE 'nav_guidance_relay [a-z_]+=[a-z0-9_]+' | sort -u | tr '\n' ' ')routes: $(nova | grep -oE 'guidance_route prompt=g[0-9]+ to=[a-z]+ reason=[A-Z_]+' | sed -E 's/prompt=g[0-9]+ //' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')"
note "relay claims: assistant_waited_ms=$(nova | grep -oE 'guidance_claim prompt=g[0-9]+ side=assistant waited_ms=[0-9]+' | grep -oE '[0-9]+$' | tr '\n' ',') amap=$(count 'guidance_claim .* side=amap') fidelity: $(nova | grep -oE 'guidance_fidelity prompt=g[0-9]+ result=[A-Z_]+' | grep -oE 'result=[A-Z_]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')fallback_tts: $(nova | grep -oE 'nav_guidance_fallback_tts accepted=[a-z]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')"
# P63: limits seen, time over, warnings spoken, Amap rings.
note "overspeed: limits=$(nova | grep -oE 'nav_speed kmh=[0-9]+ limitKmh=[0-9]+' | grep -oE 'limitKmh=[0-9]+' | sort -u | tr '\n' ' ')over_lines=$(count 'nav_speed .* over=1') alerts=$(count 'nav_overspeed_alert') queued: $(nova | grep -oE 'nav_alert_(queued to=[a-z]+|dropped reason=[a-z_]+)' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')rings=$(count 'nav_play_ring')"
note "gemini closes: $(nova | grep -oE 'gemini_closed code=[0-9]+ reason_class=[a-z_]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')"
sleep 8
touch "$ev/stop_recording"
adb shell pkill -INT screenrecord || true
wait "$recorder" || true
sleep 2
: >"$ev/segs.txt"
for i in 1 2 3; do
  if adb pull "/sdcard/nav_seg${i}.mp4" "$ev/seg${i}.mp4" >/dev/null 2>&1 && [ -s "$ev/seg${i}.mp4" ]; then echo "file 'seg${i}.mp4'" >>"$ev/segs.txt"; fi
done
(cd "$ev" && ffmpeg -v error -y -f concat -safe 0 -i segs.txt -c copy screen.mp4) || true
if [ -s "$ev/screen.mp4" ] && [ -f "$ev/demo_audio/t0.txt" ]; then
  off="$(python3 -c "import sys; print(max(0.0, float(open(sys.argv[1]).read()) - float(open(sys.argv[2]).read()) - 0.4))" "$ev/demo_audio/t0.txt" "$ev/seg1_start.txt")"
  inputs=(-i "$ev/screen.mp4" -itsoffset "$off" -i "$ev/demo_audio/driver.wav" -itsoffset "$off" -i "$ev/demo_audio/reply.wav")
  mix="[1:a]aresample=48000[d];[2:a]aresample=48000[r];[d][r]amix=inputs=2:normalize=0:duration=longest[a]"
  if [ -f "$ev/demo_audio/guidance.wav" ]; then
    inputs+=(-itsoffset "$off" -i "$ev/demo_audio/guidance.wav")
    mix="[1:a]aresample=48000[d];[2:a]aresample=48000[r];[3:a]aresample=48000,volume=0.8[g];[d][r][g]amix=inputs=3:normalize=0:duration=longest[a]"
  fi
  ffmpeg -v error -y "${inputs[@]}" -filter_complex "$mix" -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 128k \
    -movflags +faststart "$ev/nav_demo.mp4" && note "nav demo: nav_demo.mp4 written (offset ${off} s, guidance=$( [ -f "$ev/demo_audio/guidance.wav" ] && echo yes || echo no))" || note "nav demo: mux failed"
fi
# P64: the frame rate the viewer sees.
if [ -s "$ev/nav_demo.mp4" ]; then
  note "video: $(ffprobe -v error -select_streams v:0 -count_packets -show_entries stream=nb_read_packets,width,height -show_entries format=duration -of csv=p=0 "$ev/nav_demo.mp4" | tr '\n' ' ' | awk -F'[ ,]+' '$4 > 0 {printf "%sx%s frames=%s seconds=%.0f fps=%.1f", $1, $2, $3, $4, $3/$4}')"
fi
rm -f "$ev"/seg*.mp4 "$ev/screen.mp4" "$ev/segs.txt" "$ev/stop_recording" "$ev/bridge_err.txt"
shot nav_demo_end
tool voice stop
tool provider clear
tool guidance_relay off
note "nav demo: done"
