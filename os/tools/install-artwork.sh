#!/usr/bin/env bash
# Render Jungey OS's artwork into a system tree: the wallpaper, the login screen,
# the icons, the boot splash's frames and the boot menu's images. build.sh runs it
# against the image, apply-look.sh against the running system.
#
#   os/tools/install-artwork.sh ROOT      # e.g. / or the image's rootfs
#
# Needs rsvg-convert (librsvg2-bin).
set -euo pipefail

ROOT=${1:?usage: install-artwork.sh ROOT}
ART=$(cd "$(dirname "${BASH_SOURCE[0]}")/../artwork" && pwd)
render() {   # render SVG WIDTH HEIGHT OUT
    install -d "$(dirname "$4")"
    rsvg-convert -w "$2" -h "$3" "$1" -o "$4"
}

# Wallpaper and login screen.
bg=$ROOT/usr/share/backgrounds/jungey
install -Dm644 "$ART/wallpaper.svg" "$bg/jungey-default.svg"
render "$ART/wallpaper.svg" 2560 1440 "$bg/jungey-default.png"
render "$ART/login.svg" 2560 1440 "$bg/jungey-login.png"

# "jungey-os": the menu button, installer and os-release logo.
install -Dm644 "$ART/reactor.svg" "$ROOT/usr/share/icons/hicolor/scalable/apps/jungey-os.svg"
render "$ART/reactor.svg" 256 256 "$ROOT/usr/share/pixmaps/jungey-os.png"

# Boot splash (hooks/30-boot-splash.sh makes it the default). The ring has ten
# segments, so a turn of 36 degrees looks exactly like the start: eighteen frames
# of two degrees each loop without a seam, while the core breathes once per loop.
splash=$ROOT/usr/share/plymouth/themes/jungey
install -d "$splash"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for i in $(seq 0 17); do
    angle=$(awk -v i="$i" 'BEGIN { printf "%.1f", -81 + i * 2 }')
    glow=$(awk -v i="$i" 'BEGIN { printf "%.3f", 0.70 + 0.30 * sin(i / 18 * 6.28318) }')
    flash=$(awk -v i="$i" 'BEGIN { printf "%.3f", 0.10 + 0.10 * sin(i / 18 * 6.28318) }')
    sed -e "s/@ANGLE@/$angle/" -e "s/@GLOW@/$glow/" -e "s/@FLASH@/$flash/" \
        "$ART/boot/splash-frame.svg" > "$tmp/frame.svg"
    n=$(printf '%04d' $((i + 1)))
    render "$tmp/frame.svg" 220 220 "$splash/throbber-$n.png"
    cp "$splash/throbber-$n.png" "$splash/animation-$n.png"
done
render "$ART/boot/splash-wordmark.svg" 560 90 "$splash/watermark.png"
render "$ART/boot/splash-entry.svg" 320 40 "$splash/entry.png"
render "$ART/boot/splash-bullet.svg" 14 14 "$splash/bullet.png"
render "$ART/reactor.svg" 30 30 "$splash/lock.png"

# Boot menu images (hooks/35-boot-menu.sh puts the theme under /boot/grub).
grub=$ROOT/usr/share/jungey-os/grub-theme
render "$ART/boot/grub-background.svg" 1920 1080 "$grub/background.png"
render "$ART/boot/grub-select.svg" 16 48 "$grub/select_c.png"
render "$ART/boot/grub-select-w.svg" 6 48 "$grub/select_w.png"
render "$ART/reactor.svg" 32 32 "$grub/icons/jungey.png"
for class in os gnu-linux linux windows macosx memtest; do
    render "$ART/boot/grub-icon-os.svg" 32 32 "$grub/icons/$class.png"
done
render "$ART/boot/grub-icon-settings.svg" 32 32 "$grub/icons/efi.png"
