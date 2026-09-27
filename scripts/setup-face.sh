#!/usr/bin/env bash
# Give Jungey a face: Vision, as he looks in Captain America: Civil War.
#
# With a face installed, Jungey shows a photograph that talks in place of the reactor: the
# jaw and lips move with its voice as it is heard, the eyes blink and glance about, the head
# breathes and sways, and the gem on his forehead glows while he thinks.
#
# The still belongs to Marvel, so it is kept out of this repository and out of Jungey OS.
# This downloads it onto the machine that shows it, crops it to head and shoulders, and
# writes face.json - where the eyes, mouth, chin and gem are in the cropped picture.
#
#   scripts/setup-face.sh            install the face, then restart Jungey to see it
#   scripts/setup-face.sh --remove   take it away again; Jungey goes back to the reactor
set -euo pipefail

FACE_DIR="$HOME/.local/share/jungey/face"
URL='https://static.wikia.nocookie.net/marvelcinematicuniverse/images/9/91/Vision_%282016%29.jpg/revision/latest?format=original'
# The landmarks below were measured on this crop of the still at exactly this size.
SIZE=2153x898
CROP=516:668:689:22

say() { printf '\033[1m%s\033[0m\n' "$*"; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

case "${1:-}" in
  --remove)
    rm -f "$FACE_DIR/face.json" "$FACE_DIR/vision.jpg"
    rmdir "$FACE_DIR" 2>/dev/null || true
    say "Face removed. Restart Jungey to get the reactor back."
    exit 0 ;;
  "") ;;
  -h|--help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) die "Unknown option: $1" ;;
esac

for tool in curl ffmpeg ffprobe; do
  command -v "$tool" >/dev/null || die "$tool is needed: sudo apt install ${tool/ffprobe/ffmpeg}"
done

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

say "Downloading the still"
curl --fail --location --silent --show-error --output "$tmp/still.jpg" "$URL"

size="$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0:s=x "$tmp/still.jpg")"
[ "$size" = "$SIZE" ] || die "The still is ${size:-unreadable}, not $SIZE - it has changed upstream, and the landmarks would not fit it."

say "Cropping it to head and shoulders"
mkdir -p "$FACE_DIR"
ffmpeg -loglevel error -y -i "$tmp/still.jpg" -vf "crop=$CROP" -q:v 2 "$FACE_DIR/vision.jpg"

# Pixels of vision.jpg. Eyes are the openings lid to lid; the mouth is the line the lips
# meet along; head is an ellipse around it, for where the face ends and the shoulders begin.
cat > "$FACE_DIR/face.json" <<'EOF'
{
  "image": "vision.jpg",
  "eyes": [
    {"x": 210, "y": 272.5, "w": 43, "h": 21},
    {"x": 344, "y": 294.5, "w": 47, "h": 21}
  ],
  "mouth": {"left": [193, 419], "middle": [250, 428], "right": [308, 438]},
  "nose": [258, 396],
  "chin": [254, 522],
  "neck": [255, 590],
  "jaw": 100,
  "head": {"x": 262, "y": 295, "rx": 165, "ry": 262},
  "gem": {"x": 300, "y": 153, "r": 13, "color": "#ffc94a"},
  "colors": {"mouth": "#1c0709", "teeth": "#857c76", "tongue": "#4a1519"}
}
EOF

say "Installed in $FACE_DIR - restart Jungey to see it."
