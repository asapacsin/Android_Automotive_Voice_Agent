#!/usr/bin/env sh
JAVA_HOME="${JAVA_HOME:-/c/Users/Administrator/tools/jdk-17}"
export JAVA_HOME
GRADLE_HOME="${GRADLE_HOME:-/c/Users/Administrator/tools/gradle-8.11.1}"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  echo "Extract Gradle 8.11.1 from C:\\Users\\Administrator\\tools\\gradle-8.11.1-bin.zip first."
  exit 1
fi
exec "$GRADLE_HOME/bin/gradle" "$@"
