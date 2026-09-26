#!/usr/bin/env bash
# Give Jungey a human voice.
#
# Installs Piper - a neural text-to-speech engine that runs locally, for free, with no
# account and no network - and one voice for it. espeak-ng, the fallback Jungey uses when
# nothing better is installed, is formant synthesis from the 1980s: no amount of tuning
# makes it sound like a person. This is the fix.
#
#   scripts/setup-voice.sh                 # install Piper and the default voice
#   scripts/setup-voice.sh --voice en_US-amy-medium
#   scripts/setup-voice.sh --list          # show the voices this script knows about
#
set -euo pipefail

VOICE="en_GB-alan-medium"
PIPER_HOME="$HOME/.local/share/piper"
PIPER_DIR="$HOME/.local/share/jungey/piper"
VENV="$HOME/.local/share/jungey/piper-venv"
BIN="$HOME/.local/bin"
CONFIG="$HOME/.config/jungey/jungey.properties"
RELEASE="https://github.com/rhasspy/piper/releases/download/2023.11.14-2"

# Voices worth starting from, as name:path-inside-the-repository.
# The full catalogue is at https://huggingface.co/rhasspy/piper-voices
VOICES=(
  "en_GB-alan-medium:en/en_GB/alan/medium"      # male, British, calm - the default
  "en_GB-cori-high:en/en_GB/cori/high"          # female, British, the most natural of these
  "en_GB-alba-medium:en/en_GB/alba/medium"      # female, Scottish
  "en_US-amy-medium:en/en_US/amy/medium"        # female, American, bright
  "en_US-ryan-high:en/en_US/ryan/high"          # male, American, warm
  "en_US-hfc_female-medium:en/en_US/hfc_female/medium"
)

say() { printf '\033[1m%s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*" >&2; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

list_voices() {
  say "Voices this script can fetch:"
  for entry in "${VOICES[@]}"; do
    printf '  %s\n' "${entry%%:*}"
  done
  echo
  echo "Anything else from https://huggingface.co/rhasspy/piper-voices works too -"
  echo "download the .onnx and .onnx.json yourself and point voice.piper.model at it."
}

while [ $# -gt 0 ]; do
  case "$1" in
    --voice) VOICE="${2:?--voice needs a name}"; shift 2 ;;
    --list) list_voices; exit 0 ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

VOICE_PATH=""
for entry in "${VOICES[@]}"; do
  [ "${entry%%:*}" = "$VOICE" ] && VOICE_PATH="${entry#*:}"
done
[ -n "$VOICE_PATH" ] || { warn "Unknown voice: $VOICE"; list_voices; exit 1; }

command -v curl >/dev/null || die "curl is needed to download the voice."

# ---------------------------------------------------------------- the engine

if command -v piper >/dev/null; then
  say "Piper is already installed: $(command -v piper)"
else
  # Prefer the self-contained binary: it needs no Python, no pip and no root, which
  # matters because python3-venv is not installed by default on Debian or Ubuntu and
  # asking someone to sudo their way through a voice download is a poor trade.
  case "$(uname -m)" in
    x86_64|amd64) ARCH="x86_64" ;;
    aarch64|arm64) ARCH="aarch64" ;;
    armv7l) ARCH="armv7l" ;;
    *) ARCH="" ;;
  esac

  if [ -n "$ARCH" ] && [ "$(uname -s)" = "Linux" ]; then
    say "Downloading Piper for $ARCH"
    mkdir -p "$PIPER_DIR"
    curl --fail --location --progress-bar --output "$PIPER_DIR/piper.tar.gz" \
      "$RELEASE/piper_linux_$ARCH.tar.gz" || die "Download failed: $RELEASE/piper_linux_$ARCH.tar.gz"

    tar -xzf "$PIPER_DIR/piper.tar.gz" -C "$PIPER_DIR" --strip-components=1
    rm -f "$PIPER_DIR/piper.tar.gz"
    [ -x "$PIPER_DIR/piper" ] || die "The Piper archive did not contain a piper binary."

    # A wrapper rather than a symlink: the binary looks for its espeak-ng-data and its
    # shared libraries beside itself, and a wrapper keeps that true from anywhere.
    mkdir -p "$BIN"
    cat > "$BIN/piper" <<WRAPPER
#!/usr/bin/env bash
# Installed by jungey scripts/setup-voice.sh
exec "$PIPER_DIR/piper" "\$@"
WRAPPER
    chmod +x "$BIN/piper"
    say "Installed $BIN/piper"

  else
    say "No prebuilt Piper for this platform; installing from pip into $VENV"
    command -v python3 >/dev/null || die "python3 is needed to install Piper here."
    python3 -m venv "$VENV" 2>/dev/null \
      || die "Could not create a virtualenv. On Debian and Ubuntu: sudo apt install python3-venv"

    "$VENV/bin/pip" install --quiet --upgrade pip
    "$VENV/bin/pip" install --quiet piper-tts \
      || die "pip could not install piper-tts. See https://github.com/OHF-Voice/piper1-gpl"

    mkdir -p "$BIN"
    ln -sf "$VENV/bin/piper" "$BIN/piper"
    say "Linked $BIN/piper"
  fi

  case ":$PATH:" in
    *":$BIN:"*) ;;
    *) warn "$BIN is not on your PATH. Add this to ~/.bashrc and open a new terminal:"
       warn "  export PATH=\"\$HOME/.local/bin:\$PATH\"" ;;
  esac
fi

# ----------------------------------------------------------------- the voice

mkdir -p "$PIPER_HOME"
BASE="https://huggingface.co/rhasspy/piper-voices/resolve/main/$VOICE_PATH/$VOICE"

for suffix in .onnx .onnx.json; do
  target="$PIPER_HOME/$VOICE$suffix"
  if [ -s "$target" ]; then
    say "Have $(basename "$target") already"
  else
    say "Downloading $(basename "$target")"
    curl --fail --location --progress-bar --output "$target.part" "$BASE$suffix" \
      || { rm -f "$target.part"; die "Download failed: $BASE$suffix"; }
    mv "$target.part" "$target"
  fi
done

# ---------------------------------------------------------------- the config

# Only touch the model line; everything else in there is the user's business.
if [ -f "$CONFIG" ]; then
  python3 - "$CONFIG" "$PIPER_HOME/$VOICE.onnx" <<'PY'
import sys, re
path, model = sys.argv[1], sys.argv[2]
text = open(path).read()
line = f"voice.piper.model={model}"
if re.search(r"(?m)^voice\.piper\.model=", text):
    text = re.sub(r"(?m)^voice\.piper\.model=.*$", line, text)
else:
    text = text.rstrip("\n") + "\n" + line + "\n"
open(path, "w").write(text)
print(f"Set voice.piper.model to {model}")
PY
else
  warn "No config at $CONFIG yet - Jungey writes it on first run."
  warn "If you chose a voice other than the default, set voice.piper.model there afterwards."
fi

# ------------------------------------------------------------------ listen

echo
say "Done. Testing the voice:"

# Not necessarily on PATH yet in this shell, hence the fallback.
PIPER_CMD="$(command -v piper || echo "$BIN/piper")"
"$PIPER_CMD" --model "$PIPER_HOME/$VOICE.onnx" --output-raw 2>/dev/null \
  <<< "Good evening. This is how I sound now." \
  | { aplay -r 22050 -f S16_LE -t raw -q - 2>/dev/null || paplay --raw --format=s16le --rate=22050 --channels=1; } \
  || warn "Could not play the sample here, but Jungey plays audio through Java, not aplay."

echo
echo "Restart Jungey and say \"voice test\" to hear it in the app."
