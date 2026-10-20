#!/bin/bash
# Runs ONE opt-in live JVM piece on a GitHub runner and pushes a text-free summary to branch
# ci/live-jvm-evidence under runs/run_<date>_<sha>[_r<GITHUB_RUN_ID>]/<piece>/.
#   text-live  NOVA_GEMINI_TEXT_LIVE=1 ... --tests '*GeminiTextLiveMapping*' --rerun
#              rows FUZZY-TEXT-LIVE-001 (>=17/19 and FZ-15..19 all correct) and
#              MUSIC-TEXT-LIVE-001 (>=8/10 play_music, 0/10 control_music{play})
#   amap-live  NOVA_LIVE_AMAP=1 ... --tests '*DemoCommuteAmapLiveTest'
#              row DEMO-AMAP-LIVE-001 (JUnit 2/2; the 1700 m check is one of the two tests)
#   tryout     Piper u_* clips (zh_CN-huayan-medium), then NOVA_LIVE_DEMO=1 NOVA_LIVE_PROVIDER=gemini
#              NOVA_LIVE_SCENES=tryout ... --tests '*LiveDemoRun*' (8-minute live timeout), then
#              tryout_check.py; row DEMO-TRYOUT-001 (RESULT PASS, 19 lines graded)
#   style      Piper s_* clips (same voice), then NOVA_LIVE_DEMO=1 NOVA_LIVE_PROVIDER=gemini
#              NOVA_LIVE_SCENES=style ... --tests '*LiveDemoRun*' (5-minute timeout), then
#              style_check.py; row DEMO-STYLE-001 (RESULT PASS, graded=7 pass=7). Blank NOVA_GEMINI_MODEL
#              means gemini-3.8-live here; other pieces keep the harness default. Summaries carry model=.
# Usage: scripts/github_live_jvm.sh <text-live|amap-live|tryout|style>
#        scripts/github_live_jvm.sh summarize <piece> <results.txt|-> <junit.xml|-> <out-dir>
# Env (secrets, never printed): GEMINI_API_KEY, AMAP_WEB_KEY, NOVA_VENDOR_ZIP_URL[/_AUTH],
#   NOVA_DEBUG_KEYSTORE_B64, AMAP_API_KEY. Test hooks: LIVE_JVM_SKIP_SETUP=1, LIVE_JVM_SKIP_PUSH=1,
#   LIVE_JVM_GRADLE (default ./gradlew), LIVE_JVM_OUT (default evidence/live-jvm).
# The summary holds ids, counts and correct-flags only: no phrase, model text, coordinate or
# address (I-8). Raw gradle output is not kept.
set -u

