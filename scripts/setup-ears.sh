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
#   scripts/setup-ears.sh                  # install the wake model
#   scripts/setup-ears.sh --check          # report what is installed, change nothing
#
set -euo pipefail

VOSK_HOME="$HOME/.local/share/vosk"
WAKE_DIR="$VOSK_HOME/wake-model"
WAKE_MODEL="vosk-model-small-en-us-0.15"
WAKE_URL="https://alphacephei.com/vosk/models/$WAKE_MODEL.zip"
CONFIG="$HOME/.config/jungey/jungey.properties"

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
}

if [ "${1:-}" = "--check" ]; then
  report
  exit 0
fi
[ $# -eq 0 ] || die "Unknown option: $1"

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

echo
echo "Restart Jungey. The console should say \"grammar wake\" when the ears open."
