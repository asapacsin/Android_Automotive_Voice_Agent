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
# Shards (DEV-SPLIT): DEVICE_SHARD picks the sections to run - dispatch, drive, lifecycle or
# navpiece (device-checks.yml runs one shard per emulator); unset or "all" runs every section in
# the original order. The setup above the sections (install, launch helpers, origin) is shared.
# Every section starts with `if in_shard <shard>; then` (scripts/tests/test_device_shards.py).
# Verdict: each row is recorded in evidence/results.jsonl (scripts/ci_verdict.py, suite "device");
# the workflow's Verdict step fails the job on a required failure. Best-effort steps (screen
# recording, ffmpeg, logcat clearing, launcher cosmetics) are marked "|| true" on purpose.
set -eu
apk="${1:?apk path}"
shard="${DEVICE_SHARD:-}"
case "$shard" in
  ""|all|dispatch|drive|lifecycle|navpiece) ;;
  *) echo "::error title=device checks::unknown DEVICE_SHARD"; exit 2 ;;
esac
in_shard() { [ -z "$shard" ] || [ "$shard" = all ] || [ "$shard" = "$1" ]; }
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
# A shard that skipped the dispatch section installs the web key itself (P59 and the nav pieces
# search places, as in a full run where the dispatch section already installed it).
ensure_web_key() {
  [ -n "${web_key:-}" ] && return 0
  app_up || true
  web_key=0; setup_key AMAP_WEB_KEY amap_web_setup amap_web_setup.txt && web_key=1 || true
}

# ---- dispatch rows -------------------------------------------------------------------------
if in_shard dispatch; then
mark
if app_up; then note "app: map up"; res device:app-up PASS "map up"
else note "app: map did not come up"; res device:app-up FAIL "map did not come up"; fi
shot app_start
web_key=0; setup_key AMAP_WEB_KEY amap_web_setup amap_web_setup.txt && web_key=1 || true
# PIECE P53: the real app's place-search time, cold then warm (debug preflight; counts and ms only, no place).
tool preflight "横琴镇"; sleep 10
note "PIECE P53 search: $(nova | grep -oE 'preflight_search count=[0-9]+ first_m=-?[0-9]+ ms=[0-9]+' | sed -E 's/first_m=-?[0-9]+ //' | tail -1) $(nova | grep -oE 'preflight_search_warm (runs=[0-9]+ )?ms=[0-9,]+' | tail -1)"
# P53 split (debug preflight, last warm run): one line per HTTP call - REST path and phase ms only.
nova | grep -oE 'preflight_split call=[0-9]+ path=/[A-Za-z0-9/_]+ dns_ms=-?[0-9]+ connect_ms=-?[0-9]+ tls_ms=-?[0-9]+ ttfb_ms=-?[0-9]+ body_ms=-?[0-9]+ reused=(true|false)( protocol=[A-Za-z0-9./_-]+ conn_close=(true|false) gap_ms=-?[0-9]+)?' | tail -8 | while read -r l; do note "PIECE P53 split: $l"; done
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
fi

