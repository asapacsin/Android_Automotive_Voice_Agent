#!/bin/bash
# Voice rows on the GitHub emulator with the debug-only Gemini override (decided by the decider,
# 2026-10-15; AGENTS.md "Gemini first, Ayano last"). Runs tools/speech-harness/run_scenarios.py
# (S1..S21) against the real app while the screen is recorded.
# QUOTA GUARD: the run stops before injecting any audio unless the session reports
# session_provider choice=gemini_live - no Qwen session is ever opened.
# Evidence: evidence/summary.txt (scenario ids, PASS/FAIL, event counts - no transcript),
# evidence/voice_screen.mp4, screenshots. Never a key, coordinate or address.
# Verdict: every check is recorded in evidence/results.jsonl (scripts/ci_verdict.py, suite "voice");
# the workflow's Verdict step fails the job on a required failure. Best-effort steps (screen
# recording, ffmpeg muxing, logcat clearing, launcher cosmetics) are marked "|| true" on purpose.
# SHARDS (owner 2026-10-19: function tests in small units, each <=5 min after boot): the second
# argument (or VOICE_SHARD) picks one shard; each shard's verdict is its suite in config/ci_checks.json
# (voice-a, voice-b, voice-c, voice-device, voice-li-weather, voice-li-place, voice-li-traffic,
# voice-li-along, voice-demo); "all"
# runs everything and is judged by suite "voice". The id lists below must match those suites
# (scripts/tests/test_ci_tier_b.py checks it).
set -eu
apk="${1:?apk path}"
shard="${2:-${VOICE_SHARD:-all}}"
ids_voice_a="S1 S2 S3 S4 S4b S5 S5b S6 S7"
ids_voice_b="S8 S9 S10 S11 S12 S13 S14"
ids_voice_c="S15 S16 S17 S18 S19 S20 S21"
case "$shard" in
  all) suite_name=voice ;;
  voice-a|voice-b|voice-c) suite_name="$shard" ;;
  device|li-weather|li-place|li-traffic|li-along|demo) suite_name="voice-$shard" ;;
  *) echo "::error title=voice checks::unknown shard $shard"; exit 2 ;;
esac
runs() { [ "$shard" = all ] || [ "$shard" = "$1" ]; }  # does this run include shard $1
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
ev="$root/evidence"
mkdir -p "$ev"
summary="$ev/summary.txt"
: >"$summary"
R="$ev/results.jsonl"; : >"$R"
res() { python3 scripts/ci_verdict.py record --out "$R" --id "$1" --status "$2" --detail "${3:-}"; }
# build:vendor-real: BLOCKED STUB_VENDOR when the APK was built on the iFlytek stand-in, so a stub run
# is never green; every other check still runs and is recorded (decided by the decider 2026-10-19).
python3 scripts/ci_verdict.py vendor --apk "$apk" --out "$R"
# Raw runner output holds transcripts: it never survives the script, even on an error exit.
trap 'rm -f "$ev"/*_raw.txt "$ev"/bridge_err.txt' EXIT
trap 'exit 130' INT TERM  # a signal exits through the EXIT trap above
# Parse one runner's raw output into results.jsonl (one record per scenario plus <prefix>runner).
scen() { # raw-file prefix runner-rc
  python3 scripts/ci_verdict.py scenarios --raw "$1" --out "$ev/scen_part.jsonl" --prefix "$2" --runner-rc "$3"
  cat "$ev/scen_part.jsonl" >>"$R"; rm -f "$ev/scen_part.jsonl"
}
qg_fail=""  # quota guard: reasons it failed (codes only)
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
  # Every required id of this shard's suite is BLOCKED (a wildcard through its <prefix>runner).
  python3 - "$suite_name" <<'PY' | while read -r rid; do res "$rid" BLOCKED "secret GEMINI_API_KEY not set"; done
import json, sys
ids = []
for rid in json.load(open("config/ci_checks.json"))["suites"][sys.argv[1]]["required"]:
    if rid.endswith("*"):
        rid = rid[:rid.rfind(":") + 1] + "runner"
    if rid != "build:vendor-real" and rid not in ids:
        ids.append(rid)
print("\n".join(ids))
PY
  exit 0
fi
mark
tool provider gemini; sleep 1
note "voice: provider override -> $(result_of provider)"
tool voice start
if ! wait_for "session_provider choice=" 30; then
  note "voice: no session_provider line"; res voice:quota-guard FAIL "no session_provider line"; exit 1
