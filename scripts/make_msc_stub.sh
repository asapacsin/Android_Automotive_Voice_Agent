#!/usr/bin/env bash
# Builds a compile-only stand-in for the iFlytek MSC jar (tools/vendor-stub/README.md) when the real
# one is missing, so :app compiles and its JVM tests run without the vendor zip.
#
#   scripts/make_msc_stub.sh [<repo-or-worktree root>]
#
# Never overwrites a real app/libs/Msc.jar. Marks its output with app/libs/Msc.jar.STUB (both are
# gitignored). A stub build is not a product build: the wake word cannot work, and an APK built on
# it is evidence for nothing that touches the wake word (FeaturePresenceRegressionTest still fails
# on the missing engine artifacts, on purpose).
set -euo pipefail

ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
SRC="$(cd "$(dirname "$0")/.." && pwd)/tools/vendor-stub/src"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
JAR="$ROOT/app/libs/Msc.jar"
MARK="$JAR.STUB"

if [ -f "$JAR" ] && [ ! -f "$MARK" ]; then
  echo "[msc_stub] real app/libs/Msc.jar present: nothing to do" >&2
  exit 0
fi
ANDROID_JAR="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
if [ -z "$ANDROID_JAR" ]; then
  echo "[msc_stub] no android.jar under $SDK/platforms: run scripts/cloud_setup.sh first" >&2
  exit 1
fi
JAVAC="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}/bin/javac"
JARTOOL="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}/bin/jar"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
find "$SRC" -name '*.java' > "$work/sources"
"$JAVAC" --release 8 -nowarn -cp "$ANDROID_JAR" -d "$work/classes" @"$work/sources" 2>/dev/null \
  || "$JAVAC" -source 8 -target 8 -nowarn -cp "$ANDROID_JAR" -d "$work/classes" @"$work/sources"
mkdir -p "$ROOT/app/libs"
"$JARTOOL" cf "$JAR" -C "$work/classes" .
echo "compile-only stand-in from tools/vendor-stub; not the iFlytek SDK" > "$MARK"
echo "[msc_stub] wrote app/libs/Msc.jar (STUB: the wake word cannot work in this build)" >&2
