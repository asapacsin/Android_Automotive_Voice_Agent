#!/bin/bash
# The review demo: the real app used as a driver would, on the GitHub emulator - ask for a
# destination by voice, pick it, start navigation, drive the simulated route to arrival with Amap
# guidance, and talk to her on the way. Her voice is Gemini's (debug override, quota guard: the
# bridge stops unless the session is gemini_live). Driver lines are synthetic (edge-tts). Output:
# evidence/nav_demo.mp4 (screen + driver + her replies + guidance voiced with edge-tts) and summary.txt
# (counts and codes only - no transcript, address or coordinate).
# Verdict: checks go to evidence/results.jsonl (scripts/ci_verdict.py, suite "navpiece"); the script
# writes evidence/verdict.json and exits with the verdict's code (0 pass, 1 fail, 3 blocked).
# Best-effort steps (screen recording, ffmpeg muxing, logcat clearing, launcher cosmetics) are
# marked "|| true" on purpose.
set -eu
apk="${1:?apk path}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
ev="$root/evidence"
mkdir -p "$ev"
summary="$ev/summary.txt"
: >"$summary"
R="$ev/results.jsonl"; : >"$R"
# The bridge's output files hold transcripts: never left behind, even on an abort.
trap 'rm -f "$ev"/bridge_out.txt "$ev"/bridge_err.txt' EXIT
trap 'exit 130' INT TERM  # a signal exits through the EXIT trap above
res() { python3 scripts/ci_verdict.py record --out "$R" --id "$1" --status "$2" --detail "${3:-}"; }
# The verdict for this run, written to evidence/verdict.json; the script exits with its code.
# complete() is recorded only right before a normal finish: an abort can never come out PASS.
complete() { res navpiece:complete PASS "${1:-run completed}"; }
finish() {
  local rc=0
  python3 scripts/ci_verdict.py verdict --manifest config/ci_checks.json --suite navpiece \
    --results "$R" --json-out "$ev/verdict.json" || rc=$?
  exit "$rc"
}
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
# The warm worker reuses one emulator: start every run with an empty log, or counts mix runs.
adb logcat -c || true
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
web_key=0; setup_key AMAP_WEB_KEY amap_web_setup amap_web_setup.txt && web_key=1 || true
# Every mode (routes, speedoff, the voice pieces) resolves named destinations via Amap web search.
if [ "$web_key" = 0 ]; then
  note "nav demo: blocked - no AMAP_WEB_KEY"
  res navpiece:session BLOCKED "secret AMAP_WEB_KEY not set"
  finish
fi
if ! setup_key GEMINI_API_KEY gemini_setup gemini_setup.txt; then
  note "nav demo: blocked - no GEMINI_API_KEY"
  res navpiece:session BLOCKED "secret GEMINI_API_KEY not set"
  finish
fi
# Piece "routes" (owner 2026-10-16: a 5-minute demo): route length and speed cameras per candidate
# destination, no driving, no voice model. Numbers only.
if [ "${NAV_DEMO_PIECE:-}" = "1" ] && [ "$(tr -dc a-z <scripts/nav_piece.txt 2>/dev/null)" = "routes" ]; then
  adb shell am force-stop com.novadrive.app; app_up || true; sleep 3
  routes_ok=0; routes_n=0
  dests=(珠海站 拱北口岸 珠海大剧院 九洲港 香洲港 珠海市人民医院 华发商都 横琴口岸)
  # Logged by index only (route 3/8), never the place name.
  for d in "${dests[@]}"; do
    mark; tool nav_route "$d"; routes_n=$((routes_n + 1))
    if wait_for "nav_calc_success" 60; then routes_ok=$((routes_ok + 1)); sleep 2; tool nav_route_info x; sleep 1; note "route $routes_n/${#dests[@]}: $(nova | grep 'debug_tool tool=nav_route_info ' | tail -1 | sed -E 's/.*result=//')"
    else note "route $routes_n/${#dests[@]}: no route"; fi
  done
  if [ "$routes_ok" = "$routes_n" ]; then res navpiece:routes PASS "routes=$routes_ok/$routes_n"
  else res navpiece:routes FAIL "routes=$routes_ok/$routes_n"; fi
  note "nav demo: done (routes piece)"
  complete "routes piece"
  finish
fi