fi
choice="$(nova | grep -oE 'session_provider choice=[a-z_]+' | tail -1)"
note "voice: $choice"
if [ "$choice" != "session_provider choice=gemini_live" ]; then
  tool voice stop
  echo "::error title=quota guard::the session is not Gemini ($choice); stopped before any audio"
  res voice:quota-guard FAIL "session not gemini_live: ${choice#session_provider }"
  exit 1
fi
wait_for "gemini_setup_complete|state=LISTENING" 40 && note "voice: session up" || note "voice: session did not report ready ($(nova | grep -oE 'state=[A-Z_]+|err=[A-Z_]+|\b(GEMINI|QWEN)_[A-Z_]+' | tail -4 | tr '\n' ' '))"
shot voice_ready

# skeleton RAW OUT: text-free skeleton of each failing scenario's log tail: event names and ASCII key=value codes
# only; any token with CJK text (transcripts, replies, place names) or digits-with-dots is dropped.
# Every suite gets one (2026-10-17): without it a device-row failure such as AFF-AC showed only that
# a forbidden tool line matched, not the line's result= code that tells a refused duplicate from a
# second execution.
skeleton() {
python3 - "$1" >"$2" <<'PY' || true
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
}
# Scenario rows S1..S21: shard voice-a, voice-b or voice-c runs only its ids (--only); "all" runs all.
main_only=""
case "$shard" in
  voice-a) main_only="$ids_voice_a" ;;
  voice-b) main_only="$ids_voice_b" ;;
  voice-c) main_only="$ids_voice_c" ;;
esac
if runs voice-a || runs voice-b || runs voice-c; then
adb shell screenrecord --time-limit 180 --bit-rate 4000000 /sdcard/voice_part1.mp4 &
rec1=$!
set +e
# shellcheck disable=SC2086  # main_only is a word list of scenario ids
python3 tools/speech-harness/run_scenarios.py --wait 14 --provider gemini ${main_only:+--only $main_only} >"$ev/scenarios_raw.txt" 2>&1
rc=$?
set -e
wait "$rec1" || true  # best-effort: screen recording
note "voice: runner rc=$rc"
scen "$ev/scenarios_raw.txt" voice: "$rc"
grep -E '^\s+(PASS|FAIL)\s+S[0-9]+b?\b|^\s+(ok|FLAKY|FAIL)\s+S[0-9]+' "$ev/scenarios_raw.txt" | sed -E 's/^\s+//' | awk '{print $1, $2}' >"$ev/scenarios.txt" || true
grep -E '^\s+expected /|^\s+forbidden /' "$ev/scenarios_raw.txt" | sed -E "s/ matched .*/ matched (text omitted)/" >"$ev/scenario_failures.txt" || true
skeleton "$ev/scenarios_raw.txt" "$ev/failure_skeletons.txt"
note "voice: scenarios $(tr '\n' ' ' <"$ev/scenarios.txt")"
if [ ! -s "$ev/scenarios.txt" ]; then
  # The runner printed no result: keep its last lines without any CJK text (tracebacks, adb errors).
  grep -v -P '[\x{3000}-\x{9fff}\x{ff00}-\x{ffef}]' "$ev/scenarios_raw.txt" | tail -n 15 >"$ev/runner_tail.txt" || true
  note "voice: runner produced no results; adb devices: $(adb devices | tail -n +2 | tr '\n' ' ')"
fi
[ "$(count 'session_provider choice=qwen')" = 0 ] || qg_fail="$qg_fail qwen_session_main"
note "voice: session errors=$(count 'state=ERROR|_PROTOCOL_ERROR') provider_lines=$(nova | grep -oE 'session_provider choice=[a-z_]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ') quota_guard=$(grep -c 'QUOTA GUARD' "$ev/scenarios_raw.txt" 2>/dev/null || echo 0) codes=$(nova | grep -oE '\b(GEMINI|QWEN)_[A-Z_]+' | sort | uniq -c | sort -rn | head -n 5 | tr -s ' ' | tr '\n' ' ')"
adb pull /sdcard/voice_part1.mp4 "$ev/voice_part1_phone.mp4" >/dev/null 2>&1 || true
ffmpeg -v error -y -i "$ev/voice_part1_phone.mp4" -c copy -movflags +faststart "$ev/voice_screen.mp4" && rm -f "$ev/voice_part1_phone.mp4" || true
shot voice_end
rm -f "$ev/scenarios_raw.txt"
fi
tool voice stop
tool provider clear

