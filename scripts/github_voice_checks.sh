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
python3 tools/speech-harness/run_scenarios.py --wait 14 --provider gemini >"$ev/scenarios_raw.txt" 2>&1 || true
wait "$rec1" || true
grep -E '^\s+(PASS|FAIL)\s+S[0-9]+b?\b|^\s+(ok|FLAKY|FAIL)\s+S[0-9]+' "$ev/scenarios_raw.txt" | sed -E 's/^\s+//' | awk '{print $1, $2}' >"$ev/scenarios.txt" || true
grep -E '^\s+expected /|^\s+forbidden /' "$ev/scenarios_raw.txt" | sed -E "s/ matched .*/ matched (text omitted)/" >"$ev/scenario_failures.txt" || true
# Text-free skeleton of each failing scenario's log tail: event names and ASCII key=value codes
# only; any token with CJK text (transcripts, replies, place names) or digits-with-dots is dropped.
python3 - "$ev/scenarios_raw.txt" >"$ev/failure_skeletons.txt" <<'PY' || true
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
part = text.split("=== last failing log per scenario ===", 1)
if len(part) == 2:
    for line in part[1].splitlines():
        if line.startswith("-- "):
            print(line.split(":", 1)[0]); continue
        keep = []
        for tok in line.split():
            if re.search(r"[\u3000-\u9fff\uff00-\uffef]", tok) or re.search(r"\d+\.\d{3,}", tok):
                continue
            if re.fullmatch(r"[A-Za-z_]+(=[A-Za-z0-9_.:\-\[\],%✓✗]{0,40})?", tok):
                keep.append(tok)
        if keep:
            print("   " + " ".join(keep[:12]))
PY
note "voice: scenarios $(tr '\n' ' ' <"$ev/scenarios.txt")"
if [ ! -s "$ev/scenarios.txt" ]; then
  # The runner printed no result: keep its last lines without any CJK text (tracebacks, adb errors).
  grep -v -P '[\x{3000}-\x{9fff}\x{ff00}-\x{ffef}]' "$ev/scenarios_raw.txt" | tail -n 15 >"$ev/runner_tail.txt" || true
  note "voice: runner produced no results; adb devices: $(adb devices | tail -n +2 | tr '\n' ' ')"
fi
note "voice: session errors=$(count 'state=ERROR|_PROTOCOL_ERROR') provider_lines=$(nova | grep -oE 'session_provider choice=[a-z_]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ') quota_guard=$(grep -c 'QUOTA GUARD' "$ev/scenarios_raw.txt" 2>/dev/null || echo 0)"
adb pull /sdcard/voice_part1.mp4 "$ev/voice_part1_phone.mp4" >/dev/null 2>&1 || true
ffmpeg -v error -y -i "$ev/voice_part1_phone.mp4" -c copy -movflags +faststart "$ev/voice_screen.mp4" && rm -f "$ev/voice_part1_phone.mp4" || true
shot voice_end
tool voice stop
tool provider clear
rm -f "$ev/scenarios_raw.txt"

