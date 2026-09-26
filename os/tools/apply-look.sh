#!/usr/bin/env bash
# Bring an installed Jungey OS up to date with this repository's desktop, tools
# and packages, without reinstalling. From a clone of the repository:
#
#   sudo ./os/tools/apply-look.sh
#
# It installs anything in config/packages.list that is missing, refreshes the
# system-wide defaults (theme, panel, terminal, wallpaper, zsh), switches your
# account to zsh, and resets your own desktop settings so the new defaults show.
# Those settings are backed up first, and nothing else in your home changes.
set -euo pipefail

OS_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
[ "$(id -u)" -eq 0 ] || { echo "run with sudo: sudo $0" >&2; exit 1; }
user=${SUDO_USER:-}
[ -n "$user" ] && [ "$user" != root ] || { echo "run with sudo from your own account" >&2; exit 1; }
home=$(getent passwd "$user" | cut -d: -f6)
uid=$(id -u "$user")

echo "==> Installing packages"
apt-get update
# shellcheck disable=SC2046
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends librsvg2-bin \
    $(sed -e 's/#.*//' -e '/^[[:space:]]*$/d' "$OS_DIR/config/packages.list")

echo "==> Refreshing system defaults"
rsync -rlK --chown=root:root --chmod=D755 "$OS_DIR/overlay/" /
chmod 600 /etc/netplan/*.yaml
install -Dm644 "$OS_DIR/artwork/wallpaper.svg" /usr/share/backgrounds/jungey/jungey-default.svg
rsvg-convert -w 2560 -h 1440 "$OS_DIR/artwork/wallpaper.svg" -o /usr/share/backgrounds/jungey/jungey-default.png
# Icons: the reactor for the OS (menu button), the J-and-spark for Jungey itself.
# /usr/local/share comes first in the icon search path, so these win over the
# older icons the installed jungey package carries.
install -Dm644 "$OS_DIR/artwork/reactor.svg" /usr/share/icons/hicolor/scalable/apps/jungey-os.svg
rsvg-convert -w 256 -h 256 "$OS_DIR/artwork/reactor.svg" -o /usr/share/pixmaps/jungey-os.png
for size in 16 32 48 128 256; do
    install -Dm644 "$OS_DIR/../src/main/resources/icons/jungey-$size.png" \
        "/usr/local/share/icons/hicolor/${size}x${size}/apps/jungey.png"
done
gtk-update-icon-cache -q -f /usr/share/icons/hicolor || true
gtk-update-icon-cache -q -f /usr/local/share/icons/hicolor || true

# The desktop and Claude hooks only touch system defaults, so they are safe to
# rerun on an installed system (the others set up identity and the live image).
for hook in 20-desktop.sh 25-claude.sh; do
    bash -e "$OS_DIR/hooks/$hook"
done

echo "==> Setting up $user"
backup=$home/.config/jungey-look-backup-$(date +%Y%m%d-%H%M%S)
sudo -u "$user" mkdir -p "$backup"
if [ -e "$home/.zshrc" ]; then
    sudo -u "$user" cp "$home/.zshrc" "$backup/"
fi
install -o "$user" -g "$(id -gn "$user")" -m644 /etc/skel/.zshrc "$home/.zshrc"
chsh -s /usr/bin/zsh "$user"

# Drop your own overrides so the new system defaults take effect.
xml=$home/.config/xfce4/xfconf/xfce-perchannel-xml
for channel in xsettings xfwm4 xfce4-panel xfce4-terminal; do
    [ -e "$xml/$channel.xml" ] && sudo -u "$user" cp "$xml/$channel.xml" "$backup/"
done
if [ -S "/run/user/$uid/bus" ]; then
    as_user() { sudo -u "$user" DISPLAY=:0 DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$uid/bus" "$@"; }
    for channel in xsettings xfwm4 xfce4-panel xfce4-terminal; do
        as_user xfconf-query -c "$channel" -p / -r -R 2>/dev/null || true
    done
    [ -d "$home/.config/xfce4/panel" ] && sudo -u "$user" mv "$home/.config/xfce4/panel" "$backup/panel"
    as_user xfce4-panel -r 2>/dev/null || true
fi

echo
echo "Done. Your previous settings are in $backup"
echo "Log out and back in to see everything (the new zsh terminal included)."