# ---- further suites: device rows (affordances, news, vision, fuzzy with
# screenshots), live info x10 (LIVE-INFO-DEVICE-001 needs >=9/10 per kind: one kind per
# shard, ~8 min - the documented exception to the 5-minute target). Ids + PASS/FAIL only, as above.
suite() { # name, result prefix, then runner args
  local name="$1" prefix="$2" rc; shift 2
  set +e
  python3 tools/speech-harness/run_scenarios.py --wait 14 --provider gemini "$@" >"$ev/${name}_raw.txt" 2>&1
  rc=$?
  set -e
  note "suite $name: runner rc=$rc"
  scen "$ev/${name}_raw.txt" "$prefix" "$rc"
  grep -E '^\s+(ok|FLAKY|FAIL)\s+[A-Z][A-Z0-9-]*\s+[0-9]+/[0-9]+' "$ev/${name}_raw.txt" | sed -E 's/^\s+//' | awk '{print $1, $2, $3}' >"$ev/${name}.txt" || true
  grep -E '^\s+expected /|^\s+forbidden /' "$ev/${name}_raw.txt" | sed -E "s/ matched .*/ matched (text omitted)/" | sort | uniq -c >"$ev/${name}_failures.txt" || true
  skeleton "$ev/${name}_raw.txt" "$ev/${name}_skeletons.txt"
  note "suite $name: $(tr '\n' ' ' <"$ev/${name}.txt") guard=$(grep -c 'QUOTA GUARD' "$ev/${name}_raw.txt" || true)"
  rm -f "$ev/${name}_raw.txt"
}
# No Cantonese suite: the product is Mandarin-only (owner 2026-10-19, YUE-POLICY-001 option A, ADR-002).
# Prefix devrows: (not device:) so it never clashes with the "device" suite's ids.
if runs device; then
  suite device devrows: --file tools/speech-harness/scenarios-device.json --shots "$ev/shots"
  for f in "$ev"/shots/*.png; do [ -f "$f" ] && ffmpeg -v error -y -i "$f" -vf scale=540:-2 -q:v 4 "${f%.png}.jpg" && rm -f "$f"; done
fi
case "$shard" in
  all) suite liveinfo liveinfo: --file tools/speech-harness/scenarios-liveinfo.json --repeat 10 ;;
  li-weather) suite liveinfo liveinfo: --file tools/speech-harness/scenarios-liveinfo.json --repeat 10 --only LI-WEATHER ;;
  li-place) suite liveinfo liveinfo: --file tools/speech-harness/scenarios-liveinfo.json --repeat 10 --only LI-PLACE ;;
  li-traffic) suite liveinfo liveinfo: --file tools/speech-harness/scenarios-liveinfo.json --repeat 10 --only LI-TRAFFIC ;;
  li-along) suite liveinfo liveinfo: --file tools/speech-harness/scenarios-liveinfo.json --repeat 10 --only LI-ALONG ;;
esac

# ---- WAKE-SYNTH-001: a synthetic wake through the debug path ----------------------------------
# A stub build has no wake engine (tools/vendor-stub): the row is BLOCKED there, never run.
if ! runs demo; then
  :
elif [ "$(python3 -c 'import json,sys; print(any(json.loads(l).get("id")=="build:vendor-real" and json.loads(l).get("status")!="PASS" for l in open(sys.argv[1]) if l.strip()))' "$R")" = True ]; then
  res wake:WAKE-SYNTH-001 BLOCKED "STUB_VENDOR: no wake engine in a stub build"
else
  mark
  tool voice stop; sleep 3
  tool provider gemini
  tool wake on; sleep 2
  note "WAKE-SYNTH-001: wake on -> $(result_of wake) status: $(tool wake status; sleep 1; nova | grep 'debug_tool tool=wake arg=status' | tail -1 | grep -oE 'enabled=[a-z]+|credentials_complete=[a-z]+' | tr '\n' ' ')"
  adb push tools/speech-harness/speech/wake_phrase.pcm /sdcard/Android/data/com.novadrive.app/files/wake.pcm >/dev/null 2>&1 || true
  sleep 2
  tool wake inject; sleep 10
  wake_det="$(count 'wake_detection')"; wake_act="$(count 'DEEP_IDLE->ACTIVE|->ACTIVE reason=wake')"
  if [ "$wake_det" -ge 1 ] && [ "$wake_act" -ge 1 ]; then res wake:WAKE-SYNTH-001 PASS "detection=$wake_det to_active=$wake_act"
  else res wake:WAKE-SYNTH-001 FAIL "detection=$wake_det to_active=$wake_act"; fi
  note "WAKE-SYNTH-001: wake inject -> $(result_of wake) detection=$wake_det to_active=$wake_act provider=$(nova | grep -oE 'session_provider choice=[a-z_]+' | tail -1) wake_lines=$(nova | grep -oE 'wake_[a-z_]+' | sort | uniq -c | tr -s ' ' | tr '\n' ';')"
  if nova | grep -q 'session_provider choice=qwen'; then qg_fail="$qg_fail qwen_session_wake"; tool voice stop; echo "::error title=quota guard::wake opened a non-Gemini session; stopped"; fi
fi

# ---- the review demo: the real app, driver audio over the host bridge, her replies recorded -----
# Clips are the synthetic speech-harness lines. The bridge re-applies provider=gemini and stops if
# the session is not gemini_live (quota guard). Its stdout carries transcripts: not kept.
if runs demo; then
adb shell am force-stop com.novadrive.app
app_up || true
sleep 3
S="tools/speech-harness/speech"
clips="$S/help_gan_sha.pcm,$S/ctx_too_hot.pcm,$S/temp24.pcm,$S/nav_zhuhai.pcm,$S/pick_second.pcm,$S/music_on.pcm,$S/cancel.pcm,$S/volume_up.pcm"
mark
rec_start="$(date +%s.%N)"
adb shell screenrecord --time-limit 180 --bit-rate 4000000 /sdcard/voice_demo.mp4 &
rec2=$!
set +e
python3 tools/speech-harness/host_audio_bridge.py --from-file "$clips" --lead 8 --gap 11 --duration 150 \
  --no-play --provider gemini --record "$ev/demo_audio" >/dev/null 2>"$ev/bridge_err.txt"
bridge_rc=$?
set -e
wait "$rec2" || true  # best-effort: screen recording
note "demo: bridge rc=$bridge_rc"
[ "$(count 'session_provider choice=qwen')" = 0 ] || qg_fail="$qg_fail qwen_session_demo"
note "demo: provider=$(nova | grep -oE 'session_provider choice=[a-z_]+' | tail -1) tools=$(nova | grep -oE '^.*tool=[a-z_]+ ' | grep -oE 'tool=[a-z_]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ') errors=$(count 'state=ERROR|_PROTOCOL_ERROR')"
adb pull /sdcard/voice_demo.mp4 "$ev/voice_demo_screen.mp4" >/dev/null 2>&1 || true
if [ -f "$ev/demo_audio/t0.txt" ] && [ -f "$ev/voice_demo_screen.mp4" ]; then
  off="$(python3 -c "import sys; print(max(0.0, float(open(sys.argv[1]).read()) - float(sys.argv[2]) - 0.4))" "$ev/demo_audio/t0.txt" "$rec_start")"
  ffmpeg -v error -y -i "$ev/voice_demo_screen.mp4" -itsoffset "$off" -i "$ev/demo_audio/driver.wav" -itsoffset "$off" -i "$ev/demo_audio/reply.wav" \
    -filter_complex "[1:a]aresample=48000[d];[2:a]aresample=48000[r];[d][r]amix=inputs=2:normalize=0[a]" \
    -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 128k -shortest -movflags +faststart "$ev/voice_demo.mp4" \
    && note "demo: voice_demo.mp4 written (audio offset ${off} s)" || note "demo: mux failed"
  rm -f "$ev/voice_demo_screen.mp4"
  if [ -s "$ev/voice_demo.mp4" ]; then res voice:demo-recording PASS "voice_demo.mp4 written"
  else res voice:demo-recording FAIL "mux failed"; fi
else
  note "demo: no recording ($(tail -c 300 "$ev/bridge_err.txt" 2>/dev/null | tr '\n' ' '))"
  if [ "$bridge_rc" != 0 ]; then res voice:demo-recording FAIL "no recording, bridge rc=$bridge_rc"
  else res voice:demo-recording SKIPPED "no recording"; fi
fi
rm -f "$ev/bridge_err.txt"
fi
if [ "$choice" = "session_provider choice=gemini_live" ] && [ -z "$qg_fail" ]; then
  res voice:quota-guard PASS "gemini_live, no qwen session"
else
  res voice:quota-guard FAIL "${qg_fail# }"
fi
note "voice: shard $shard done, override cleared"
