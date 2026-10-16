#!/bin/bash
# Tier A gate, run the same way locally and on GitHub (.github/workflows/unit-tests.yml).
#   bash scripts/ci_tier_a.sh gates       # harness gates: verdict selftest, script tests,
#                                         # delegation/review audit, review-hook selftest, matrix
#   bash scripts/ci_tier_a.sh build-test  # :app:assembleDebug, JVM tests, JUnit XML per module
# Every check's exit code is captured and recorded PASS/FAIL/BLOCKED; the job's exit code is the
# exit code of `ci_verdict.py verdict` (0 PASS, 1 FAIL, 3 BLOCKED), never "the script finished".
# Results: ${CI_RESULTS_DIR:-evidence/ci}/tier-a-PHASE.jsonl and verdict-PHASE.json.
# Test overrides (defaults are production): CI_PYTHON, CI_GRADLE, CI_BUILD_SCRIPT.
set -u
set +e
cd "$(dirname "$0")/.."

PHASE="${1:-}"
case "$PHASE" in
  gates) SUITE=tier-a-gates ;;
  build-test) SUITE=tier-a-build ;;
  *) echo "usage: $0 gates|build-test" >&2; exit 2 ;;
esac

PY="${CI_PYTHON:-python3}"
RESULTS_DIR="${CI_RESULTS_DIR:-evidence/ci}"
mkdir -p "$RESULTS_DIR"
OUT="$RESULTS_DIR/tier-a-$PHASE.jsonl"
: > "$OUT"
JUNIT_OUT="$RESULTS_DIR/.tier-a-junit.jsonl"

record() {  # id status detail
  "$PY" scripts/ci_verdict.py record --out "$OUT" --id "$1" --status "$2" --detail "$3" >/dev/null
}

run_check() {  # id label cmd...  -> records PASS/FAIL by exit code
  local id="$1" label="$2"; shift 2
  local rc
  echo "== $id: $*"
  "$@"
  rc=$?
  if [ "$rc" -eq 0 ]; then record "$id" PASS "$label ok"; else record "$id" FAIL "$label exit $rc"; fi
  return 0
}

gates() {
  run_check ci:verdict-selftest "ci_verdict selftest" "$PY" scripts/ci_verdict.py --selftest
  run_check ci:script-tests "script unittests" "$PY" -m unittest discover -s scripts/tests -t .

  local args=(--ci) dc_out dc_rc line gate st
  if [ -n "${CI_UPSTREAM_REF:-}" ]; then args+=(--upstream "$CI_UPSTREAM_REF"); fi
  echo "== delegation_check ${args[*]}"
  dc_out="$("$PY" scripts/delegation_check.py "${args[@]}" 2>&1)"
  dc_rc=$?
  printf '%s\n' "$dc_out"
  for gate in delegation review-records; do
    line="$(printf '%s\n' "$dc_out" | grep -E "^GATE $gate (PASS|FAIL)\$" | tail -1)"
    st="${line##* }"
    if [ -z "$line" ]; then
      record "gate:$gate" FAIL "no GATE line (delegation_check exit $dc_rc)"
    elif [ "$st" = PASS ]; then
      record "gate:$gate" PASS "GATE $gate PASS"
    else
      record "gate:$gate" FAIL "GATE $gate FAIL (delegation_check exit $dc_rc)"
    fi
  done

  run_check gate:review-hook-selftest "review hook selftest" \
    "$PY" .claude/hooks/require-review-before-merge.py --selftest

  if "$PY" -c "import yaml" >/dev/null 2>&1; then
    run_check matrix:validate "test_matrix validate" "$PY" scripts/test_matrix.py --validate
  else
    record matrix:validate BLOCKED "PyYAML missing"
  fi
}

BUILD_IDS=(build:assembleDebug gradle:test junit:app junit:behavior-test junit:contracts
           junit:evaluation junit:ingress junit:safety junit:simulator)
MODULES=(app behavior-test contracts evaluation ingress safety simulator)

build_test() {
  local name missing=""
  for name in AMAP_API_KEY NOVA_VENDOR_ZIP_URL NOVA_DEBUG_KEYSTORE_B64; do
    if [ -z "${!name:-}" ]; then missing="$name"; break; fi
  done
  if [ -n "$missing" ]; then
    echo "required secret $missing is not set; build and tests are BLOCKED"
    local id
    for id in "${BUILD_IDS[@]}"; do record "$id" BLOCKED "secret $missing not set"; done
    return 0
  fi

  # shellcheck disable=SC2206
  local build_cmd=(${CI_BUILD_SCRIPT:-bash scripts/github_build_apk.sh})
  run_check build:assembleDebug "github_build_apk.sh" "${build_cmd[@]}"

  # As scripts/github_build_apk.sh sets them. Tests run even if the build failed: test
  # compilation shows the real breakage, and missing reports FAIL the junit records.
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  export ANDROID_HOME=/opt/android-sdk
  # shellcheck disable=SC2206
  local gradle_cmd=(${CI_GRADLE:-./gradlew})
  run_check gradle:test "gradle test" "${gradle_cmd[@]}" test :behavior-test:test --continue

  local root="${NOVA_BUILD_DIR:-$HOME/nova-drive-build}" margs=() m
  for m in "${MODULES[@]}"; do margs+=(--module "$m"); done
  "$PY" scripts/ci_verdict.py junit --root "$root" "${margs[@]}" --out "$JUNIT_OUT" \
    && cat "$JUNIT_OUT" >> "$OUT"
  rm -f "$JUNIT_OUT"
}

set +e  # every check captures its own exit code; the verdict decides
if [ "$PHASE" = gates ]; then gates; else build_test; fi

"$PY" scripts/ci_verdict.py verdict --manifest config/ci_checks.json --suite "$SUITE" \
  --results "$OUT" --json-out "$RESULTS_DIR/verdict-$PHASE.json" \
  --summary "${GITHUB_STEP_SUMMARY:-/dev/null}"
exit $?
