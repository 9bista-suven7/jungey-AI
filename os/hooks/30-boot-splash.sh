# Runs inside the new system. The boot splash: the machine maker's logo with a
# spinner (Ubuntu's "bgrt" theme), and Jungey's emblem where Ubuntu's would be.
set -euo pipefail

watermark=/usr/share/plymouth/themes/spinner/watermark.png
if ! dpkg-divert --list "$watermark" | grep -q .; then
    dpkg-divert --local --rename --divert "$watermark.ubuntu" --add "$watermark"
fi
# A copy, not a link: the initramfs gets the theme's files, not what they point to.
cp /usr/share/jungey-os/boot-watermark.png "$watermark"
