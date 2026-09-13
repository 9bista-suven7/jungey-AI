#!/usr/bin/env bash
# Start Jungey. First run downloads JavaFX and Jackson.
set -e
cd "$(dirname "$0")"

# The build targets 21, which is often not the system default JDK.
if [ -z "$JAVA_HOME" ] && ! javac -version 2>&1 | grep -q ' 21\.'; then
  for jdk in /usr/lib/jvm/*21*; do
    if [ -x "$jdk/bin/javac" ]; then
      export JAVA_HOME="$jdk"
      break
    fi
  done
fi

exec mvn -q javafx:run