summarize() { # piece results xml outdir
  python3 - "$@" <<'PY'
import os, re, sys
import xml.etree.ElementTree as ET
piece, results, xml, outdir = sys.argv[1:5]
os.makedirs(outdir, exist_ok=True)
lines = []
tests = fail = err = skip = 0
xml_ok = False
if xml != "-" and os.path.isfile(xml):
    try:
        for ts in ET.parse(xml).getroot().iter("testsuite"):
            tests += int(ts.get("tests", 0)); fail += int(ts.get("failures", 0))
            err += int(ts.get("errors", 0)); skip += int(ts.get("skipped", 0))
        xml_ok = True
    except Exception:
        xml_ok = False
lines.append(f"piece={piece}")
lines.append(f"junit tests={tests} failures={fail} errors={err} skipped={skip} parsed={'yes' if xml_ok else 'no'}")
if piece == "text-live":
    sets = {}
    ctl_play = 0
    have = results != "-" and os.path.isfile(results)
    if have:
        for raw in open(results, encoding="utf-8", errors="replace"):
            p = [x.strip() for x in raw.rstrip("\n").split(" | ")]
            if len(p) < 5 or p[4] not in ("PASS", "MISS"):
                continue
            sets.setdefault(p[0], []).append((p[1], p[4] == "PASS"))
            if p[0] == "MUSIC":
                m = re.match(r"control_music(\{.*)?", p[3].replace("got ", "", 1))
                if m and re.search(r"play", m.group(0)):
                    ctl_play += 1
    lines.append(f"results_txt={'present' if have else 'missing'}")
    if have:
        for raw in open(results, encoding="utf-8", errors="replace"):
            if raw.startswith("model="):
                lines.append("model=" + re.sub(r"[^A-Za-z0-9._-]", "", raw[6:].strip())[:80])
                break
    fz = sets.get("FUZZY", []); mu = sets.get("MUSIC", [])
    fzp = sum(1 for _, ok in fz if ok); mup = sum(1 for _, ok in mu if ok)
    tail = {i: ok for i, ok in fz if re.fullmatch(r"FZ-(15|16|17|18|19)", i)}
    tail_ok = len(tail) == 5 and all(tail.values())
    lines.append(f"FUZZY correct={fzp}/{len(fz)} fz15_19_all_correct={'yes' if tail_ok else 'no'}")
    lines.append(f"MUSIC play_music_correct={mup}/{len(mu)} control_music_play={ctl_play}/{len(mu)}")
    for set_name, rows in sets.items():
        lines.append(f"{set_name} " + " ".join(f"{i}={'ok' if ok else 'miss'}" for i, ok in rows))
    lines.append("FUZZY-TEXT-LIVE-001 rule=" + ("MET" if have and len(fz) == 19 and fzp >= 17 and tail_ok else "NOT_MET"))
    lines.append("MUSIC-TEXT-LIVE-001 rule=" + ("MET" if have and len(mu) == 10 and mup >= 8 and ctl_play == 0 else "NOT_MET"))
elif piece == "tryout":
    # tryout_check.py lines: "  <key> <verdict> <time>s <tools> <note>". The note can carry reply
    # wording, so it is kept only when it is a fixed token; tools are sanitized (I-8).
    have = results != "-" and os.path.isfile(results)
    graded = []; result = None; warns = "-"
    if have:
        for raw in open(results, encoding="utf-8", errors="replace"):
            m = re.match(r"^\s+(u_[A-Za-z0-9_]+)\s+(PASS|FAIL)\s+(\d+\.\d+s|--)\s+(\S+)\s*(.*)$", raw.rstrip("\n"))
            if m:
                key, verdict, t, tools, note = m.groups()
                tools = re.sub(r"[^A-Za-z0-9_.,=-]", "", tools)[:120] or "-"
                note = note.strip()
                row = f"{key} {verdict} {t} {tools}"
                if re.fullmatch(r"[A-Za-z0-9_.=-]{1,40}", note):
                    row += f" {note}"
                graded.append((key, verdict, row))
                continue
            m = re.match(r"^RESULT (PASS|FAIL)\s*(\S*)", raw)
            if m:
                result = (m.group(1), re.sub(r"[^A-Za-z0-9_,]", "", m.group(2)))
                continue
            m = re.match(r"^WARN (\S+)", raw)
            if m:
                warns = re.sub(r"[^A-Za-z0-9_,-]", "", m.group(1)) or "-"
    lines.append(f"tryout_check={'present' if have else 'missing'}")
    for _, _, row in graded:
        lines.append(row)
    passed = sum(1 for _, v, _ in graded if v == "PASS")
    lines.append(f"graded={len(graded)} pass={passed}")
    if result:
        lines.append(f"RESULT {result[0]}" + (f" {result[1]}" if result[1] else ""))
    else:
        lines.append("RESULT missing")
    lines.append(f"WARN {warns}")
    lines.append("DEMO-TRYOUT-001 rule=" + ("MET" if result and result[0] == "PASS" and len(graded) == 19 else "NOT_MET"))
elif piece == "style":
    # style_check.py lines: "  <key> <verdict> <time> <tools> <style> <F0> <spread> <pace> [<mk>] <note>".
    # Notes are dropped; every kept field is reduced to [A-Za-z0-9_.,=+-] (I-8).
    have = results != "-" and os.path.isfile(results)
    graded = []; result = None
    san = lambda v, n=40: re.sub(r"[^A-Za-z0-9_.,=+-]", "", v)[:n] or "-"
    if have:
        for raw in open(results, encoding="utf-8", errors="replace"):
            m = re.match(r"^\s+(s_[A-Za-z0-9_]+)\s+(PASS|FAIL)\s+(-?\d+\.\d+s|--)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)(?:\s+(\d+))?", raw.rstrip("\n"))
            if m:
                key, verdict, t, tools, style, f0, spread, pace, mk = m.groups()
                graded.append((key, verdict, f"{key} {verdict} {t} tools={san(tools, 120)} style={san(style)} "
                                             f"f0={san(f0)} spread={san(spread)} pace={san(pace)} markers={mk or '-'}"))
                continue
            m = re.match(r"^RESULT (PASS|FAIL)\s*(\S*)", raw)
            if m:
                result = (m.group(1), re.sub(r"[^A-Za-z0-9_,]", "", m.group(2)))
    lines.append(f"style_check={'present' if have else 'missing'}")
    for _, _, row in graded:
        lines.append(row)
    spass = sum(1 for _, v, _ in graded if v == 'PASS')
    lines.append(f"graded={len(graded)} pass={spass}")
    if result:
        lines.append(f"RESULT {result[0]}" + (f" {result[1]}" if result[1] else ""))
    else:
        lines.append("RESULT missing")
    lines.append("DEMO-STYLE-001 rule=" + ("MET" if result and result[0] == "PASS" and len(graded) == 7 and spass == 7 else "NOT_MET"))
elif piece == "amap-live":
    lines.append("DEMO-AMAP-LIVE-001 rule=" + ("MET" if xml_ok and tests == 2 and fail == 0 and err == 0 and skip == 0 else "NOT_MET"))
open(os.path.join(outdir, "summary.txt"), "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
PY
}

evidence_dir() { # sha stamp piece; _r<GITHUB_RUN_ID> keeps same-minute runs on one sha apart
  printf 'runs/run_%s_%s%s/%s\n' "$2" "${1:0:7}" "${GITHUB_RUN_ID:+_r$GITHUB_RUN_ID}" "$3"
}

if [ "${1:-}" = "evidence-dir" ]; then
  [ "$#" -eq 4 ] || { echo "usage: $0 evidence-dir <sha> <yyyymmdd_hhmm> <piece>" >&2; exit 2; }
  evidence_dir "$2" "$3" "$4"; exit 0
fi
if [ "${1:-}" = "summarize" ]; then
  [ "$#" -eq 5 ] || { echo "usage: $0 summarize <piece> <results|-> <xml|-> <outdir>" >&2; exit 2; }
  summarize "$2" "$3" "$4" "$5"; exit 0
fi

piece="${1:-}"
case "$piece" in
  text-live) env_flag=NOVA_GEMINI_TEXT_LIVE; tests='*GeminiTextLiveMapping*'; rerun="--rerun"; need=GEMINI_API_KEY
             xmlname=TEST-com.novadrive.app.voice.GeminiTextLiveMappingTest.xml ;;
  amap-live) env_flag=NOVA_LIVE_AMAP; tests='*DemoCommuteAmapLiveTest'; rerun=""; need=AMAP_WEB_KEY
             xmlname=TEST-com.novadrive.app.livedemo.DemoCommuteAmapLiveTest.xml ;;
  tryout)    need=GEMINI_API_KEY; xmlname=TEST-com.novadrive.app.livedemo.LiveDemoRun.xml ;;
  style)     need=GEMINI_API_KEY; xmlname=TEST-com.novadrive.app.livedemo.LiveDemoRun.xml ;;
  *) echo "usage: $0 <text-live|amap-live|tryout|style>" >&2; exit 2 ;;
