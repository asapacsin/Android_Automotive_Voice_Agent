#!/bin/bash
# The autonomous device rows that need the real app but no voice session, on one emulator:
# dispatch checks (PLACE-UNSET-001, PLACE-SAVE-001, CALL-NOSIM-001, APPS-001), the transcript
# bubble probe (TRANSCRIPT-FADE-EMU-001), a simulated drive to arrival with screenshots
# (NAV-UI-002..007, NAV-MID-ROUTE-001, NAV-E2E-ARRIVAL-001), process death (PROCDEATH-001) and a
# revoked microphone (PERM-DENY-001).
#
# Evidence: evidence/<name>.jpg screenshots and evidence/summary.txt, both pushed by the workflow.
# summary.txt holds event names, counts, result codes and ok flags only - never a coordinate, an
# address, a place name or a transcript (AGENTS.md); the same lines go out as annotations.
# Verdict: each row is recorded in evidence/results.jsonl (scripts/ci_verdict.py, suite "device");
# the workflow's Verdict step fails the job on a required failure. Best-effort steps (screen
# recording, ffmpeg, logcat clearing, launcher cosmetics) are marked "|| true" on purpose.
set -eu
apk="${1:?apk path}"
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
# PASS/FAIL from a condition (a shell expression, evaluated as one unit): pf ID DETAIL 'COND'
# Conditions read only shell variables, so scripts/tests/test_ci_tier_b.py can replay them on real lines.
pf() { if eval "$3"; then res "$1" PASS "$2"; else res "$1" FAIL "$2"; fi; }
# App crashes only (another process's crash is not ours): the AndroidRuntime "Process:" line of a
# FATAL EXCEPTION, a native "Fatal signal ... (com.novadrive...)", an ANR in the app.
app_fatal() { adb logcat -d | grep -cE 'Process: com\.novadrive|Fatal signal .*\(com\.novadrive' || true; }
app_anr() { adb logcat -d | grep -cE 'ANR in com\.novadrive' || true; }
trap 'echo "::error title=device checks failed::line ${LINENO} exit $?"' ERR

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

# ---- dispatch rows -------------------------------------------------------------------------
mark
if app_up; then note "app: map up"; res device:app-up PASS "map up"
else note "app: map did not come up"; res device:app-up FAIL "map did not come up"; fi
shot app_start
web_key=0; setup_key AMAP_WEB_KEY amap_web_setup amap_web_setup.txt && web_key=1 || true
tool dispatch "navigate_to:destination=回家"; sleep 4
asks="$(nova | grep 'debug_tool tool=dispatch ' | tail -1 | grep -q '地址' && echo yes || echo no)"
note "PLACE-UNSET-001: navigate_to 回家 before a home is saved -> $(result_of dispatch) asks_for_address=$asks"
pf device:PLACE-UNSET-001 "asks_for_address=$asks" '[ "$asks" = yes ]'
tool dispatch "save_place:slot=home,address=珠海站"; sleep 8
saved="$(count 'saved_place_set slot=HOME')"
note "PLACE-SAVE-001: save_place home -> $(result_of dispatch) saved_place_set_slot_home=$saved"
if [ "$web_key" = 0 ]; then res device:PLACE-SAVE-001 BLOCKED "secret AMAP_WEB_KEY not set"
else pf device:PLACE-SAVE-001 "saved_place_set_slot_home=$saved" '[ "$saved" -ge 1 ]'; fi
dialer_before="$(adb shell dumpsys activity activities | grep -c 'com.android.dialer' || true)"
tool dispatch "place_call:contact=张三"; sleep 4
dialer_after="$(adb shell dumpsys activity activities | grep -c 'com.android.dialer' || true)"
call_res="$(result_of dispatch)"
# Optional (the emulator has a simulated SIM, no contacts): PASS is a graceful outcome - the call
# went out ("ok":true) or the dispatch refused with a code (e.g. CONTACT_NOT_FOUND) - and no crash.
call_fatal="$(app_fatal)"
pf device:CALL-NOSIM-001 "result=$call_res fatal=$call_fatal" '{ grep -q "\"ok\":true" <<<"$call_res" || grep -qE "\"error\":\"[A-Z_]+\"" <<<"$call_res"; } && [ "$call_fatal" = 0 ]'
note "CALL-NOSIM-001: place_call -> $call_res dialer_activities_before=$dialer_before after=$dialer_after (the emulator has a simulated SIM)"
adb shell am start -W -n com.novadrive.app/.MainActivity >/dev/null
apps_ok=0
for app in settings maps; do
  tool open_app "$app"; sleep 3
  app_res="$(result_of open_app)"
  note "APPS-001: open_app $app -> $app_res"
  grep -q 'result=Accepted' <<<"$app_res" && apps_ok=$((apps_ok + 1))
  adb shell am start -W -n com.novadrive.app/.MainActivity >/dev/null