# Piece "speedoff" (NAV-OVERSPEED-001): the shipped path - relay OFF, Amap's voice - at the real 60 km/h
# camera on the 珠海站 route (routes piece 4d09b8b), 120 km/h, no voice model. Codes and counts only.
if [ "${NAV_DEMO_PIECE:-}" = "1" ] && [ "$(tr -dc a-z <scripts/nav_piece.txt 2>/dev/null)" = "speedoff" ]; then
  tool guidance_relay off; adb shell am force-stop com.novadrive.app; app_up || true; sleep 3; mark
  tool nav_route 珠海站
  if wait_for "nav_calc_success" 60; then
    tool nav_start emulator:120; wait_for "nav_started" 30 || true
    i=0; while [ "$i" -lt 60 ]; do nova | grep -q "nav_overspeed_alert" && { sleep 25; break; }; nova | grep -q "nav_stopped" && break; i=$((i + 1)); sleep 5; done
    os_limits="$(nova | grep -oE 'nav_speed kmh=[0-9]+ limitKmh=[1-9][0-9]*' | grep -oE 'limitKmh=[0-9]+' | sort -u | tr '\n' ' ')"
    os_alerts="$(count 'nav_overspeed_alert')"; os_amap="$(count 'nav_alert_queued to=amap')"
    if [ -n "$os_limits" ] && [ "$os_alerts" -ge 1 ] && [ "$os_amap" -ge 1 ]; then
      res navpiece:NAV-OVERSPEED-001 PASS "limits=${os_limits% } alerts=$os_alerts queued_to_amap=$os_amap"
    else
      res navpiece:NAV-OVERSPEED-001 FAIL "limits=${os_limits:-none} alerts=$os_alerts queued_to_amap=$os_amap"
    fi
    note "NAV-OVERSPEED-001: queue=$(count 'nav_alert_queue installed=true') limits=$(nova | grep -oE 'nav_speed kmh=[0-9]+ limitKmh=[1-9][0-9]*' | grep -oE 'limitKmh=[0-9]+' | sort -u | tr '\n' ' ')over=$(count 'nav_speed .*over=1') alerts=$(count 'nav_overspeed_alert') queued: $(nova | grep -oE 'nav_alert_(queued to=[a-z]+|dropped reason=[a-z_]+)' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')routes: $(nova | grep -oE 'guidance_route prompt=g[0-9]+ to=[a-z]+ reason=[A-Z_]+' | sed -E 's/prompt=g[0-9]+ //' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')fallback_tts: $(nova | grep -oE 'nav_guidance_fallback_tts accepted=[a-z]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')play_start=$(count 'nav_guidance_play_start') uplink_closed=$(count 'speech_arbiter out=uplink decision=CLOSED')"
    tool nav_stop x; sleep 2
  else note "NAV-OVERSPEED-001: no route"; res navpiece:NAV-OVERSPEED-001 FAIL "no route"; fi
  note "nav demo: done (speedoff piece)"
  complete "speedoff piece"
  finish
fi

# P62 / GUIDANCE-EMU-001: guidance in her voice (SPEC-018 relay, ADR-014), Amap only as the fallback.
# 3000 ms is a measurement deadline (SPEC-018's default 1200 ms is set from G-1, never measured).
tool guidance_relay on:3000; sleep 1
note "guidance relay: $(result_of guidance_relay)"
adb shell am force-stop com.novadrive.app
app_up || true
sleep 3

# The 5-minute demo drives faster; decided before the speed-up loop below is forked.
[ "${NAV_DEMO_PIECE:-}" = "1" ] && [ "$(tr -dc a-z <scripts/nav_piece.txt 2>/dev/null)" = "demo" ] && DEMO_SPEED=120
# Screen: up to five back-to-back 180 s recordings (screenrecord's limit), stopped once the drive ends.
rec_start="$(date +%s.%N)"
(
  for i in 1 2 3 4 5 6; do
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
    if nova | grep -q "nav_started"; then sleep 8; tool nav_speed "${DEMO_SPEED:-90}"; break; fi
    sleep 3
  done
) &

