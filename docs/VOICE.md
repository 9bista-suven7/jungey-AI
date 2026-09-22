# Voice

Two separate problems live here, and they have separate answers.

- **The voice sounds like a robot.** That is the speech *engine*, and the fix is to install
  a better one. No amount of code changes how espeak-ng sounds.
- **Commands are misheard.** That is the *recogniser*, and the fix is partly architectural
  and partly a better model.

## Sounding human

```bash
scripts/setup-voice.sh
```

That installs [Piper](https://github.com/rhasspy/piper) — a single self-contained binary,
no Python, no pip, no root — into `~/.local/share/jungey/piper`, links it into
`~/.local/bin`, downloads a voice from Hugging Face, and points the config at it. Restart
Jungey and say `voice test`.

Piper is a neural text-to-speech model that runs on your CPU. It is free, it needs no
account, it works on a train, and it starts speaking in about a fifth of a second. It is
the right answer for a desktop assistant, and everything else on this page is a fallback.

Voices come from [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices).
`scripts/setup-voice.sh --list` shows the handful worth starting with; `--voice NAME`
installs a different one.

| Voice | Sounds like |
|---|---|
| `en_GB-alan-medium` | male, British, calm — the default |
| `en_GB-cori-high` | female, British — the most natural of the set |
| `en_US-amy-medium` | female, American, bright |
| `en_US-ryan-high` | male, American, warm |

`high` voices are slower to synthesise and noticeably better. On a modern laptop the
difference is not worth worrying about.

Tuning, in `~/.config/jungey/jungey.properties`:

| Key | Default | Notes |
|---|---|---|
| `voice.piper.model` | `~/.local/share/piper/en_GB-alan-medium.onnx` | the `.onnx`; its `.onnx.json` must sit beside it |
| `voice.piper.speed` | `1.0` | above 1 is slower and more deliberate, below is quicker |
| `voice.piper.pause` | `0.35` | seconds of silence between sentences |
| `voice.maxChars` | `400` | longer replies are cut short aloud and left in full on screen |

### The hosted option

Set `voice.engine=hf` and Jungey sends each line to Hugging Face and plays back the audio
it returns. It needs a token (see below) and the network, and it adds roughly a second to
every reply, which is a long time when you are waiting to be told what time it is. Repeated
lines are cached under `~/.cache/jungey/tts`, so greetings only cost the round trip once.

`voice.hf.ttsModel` picks the model. `facebook/mms-tts-eng` is the default because it is
almost always served; `hexgrad/Kokoro-82M` sounds considerably more human when a provider
has it loaded. Which models are available through the inference API changes over time, so
if a model answers 404, try another one — Jungey falls back to the local engine and says so
in the console rather than going quiet.

### Why it used to break up

espeak-ng opened the ALSA device itself. On a PulseAudio desktop that is contended, and
contention sounds like crackling and dropped syllables. Every engine now writes audio to
Jungey, which plays it through one Java mixer line, so `stop` also cuts speech off cleanly
whoever is speaking.

Replies are also rewritten before they are spoken: `7.8/15.5 GB` becomes "7.8 of 15.5
gigabytes", `61%` becomes "61 percent", paths shrink to their filename and URLs become
"a link". That is [Spoken](../src/main/java/dev/suven/jungey/voice/Spoken.java), and it is
half of why the same sentence sounds less like a machine.

## Hearing correctly

```bash
scripts/setup-ears.sh
```

Waking and understanding are different jobs, so they use different recognisers.

**Waking** runs against a grammar containing the wake phrase and nothing else. A recogniser
with the whole dictionary open has to pick the name out of a field of eighty thousand
candidates; one that can only answer "the wake word" or "something else" gets it right far
more often, and costs almost nothing to leave running all day.

There is a catch, and it is the reason for the script above. Only Vosk's *small* models
accept a grammar. The large ones are compiled into a single static graph, and when handed a
grammar they log `Runtime graphs are not supported by this model` and carry on with every
word open — which looks exactly like the grammar working badly. So `setup-ears.sh` installs
a 40 MB model next to the large one, used for nothing but hearing the name. The large model
keeps transcribing the command.

The difference is not subtle. Feeding the same clip of "purple, what time is it" to both:

```
small model, grammar    ->  "purple [unk]"           woken
large model, everything ->  "apple what time is it"  never woken
```

`scripts/setup-ears.sh --check` reports which models are installed and whether the one you
have can take a grammar. When the ears open, the console says `grammar wake` or `open wake`
so you can see which you are getting.

**Understanding** needs the full vocabulary. Vosk does it offline and adequately. Whisper
does it considerably better, especially across a room, with a fan running, or in an accent
the model was not built around — but it cannot run on a live stream here, so Vosk decides
when the sentence starts and stops, and Whisper decides what the words were. Two seconds of
audio from before the wake word fired are kept, so "purple, what time is it" said in one
breath does not lose its first word.

If Whisper is not configured, or the network is down, or it returns nothing, whatever Vosk
heard is used instead. Nothing silently stops working.

### Turning Whisper on

`voice.input.engine` chooses:

| Value | What it does |
|---|---|
| `auto` | whisper.cpp if it is installed, Vosk alone otherwise — never leaves the machine |
| `vosk` | Vosk alone |
| `whispercpp` | local [whisper.cpp](https://github.com/ggml-org/whisper.cpp); needs `whisper-cli` on PATH and a model at `voice.input.whisperModel` |
| `hf` | hosted Whisper through Hugging Face; needs a token, and **sends your command audio to their servers** |

Local, if you have a compiler and ten minutes:

```bash
git clone https://github.com/ggml-org/whisper.cpp && cd whisper.cpp
cmake -B build && cmake --build build -j --config Release
sudo install build/bin/whisper-cli /usr/local/bin/
mkdir -p ~/.local/share/whisper
sh ./models/download-ggml-model.sh base.en
cp models/ggml-base.en.bin ~/.local/share/whisper/
```

Then `voice.input.engine=whispercpp`. `base.en` is a good trade; `small.en` is better and
about three times slower.

Hosted, if you would rather not:

```bash
pip install huggingface_hub && hf auth login     # or: export HF_TOKEN=hf_...
```

Then `voice.input.engine=hf`. `voice.input.hf.model` defaults to `openai/whisper-large-v3`.

### The wake word

It must be a word the Vosk model already knows — a recogniser can only emit things in its
vocabulary, which is why the wake word is not "Jungey". Check a candidate before choosing it:

```bash
grep -cx "yourword [0-9]*" ~/.local/share/vosk/model/graph/words.txt
```

`voice.input.wakeModel` points at the small model used for waking; `voice.input.model` is
the large one used for the command.

`voice.input.wakeFuzzy` (on by default) also accepts a wake word one letter wrong, which
covers a microphone having a bad moment and Whisper spelling it its own way.
`voice.input.wakeVariants` takes a comma-separated list of other phrases to accept.

### Tokens

`hf.token` in the config, or `HF_TOKEN` in the environment, or the token
`hf auth login` writes to `~/.cache/huggingface/token` — checked in that order. A config
file is a plain-text file on disk; the environment or the CLI's own store is the better
place for a secret.

## Troubleshooting

**`VOICE ESPEAK` in the status bar.** Piper is not installed, or the `.onnx` at
`voice.piper.model` is missing. `voice` reports which engine is in use and why.

**Nothing is spoken at all.** `voice on`. Then check `voice.enabled` and that
`voice.engine` is not `none`.

**It wakes up on its own.** Pick a less common wake word, or turn off
`voice.input.wakeFuzzy`.

**It never wakes up.** Check `MIC WAKE` is in the status bar at all — if it says `MIC OFF`,
the model or the microphone is missing and the console said so at boot. If the console says
`open wake`, run `scripts/setup-ears.sh`. If the wake word is not in the model's vocabulary,
the console says that too, at startup.

**Commands come out as gibberish.** That is the recogniser, not the wake word. Turn on
Whisper above.