done
tool dispatch "open_app:app=music"; sleep 3
music_res="$(result_of dispatch)"
note "APPS-001: dispatch open_app app=music (outside the allow-list) -> $music_res"
music_refused=no
grep -qE '"ok":false|"error":"[A-Z_]+"|Rejected\(' <<<"$music_res" && music_refused=yes
pf device:APPS-001 "allowed_ok=$apps_ok/2 music_refused=$music_refused" '[ "$apps_ok" = 2 ] && [ "$music_refused" = yes ]'
adb shell am start -W -n com.novadrive.app/.MainActivity >/dev/null

# ---- simulated drive to arrival ------------------------------------------------------------
mark
app_up || true
adb shell screenrecord --time-limit 180 --bit-rate 6000000 /sdcard/checks_drive.mp4 &
recorder=$!
tool nav_route "$dest"
if wait_for "nav_calc_success" 90; then note "NAV: route calculated"; res device:NAV-ROUTE-001 PASS "nav_calc_success"
else
  calc_fail="$(nova | grep -oE 'nav_calc_failure code=-?[0-9]+' | tail -1)"
  note "NAV: route failed $calc_fail"; res device:NAV-ROUTE-001 FAIL "no nav_calc_success ${calc_fail}"
fi
shot nav_route_preview
tool nav_start emulator:50
if wait_for "nav_started" 30; then note "NAV: navigation started (emulator 50 km/h)"; res device:NAV-START-001 PASS "nav_started"
else note "NAV: navigation did not start"; res device:NAV-START-001 FAIL "no nav_started"; fi
view() { tool nav_view_state x; sleep 1; note "P58 view $1: $(nova | grep 'debug_tool tool=nav_view_state ' | tail -1 | grep -oE 'naviMode=[a-z_0-9]+ bearing=[0-9.-]+ tilt=[0-9.-]+')"; }
sleep 10; shot nav_t10; view t10
sleep 10; shot nav_t20; view t20
sleep 15; shot nav_t35; view t35
tool nav_overview overview; sleep 3; shot nav_overview
ov_res="$(result_of nav_overview)"
note "NAV-UI-007: overview -> $ov_res"
sleep 5
tool nav_lock lock; sleep 3; shot nav_lock_restored
lock_res="$(result_of nav_lock)"
note "NAV-UI-007: lock -> $lock_res"
pf device:NAV-UI-007 "overview=$ov_res lock=$lock_res" 'grep -q "result=overview=true" <<<"$ov_res" && grep -q "result=lock=true" <<<"$lock_res"'
i=0
while [ "$i" -lt 60 ]; do
  if nova | grep -q "nav_stopped"; then break; fi
  if [ $((i % 6)) -eq 0 ]; then shot "nav_drive_$((i * 5))s"; view "drive_$((i * 5))s"; fi
  i=$((i + 1)); sleep 5
done
wait "$recorder" || true
sleep 3
adb pull /sdcard/checks_drive.mp4 "$ev/checks_drive_phone.mp4" >/dev/null || true
ffmpeg -v error -y -i "$ev/checks_drive_phone.mp4" -c copy -movflags +faststart "$ev/checks_drive.mp4" && rm -f "$ev/checks_drive_phone.mp4" || true
shot nav_end
note "NAV-MID-ROUTE-001 / NAV-UI-00x: maneuver=$(count 'nav_maneuver ') lane_shown=$(count 'nav_lane_info shown=true') junction_shown=$(count 'nav_junction shown=true') traffic_update=$(count 'nav_traffic_status_update') presentation_driving=$(count 'nav_presentation mode=driving') lights=$(count 'nav_remain_lights') cameras=$(count 'nav_camera_ahead') speed_over=$(count 'nav_speed .*over=1') ring=$(count 'nav_play_ring') guidance_play=$(count 'nav_guidance_play_start')"
stopped="$(nova | grep -oE 'nav_stopped reached=(true|false) reason=[a-z_]+' | tail -1)"
prox="$(count 'nav_proximity_arrival')"
note "NAV-E2E-ARRIVAL-001: proximity_arrival=$prox stopped=$stopped"
# reached=true is also logged on a manual stop: arrival needs emulator_end or a proximity arrival.
pf device:NAV-E2E-ARRIVAL-001 "proximity_arrival=$prox ${stopped:-no nav_stopped}" 'grep -q "nav_stopped reached=true" <<<"$stopped" && { grep -q "reason=emulator_end" <<<"$stopped" || [ "$prox" -ge 1 ]; }'
fatal="$(app_fatal)"
anr="$(app_anr)"
note "NAV crash check: fatal=$fatal anr=$anr"
pf device:NAV-CRASH-001 "fatal=$fatal anr=$anr" '[ "$fatal" = 0 ] && [ "$anr" = 0 ]'

