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
set -eu
apk="${1:?apk path}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
ev="$root/evidence"
mkdir -p "$ev"
summary="$ev/summary.txt"
: >"$summary"
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
app_up && note "app: map up" || note "app: map did not come up"
shot app_start
web_key=0; setup_key AMAP_WEB_KEY amap_web_setup amap_web_setup.txt && web_key=1 || true
gemini_key=0; setup_key GEMINI_API_KEY gemini_setup gemini_setup.txt && gemini_key=1 || true
tool dispatch "navigate_to:destination=回家"; sleep 4
note "PLACE-UNSET-001: navigate_to 回家 before a home is saved -> $(result_of dispatch) asks_for_address=$(nova | grep 'debug_tool tool=dispatch ' | tail -1 | grep -q '地址' && echo yes || echo no)"
tool dispatch "save_place:slot=home,address=珠海站"; sleep 8
note "PLACE-SAVE-001: save_place home -> $(result_of dispatch) saved_place_set_slot_home=$(count 'saved_place_set slot=HOME')"
dialer_before="$(adb shell dumpsys activity activities | grep -c 'com.android.dialer' || true)"
tool dispatch "place_call:contact=张三"; sleep 4
dialer_after="$(adb shell dumpsys activity activities | grep -c 'com.android.dialer' || true)"
note "CALL-NOSIM-001: place_call -> $(result_of dispatch) dialer_activities_before=$dialer_before after=$dialer_after (the emulator has a simulated SIM)"
adb shell am start -W -n com.novadrive.app/.MainActivity >/dev/null
for app in settings maps; do
  tool open_app "$app"; sleep 3
  note "APPS-001: open_app $app -> $(result_of open_app)"
  adb shell am start -W -n com.novadrive.app/.MainActivity >/dev/null
done
tool dispatch "open_app:app=music"; sleep 3
note "APPS-001: dispatch open_app app=music (outside the allow-list) -> $(result_of dispatch)"
adb shell am start -W -n com.novadrive.app/.MainActivity >/dev/null

# ---- simulated drive to arrival ------------------------------------------------------------
mark
app_up || true
adb shell screenrecord --time-limit 180 --bit-rate 6000000 /sdcard/checks_drive.mp4 &
recorder=$!
tool nav_route "$dest"
wait_for "nav_calc_success" 90 && note "NAV: route calculated" || note "NAV: route failed $(nova | grep -oE 'nav_calc_failure code=-?[0-9]+' | tail -1)"
shot nav_route_preview
tool nav_start emulator:50
wait_for "nav_started" 30 && note "NAV: navigation started (emulator 50 km/h)" || note "NAV: navigation did not start"
view() { tool nav_view_state x; sleep 1; note "P58 view $1: $(nova | grep 'debug_tool tool=nav_view_state ' | tail -1 | grep -oE 'naviMode=[a-z_0-9]+ bearing=[0-9.-]+ tilt=[0-9.-]+')"; }
sleep 10; shot nav_t10; view t10
sleep 10; shot nav_t20; view t20
sleep 15; shot nav_t35; view t35
tool nav_overview overview; sleep 3; shot nav_overview
note "NAV-UI-007: overview -> $(result_of nav_overview)"
sleep 5
tool nav_lock lock; sleep 3; shot nav_lock_restored
note "NAV-UI-007: lock -> $(result_of nav_lock)"
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
note "NAV-E2E-ARRIVAL-001: proximity_arrival=$(count 'nav_proximity_arrival') stopped=$(nova | grep -oE 'nav_stopped reached=(true|false) reason=[a-z_]+' | tail -1)"
note "NAV crash check: fatal=$(adb logcat -d | grep -cE 'FATAL EXCEPTION|Fatal signal' || true) anr=$(adb logcat -d | grep -cE 'ANR in com.novadrive' || true)"

# ---- P59: does a cancelled route stay drawn, and which call removes it -------------------------
# The voice flow, by dispatch: navigate_to -> candidates -> choose index 2 -> routes -> exit
# (the cancel the model calls for 算了), then one removal variant per round, screenshot each.
p59_round() { # variant
  mark
  tool dispatch "navigate_to:destination=珠海站"; sleep 8
  tool dispatch "choose_navigation_option:index=2"; sleep 10
  shot "p59_${1}_routes"
  tool dispatch "exit_navigation_mode"; sleep 4
  shot "p59_${1}_cancelled"
  if [ "$1" != "none" ]; then tool nav_clear_try "$1"; sleep 3; shot "p59_${1}_after"; fi
  note "P59 $1: candidates=$(count 'nav_resolve_candidates') calc=$(count 'nav_calc_success') cancelled=$(count 'nav_flow_cancelled') clear=$(nova | grep 'debug_tool tool=nav_clear_try' | tail -1 | grep -oE 'result=[a-zA-Z_ =]+' | head -1)"
}
if [ "${web_key:-0}" = 1 ]; then
  adb shell am force-stop com.novadrive.app; app_up || true
  for v in none stopnavi markers idle map_clear; do p59_round "$v"; done
  # After map_clear the next route must still draw.
  p59_round none2
fi

# ---- process death -------------------------------------------------------------------------
mark
app_up || true
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am kill com.novadrive.app || true
sleep 2
note "PROCDEATH-001: after am kill pid=$(adb shell pidof com.novadrive.app || echo none)"
app_up && note "PROCDEATH-001: relaunched, map up" || note "PROCDEATH-001: relaunch did not reach the map"
sleep 5; shot procdeath_relaunched
note "PROCDEATH-001: fatal=$(adb logcat -d | grep -cE 'FATAL EXCEPTION.*|Process: com.novadrive' || true) wake=$(nova | grep -oE 'wake_[a-z_]+' | sort | uniq -c | tr -s ' ' | tr '\n' ';') listening_claims=$(count 'state=LISTENING')"

# ---- revoked microphone --------------------------------------------------------------------
mark
adb shell pm revoke com.novadrive.app android.permission.RECORD_AUDIO || true
adb shell am force-stop com.novadrive.app
app_up || true
tool voice start; sleep 6
shot perm_denied
note "PERM-DENY-001: voice start -> $(result_of voice) mic_missing=$(count 'MicPermissionMissing|MIC_PERMISSION') wake_permission_failure=$(count 'wake_permission_failure') listening=$(count 'state=LISTENING') fatal=$(adb logcat -d | grep -c 'FATAL EXCEPTION' || true)"
adb shell pm grant com.novadrive.app android.permission.RECORD_AUDIO || true

# ---- transcript bubble probe ---------------------------------------------------------------
mark
adb shell am start -W -n com.novadrive.app/.TranscriptFadeProbeActivity --el speaking_ms 5000 --el held_ms 15000 >/dev/null
sleep 3; shot transcript_probe_shown
sleep 30; shot transcript_probe_after
note "TRANSCRIPT-FADE-EMU-001: probe $(nova | grep -oE 'transcript_bubble_(shown|faded)( since_line_ms=[0-9]+)?' | tr '\n' ' ')"
# The probe activity must not stay on top of the app (run 38753002305: every later screenshot was the probe).
adb shell am force-stop com.novadrive.app

ls -1 "$ev" | tee -a "$summary" >/dev/null
echo "device checks finished"