esac

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
out="${LIVE_JVM_OUT:-$root/evidence/live-jvm}/$piece"
mkdir -p "$out"
gradle="${LIVE_JVM_GRADLE:-./gradlew}"

blocked() { # step
  echo "BLOCKED at $1"
  printf 'piece=%s\nBLOCKED step %s failed\n' "$piece" "$1" >"$out/summary.txt"
  rc=3
}

piper_setup() { # sets work, voice, run, py; returns 1 after blocked()
  # Piper voice and venv live outside the repo; generated clips are untracked and never pushed.
  local venv
  work="${LIVE_JVM_TRYOUT_WORK:-$(mktemp -d)}"; venv="$work/venv"; voice="$work/voice"; run="$work/run"
  mkdir -p "$voice" "$run"
  py="${LIVE_JVM_PYTHON:-}"
  if [ "${LIVE_JVM_SKIP_SETUP:-}" != 1 ]; then
    command -v ffmpeg >/dev/null 2>&1 || sudo apt-get install -y -q ffmpeg >/dev/null 2>&1 || { blocked ffmpeg; return 1; }
    python3 -m venv "$venv" >/dev/null 2>&1 || { blocked venv; return 1; }
    "$venv/bin/pip" install -q piper-tts edge-tts imageio-ffmpeg numpy >/dev/null 2>&1 || { blocked pip-piper-tts; return 1; }
    py="$venv/bin/python3"
    local base=https://huggingface.co/rhasspy/piper-voices/resolve/main/zh/zh_CN/huayan/medium
    for f in zh_CN-huayan-medium.onnx zh_CN-huayan-medium.onnx.json; do
      curl -fsSL --retry 4 --retry-delay 5 -o "$voice/$f" "$base/$f" >/dev/null 2>&1 || { blocked voice-download; return 1; }
    done
  fi
  py="${py:-python3}"
}

