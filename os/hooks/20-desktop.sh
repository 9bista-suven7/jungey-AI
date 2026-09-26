# Runs inside the new system. Xfce defaults for every user, live or installed.
# Xfce reads /etc/xdg/xdg-jungey first (see /usr/bin/jungey-session), so these
# files override Xfce's without editing the ones its packages own.
set -euo pipefail

xdg=/etc/xdg/xdg-jungey/xfce4/xfconf/xfce-perchannel-xml
mkdir -p "$xdg"
stock=/etc/xdg/xfce4/xfconf/xfce-perchannel-xml

# Appearance: start from Ubuntu's Xfce defaults and change only what differs.
sed -e 's|\("ThemeName" type="string" value="\)[^"]*|\1Greybird-dark|' \
    -e 's|\("IconThemeName" type="string" value="\)[^"]*|\1elementary-xfce-dark|' \
    -e 's|\("FontName" type="string" value="\)[^"]*|\1Ubuntu 10|' \
    -e 's|\("MonospaceFontName" type="string" value="\)[^"]*|\1Ubuntu Mono 11|' \
    -e 's|\("CursorThemeName" type="string" value="\)[^"]*|\1DMZ-White|' \
    -e 's|\("CursorThemeSize" type="int" value="\)[^"]*|\124|' \
    -e 's|\("Antialias" type="int" value="\)[^"]*|\11|' \
    -e 's|\("Hinting" type="int" value="\)[^"]*|\11|' \
    -e 's|\("HintStyle" type="string" value="\)[^"]*|\1hintslight|' \
    -e 's|\("RGBA" type="string" value="\)[^"]*|\1rgb|' \
    "$stock/xsettings.xml" > "$xdg/xsettings.xml"
grep -q 'Greybird-dark' "$xdg/xsettings.xml"

# Keyboard: the Super key opens the menu, Super+J opens Jungey.
sed -e '/<property name="commands" type="empty">/,/<\/property>/{
/<property name="default" type="empty">/a\
      <property name="Super_L" type="string" value="xfce4-popup-whiskermenu"/>\
      <property name="&lt;Super&gt;j" type="string" value="jungey"/>\
      <property name="&lt;Super&gt;t" type="string" value="exo-open --launch TerminalEmulator"/>
}' "$stock/xfce4-keyboard-shortcuts.xml" > "$xdg/xfce4-keyboard-shortcuts.xml"
grep -q 'xfce4-popup-whiskermenu' "$xdg/xfce4-keyboard-shortcuts.xml"

# The desktop background. xfdesktop falls back to this file for any monitor it
# has no setting for, whatever the monitor is called, so replacing it covers
# every screen. The diversion keeps xfdesktop updates from restoring it.
default_bg=/usr/share/backgrounds/xfce/xfce-shapes.svg
if ! dpkg-divert --list "$default_bg" | grep -q .; then
    dpkg-divert --local --rename --divert "$default_bg.xfce" --add "$default_bg"
fi
ln -sf ../jungey/jungey-default.svg "$default_bg"

# Icon caches, so the Jungey icon shows up in menus straight away.
gtk-update-icon-cache -q -f /usr/share/icons/hicolor || true
