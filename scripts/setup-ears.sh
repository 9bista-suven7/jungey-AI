#!/usr/bin/env bash
# Make Jungey hear its name reliably.
#
# Installs a small Vosk model whose only job is spotting the wake word. Waking is a
# yes-or-no question, and a recogniser answers it far more accurately when it is given a
# grammar containing the wake phrase and nothing else - but only the small Vosk models
# accept a grammar at all. The large models are compiled into one static graph, ignore the
# grammar, and leave the name competing with their entire dictionary.
#
# The large model stays where it is and keeps doing what it is good at: transcribing the
# command that follows.
#
# --whisper also builds whisper.cpp, which hears the command itself far better than Vosk
# does - across a room, over a fan, in any accent. Jungey keeps its server running with the
# model loaded, so it costs a moment per command rather than a model load.
#
#   scripts/setup-ears.sh                  # install the wake model
#   scripts/setup-ears.sh --whisper        # ... and whisper.cpp with its base.en model
#   scripts/setup-ears.sh --check          # report what is installed, change nothing
#
set -euo pipefail

VOSK_HOME="$HOME/.local/share/vosk"
WAKE_DIR="$VOSK_HOME/wake-model"
WAKE_MODEL="vosk-model-small-en-us-0.15"
WAKE_URL="https://alphacephei.com/vosk/models/$WAKE_MODEL.zip"
CONFIG="$HOME/.config/jungey/jungey.properties"
BIN="$HOME/.local/bin"
WHISPER_TAG="v1.9.4"
WHISPER_SRC="$HOME/.local/share/jungey/whisper.cpp"
WHISPER_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin"

say() { printf '\033[1m%s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*" >&2; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

wake_word() {
  local word=""
  [ -f "$CONFIG" ] && word="$(awk -F= '/^voice\.input\.wakeWord=/ {print $2; exit}' "$CONFIG")"
  # A config written before the wake word existed has no line for it; Jungey's own
  # default applies in that case.
  printf '%s' "${word:-purple}"
}

setting() {
  [ -f "$CONFIG" ] && awk -F= -v key="$1" '$1 == key {print substr($0, length(key) + 2); exit}' "$CONFIG"
}

whisper_model() {
  local path
  path="$(setting voice.input.whisperModel)"
  path="${path:-$HOME/.local/share/whisper/ggml-base.en.bin}"
  printf '%s' "${path/#\~/$HOME}"
}

report() {
  local main="$VOSK_HOME/model"
  say "Command model: $main"
  if [ -d "$main/am" ]; then
    printf '  installed, %s\n' "$(du -sh "$main" | cut -f1)"
    if [ -f "$main/graph/Gr.fst" ]; then
      printf '  takes a grammar, so it can spot the wake word on its own\n'
    else
      printf '  static graph - cannot take a grammar, hence the wake model below\n'
    fi
  else
    warn "  missing. Jungey stays keyboard-only until it is installed; see README."
  fi

  say "Wake model: $WAKE_DIR"
  if [ -f "$WAKE_DIR/graph/Gr.fst" ]; then
    printf '  installed, %s\n' "$(du -sh "$WAKE_DIR" | cut -f1)"
  elif [ -d "$WAKE_DIR" ]; then
    warn "  present but has no Gr.fst - not a grammar-capable model."
  else
    printf '  not installed\n'
  fi

  say "Whisper: $(whisper_model)"
  if [ -f "$(whisper_model)" ] && command -v whisper-server >/dev/null; then
    printf '  installed, with the server Jungey keeps running\n'
  elif [ -f "$(whisper_model)" ] && command -v whisper-cli >/dev/null; then
    printf '  installed, without whisper-server; --whisper adds it\n'
  else
    printf '  not installed; --whisper adds it\n'
  fi
}

install_whisper() {
  local tool
  for tool in git cmake g++ make; do
    command -v "$tool" >/dev/null \
      || die "$tool is needed to build whisper.cpp. On Debian and Ubuntu: sudo apt install git cmake build-essential"
  done

  if [ -x "$WHISPER_SRC/build/bin/whisper-server" ] \
    && [ "$(git -C "$WHISPER_SRC" describe --tags 2>/dev/null)" = "$WHISPER_TAG" ]; then
    say "whisper.cpp $WHISPER_TAG is already built"
  else
    rm -rf "$WHISPER_SRC"
    mkdir -p "$(dirname "$WHISPER_SRC")"
    say "Downloading whisper.cpp $WHISPER_TAG"
    git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$WHISPER_TAG" \
      https://github.com/ggml-org/whisper.cpp "$WHISPER_SRC" || die "Could not download whisper.cpp."
    say "Building it - a few minutes"
    # Static, so the two binaries can be linked anywhere and still find their libraries.
    cmake -S "$WHISPER_SRC" -B "$WHISPER_SRC/build" -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=OFF -DWHISPER_BUILD_TESTS=OFF >/dev/null
    cmake --build "$WHISPER_SRC/build" -j "$(nproc)" --config Release \
      --target whisper-server whisper-cli >/dev/null
  fi

  mkdir -p "$BIN"
  ln -sf "$WHISPER_SRC/build/bin/whisper-server" "$BIN/whisper-server"
  ln -sf "$WHISPER_SRC/build/bin/whisper-cli" "$BIN/whisper-cli"
  say "Linked whisper-server and whisper-cli into $BIN"

  local model
  model="$(whisper_model)"
  if [ -s "$model" ]; then
    say "The Whisper model is already at $model"
  else
    mkdir -p "$(dirname "$model")"
    say "Downloading the base.en Whisper model (about 140 MB)"
    curl --fail --location --progress-bar --output "$model.part" "$WHISPER_URL" \
      || die "Download failed: $WHISPER_URL"
    mv -f "$model.part" "$model"
    say "Installed $model"
  fi

  case "$(setting voice.input.engine)" in
    vosk | none | off)
      warn "voice.input.engine in $CONFIG is set to Vosk alone, so Whisper will not be used."
      warn "Set it to auto (or whispercpp) to turn Whisper on." ;;
  esac
}

