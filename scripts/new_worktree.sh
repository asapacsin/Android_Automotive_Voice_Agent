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

#   scripts/new_worktree.sh --provision <TASK_ID>   # only add the vendor files to an existing worktree
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/nova-drive/vendor.zip"

if [ $# -eq 2 ] && [ "$1" = "--provision" ]; then
  WT="$(cd "$ROOT/.." && pwd)/nova-wt/$2"
  [ -d "$WT" ] || { echo "no worktree at $WT" >&2; exit 2; }
elif [ $# -eq 3 ]; then
  task="$1" branch="$2" base="$3"
  WT="$(cd "$ROOT/.." && pwd)/nova-wt/$task"
  base_full="$(git -C "$ROOT" rev-parse --verify "$base^{commit}")"
  git -C "$ROOT" worktree add -q -b "$branch" "$WT" "$base_full"
  head="$(git -C "$WT" rev-parse HEAD)"
  if [ "$head" != "$base_full" ]; then
    echo "worktree HEAD $head != BASE_COMMIT $base_full" >&2
    exit 1
  fi
else
  echo "usage: $0 <TASK_ID> <branch> <BASE_COMMIT>  |  $0 --provision <TASK_ID>" >&2
  exit 2
fi
[ -f "$ROOT/local.properties" ] && install -m 600 "$ROOT/local.properties" "$WT/local.properties"

# WebRTC AEC3 sources (gitignored, needed by :app's CMake build) at the pin scripts/cloud_setup.sh
# uses. Taken from the main checkout's copy when it has that commit (no network), else from GitHub.
# The D-CANCEL-HOST-OVERVIEW executor's assembleDebug failed without them (2026-10-17).
AEC3_PIN="$(sed -n 's/^WEBRTC_AEC3_PIN=//p' "$ROOT/scripts/cloud_setup.sh")"
AEC3_DIR=app/src/main/cpp/third_party/webrtc-aec3
if [ -n "$AEC3_PIN" ] && [ "$(git -C "$WT/$AEC3_DIR" rev-parse HEAD 2>/dev/null)" != "$AEC3_PIN" ]; then
  rm -rf "${WT:?}/$AEC3_DIR"
  mkdir -p "$WT/$AEC3_DIR"
  git -C "$WT/$AEC3_DIR" init -q
  if git -C "$ROOT/$AEC3_DIR" cat-file -e "$AEC3_PIN^{commit}" 2>/dev/null; then
    git -C "$WT/$AEC3_DIR" fetch -q --depth 1 "$ROOT/$AEC3_DIR" "$AEC3_PIN"
  else
    git -C "$WT/$AEC3_DIR" fetch -q --depth 1 https://github.com/Enaium/webrtc-aec3.git "$AEC3_PIN" || \
      echo "WARN WebRTC AEC3 fetch failed: :app native build will fail in $WT" >&2
  fi
  git -C "$WT/$AEC3_DIR" checkout -q --detach FETCH_HEAD 2>/dev/null || true
  rm -rf "$WT/app/.cxx"
fi

VENDOR_FILES=(
  app/libs/Msc.jar
  app/src/main/jniLibs/arm64-v8a/libmsc.so app/src/main/jniLibs/arm64-v8a/libw_ivw.so
  app/src/main/jniLibs/armeabi-v7a/libmsc.so app/src/main/jniLibs/armeabi-v7a/libw_ivw.so
  app/src/main/assets/ivw/wakeword.jet
  app/src/main/res/raw/bach_air_usaf.mp3
)
MUSIC_FILE=app/src/main/res/raw/bach_air_usaf.mp3

if [ ! -s "$CACHE" ] && [ -z "${NOVA_VENDOR_ZIP_URL:-}" ]; then
  # No zip: copy the gitignored vendor files the main checkout (or, for the music track, another
  # worktree) already has. Owner, 2026-10-17: "if you lack music you just find a way to handle".
  # Read-only on the main checkout.
  copied=0
  for f in "${VENDOR_FILES[@]}"; do
    src="$ROOT/$f"
    if [ ! -s "$src" ] && [ "$f" = "$MUSIC_FILE" ]; then
      for other in "$(dirname "$WT")"/*/"$MUSIC_FILE"; do
        [ -s "$other" ] && { src="$other"; break; }
      done
    fi
    [ -s "$src" ] || continue
    git -C "$WT" check-ignore -q --no-index "$f" || { echo "ERROR $f is not gitignored" >&2; exit 1; }
    mkdir -p "$(dirname "$WT/$f")"
    cp "$src" "$WT/$f"
    copied=$((copied + 1))
  done
  if [ ! -s "$WT/$MUSIC_FILE" ]; then
    # Nowhere local: download a public-domain recording (same sources as scripts/cloud_setup.sh).
    mkdir -p "$(dirname "$WT/$MUSIC_FILE")"
    for url in $(sed -n 's/^MUSIC_URL\(_FALLBACK\)\{0,1\}="\(.*\)"$/\2/p' "$ROOT/scripts/cloud_setup.sh"); do
      if curl -sSfL --max-time 120 -o "$WT/$MUSIC_FILE.tmp" "$url" && head -c 3 "$WT/$MUSIC_FILE.tmp" | grep -q ID3; then
        mv "$WT/$MUSIC_FILE.tmp" "$WT/$MUSIC_FILE"
        copied=$((copied + 1))
        break
      fi
      rm -f "$WT/$MUSIC_FILE.tmp"
    done
  fi
  echo "INFO NOVA_VENDOR_ZIP_URL not set: provisioned $copied vendor file(s) from local checkouts or mirrors" >&2
  if [ ! -s "$WT/app/libs/Msc.jar" ] || [ ! -s "$WT/$MUSIC_FILE" ]; then
    echo "WARN $WT still lacks Msc.jar or the music track: :app will not compile" >&2
  fi
  echo "$WT"
  exit 0
fi

if [ ! -s "$CACHE" ]; then
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