# ---- simulated drive to arrival ------------------------------------------------------------
if in_shard drive; then
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
# NAV-MID-ROUTE-001: drive above the route's speed-camera limits (40-60 km/h) so the overspeed ring shows.
tool nav_speed 80
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
mr_over="$(count 'nav_speed .*over=1')"; mr_ring="$(count 'nav_play_ring')"
pf device:NAV-MID-ROUTE-OVERSPEED "speed_over=$mr_over ring=$mr_ring" '[ "$mr_over" -ge 1 ]'
# NAV-UI-002..006 from lines already collected: the P58 view notes (mode, bearing numbers) and the counters above.
ui_modes="$(grep -E '^P58 view ' "$summary" | grep -oE 'naviMode=[a-z_0-9]+' | sort | uniq -c | tr -s ' ' | tr '\n' ',')"
ui_views="$(grep -cE '^P58 view .*naviMode=' "$summary" || true)"
ui_carup="$(grep -cE '^P58 view [^:]*: naviMode=car_up ' "$summary" || true)"
ui_bearings="$(grep -E '^P58 view ' "$summary" | grep -oE 'bearing=[0-9.-]+' | cut -d= -f2 | sort -u | tr '\n' ' ')"
ui_distinct="$(wc -w <<<"$ui_bearings" | tr -d ' ')"
ui_man="$(count 'nav_maneuver ')"; ui_lane="$(count 'nav_lane_info shown=true')"; ui_junc="$(count 'nav_junction shown=true')"; ui_traf="$(count 'nav_traffic_status_update')"
pf device:NAV-UI-002 "view_lines=$ui_views car_up=$ui_carup modes=$ui_modes" '[ "$ui_views" -ge 3 ] && [ "$ui_carup" = "$ui_views" ]'
pf device:NAV-UI-003 "distinct_bearings=$ui_distinct bearings=$ui_bearings" '[ "$ui_distinct" -ge 3 ] && [ "$(tr -d "0 ." <<<"$ui_bearings")" != "" ]'
pf device:NAV-UI-005 "maneuver=$ui_man" '[ "$ui_man" -ge 1 ]'
if [ "$ui_traf" -ge 1 ]; then res device:NAV-UI-004 PASS "traffic_update=$ui_traf"; else res device:NAV-UI-004 SKIPPED "Amap supplied no traffic status"; fi
if [ $((ui_lane + ui_junc)) -ge 1 ]; then res device:NAV-UI-006 PASS "lane_shown=$ui_lane junction_shown=$ui_junc"; else res device:NAV-UI-006 SKIPPED "Amap supplied no lane or junction"; fi
stopped="$(nova | grep -oE 'nav_stopped reached=(true|false) reason=[a-z_]+' | tail -1)"
prox="$(count 'nav_proximity_arrival')"
note "NAV-E2E-ARRIVAL-001: proximity_arrival=$prox stopped=$stopped"
# reached=true is also logged on a manual stop: arrival needs emulator_end or a proximity arrival.
pf device:NAV-E2E-ARRIVAL-001 "proximity_arrival=$prox ${stopped:-no nav_stopped}" 'grep -q "nav_stopped reached=true" <<<"$stopped" && { grep -q "reason=emulator_end" <<<"$stopped" || [ "$prox" -ge 1 ]; }'
fatal="$(app_fatal)"
anr="$(app_anr)"
note "NAV crash check: fatal=$fatal anr=$anr"
pf device:NAV-CRASH-001 "fatal=$fatal anr=$anr" '[ "$fatal" = 0 ] && [ "$anr" = 0 ]'
fi

# ---- LIVE-INFO-SDK through the navigation controller -------------------------------------------
# Needs no drive state (it routes by place search with the web key from the dispatch section), so
# it runs in the dispatch shard; in a full run it still follows the drive.
if in_shard dispatch; then
# LIVE-INFO-L6 (SDK half, emulator) through the app's own navigation controller, as the model would
# reach it (the debug nav_route/nav_start above bypass the controller, so query_live_info answers
# NOT_NAVIGATING there - run 20261020_0928). navigate_to -> pick the first place (routes on screen):
# along_route (RoutePOISearch); then pick the first route (guidance): route_traffic
# (AMapNavi.getTrafficStatuses). ok/code/ms only (I-8). Optional: the emulator may get no data.
tool dispatch "navigate_to:destination=珠海站"; sleep 8
tool dispatch "choose_navigation_option:index=1"; sleep 10
tool dispatch "query_live_info:kind=along_route,category=fuel"; sleep 8
li_along="$(nova | grep -oE 'live_info kind=along_route ok=(true|false) code=[A-Z_a-z-]* ms=[0-9]+' | tail -1)"
note "LIVE-INFO-SDK along_route: ${li_along:-no live_info line} dispatch $(result_of dispatch)"
li_search="$(nova | grep -oE 'along_route_search code=-?[0-9]+ raw=-?[0-9]+ mapped=[0-9]+ dropped_no_point=[0-9]+ dropped_other=[0-9]+ range_m=[0-9]+ route_m=-?[0-9]+' | tail -1)"
note "PIECE LIVE-INFO-SDK search: ${li_search:-no along_route_search line}"
pf device:LIVE-INFO-SDK-ALONG "${li_along:-no live_info line}" 'grep -q "ok=true" <<<"$li_along"'
# ALONG-PROBE: fuel stations on the same route at 250/500/1000/2000 m (counts only); notes, no pf.
tool along_probe x
ap_n=0
for _ in $(seq 1 45); do
  ap_n="$(nova | grep -cE 'along_probe (range_m|form)=' || true)"
  [ "$ap_n" -ge 7 ] && break
  sleep 1
