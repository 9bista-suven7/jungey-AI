# Runs inside the new system. Put Claude in the panel and the menu's favourites,
# or take its placeholders out when the image is built without it.
set -euo pipefail

panel=/etc/xdg/xdg-jungey/xfce4/panel/default.xml

# The app's visible menu entry: packages can also ship hidden ones, such as a
# handler for claude:// links, which must not end up in the panel.
entry=
for file in $(dpkg-query -L claude-desktop 2>/dev/null | grep '^/usr/share/applications/[^/]*\.desktop$' || true); do
    if ! grep -qi '^NoDisplay=true' "$file"; then
        entry=$(basename "$file")
        break
    fi
done

if [ -n "$entry" ]; then
    sed -i -e "s|@CLAUDE_DESKTOP@|$entry|" -e 's|<!-- claude -->||' "$panel"
else
    sed -i '/<!-- claude -->/d' "$panel"
fi
! grep -q '@CLAUDE_DESKTOP@' "$panel"
