#!/usr/bin/env bash
# Start Jungey. The first run downloads JavaFX and Jackson and builds the jar; after that
# the jar is only rebuilt when the code has changed, so starting takes well under a second
# instead of the several Maven spends checking the build every time.
#
#   ./run.sh               build if the code changed, then start (or take over from an older Jungey)
#   ./run.sh --build-only  build if the code changed, and stop there
#
# Every build is also copied to ~/.local/share/jungey/jungey.jar, which is the jar the
# desktop launches - the menu, Super+J and the login autostart - so after a git pull
# and one ./run.sh, however you open Jungey you get the new code.
set -e
cd "$(dirname "$0")"

build_only=false
if [ "${1:-}" = --build-only ]; then
  build_only=true
  shift
fi

# The build targets 21, which is often not the system default JDK.
if [ -z "$JAVA_HOME" ] && ! javac -version 2>&1 | grep -q ' 21\.'; then
  for jdk in /usr/lib/jvm/*21*; do
    if [ -x "$jdk/bin/javac" ]; then
      export JAVA_HOME="$jdk"
      break
    fi
  done
fi
JAVA=java
[ -n "$JAVA_HOME" ] && JAVA="$JAVA_HOME/bin/java"

built_jar() {
  # shellcheck disable=SC2012  # our own jar names, and ls sorts by age where find cannot
  ls -t target/jungey-*.jar 2>/dev/null | head -n 1
}

if [ -z "$(built_jar)" ] || [ -n "$(find pom.xml src -newer "$(built_jar)" -print -quit)" ]; then
  echo "Building Jungey..."
  mvn -q -DskipTests package
fi
jar="$(built_jar)"

# The copy the desktop launches. -p keeps the build's own time on it, which is how the
# launcher tells it apart from - and prefers it to - an older jar that came with the system.
installed="${XDG_DATA_HOME:-$HOME/.local/share}/jungey/jungey.jar"
if [ ! -f "$installed" ] || [ "$jar" -nt "$installed" ]; then
  mkdir -p "$(dirname "$installed")"
  cp -p "$jar" "$installed.tmp"
  mv -f "$installed.tmp" "$installed"
fi

$build_only && exit 0

# Class data sharing: the first start records the classes Jungey loads, and later starts
# map that archive in rather than loading and checking each class again. Java 21 does not
# rebuild an archive when the jar changes - it just stops using it - so each build gets
# an archive of its own, named after the jar's timestamp and size.
cache="$HOME/.cache/jungey"
cds="$cache/jungey-$(stat -c %Y-%s "$jar").jsa"
mkdir -p "$cache"
find "$cache" -maxdepth 1 -name 'jungey*.jsa' ! -path "$cds" -delete
exec "$JAVA" -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile="$cds" -jar "$jar" "$@"