done
note "PIECE ALONG-PROBE: dispatch $(result_of along_probe)"
[ "$ap_n" -ge 7 ] || note "PIECE ALONG-PROBE: only $ap_n of 7 lines"
nova | grep -oE 'along_probe range_m=[0-9]+ code=-?[0-9]+ raw=-?[0-9]+ ms=[0-9]+' | tail -4 | while read -r l; do note "PIECE ALONG-PROBE: $l"; done
nova | grep -oE 'along_probe form=polyline range_m=[0-9]+ code=-?[0-9]+ raw=-?[0-9]+ points=[0-9]+' | tail -1 | while read -r l; do note "PIECE ALONG-PROBE: $l"; done
nova | grep -oE 'along_probe form=startend mode=[0-9]+ range_m=[0-9]+ code=-?[0-9]+ raw=-?[0-9]+' | tail -1 | while read -r l; do note "PIECE ALONG-PROBE: $l"; done
nova | grep -oE 'along_probe form=web_around radius_m=[0-9]+ count=-?[0-9]+' | tail -1 | while read -r l; do note "PIECE ALONG-PROBE: $l"; done
tool dispatch "exit_navigation_mode"; sleep 4
tool dispatch "navigate_to:destination=珠海站"; sleep 8
tool dispatch "choose_navigation_option:index=1"; sleep 10
tool dispatch "choose_navigation_option:index=1"; sleep 12
guiding="$(nova | grep -cE 'nav_started' || true)"
tool dispatch "query_live_info:kind=route_traffic"; sleep 6
li_traffic="$(nova | grep -oE 'live_info kind=route_traffic ok=(true|false) code=[A-Z_a-z-]* ms=[0-9]+' | tail -1)"
note "LIVE-INFO-SDK route_traffic: ${li_traffic:-no live_info line} nav_started_lines=$guiding dispatch $(result_of dispatch)"
pf device:LIVE-INFO-SDK-TRAFFIC "${li_traffic:-no live_info line}" 'grep -q "ok=true" <<<"$li_traffic"'
tool dispatch "exit_navigation_mode"; sleep 4
fi

# ---- P59: a cancelled route choice leaves no route drawn, and the next search still draws -------
# The voice flow, by dispatch: navigate_to -> candidates -> choose index 2 -> routes -> exit (the
# cancel the model calls for 算了). Fixed 2026-10-19: route drawing follows the map presentation
# (MapPresentationPolicy.drawsRoute; the experiment rounds of runs 39177948610 and run_20261019_0423
# showed setAutoDrawRoute(false) removes the line). The check asserts each round calculated a route,
# previewed with autoDraw=true and went idle with autoDraw=false; the screenshots p59_<round>_routes
# (route drawn) and p59_<round>_cancelled (no line) are the visual proof and are read by a person.
# The debug nav_route path above calculates without the controller, so nav_route_preview shows no
# route line since this fix (it never reaches the PREVIEW presentation).
if in_shard lifecycle; then
p59_round() { # round name
  mark
  tool dispatch "navigate_to:destination=珠海站"; sleep 8
  tool dispatch "choose_navigation_option:index=2"; sleep 10
  shot "p59_${1}_routes"
  tool dispatch "exit_navigation_mode"; sleep 4
  shot "p59_${1}_cancelled"
  local calc; calc="$(count 'nav_calc_success')"
  [ "$calc" -ge 1 ] || p59_bad=$((p59_bad + 1))
  # The fix's own log: the picker drew with autoDraw on, the cancel turned it off.
  [ "$(count 'nav_presentation mode=preview.*autoDraw=true')" -ge 1 ] || p59_bad=$((p59_bad + 1))
  [ "$(count 'nav_presentation mode=idle.*autoDraw=false')" -ge 1 ] || p59_bad=$((p59_bad + 1))
  note "P59 $1: candidates=$(count 'nav_resolve_candidates') calc=$calc cancelled=$(count 'nav_flow_cancelled') idle_off=$(count 'nav_presentation mode=idle.*autoDraw=false') preview_on=$(count 'nav_presentation mode=preview.*autoDraw=true')"
}
ensure_web_key
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
fi

# ---- process death -------------------------------------------------------------------------
if in_shard lifecycle; then
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
fi

# ---- revoked microphone --------------------------------------------------------------------
if in_shard lifecycle; then
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
fi

# ---- transcript bubble probe ---------------------------------------------------------------
if in_shard lifecycle; then
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
fi

# ---- VLC hand-off (MUSIC-VLC-EMU-001, SPEC-017 A6) ------------------------------------------
# Pinned VLC for Android x86_64 from VideoLAN (sha256 = the published .sha256), downloaded at run
# time and never committed. Two generated sine tones with test-only tags (no copyrighted media).
# Notes carry codes and counts only; the only title compared is the generated test title (I-8).
if in_shard lifecycle; then
vlc_url="https://download.videolan.org/pub/videolan/vlc-android/3.6.5/VLC-Android-3.6.5-x86_64.apk"
vlc_sha="d7049239374c91c11b143bb517fcd8210b85281480d903087cac90ebbab09ec2"
vlc_listener="com.novadrive.app/com.novadrive.app.media.NovaMediaListener"
vlc_apk="$(mktemp -d)/vlc.apk"
vlc_ok=no
if curl -fsSL --retry 3 -o "$vlc_apk" "$vlc_url" && echo "$vlc_sha  $vlc_apk" | sha256sum -c --status; then
  if adb install -r -g "$vlc_apk" >/dev/null 2>&1; then vlc_ok=yes; fi
