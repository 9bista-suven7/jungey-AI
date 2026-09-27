#!/usr/bin/env bash
# Put Jungey on the desktop: in the app menu, and started when you log in.
#
# Builds the checkout, keeps the jar in ~/.local/share/jungey, and adds a `jungey`
# command, a menu entry and its icon for your account. No root needed, and on Jungey OS
# it takes the place of the system's own. Re-run it, or just ./run.sh, after a git pull:
# whatever opens Jungey then opens the new build, and a running older one hands over.
#
#   scripts/install.sh                  install, and start Jungey at login
#   scripts/install.sh --no-autostart   install, without starting it at login
#   scripts/install.sh --uninstall      take all of that away again
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
data="${XDG_DATA_HOME:-$HOME/.local/share}"
config="${XDG_CONFIG_HOME:-$HOME/.config}"
bin="$HOME/.local/bin"
autostart=true

say() { printf '\033[1m%s\033[0m\n' "$*"; }

case "${1:-}" in
  --no-autostart) autostart=false ;;
  --uninstall)
    rm -f "$bin/jungey" "$data/applications/jungey.desktop" "$config/autostart/jungey.desktop" \
          "$data/jungey/jungey.jar"
    for size in 16 32 48 128 256; do rm -f "$data/icons/hicolor/${size}x${size}/apps/jungey.png"; done
    say "Removed. Your notes, memory and settings are left where they were."
    exit 0 ;;
  "") ;;
  -h|--help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "Unknown option: $1" >&2; exit 1 ;;
esac

say "Building Jungey"
"$repo/run.sh" --build-only

say "Adding the jungey command, menu entry and icon"
# A launcher of your own, ahead of the system's in PATH: one installed before this
# version always opens the jar that came with the system, however new yours is.
install -Dm755 "$repo/packaging/jungey" "$bin/jungey"
launcher="$bin/jungey"
for size in 16 32 48 128 256; do
  install -Dm644 "$repo/src/main/resources/icons/jungey-$size.png" \
    "$data/icons/hicolor/${size}x${size}/apps/jungey.png"
done
# Same name as the system's entry, so the menu shows this one in its place.
mkdir -p "$data/applications"
sed "s|^Exec=jungey|Exec=$launcher|" "$repo/packaging/jungey.desktop" \
  > "$data/applications/jungey.desktop"
command -v update-desktop-database >/dev/null && update-desktop-database -q "$data/applications" || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t "$data/icons/hicolor" || true

if $autostart; then
  if [ -f /etc/xdg/autostart/jungey.desktop ]; then
    say "Jungey already starts at login (turn it off in Session and Startup)"
  else
    say "Starting Jungey at login"
    mkdir -p "$config/autostart"
    sed "s|^Exec=jungey|Exec=$launcher|" "$repo/packaging/jungey-autostart.desktop" \
      > "$config/autostart/jungey.desktop"
  fi
elif [ -f /etc/xdg/autostart/jungey.desktop ]; then
  # The system starts it for everyone; a hidden entry of your own switches that off for you.
  mkdir -p "$config/autostart"
  printf '[Desktop Entry]\nType=Application\nName=Jungey\nExec=jungey --autostart\nHidden=true\n' \
    > "$config/autostart/jungey.desktop"
else
  rm -f "$config/autostart/jungey.desktop"
fi

say "Done. Open Jungey from the menu, or run: $launcher"
case ":$PATH:" in
  *":$bin:"*) ;;
  *) echo "Log out and back in once, so $bin is on your PATH and Super+J uses the new launcher." ;;
esac
