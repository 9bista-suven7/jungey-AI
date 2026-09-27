#!/usr/bin/env bash
# Bring an installed Jungey OS up to date with this repository's desktop, tools
# and packages, without reinstalling. From a clone of the repository:
#
#   sudo ./os/tools/apply-look.sh
#
# It installs anything in config/packages.list that is missing, refreshes the
# system-wide defaults (the Jungey theme, panel, terminal, wallpaper, login
# screen, boot splash, boot menu, zsh, the Jungey launcher and its login entry),
# switches your account to zsh, and resets your own desktop settings so the new
# defaults show. Those settings are backed up first, and nothing else in your
# home changes. The boot splash and boot menu show from the next restart.
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
# Wallpaper, login screen, the reactor icon, boot splash and boot menu images.
"$OS_DIR/tools/install-artwork.sh" /
# The J-and-spark for Jungey itself. /usr/local/share comes first in the icon
# search path, so these win over the older icons the installed jungey package carries.
for size in 16 32 48 128 256; do
    install -Dm644 "$OS_DIR/../src/main/resources/icons/jungey-$size.png" \
        "/usr/local/share/icons/hicolor/${size}x${size}/apps/jungey.png"
done
gtk-update-icon-cache -q -f /usr/share/icons/hicolor || true
gtk-update-icon-cache -q -f /usr/local/share/icons/hicolor || true

# The Jungey launcher and its login entry. The launcher opens a newer jar built from
# a checkout (./run.sh keeps one in ~/.local/share/jungey) in place of the packaged one.
install -Dm755 "$OS_DIR/debs/jungey/usr/bin/jungey" /usr/bin/jungey
install -Dm644 "$OS_DIR/debs/jungey/etc/xdg/autostart/jungey.desktop" /etc/xdg/autostart/jungey.desktop

# These hooks only touch system defaults, so they are safe to rerun on an
# installed system (the others set up identity and the live image).
for hook in 12-names.sh 18-theme.sh 20-desktop.sh 25-claude.sh 30-boot-splash.sh 35-boot-menu.sh; do
    bash -e "$OS_DIR/hooks/$hook"
done
echo "==> Updating the boot splash and boot menu"
update-initramfs -u
update-grub

echo "==> Setting up $user"
backup=$home/.config/jungey-look-backup-$(date +%Y%m%d-%H%M%S)
sudo -u "$user" mkdir -p "$backup"
if [ -e "$home/.zshrc" ]; then
    sudo -u "$user" cp "$home/.zshrc" "$backup/"
fi
install -o "$user" -g "$(id -gn "$user")" -m644 /etc/skel/.zshrc "$home/.zshrc"
chsh -s /usr/bin/zsh "$user"
if [ -e "$home/.config/neofetch/config.conf" ]; then
    sudo -u "$user" cp "$home/.config/neofetch/config.conf" "$backup/neofetch.conf"
fi
sudo -u "$user" install -D -m644 /etc/skel/.config/neofetch/config.conf "$home/.config/neofetch/config.conf"

# Drop your own overrides so the new system defaults take effect.
xml=$home/.config/xfce4/xfconf/xfce-perchannel-xml
for channel in xsettings xfwm4 xfce4-panel xfce4-terminal xfce4-notifyd; do
    [ -e "$xml/$channel.xml" ] && sudo -u "$user" cp "$xml/$channel.xml" "$backup/"
done
if [ -S "/run/user/$uid/bus" ]; then
    as_user() { sudo -u "$user" DISPLAY=:0 DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$uid/bus" "$@"; }
    for channel in xsettings xfwm4 xfce4-panel xfce4-terminal xfce4-notifyd; do
        as_user xfconf-query -c "$channel" -p / -r -R 2>/dev/null || true
    done
    [ -d "$home/.config/xfce4/panel" ] && sudo -u "$user" mv "$home/.config/xfce4/panel" "$backup/panel"
    as_user xfce4-panel -r 2>/dev/null || true
fi

echo
echo "Done. Your previous settings are in $backup"
echo "Log out and back in to see everything (the new zsh terminal included)."