fi
if [ "$vlc_ok" != yes ]; then
  note "MUSIC-VLC-EMU-001: VLC download, checksum or install failed"
  res device:MUSIC-VLC-HANDOFF BLOCKED "vlc_unavailable"
  res device:MUSIC-VLC-NOLISTENER BLOCKED "vlc_unavailable"
else
  vlc_media="$(mktemp -d)"
  python3 tools/ci-media/make_tagged_tones.py "$vlc_media" >/dev/null
  adb shell mkdir -p /sdcard/Music
  for f in "$vlc_media"/*.mp3; do
    adb push "$f" /sdcard/Music/ >/dev/null
    adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Music/$(basename "$f")" >/dev/null || true
  done
  # API 34 ignores MEDIA_SCANNER_SCAN_FILE from the shell: ask MediaProvider to scan the volume.
  adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
  vlc_indexed="$(adb shell content query --uri content://media/external/audio/media --projection title --where "title\ LIKE\ \'NovaTestTone%\'" 2>/dev/null | grep -c '^Row:' || true)"
  adb shell pm grant org.videolan.vlc android.permission.READ_MEDIA_AUDIO >/dev/null 2>&1 || true
  # VLC builds its own media library on first launch: open it once, then leave it.
  adb shell monkey -p org.videolan.vlc -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  sleep 30
  adb shell am force-stop org.videolan.vlc || true
  note "MUSIC-VLC-EMU-001 setup: mediastore_test_rows=$vlc_indexed"
  # 1) listener granted: status=playing, matches_request=true, the session names the test file.
  adb shell cmd notification allow_listener "$vlc_listener" || true
  adb shell am force-stop com.novadrive.app
  mark
  app_up || true
  tool dispatch "play_music:title=NovaTestToneA"
  wait_for "debug_tool tool=dispatch arg=play_music[^ ]* deferred_result=" 20 || true
  vh_res="$(nova | grep -oE 'deferred_result=.*' | tail -1 | grep -oE 'ok=[a-z]+|status=[a-z_]+|error=[A-Za-z_]+|matches_request=[a-z]+' | tr '\n' ' ')"
  vh_verify="$(nova | grep -oE 'music_verify result=[a-z_]+ match=[a-z]+' | tail -1)"
  vh_named="$(adb shell dumpsys media_session | grep -c 'NovaTestToneA' || true)"
  vh_sessions="$(adb shell dumpsys media_session | grep -cE '^ +[^ ].* package=' || true)"
  vh_vlc_sessions="$(adb shell dumpsys media_session | grep -c 'package=org.videolan.vlc' || true)"
  sleep 3; shot vlc_handoff
  vh_front="$(adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | grep -c 'com.novadrive.app' || true)"
  note "MUSIC-VLC-EMU-001 handoff: $vh_res verify=[$vh_verify] session_names_file=$vh_named sessions=$vh_sessions vlc_sessions=$vh_vlc_sessions mediastore_test_rows=$vlc_indexed nova_foreground=$vh_front"
  pf device:MUSIC-VLC-HANDOFF "$vh_res named=$vh_named front=$vh_front" 'grep -q "status=playing" <<<"$vh_res" && grep -q "matches_request=true" <<<"$vh_res" && [ "$vh_named" -ge 1 ]'
  adb shell am force-stop org.videolan.vlc || true
  # 2) listener revoked: status=requested_unverified (no song named).
  adb shell cmd notification disallow_listener "$vlc_listener" || true
  adb shell am force-stop com.novadrive.app
  mark
  app_up || true
  tool dispatch "play_music:title=NovaTestToneB"
  wait_for "debug_tool tool=dispatch arg=play_music[^ ]* deferred_result=" 20 || true
  vn_res="$(nova | grep -oE 'deferred_result=.*' | tail -1 | grep -oE 'ok=[a-z]+|status=[a-z_]+|matches_request=[a-z]+' | tr '\n' ' ')"
  note "MUSIC-VLC-EMU-001 no listener: $vn_res"
  pf device:MUSIC-VLC-NOLISTENER "$vn_res" 'grep -q "status=requested_unverified" <<<"$vn_res"'
  adb shell am force-stop org.videolan.vlc || true
  adb shell am force-stop com.novadrive.app
fi
fi

# ---- Nav demo piece checks (owner rule 2026-10-16: test each piece before a full demo run) -----
# Relay off (the shipped path). No voice model is opened.
if in_shard navpiece; then
ensure_web_key
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
fi

ls -1 "$ev" | tee -a "$summary" >/dev/null
echo "device checks finished"
