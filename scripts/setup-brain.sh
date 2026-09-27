#!/usr/bin/env bash
# Bring Jungey's reasoning core online.
#
# Installs Ollama, makes sure its server is running, pulls a Llama 3 model and points
# llm.model at it. Anything no skill matches goes to this model; without it Jungey
# answers "My reasoning core is offline".
#
#   scripts/setup-brain.sh                    # Llama 3.2 3B, about 2 GB
#   scripts/setup-brain.sh --model llama3     # Llama 3 8B, about 4.7 GB, needs ~8 GB RAM
#   scripts/setup-brain.sh --model llama3.2:1b
#
set -euo pipefail

MODEL="llama3.2:3b"
URL="http://localhost:11434"
CONFIG="$HOME/.config/jungey/jungey.properties"

say() { printf '\033[1m%s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*" >&2; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL="${2:?--model needs a name}"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

command -v curl >/dev/null || die "curl is needed to install Ollama."

up() { curl -fsS "$URL/api/version" >/dev/null 2>&1; }

# ---------------------------------------------------------------- the engine

if command -v ollama >/dev/null; then
  say "Ollama is already installed: $(command -v ollama)"
else
  say "Installing Ollama"
  # The official installer also registers a systemd service on Linux, so the server
  # comes back after a reboot without Jungey having to start it.
  curl -fsSL https://ollama.com/install.sh | sh || die "The Ollama installer failed."
fi

# ---------------------------------------------------------------- the server

if up; then
  say "Ollama is running at $URL"
else
  say "Starting Ollama"
  if command -v systemctl >/dev/null && systemctl list-unit-files ollama.service >/dev/null 2>&1; then
    sudo systemctl enable --now ollama >/dev/null 2>&1 || true
  fi
  if ! up; then
    # No systemd (a container, WSL without it): run it in the background ourselves.
    mkdir -p "$HOME/.local/share/jungey"
    nohup ollama serve >"$HOME/.local/share/jungey/ollama.log" 2>&1 &
  fi
  for _ in $(seq 1 30); do up && break; sleep 1; done
  up || die "Ollama did not start. See ~/.local/share/jungey/ollama.log"
  say "Ollama is running at $URL"
fi

# ----------------------------------------------------------------- the model

say "Pulling $MODEL"
ollama pull "$MODEL" || die "Could not pull $MODEL. Names are listed at https://ollama.com/library"

# ---------------------------------------------------------------- the config

# Only touch the model line; everything else in there is the user's business.
if [ -f "$CONFIG" ]; then
  python3 - "$CONFIG" "$MODEL" <<'PY'
import sys, re
path, model = sys.argv[1], sys.argv[2]
text = open(path).read()
line = f"llm.model={model}"
if re.search(r"(?m)^llm\.model=", text):
    text = re.sub(r"(?m)^llm\.model=.*$", line, text)
else:
    text = text.rstrip("\n") + "\n" + line + "\n"
open(path, "w").write(text)
print(f"Set llm.model to {model}")
PY
elif [ "$MODEL" != "llama3.2:3b" ]; then
  warn "No config at $CONFIG yet - Jungey writes it on first run."
  warn "Set llm.model=$MODEL there afterwards."
fi

# ------------------------------------------------------------------- think

echo
say "Done. Asking $MODEL a question:"
ollama run "$MODEL" "In one short sentence, introduce yourself as Jungey's reasoning core." \
  || warn "The model is pulled but did not answer; try: ollama run $MODEL"

echo
echo "Restart Jungey and ask it anything no skill covers."
