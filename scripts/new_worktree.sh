#!/usr/bin/env bash
# Create an executor worktree that can build :app, in one step (owner time rule, 2026-10-17).
#
#   scripts/new_worktree.sh <TASK_ID> <branch> <BASE_COMMIT>
#
# Creates ../nova-wt/<TASK_ID> on a new <branch> from <BASE_COMMIT>, checks that HEAD equals the
# base, copies local.properties, and unpacks the gitignored vendor files (iFlytek SDK, music track)
# from the owner's private zip (NOVA_VENDOR_ZIP_URL / NOVA_VENDOR_ZIP_AUTH, docs/CLOUD_BUILD.md).
# The zip is downloaded once per container and cached outside the repository. Nothing is
# committed: every provisioned file is gitignored, and the script checks that.
#
# Why: on 2026-10-17 three fixes waited ~13 h because worktrees lacked these files and copying
# them by hand was refused. A worktree that cannot compile :app is not ready for an executor.
set -euo pipefail

if [ $# -ne 3 ]; then
  echo "usage: $0 <TASK_ID> <branch> <BASE_COMMIT>" >&2
  exit 2
fi
task="$1" branch="$2" base="$3"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WT="$(cd "$ROOT/.." && pwd)/nova-wt/$task"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/nova-drive/vendor.zip"

base_full="$(git -C "$ROOT" rev-parse --verify "$base^{commit}")"
git -C "$ROOT" worktree add -q -b "$branch" "$WT" "$base_full"
head="$(git -C "$WT" rev-parse HEAD)"
if [ "$head" != "$base_full" ]; then
  echo "worktree HEAD $head != BASE_COMMIT $base_full" >&2
  exit 1
fi
[ -f "$ROOT/local.properties" ] && install -m 600 "$ROOT/local.properties" "$WT/local.properties"

if [ ! -s "$CACHE" ]; then
  if [ -z "${NOVA_VENDOR_ZIP_URL:-}" ]; then
    echo "WARN NOVA_VENDOR_ZIP_URL not set: $WT has no vendor files, :app will not compile" >&2
    echo "$WT"
    exit 0
  fi
  mkdir -p "$(dirname "$CACHE")"
  tmp="$(mktemp --suffix=.zip)"
  auth=()
  [ -n "${NOVA_VENDOR_ZIP_AUTH:-}" ] && auth=(-H "Authorization: $NOVA_VENDOR_ZIP_AUTH")
  ok=no
  for delay in 2 4 8 16; do
    if curl -sSfL "${auth[@]}" -o "$tmp" "$NOVA_VENDOR_ZIP_URL"; then ok=yes; break; fi
    sleep "$delay"
  done
  if [ "$ok" != yes ] || ! unzip -tq "$tmp" >/dev/null 2>&1; then
    rm -f "$tmp"
    echo "WARN vendor zip download failed or is not a zip: $WT has no vendor files" >&2
    echo "$WT"
    exit 0
  fi
  mv "$tmp" "$CACHE"
  chmod 600 "$CACHE"
fi

unzip -q -o "$CACHE" -d "$WT"
# Every provisioned path must be ignored by git, or it could be committed by accident.
tracked_risk="$(unzip -Z1 "$CACHE" | grep -v '/$' | git -C "$WT" check-ignore --no-index -v --stdin -n \
  | awk '$1 == "::" {print $2}')"
if [ -n "$tracked_risk" ]; then
  echo "ERROR not gitignored in $WT:" >&2
  echo "$tracked_risk" >&2
  exit 1
fi
echo "$WT"
