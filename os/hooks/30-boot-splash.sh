# Runs inside the new system. The boot splash: Jungey's reactor turning over on
# the navy of the wallpaper (install-artwork.sh renders the frames). It uses
# Ubuntu's two-step engine, so encrypted-disk passwords and boot messages work
# exactly as they do under Ubuntu's own spinner.
set -euo pipefail

theme=/usr/share/plymouth/themes/jungey
# The keyboard-layout and caps-lock hints come from the spinner theme as they are.
for f in capslock.png keyboard.png keymap-render.png; do
    cp /usr/share/plymouth/themes/spinner/$f "$theme/$f"
done

# Earlier builds put Jungey's emblem into the spinner theme; give it back.
watermark=/usr/share/plymouth/themes/spinner/watermark.png
if dpkg-divert --list "$watermark" | grep -q .; then
    rm -f "$watermark"
    dpkg-divert --local --rename --remove "$watermark"
fi

update-alternatives --install /usr/share/plymouth/themes/default.plymouth default.plymouth \
    "$theme/jungey.plymouth" 200
update-alternatives --set default.plymouth "$theme/jungey.plymouth"