# ---- further suites: Cantonese (TRUTH-MEDIA-001), device rows (affordances, news, vision, fuzzy with
# screenshots), live info x10 (LIVE-INFO-DEVICE-001). Ids + PASS/FAIL only, as above.
suite() { # name, then runner args
  local name="$1"; shift
  python3 tools/speech-harness/run_scenarios.py --wait 14 --provider gemini "$@" >"$ev/${name}_raw.txt" 2>&1 || true
  grep -E '^\s+(ok|FLAKY|FAIL)\s+[A-Z][A-Z0-9-]*\s+[0-9]+/[0-9]+' "$ev/${name}_raw.txt" | sed -E 's/^\s+//' | awk '{print $1, $2, $3}' >"$ev/${name}.txt" || true
  grep -E '^\s+expected /|^\s+forbidden /' "$ev/${name}_raw.txt" | sed -E "s/ matched .*/ matched (text omitted)/" | sort | uniq -c >"$ev/${name}_failures.txt" || true
  note "suite $name: $(tr '\n' ' ' <"$ev/${name}.txt") guard=$(grep -c 'QUOTA GUARD' "$ev/${name}_raw.txt" || true)"
  rm -f "$ev/${name}_raw.txt"
}
suite cantonese --file tools/speech-harness/scenarios-cantonese.json
suite device --file tools/speech-harness/scenarios-device.json --shots "$ev/shots"
for f in "$ev"/shots/*.png; do [ -f "$f" ] && ffmpeg -v error -y -i "$f" -vf scale=540:-2 -q:v 4 "${f%.png}.jpg" && rm -f "$f"; done
suite liveinfo --file tools/speech-harness/scenarios-liveinfo.json --repeat 10

# ---- WAKE-SYNTH-001: a synthetic wake through the debug path ----------------------------------
mark
tool voice stop; sleep 3
tool provider gemini
tool wake on; sleep 2
note "WAKE-SYNTH-001: wake on -> $(result_of wake) status: $(tool wake status; sleep 1; nova | grep 'debug_tool tool=wake arg=status' | tail -1 | grep -oE 'enabled=[a-z]+|credentials_complete=[a-z]+' | tr '\n' ' ')"
adb push tools/speech-harness/speech/wake_phrase.pcm /sdcard/Android/data/com.novadrive.app/files/wake.pcm >/dev/null 2>&1 || true
sleep 2
tool wake inject; sleep 10
note "WAKE-SYNTH-001: wake inject -> $(result_of wake) detection=$(count 'wake_detection') to_active=$(count 'DEEP_IDLE->ACTIVE|->ACTIVE reason=wake') provider=$(nova | grep -oE 'session_provider choice=[a-z_]+' | tail -1) wake_lines=$(nova | grep -oE 'wake_[a-z_]+' | sort | uniq -c | tr -s ' ' | tr '\n' ';')"
if nova | grep -q 'session_provider choice=qwen'; then tool voice stop; echo "::error title=quota guard::wake opened a non-Gemini session; stopped"; fi

# ---- the review demo: the real app, driver audio over the host bridge, her replies recorded -----
# Clips are the synthetic speech-harness lines. The bridge re-applies provider=gemini and stops if
# the session is not gemini_live (quota guard). Its stdout carries transcripts: not kept.
adb shell am force-stop com.novadrive.app
app_up || true
sleep 3
S="tools/speech-harness/speech"
clips="$S/help_gan_sha.pcm,$S/ctx_too_hot.pcm,$S/temp24.pcm,$S/nav_zhuhai.pcm,$S/pick_second.pcm,$S/music_on.pcm,$S/cancel.pcm,$S/volume_up.pcm"
mark
rec_start="$(date +%s.%N)"
adb shell screenrecord --time-limit 180 --bit-rate 4000000 /sdcard/voice_demo.mp4 &
rec2=$!
python3 tools/speech-harness/host_audio_bridge.py --from-file "$clips" --lead 8 --gap 11 --duration 150 \
  --no-play --provider gemini --record "$ev/demo_audio" >/dev/null 2>"$ev/bridge_err.txt" || true
wait "$rec2" || true
note "demo: provider=$(nova | grep -oE 'session_provider choice=[a-z_]+' | tail -1) tools=$(nova | grep -oE '^.*tool=[a-z_]+ ' | grep -oE 'tool=[a-z_]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ') errors=$(count 'state=ERROR|_PROTOCOL_ERROR')"
adb pull /sdcard/voice_demo.mp4 "$ev/voice_demo_screen.mp4" >/dev/null 2>&1 || true
if [ -f "$ev/demo_audio/t0.txt" ] && [ -f "$ev/voice_demo_screen.mp4" ]; then
  off="$(python3 -c "import sys; print(max(0.0, float(open(sys.argv[1]).read()) - float(sys.argv[2]) - 0.4))" "$ev/demo_audio/t0.txt" "$rec_start")"
  ffmpeg -v error -y -i "$ev/voice_demo_screen.mp4" -itsoffset "$off" -i "$ev/demo_audio/driver.wav" -itsoffset "$off" -i "$ev/demo_audio/reply.wav" \
    -filter_complex "[1:a]aresample=48000[d];[2:a]aresample=48000[r];[d][r]amix=inputs=2:normalize=0[a]" \
    -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 128k -shortest -movflags +faststart "$ev/voice_demo.mp4" \
    && note "demo: voice_demo.mp4 written (audio offset ${off} s)" || note "demo: mux failed"
  rm -f "$ev/voice_demo_screen.mp4"
else
  note "demo: no recording ($(tail -c 300 "$ev/bridge_err.txt" 2>/dev/null | tr '\n' ' '))"
fi
rm -f "$ev/bridge_err.txt"
note "voice: done, override cleared"