run_tryout() {
  local work voice run py log
  piper_setup || return
  PIPER_MODEL="$voice/zh_CN-huayan-medium.onnx" "$py" tools/demo/live/make_clips_cloud.py tryout >/dev/null 2>&1 \
    || { blocked make-clips; return; }
  # The row's own procedure, under the 8-minute live timeout. Raw output is discarded.
  env NOVA_LIVE_DEMO=1 NOVA_LIVE_PROVIDER=gemini NOVA_LIVE_SCENES=tryout NOVA_LIVE_OUT="$run" \
    timeout 480 "$gradle" :app:testDebugUnitTest --tests '*LiveDemoRun*' >/dev/null 2>&1
  rc=$?
  echo "gradle exit=$rc"
  log="$work/tryout_check.txt"
  "$py" tools/demo/live/tryout_check.py "$run" >"$log" 2>/dev/null
  echo "tryout_check exit=$?"
  xml="$(find "$buildroot/app" "$root/app/build" -name "$xmlname" 2>/dev/null | head -1)"
  summarize "$piece" "$log" "${xml:--}" "$out" >/dev/null
  echo "gradle_exit=$rc" >>"$out/summary.txt"
  [ "$rc" = 124 ] && echo "timeout=yes" >>"$out/summary.txt"
  grep -q "rule=MET" "$out/summary.txt" || { [ "$rc" = 0 ] && rc=1; }
  cat "$out/summary.txt"
}

run_style() {
  local work voice run py log
  piper_setup || return
  PIPER_MODEL="$voice/zh_CN-huayan-medium.onnx" "$py" tools/demo/live/make_clips_cloud.py style >/dev/null 2>&1 \
    || { blocked make-clips; return; }
  # Blank workflow input: gemini-3.8-live for style only (the harness default 3.1 cannot show a pitch change).
  export NOVA_GEMINI_MODEL="${NOVA_GEMINI_MODEL:-gemini-3.8-live}"
  # The row's own procedure, under a 5-minute timeout. Raw output is discarded.
  env NOVA_LIVE_DEMO=1 NOVA_LIVE_PROVIDER=gemini NOVA_LIVE_SCENES=style NOVA_LIVE_OUT="$run" \
    timeout 300 "$gradle" :app:testDebugUnitTest --tests '*LiveDemoRun*' >/dev/null 2>&1
  rc=$?
  echo "gradle exit=$rc"
  log="$work/style_check.txt"
  "$py" tools/demo/live/style_check.py "$run" >"$log" 2>/dev/null
  echo "style_check exit=$?"
  xml="$(find "$buildroot/app" "$root/app/build" -name "$xmlname" 2>/dev/null | head -1)"
  summarize "$piece" "$log" "${xml:--}" "$out" >/dev/null
  echo "gradle_exit=$rc" >>"$out/summary.txt"
  [ "$rc" = 124 ] && echo "timeout=yes" >>"$out/summary.txt"
  grep -q "rule=MET" "$out/summary.txt" || { [ "$rc" = 0 ] && rc=1; }
  cat "$out/summary.txt"
}

if [ -z "${!need:-}" ]; then
  echo "secret $need is not set"
  printf 'piece=%s\nBLOCKED secret %s not set\n' "$piece" "$need" >"$out/summary.txt"
  rc=3