# ---- P59: a cancelled route choice leaves no route drawn, and the next search still draws -------
# The voice flow, by dispatch: navigate_to -> candidates -> choose index 2 -> routes -> exit (the
# cancel the model calls for 算了). Fixed 2026-10-19: route drawing follows the map presentation
# (MapPresentationPolicy.drawsRoute; the experiment rounds of runs 39177948610 and run_20261019_0423
# showed setAutoDrawRoute(false) removes the line). Screenshots p59_<round>_cancelled are the visual
# proof; the check itself asserts that both rounds calculated a route and went idle.
p59_round() { # round name
  mark
  tool dispatch "navigate_to:destination=珠海站"; sleep 8
  tool dispatch "choose_navigation_option:index=2"; sleep 10
  shot "p59_${1}_routes"
  tool dispatch "exit_navigation_mode"; sleep 4
  shot "p59_${1}_cancelled"
  local calc; calc="$(count 'nav_calc_success')"
  [ "$calc" -ge 1 ] || p59_bad=$((p59_bad + 1))
  [ "$(count 'nav_presentation mode=idle')" -ge 1 ] || p59_bad=$((p59_bad + 1))
  note "P59 $1: candidates=$(count 'nav_resolve_candidates') calc=$calc cancelled=$(count 'nav_flow_cancelled') idle=$(count 'nav_presentation mode=idle') preview=$(count 'nav_presentation mode=preview')"
}
p59_bad=0
if [ "${web_key:-0}" = 1 ]; then
  adb shell am force-stop com.novadrive.app; app_up || true
  # The second round proves drawing comes back for the next route after a cancel turned it off.
  p59_round first
  p59_round second
  pf device:P59 "rounds_failed=$p59_bad/2" '[ "$p59_bad" = 0 ]'
else
  res device:P59 SKIPPED "secret AMAP_WEB_KEY not set"
fi

# ---- process death -------------------------------------------------------------------------
mark
app_up || true
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am kill com.novadrive.app || true
sleep 2
note "PROCDEATH-001: after am kill pid=$(adb shell pidof com.novadrive.app || echo none)"
mark  # the relaunch must log a fresh map line
pd_up=no
if app_up; then pd_up=yes; note "PROCDEATH-001: relaunched, map up"; else note "PROCDEATH-001: relaunch did not reach the map"; fi
sleep 5; shot procdeath_relaunched
pd_fatal="$(app_fatal)"
pf device:PROCDEATH-001 "map_up=$pd_up fatal=$pd_fatal" '[ "$pd_up" = yes ] && [ "$pd_fatal" = 0 ]'
note "PROCDEATH-001: fatal=$pd_fatal wake=$(nova | grep -oE 'wake_[a-z_]+' | sort | uniq -c | tr -s ' ' | tr '\n' ';') listening_claims=$(count 'state=LISTENING')"

# ---- revoked microphone --------------------------------------------------------------------
mark
adb shell pm revoke com.novadrive.app android.permission.RECORD_AUDIO || true
adb shell am force-stop com.novadrive.app
app_up || true
tool voice start; sleep 6
shot perm_denied
mic_missing="$(count 'MicPermissionMissing|MIC_PERMISSION')"; listening="$(count 'state=LISTENING')"
pm_fatal="$(app_fatal)"
note "PERM-DENY-001: voice start -> $(result_of voice) mic_missing=$mic_missing wake_permission_failure=$(count 'wake_permission_failure') listening=$listening fatal=$pm_fatal"
pf device:PERM-DENY-001 "mic_missing=$mic_missing listening=$listening fatal=$pm_fatal" '[ "$mic_missing" -ge 1 ] && [ "$listening" = 0 ] && [ "$pm_fatal" = 0 ]'
adb shell pm grant com.novadrive.app android.permission.RECORD_AUDIO || true