S="tools/speech-harness/speech"
# P63: 十字门 (20 km; a 60 km/h limit on the way). 90 km/h: at 120 the prompts came so often that a driver
# line was cut (run 38853123513, P65); the bridge records 16 min so the drive arrives on video.
clips="$S/nav_shizimen.pcm,$S/pick_first.pcm,$S/start_nav.pcm,$S/ctx_too_hot.pcm,$S/music_on.pcm,$S/eta_q.pcm,$S/gas_nearby.pcm,$S/music_off.pcm"
# P65 (decider 2026-10-16): the driver waits (<= 10 s) for the uplink to open and for her to be quiet.
# Piece mode (NAV_DEMO_PIECE=1, owner rule 2026-10-16): the first six lines only, ~150 s, no arrival.
duration=960
# The running worker passes NAV_DEMO_PIECE=1; scripts/nav_piece.txt names which piece (default: lines).
if [ "${NAV_DEMO_PIECE:-}" = "1" ] && [ -s scripts/nav_piece.txt ]; then NAV_DEMO_PIECE="$(tr -dc a-z <scripts/nav_piece.txt)"; fi
if [ "${NAV_DEMO_PIECE:-}" = "demo" ]; then
  # The 5-minute demo (owner 2026-10-16): main driver functions and ambiguous commands, one drive.
  # 珠海站: ~9.9 km, a 60 km/h camera on the recommended route (routes piece 4d09b8b), 120 km/h.
  clips="$S/nav_demo_dest.pcm,$S/pick_first.pcm,$S/start_nav.pcm,$S/ctx_too_hot.pcm,$S/ctx_cooler.pcm,$S/music_on.pcm,$S/fz_mosquito.pcm,$S/fz_mosquito_done.pcm,$S/fz_seat_high.pcm,$S/fz_lower_more.pcm,$S/gas_nearby.pcm,$S/cancel.pcm,$S/eta_q.pcm,$S/music_off.pcm"
  duration=400; DEMO_GAP=12
  note "piece mode: 5-minute demo, ${duration} s"
elif [ "${NAV_DEMO_PIECE:-}" = "window" ]; then
  # Piece: the ambiguous window lines while navigating (5-minute demo 5224551: 有蚊子 was cut by a prompt).
  clips="$S/nav_demo_dest.pcm,$S/pick_first.pcm,$S/start_nav.pcm,$S/fz_mosquito.pcm,$S/fz_mosquito_done.pcm"; duration=120; DEMO_GAP=12
  note "piece mode: window lines while navigating, ${duration} s"
elif [ "${NAV_DEMO_PIECE:-}" = "gas" ]; then
  # Piece P61 in the real order: navigate, pick, start, then the gas question while driving.
  clips="$S/nav_shizimen.pcm,$S/pick_first.pcm,$S/start_nav.pcm,$S/gas_nearby.pcm"; duration=130
  note "piece mode: gas question while navigating, ${duration} s"
elif [ -n "${NAV_DEMO_PIECE:-}" ]; then
  clips="$(echo "$clips" | cut -d, -f1-6)"; duration=150
  note "piece mode: first 6 lines, ${duration} s"
fi
set +e
python3 tools/speech-harness/host_audio_bridge.py --from-file "$clips" --lead 8 --gap "${DEMO_GAP:-20}" --duration "$duration" --wait-quiet \
  --no-play --provider gemini --record "$ev/demo_audio" >"$ev/bridge_out.txt" 2>"$ev/bridge_err.txt"
