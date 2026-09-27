# Runs inside the new system. The boot menu: Jungey's theme, and its entries
# named Jungey OS.
set -euo pipefail

# --- the theme, where GRUB can read it before any other file system is up ---
theme=/boot/grub/themes/jungey
rm -rf "$theme"
install -d "$theme"
cp -r /usr/share/jungey-os/grub-theme/. "$theme/"
inter=/usr/share/fonts/opentype/inter/Inter-Regular.otf
for size in 16 20 24; do
    # Latin, punctuation, arrows and the middle dot are all the menu ever shows.
    grub-mkfont -s "$size" -r 0x20-0x7E,0xA0-0x17F,0x2000-0x206F,0x2190-0x21FF \
        -o "$theme/inter-$size.pf2" "$inter" 2>/dev/null
done

# --- the names ---
# GRUB_DISTRIBUTOR stays "Ubuntu": grub-install names the EFI folder after it,
# and Secure Boot needs that folder to be "ubuntu". So the menu is renamed where
# it is written instead: Ubuntu's 10_linux, moved out of grub.d, runs under a
# wrapper that relabels its entries. dpkg keeps updating the moved original.
distro=/usr/lib/jungey-os/grub.d/10_linux.distro
install -d "$(dirname "$distro")"
if ! dpkg-divert --list /etc/grub.d/10_linux | grep -q .; then
    dpkg-divert --local --rename --divert "$distro" --add /etc/grub.d/10_linux
fi
cat > /etc/grub.d/10_linux <<'WRAPPER'
#! /bin/sh
# Jungey OS: Ubuntu's 10_linux (moved to /usr/lib/jungey-os/grub.d by a dpkg
# diversion), with its menu entries named Jungey OS and given Jungey's icon.
set -e
entries=$(/usr/lib/jungey-os/grub.d/10_linux.distro "$@")
printf '%s\n' "$entries" | sed \
    -e "/^[[:space:]]*\(menuentry\|submenu\) '/{
          s/^\([[:space:]]*[a-z]* '[^']*\)Ubuntu/\1Jungey OS/
          s/Jungey OS GNU\/Linux/Jungey OS/
          s/--class ubuntu /--class jungey --class ubuntu /
        }"
WRAPPER
chmod 755 /etc/grub.d/10_linux
# On a ZFS root it looks for its ZFS sibling next to itself.
ln -sf /etc/grub.d/10_linux_zfs "$(dirname "$distro")/10_linux_zfs"