# ---- transcript bubble probe ---------------------------------------------------------------
mark
adb shell am start -W -n com.novadrive.app/.TranscriptFadeProbeActivity --el speaking_ms 5000 --el held_ms 15000 >/dev/null
sleep 3; shot transcript_probe_shown
sleep 30; shot transcript_probe_after
bubble="$(nova | grep -oE 'transcript_bubble_(shown|faded)( since_line_ms=[0-9]+)?' | tr '\n' ' ')"
note "TRANSCRIPT-FADE-EMU-001: probe $bubble"
bub_s="$(grep -o transcript_bubble_shown <<<"$bubble" | wc -l)"; bub_f="$(grep -o transcript_bubble_faded <<<"$bubble" | wc -l)"
pf device:TRANSCRIPT-FADE-EMU-001 "shown=$bub_s faded=$bub_f" '[ "$bub_s" -ge 1 ] && [ "$bub_f" -ge 1 ]'
# The probe activity must not stay on top of the app (run 38753002305: every later screenshot was the probe).
adb shell am force-stop com.novadrive.app

# ---- Nav demo piece checks (owner rule 2026-10-16: test each piece before a full demo run) -----
# Relay off (the shipped path). No voice model is opened.
mark
tool guidance_relay off
adb shell am force-stop com.novadrive.app
app_up || true
tool nav_route "$dest"
if wait_for "nav_calc_success" 90; then
  tool nav_start emulator:50
  p61_nav=no; wait_for "nav_started" 30 && p61_nav=yes
  sleep 10
  # Piece P61-after-restart: a voice-session stop clears the legacy flag (VoiceSessionController.stop);
  # the nearby question must still be refused while the map navigates.
  tool voice stop; sleep 2
  tool turn "附近有加油站吗"; sleep 1
  tool dispatch "navigate_to:destination=附近的加油站"; sleep 6
  p61_res="$(result_of dispatch)"; replaced="$(count 'nav_stopped .*reason=replaced')"
  note "PIECE P61 after session stop: dispatch -> $p61_res route_replaced=$replaced nav_stopped_lines=$(count 'nav_stopped')"
  if [ "$p61_nav" != yes ]; then res device:PIECE-P61 FAIL "not navigating"
  else pf device:PIECE-P61 "route_replaced=$replaced result=$p61_res" '[ "$replaced" = 0 ] && grep -qE "\"error\":\"NEARBY_QUESTION_WHILE_NAVIGATING\"|\"ok\":false" <<<"$p61_res"'; fi
  # Piece P63 speak path (relay off): the alert queue waits for Amap, plays it, gates the mic.
  tool speak_alert 60; sleep 8
  p63_amap="$(count 'nav_alert_queued to=amap')"
  note "PIECE P63 speak path: $(result_of speak_alert) queued: $(nova | grep -oE 'nav_alert_(queued to=[a-z]+|dropped reason=[a-z_]+)' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')routes: $(nova | grep -oE 'guidance_route prompt=g[0-9]+ to=[a-z]+ reason=[A-Z_]+' | sed -E 's/prompt=g[0-9]+ //' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')fallback_tts: $(nova | grep -oE 'nav_guidance_fallback_tts accepted=[a-z]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')brackets: $(nova | grep -oE 'speech_arbiter out=uplink decision=[A-Z]+' | sort | uniq -c | tr -s ' ' | tr '\n' ' ')"
  tool nav_stop x; sleep 3
  mark  # only the alert after the stop counts
  tool speak_alert 60; sleep 3
  p63_drop="$(nova | grep -oE 'nav_alert_dropped reason=[a-z_]+' | tail -1)"
  note "PIECE P63 after stop: $p63_drop"
  pf device:PIECE-P63 "queued_to_amap=$p63_amap after_stop=${p63_drop:-none}" '[ "$p63_amap" -ge 1 ] && [ "$p63_drop" = "nav_alert_dropped reason=not_navigating" ]'
else
  note "PIECE checks: route failed, not run"
  res device:PIECE-P61 FAIL "route failed"
  res device:PIECE-P63 FAIL "route failed"
fi

ls -1 "$ev" | tee -a "$summary" >/dev/null
echo "device checks finished"
