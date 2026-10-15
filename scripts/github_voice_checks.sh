#!/bin/bash
# Voice rows on the GitHub emulator with the debug-only Gemini override (decided by the decider,
# 2026-10-15; AGENTS.md "Gemini first, Ayano last"). Runs tools/speech-harness/run_scenarios.py
# (S1..S21) against the real app while the screen is recorded.
# QUOTA GUARD: the run stops before injecting any audio unless the session reports
# session_provider choice=gemini_live - no Qwen session is ever opened.
# Evidence: evidence/summary.txt (scenario ids, PASS/FAIL, event counts - no transcript),
# evidence/voice_screen.mp4, screenshots. Never a key, coordinate or address.
set -eu
apk="${1:?apk path}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
ev="$root/evidence"
mkdir -p "$ev"
summary="$ev/summary.txt"
: >"$summary"
trap 'echo "::error title=voice checks failed::line ${LINENO} exit $?"' ERR

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
if ! setup_key GEMINI_API_KEY gemini_setup gemini_setup.txt; then
  note "voice: skipped - no GEMINI_API_KEY secret"
  exit 0
fi
mark
tool provider gemini; sleep 1
note "voice: provider override -> $(result_of provider)"
tool voice start
if ! wait_for "session_provider choice=" 30; then note "voice: no session_provider line"; exit 1; fi
choice="$(nova | grep -oE 'session_provider choice=[a-z_]+' | tail -1)"
note "voice: $choice"
if [ "$choice" != "session_provider choice=gemini_live" ]; then
  tool voice stop
  echo "::error title=quota guard::the session is not Gemini ($choice); stopped before any audio"
  exit 1
fi
wait_for "gemini_setup_complete|state=LISTENING" 40 && note "voice: session up" || note "voice: session did not report ready ($(nova | grep -oE 'state=[A-Z_]+|err=[A-Z_]+' | tail -2 | tr '\n' ' '))"
shot voice_ready

adb shell screenrecord --time-limit 180 --bit-rate 4000000 /sdcard/voice_part1.mp4 &
rec1=$!
python3 tools/speech-harness/run_scenarios.py --wait 14 >"$ev/scenarios_raw.txt" 2>&1 || true
wait "$rec1" || true
grep -E '^\s+(PASS|FAIL)\s+S[0-9]+b?\b|^\s+(ok|FLAKY|FAIL)\s+S[0-9]+' "$ev/scenarios_raw.txt" | sed -E 's/^\s+//' | awk '{print $1, $2}' >"$ev/scenarios.txt" || true
grep -E '^\s+expected /|^\s+forbidden /' "$ev/scenarios_raw.txt" | sed -E "s/ matched .*/ matched (text omitted)/" >"$ev/scenario_failures.txt" || true
rm -f "$ev/scenarios_raw.txt"
note "voice: scenarios $(tr '\n' ' ' <"$ev/scenarios.txt")"
note "voice: session errors=$(count 'state=ERROR|_PROTOCOL_ERROR') provider_lines=$(nova | grep -oE 'session_provider choice=[a-z_]+' | sort | uniq -c | tr '\n' ' ')"
adb pull /sdcard/voice_part1.mp4 "$ev/voice_part1_phone.mp4" >/dev/null 2>&1 || true
ffmpeg -v error -y -i "$ev/voice_part1_phone.mp4" -c copy -movflags +faststart "$ev/voice_screen.mp4" && rm -f "$ev/voice_part1_phone.mp4" || true
shot voice_end
tool voice stop
tool provider clear
note "voice: done, override cleared"
