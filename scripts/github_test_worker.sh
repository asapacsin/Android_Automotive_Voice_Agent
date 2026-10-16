#!/bin/bash
# Resident test worker (owner, 2026-10-16: "make a cycle within 5 mins"). Runs inside one GitHub job
# with the emulator booted once. Every ~20 s it fetches the working branch; when the newest commit's
# message asks for a piece with [worker:<piece>], it checks that commit out, rebuilds incrementally
# (warm Gradle), reinstalls the app and runs the piece, then pushes the evidence to
# ci/test-worker runs/<time>_<sha>_<piece>/. Pieces: navpiece (first six demo lines, ~150 s),
# navdemo (the full drive). Logs carry codes only, as in the scripts it calls.
# Each published run ends summary.txt with "verdict: PASS|FAIL|BLOCKED" (scripts/ci_verdict.py, suite
# navpiece). Emulator evidence: never proof of cabin acoustics, echo cancellation, voice or hardware.
set -u
branch="${WORKER_BRANCH:?branch}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export ANDROID_USER_HOME="$HOME/.android"
unset ANDROID_PREFS_ROOT ANDROID_SDK_HOME
apk="$HOME/nova-drive-build/app/outputs/apk/debug/app-debug.apk"
deadline=$(( $(date +%s) + ${WORKER_MINUTES:-320} * 60 ))
done_sha="$(git rev-parse HEAD)"
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

publish() { # dir-name: evidence/ -> ci/test-worker runs/<name>/, through a separate worktree
  local name="$1" wt="/tmp/worker-ci"
  rm -rf "$wt"; git worktree prune
  if git fetch -q origin ci/test-worker 2>/dev/null; then
    git worktree add -q --detach "$wt" FETCH_HEAD
  else
    git worktree add -q --detach "$wt" HEAD && (cd "$wt" && git checkout -q --orphan worker-tmp && git rm -rqf . >/dev/null)
  fi
  mkdir -p "$wt/runs" && mv evidence "$wt/runs/$name" 2>/dev/null || mkdir -p "$wt/runs/$name"
  (cd "$wt" && git add "runs/$name" && git commit -qm "Test worker $name" && git push -q origin HEAD:ci/test-worker) \
    || echo "::warning title=worker::push failed for $name"
  git worktree remove --force "$wt" 2>/dev/null || rm -rf "$wt"
}

echo "::notice title=worker::ready at $(date -u +%H:%M) for ${WORKER_MINUTES:-320} min on $branch"
mkdir -p evidence && date -u +%H:%M:%S >evidence/ready.txt && publish "$(date -u +%Y%m%d_%H%M)_ready"

while [ "$(date +%s)" -lt "$deadline" ]; do
  sleep 20
  git fetch -q origin "$branch" || continue
  sha="$(git rev-parse FETCH_HEAD)"
  [ "$sha" = "$done_sha" ] && continue
  done_sha="$sha"
  piece="$(git log -1 --format=%B "$sha" | grep -oE '\[worker:[a-z]+\]' | head -1 | sed -E 's/\[worker:([a-z]+)\]/\1/')"
  [ -n "$piece" ] || continue
  git -c advice.detachedHead=false checkout -q -f --detach "$sha"
  name="$(date -u +%Y%m%d_%H%M)_${sha:0:7}_${piece}"
  t0=$(date +%s)
  rm -rf evidence && mkdir -p evidence
  if ! ./gradlew -q :app:assembleDebug >evidence/build.log 2>&1; then
    tail -30 evidence/build.log | grep -E "^e:|error:|FAILED" | head -10 >evidence/build_errors.txt
    rm -f evidence/build.log
    echo "build: FAILED after $(( $(date +%s) - t0 )) s" >evidence/summary.txt
    publish "$name"; continue
  fi
  rm -f evidence/build.log
  build_s=$(( $(date +%s) - t0 ))
  python3 tools/speech-harness/make_speech.py >/dev/null 2>&1 || true
  # The loop never dies on a piece failure (no set -e here); the piece's rc and verdict are recorded.
  piece_rc=0
  case "$piece" in
    navpiece) NAV_DEMO_PIECE=1 timeout 600 bash scripts/github_nav_demo.sh "$apk"; piece_rc=$? ;;
    navdemo) NAV_DEMO_PIECE= timeout 1500 bash scripts/github_nav_demo.sh "$apk"; piece_rc=$? ;;
    *) echo "unknown piece $piece" >>evidence/summary.txt; piece_rc=2 ;;
  esac
  # The script writes evidence/verdict.json; if it died before that, judge what it recorded.
  if [ ! -s evidence/verdict.json ]; then
    python3 scripts/ci_verdict.py verdict --manifest config/ci_checks.json --suite navpiece \
      --results evidence/results.jsonl --json-out evidence/verdict.json >/dev/null 2>&1
  fi
  verdict="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["verdict"])' evidence/verdict.json 2>/dev/null || echo BLOCKED)"
  echo "worker: piece=$piece rc=$piece_rc build_s=$build_s total_s=$(( $(date +%s) - t0 ))" >>evidence/summary.txt
  echo "verdict: $verdict" >>evidence/summary.txt
  publish "$name"
done
echo "::notice title=worker::stopped at $(date -u +%H:%M)"