bridge_rc=$?
set -e
if [ "$bridge_rc" = 0 ]; then res navpiece:bridge PASS "rc=0"; else res navpiece:bridge FAIL "rc=$bridge_rc"; fi
# Codes and numbers only from the bridge output (it also echoes app lines, which are never kept).
note "driver waits: $(grep -oE 'wait_quiet line=[0-9]+ ms=[0-9]+ cap=[01] reason=[a-z]+' "$ev/bridge_out.txt" | sed -E 's/wait_quiet //' | tr '\n' ' ')(the scripted driver reads the app's guidance log; a real driver cannot)"
rm -f "$ev/bridge_out.txt"
note "nav demo: provider=$(nova | grep -oE 'session_provider choice=[a-z_]+' | tail -1) started=$(count 'nav_started') stopped=$(nova | grep -oE 'nav_stopped reached=[a-z]+ reason=[a-z_]+' | tail -1) guidance=$(count 'nav_guidance_play_start') tools=$(nova | grep -oE '^tool=[a-z_]+|tool=[a-z_]+ args' | grep -oE 'tool=[a-z_]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ') errors=$(count 'state=ERROR|_PROTOCOL_ERROR')"
nd_choice="$(nova | grep -oE 'session_provider choice=[a-z_]+' | tail -1)"; nd_qwen="$(count 'session_provider choice=qwen')"
if [ "$nd_choice" = "session_provider choice=gemini_live" ] && [ "$nd_qwen" = 0 ]; then res navpiece:quota-guard PASS "gemini_live, no qwen session"
else res navpiece:quota-guard FAIL "provider=${nd_choice#session_provider choice=} qwen_lines=$nd_qwen"; fi
nd_started="$(count 'nav_started')"
if [ "$nd_started" -ge 1 ]; then res navpiece:nav-started PASS "started=$nd_started"; else res navpiece:nav-started FAIL "started=0"; fi
# Let the drive reach the destination if it has not yet.
# Pieces stop early; the full drives (and the 5-minute demo) wait for arrival.
{ [ -z "${NAV_DEMO_PIECE:-}" ] || [ "${NAV_DEMO_PIECE:-}" = "demo" ]; } && for _ in $(seq 1 24); do nova | grep -q "nav_stopped" && break; sleep 5; done
# P62 relay: who spoke each guidance sentence, and how long until her audio (codes and ms only).
note "relay: $(nova | grep -oE 'nav_guidance_relay [a-z_]+=[a-z0-9_]+' | sort -u | tr '\n' ' ')routes: $(nova | grep -oE 'guidance_route prompt=g[0-9]+ to=[a-z]+ reason=[A-Z_]+' | sed -E 's/prompt=g[0-9]+ //' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')"
note "relay claims: assistant_waited_ms=$(nova | grep -oE 'guidance_claim prompt=g[0-9]+ side=assistant waited_ms=[0-9]+' | grep -oE '[0-9]+$' | tr '\n' ',') amap=$(count 'guidance_claim .* side=amap') fidelity: $(nova | grep -oE 'guidance_fidelity prompt=g[0-9]+ result=[A-Z_]+' | grep -oE 'result=[A-Z_]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')fallback_tts: $(nova | grep -oE 'nav_guidance_fallback_tts accepted=[a-z]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')echo_dropped=$(count 'guidance_echo_dropped')"
# P63: limits seen, time over, warnings spoken, Amap rings.
note "overspeed: limits=$(nova | grep -oE 'nav_speed kmh=[0-9]+ limitKmh=[0-9]+' | grep -oE 'limitKmh=[0-9]+' | sort -u | tr '\n' ' ')over_lines=$(count 'nav_speed .* over=1') alerts=$(count 'nav_overspeed_alert') queued: $(nova | grep -oE 'nav_alert_(queued to=[a-z]+|dropped reason=[a-z_]+)' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')rings=$(count 'nav_play_ring')"
note "listening: $(nova | grep -oE 'listening [A-Z_]+->[A-Z_]+ reason=[a-z_]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')uplink_closed=$(count 'speech_arbiter out=uplink decision=CLOSED') droppedGuidance=$(nova | grep -oE 'droppedGuidance=[0-9]+' | tail -1)"
note "P61 guard: $(nova | grep -oE 'guard_nearby_nav decision=[a-z_]+ cur=[0-9]+ cap=[0-9]+' | tr '\n' ' ')refused=$(count 'NEARBY_QUESTION_WHILE_NAVIGATING') nav_stopped: $(nova | grep -oE 'nav_stopped reached=[a-z]+ reason=[a-z_]+' | tr '\n' ' ')tools_in_order: $(nova | grep -oE 'tool=[a-z_]+ args=\[[^]]*\] result=[^ ]+' | sed -E 's/args=\[[^]]*\] //' | uniq | tr '\n' ' ')guard_vague_exit: $(nova | grep -oE 'guard_vague_exit decision=[a-z_]+' | tr '\n' ' ')"
note "gemini closes: $(nova | grep -oE 'gemini_closed code=[0-9]+ reason_class=[a-z_]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')"
sleep 8
touch "$ev/stop_recording"
adb shell pkill -INT screenrecord || true
wait "$recorder" || true
sleep 2
: >"$ev/segs.txt"
for i in 1 2 3 4 5 6; do
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
if [ -s "$ev/nav_demo.mp4" ]; then res navpiece:video PASS "nav_demo.mp4 written"; else res navpiece:video FAIL "nav_demo.mp4 not written"; fi
nd_fatal="$(adb logcat -d | grep -cE 'FATAL EXCEPTION|Fatal signal' || true)"
nd_anr="$(adb logcat -d | grep -cE 'ANR in com.novadrive' || true)"
if [ "$nd_fatal" = 0 ] && [ "$nd_anr" = 0 ]; then res navpiece:no-crash PASS "fatal=0 anr=0"
else res navpiece:no-crash FAIL "fatal=$nd_fatal anr=$nd_anr"; fi
rm -f "$ev"/seg*.mp4 "$ev/screen.mp4" "$ev/segs.txt" "$ev/stop_recording" "$ev/bridge_err.txt"
shot nav_demo_end
tool voice stop
tool provider clear
tool guidance_relay off
note "nav demo: done"
complete "voice piece"
finish