else
  if [ "${LIVE_JVM_SKIP_SETUP:-}" != 1 ]; then
    # Same toolchain, vendor zip and debug keystore setup as scripts/github_build_apk.sh.
    sudo apt-get update -q >/dev/null
    sudo apt-get install -y -q openjdk-17-jdk-headless unzip >/dev/null
    sudo mkdir -p /opt/android-sdk; sudo chown -R "$USER" /opt/android-sdk
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/opt/android-sdk
    unset ANDROID_PREFS_ROOT ANDROID_SDK_HOME
    export ANDROID_USER_HOME="$HOME/.android"
    export NOVA_VENDOR_STUB_OK=yes  # JVM tests do not touch the vendor engine; a dead zip falls back to the stand-in
    bash scripts/cloud_setup.sh >/dev/null 2>&1 || echo "cloud_setup.sh exited non-zero"
    chmod +x ./gradlew
  fi
  buildroot="${NOVA_BUILD_DIR:-$HOME/nova-drive-build}"
  [ -z "${TEXT_LIVE_MODEL:-}" ] && unset TEXT_LIVE_MODEL  # a blank var must not reach the test (it only defaults on null)
  # Warm-up so the timed row is not eaten by a cold Gradle + Kotlin compile (output discarded).
  [ "${LIVE_JVM_SKIP_SETUP:-}" = 1 ] || timeout 900 "$gradle" :app:compileDebugUnitTestKotlin >/dev/null 2>&1 || true
  rm -rf "$root/app/build/text-live" "$buildroot/app/test-results"
  if [ "$piece" = tryout ]; then
    run_tryout
  elif [ "$piece" = style ]; then
    run_style
  else
  # The exact invocation of the row's evidence line, under a 5-minute timeout. Raw output is discarded.
  env "$env_flag=1" timeout 300 "$gradle" :app:testDebugUnitTest --tests "$tests" $rerun >/dev/null 2>&1
  rc=$?
  echo "gradle exit=$rc"
  xml="$(find "$buildroot/app" "$root/app/build" -name "$xmlname" 2>/dev/null | head -1)"
  results="$(find "$buildroot/app" "$root/app/build" -path '*text-live/results.txt' 2>/dev/null | head -1)"
  summarize "$piece" "${results:--}" "${xml:--}" "$out"
  echo "gradle_exit=$rc" >>"$out/summary.txt"
  [ "$rc" = 124 ] && echo "timeout=yes" >>"$out/summary.txt"
  fi
  # The model passed to the run (harness-default when blank); text-live may already carry its own.
  if [ -f "$out/summary.txt" ] && ! grep -q '^model=' "$out/summary.txt"; then
    if [ "$piece" = text-live ]; then m="${TEXT_LIVE_MODEL:-}"; else m="${NOVA_GEMINI_MODEL:-}"; fi
    m="$(printf '%s' "$m" | LC_ALL=C tr -cd 'A-Za-z0-9._-' | cut -c1-80)"
    echo "model=${m:-harness-default}" >>"$out/summary.txt"
  fi
fi

if [ "${LIVE_JVM_SKIP_PUSH:-}" = 1 ]; then exit "$rc"; fi
sha="${GITHUB_SHA:-$(git rev-parse HEAD)}"
dir="$(evidence_dir "$sha" "$(date -u +%Y%m%d_%H%M)" "$piece")"
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
stage="$(mktemp -d)"; cp "$out/summary.txt" "$stage/summary.txt"
push_rc=1
for attempt in 1 2 3 4 5; do
  if git fetch origin ci/live-jvm-evidence 2>/dev/null; then
    git switch -C ci/live-jvm-evidence FETCH_HEAD >/dev/null 2>&1
  elif [ "$attempt" = 1 ]; then
    git switch --orphan ci/live-jvm-evidence >/dev/null 2>&1
    git rm -rf . >/dev/null 2>&1 || true
  fi
  mkdir -p "$dir"; cp "$stage/summary.txt" "$dir/summary.txt"
  git add "$dir"
  git commit -q -m "Live JVM $piece $(basename "$(dirname "$dir")") (run ${GITHUB_RUN_ID:-local})" || true
  if git push origin ci/live-jvm-evidence 2>/dev/null; then push_rc=0; break; fi
  git reset -q --hard HEAD~1 2>/dev/null || true  # re-fetch and re-apply on top of the new tip
  sleep $((attempt * 3))
done
[ "$push_rc" = 0 ] && echo "evidence pushed: $dir" || echo "::error title=evidence push failed::$dir"
if [ "$rc" = 0 ] && [ "$push_rc" = 0 ]; then exit "$rc"; fi
exit $([ "$rc" = 0 ] && echo 1 || echo "$rc")
