#!/usr/bin/env sh
# Cross-platform Gradle launcher. Windows owners still use gradlew.bat.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in \
    /usr/lib/jvm/java-17-openjdk-amd64 \
    /usr/lib/jvm/java-17-openjdk \
    "/c/Users/Administrator/tools/jdk-17"
  do
    if [ -x "$candidate/bin/java" ]; then
      JAVA_HOME=$candidate
      break
    fi
  done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "JAVA_HOME must point at a JDK 17 install." >&2
  exit 1
fi
export JAVA_HOME

if [ -z "${GRADLE_HOME:-}" ]; then
  for candidate in \
    "$HOME/tools/gradle-8.11.1" \
    /home/ubuntu/tools/gradle-8.11.1 \
    "/c/Users/Administrator/tools/gradle-8.11.1"
  do
    if [ -x "$candidate/bin/gradle" ]; then
      GRADLE_HOME=$candidate
      break
    fi
  done
fi
if [ -z "${GRADLE_HOME:-}" ] || [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  echo "Extract Gradle 8.11.1 and set GRADLE_HOME (expected bin/gradle)." >&2
  exit 1
fi

if [ -z "${NOVA_BUILD_DIR:-}" ] && [ "$(uname -s)" != "Windows_NT" ]; then
  export NOVA_BUILD_DIR="${HOME}/nova-drive-build"
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
