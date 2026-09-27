#!/usr/bin/env bash
# Put Jungey Notepad on this computer, for your account: the jungey-notepad command (which
# Jungey uses to read and write notes), and Jungey Notepad in the app menu. No root needed.
#
#   apps/notepad/install.sh              build and install
#   apps/notepad/install.sh --uninstall  take it away again (the notes themselves stay)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
data="${XDG_DATA_HOME:-$HOME/.local/share}"
bin="$HOME/.local/bin"

say() { printf '\033[1m%s\033[0m\n' "$*"; }

case "${1:-}" in
  --uninstall)
    rm -f "$bin/jungey-notepad" "$data/applications/jungey-notepad.desktop" "$data/jungey-notepad/jungey-notepad.jar"
    for size in 16 32 48 128 256; do rm -f "$data/icons/hicolor/${size}x${size}/apps/jungey-notepad.png"; done
    say "Removed. Your notes are still in ~/Documents/Jungey, and their history in $data/jungey-notepad/history."
    exit 0 ;;
  "") ;;
  -h|--help) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "Unknown option: $1" >&2; exit 1 ;;
esac

# The build targets 21, which is often not the system default JDK.
if [ -z "${JAVA_HOME:-}" ] && ! javac -version 2>&1 | grep -q ' 21\.'; then
  for jdk in /usr/lib/jvm/*21*; do
    if [ -x "$jdk/bin/javac" ]; then export JAVA_HOME="$jdk"; break; fi
  done
fi

say "Building Jungey Notepad"
mvn -q -B -f "$here/pom.xml" package
jar="$(ls -t "$here"/target/jungey-notepad-*.jar | grep -v original- | head -n 1)"

say "Installing the jungey-notepad command, menu entry and icon"
install -Dm644 "$jar" "$data/jungey-notepad/jungey-notepad.jar"
install -Dm755 "$here/jungey-notepad" "$bin/jungey-notepad"
for size in 16 32 48 128 256; do
  install -Dm644 "$here/src/main/resources/icons/jungey-notepad-$size.png" \
    "$data/icons/hicolor/${size}x${size}/apps/jungey-notepad.png"
done
mkdir -p "$data/applications"
sed "s|^Exec=jungey-notepad|Exec=$bin/jungey-notepad|" "$here/jungey-notepad.desktop" > "$data/applications/jungey-notepad.desktop"
command -v update-desktop-database >/dev/null && update-desktop-database -q "$data/applications" || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t "$data/icons/hicolor" || true

say "Done. Open Jungey Notepad from the menu, or try: $bin/jungey-notepad list"
case ":$PATH:" in
  *":$bin:"*) ;;
  *) echo "Log out and back in once, so $bin is on your PATH." ;;
esac