WHISPER=0
case "${1:-}" in
  --check)
    report
    exit 0 ;;
  --whisper) WHISPER=1 ;;
  "") ;;
  *) die "Unknown option: $1" ;;
esac

command -v curl >/dev/null || die "curl is needed to download the model."
command -v unzip >/dev/null || die "unzip is needed. On Debian and Ubuntu: sudo apt install unzip"

if [ -f "$WAKE_DIR/graph/Gr.fst" ]; then
  say "The wake model is already installed at $WAKE_DIR"
else
  mkdir -p "$VOSK_HOME"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT

  say "Downloading $WAKE_MODEL (about 40 MB)"
  curl --fail --location --progress-bar --output "$tmp/model.zip" "$WAKE_URL" \
    || die "Download failed: $WAKE_URL"

  say "Unpacking"
  unzip -q "$tmp/model.zip" -d "$tmp"
  [ -d "$tmp/$WAKE_MODEL" ] || die "The archive did not contain $WAKE_MODEL."

  rm -rf "$WAKE_DIR"
  mv "$tmp/$WAKE_MODEL" "$WAKE_DIR"

  [ -f "$WAKE_DIR/graph/Gr.fst" ] \
    || die "That model has no Gr.fst, so it cannot take a grammar. Nothing has been changed."
  say "Installed $WAKE_DIR"
fi

# ------------------------------------------------- is the wake word usable?

# The small model keeps its vocabulary inside Gr.fst rather than in a words.txt, so the
# large model's list is the closest thing to a check that a shell script can do. The two
# agree on ordinary English words, and Jungey says so at startup if the grammar is
# rejected anyway.
WORD="$(wake_word)"
WORDS="$VOSK_HOME/model/graph/words.txt"
if [ -n "$WORD" ] && [ -f "$WORDS" ]; then
  if grep -qx "$WORD [0-9]*" "$WORDS"; then
    say "\"$WORD\" is a word the models know."
  else
    warn "\"$WORD\" is not in the speech model's vocabulary, so it will never be heard."
    warn "Pick another word and set voice.input.wakeWord in $CONFIG. To test one:"
    warn "  grep -cx \"yourword [0-9]*\" $WORDS"
  fi
fi

if [ "$WHISPER" = 1 ]; then
  install_whisper
fi

echo
echo "Restart Jungey. The console should say \"grammar wake\" when the ears open."
if [ "$WHISPER" = 1 ]; then
  echo "It should end its \"Listening for\" line with \"vosk + whisper.cpp (server)\"."
fi
