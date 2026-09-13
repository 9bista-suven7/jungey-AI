# Jungey

*Just a Unified Neural Gateway Engineered for You.*

A local-first desktop assistant for Linux, in the spirit of JARVIS. Java 21 + JavaFX.
Simple commands are answered instantly on-device; it reaches the network only when
the question actually needs live data.

---

## Run it

```bash
cd ~/Documents/jungey-AI
mvn javafx:run
```

First run downloads JavaFX and Jackson (~40 MB) and takes a minute. After that it starts in a couple of seconds.

To build a standalone jar instead:

```bash
mvn package
java -jar target/jungey-0.1.0.jar
```

## Try these

```
help
what time is it
status report
weather
weather in Kathmandu
who is Nikola Tesla
news
open firefox
open camera
take a photo
record video
watch this
what happened
stop watching
screenshot
what is 15% of 240
what is on my screen
read my screen
read that
what is this
why is my laptop slow
any updates
translate this to Nepali
what does this error mean
explain this
note: pick up the dry cleaning
add to my todo call the bank
set a timer for 10 minutes
remind me in 5 minutes to stretch
am I online
switch to firefox
find my invoice pdf
volume up
lock the screen
purple, what time is it
voice off
good answer
the correct answer is Kathmandu
training stats
export training data
clear
exit
```

## How it's put together

Everything routes through `Brain`, which asks each skill in priority order whether it
wants the utterance. First one to claim it wins.

| Tier | Priority | Skills | Cost |
|---|---|---|---|
| Local | 5–99 | help, time, maths, voice, timers, notes, diagnostics, system, network, controls, updates, ocr, camera, screenshot, windows, launcher, find, translate, clipboard, training | instant, offline |
| Online | 200–999 | weather, vision, lookup, news | one HTTP call, or a local vision model |
| Model | 9000 | converse | local LLM, catch-all |

That ordering is the whole point: "what time is it" never touches a model, so Jungey
feels immediate. Only genuinely open-ended questions fall through to the LLM.

```
src/main/java/dev/suven/jungey/
├── JungeyApp.java        window, event loop, boot sequence
├── Launcher.java         entry point for the shaded jar
├── core/
│   ├── Brain.java        routes utterances to skills
│   ├── Skill.java        the interface every skill implements
│   ├── Config.java       ~/.config/jungey/jungey.properties
│   ├── Personality.java  greetings and voice lines
│   └── SysInfo.java      reads /proc and /sys directly
├── skills/               one file per capability
├── ui/
│   ├── ReactorView.java  the animated arc reactor
│   ├── ConsoleView.java  transcript with typewriter effect
│   └── StatusBar.java    live CPU / memory / battery
├── net/Http.java         shared HTTP client
└── voice/
    ├── Speaker.java      text to speech
    └── Listener.java     wake word and speech recognition
```

## Adding a skill

Implement `Skill`, register it in `Brain`'s constructor. Four methods and it's live —
`help` picks it up automatically.

```java
public class CoffeeSkill implements Skill {
    public String name() { return "coffee"; }
    public String description() { return "Counts your coffees."; }
    public String[] examples() { return new String[]{"another coffee"}; }
    public int priority() { return 50; }
    public boolean matches(String in) { return in.contains("coffee"); }
    public SkillResult run(String in) { return SkillResult.of("That's four today."); }
}
```

Keep `matches()` cheap — it runs on the UI thread. `run()` runs on a worker, so blocking
network calls there are fine.

## Optional extras

Jungey works without both of these. They're upgrades, not requirements.

**Voice output** — it speaks if a TTS engine is on PATH, stays silent otherwise.

```bash
sudo apt install espeak-ng
```

Speech is on once an engine is installed. Say `voice off` to mute it and `voice on` to
bring it back; the choice is written to the config, so it survives a restart. `voice`
on its own reports the current state.

For a much better voice, install [Piper](https://github.com/rhasspy/piper) and drop a
`.onnx` model at `~/.local/share/piper/en_GB-alan-medium.onnx`. Jungey prefers Piper
when it finds it.

**Speech input** — say "purple" and it listens for the next thing you say; "purple, what
time is it" in one breath works too, and it has to lead the sentence, so "I like purple
shirts" is ignored.

The wake word must be a word the recogniser already knows. A model can only emit words
from its vocabulary, so a name it has never seen comes out as whatever sounds nearest,
differently every time — which is why the wake word is not "Jungey". Check a replacement
before choosing it:

```bash
grep -cx "yourword [0-9]*" ~/.local/share/vosk/model/graph/words.txt
```

Recognition is offline via Vosk, so audio never leaves the machine. The engine comes from
Maven but the model does not:

```bash
mkdir -p ~/.local/share/vosk
curl -LO https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip
unzip vosk-model-en-us-0.22.zip -d ~/.local/share/vosk
mv ~/.local/share/vosk/vosk-model-en-us-0.22 ~/.local/share/vosk/model
```

That one is 1.8 GB and wants a few GB of RAM. On a laptop CPU, move its `rescore` and `rnnlm`
folders out of the model directory: they are optional rescoring passes that keep a whole core
busy, fall behind live speech and drop audio - which is exactly when a wake word goes unheard. `vosk-model-small-en-us-0.15` is 40 MB and
plenty for commands if you would rather not spend the disk. The status bar shows `MIC WAKE`
while waiting for the wake word and `MIC LIVE` while a command is being taken. With no
model installed Jungey says so once at boot and stays keyboard-only.

**Camera and screen** — "open camera", "take a photo", "record video", "stop recording"
and "screenshot". Stills and screenshots land in `~/Pictures/Jungey`, video in
`~/Videos/Jungey`. Capture needs ffmpeg; the preview uses Cheese, and screenshots prefer
whatever the desktop already provides.

```bash
sudo apt install ffmpeg cheese
```

Photos discard the first 30 frames, because webcams open dark and need a moment to settle
their exposure. Recording stops itself after five minutes if nobody says "stop recording".

**Watching a scene** - "watch this" keeps an eye on whatever the camera sees; "what happened"
(or "brief me") says what changed: something put down, taken away, nudged or moved from one
spot to another, with a before-and-after picture of each. Detection is pixel arithmetic at two
frames a second, so it costs almost nothing while nothing happens; a hand passing through
leaves nothing changed and is not reported as a change. Only real changes go to the vision
model, which names the objects in the background so the briefing is usually ready when asked.
Pictures are kept in `~/Pictures/Jungey/watch`. The camera is held while watching, so say
"stop watching" before opening the preview or recording.

**Notes and reminders** — notes and todos are appended to plain markdown in
`~/Documents/Jungey`, so they outlive Jungey and can be grepped, synced or edited by hand.
Timers live in memory and speak up when they are due, through the transcript, the voice
and the desktop's notifications — the window is usually not what you are looking at.

**Pointing at things** — "read that" and "what is this" let you drag a box around part of
the screen, then run OCR or the vision model on just that. Faster than the whole screen,
and far more precise.

**Reading text** — "read my screen" runs OCR through tesseract and returns the characters
that are actually there. Prefer it over the vision model for anything exact: a version
number, a stack trace, an error code.

```bash
sudo apt install tesseract-ocr
```

**Sight** — "what is on my screen", "what does this error mean", "what am I holding".
Jungey grabs a frame, scales it down and asks a local vision model about it. Nothing is
uploaded.

```bash
ollama pull moondream
```

`moondream` is about 1.7 GB and answers in a few seconds on a CPU. `llava:7b` sees more
detail and takes longer; set `llm.visionModel` to whichever you pulled.

**Conversation** — anything no skill matched goes to a local model via Ollama.

```bash
curl -fsSL https://ollama.com/install.sh | sh
ollama pull llama3.2:3b
```

`llama3.2:3b` is about 2 GB and runs on modest hardware. If it feels slow, drop to
`llama3.2:1b` in the config. Without Ollama everything else still works — unmatched
input just says the reasoning core is offline.

## Odds and ends

Up and down at the prompt walk back through what you have typed. "Stop" cuts off a
sentence being spoken without turning speech off — that is what "voice off" is for. Every
exchange is appended to `~/.local/share/jungey/transcript.log`, so the conversation
outlives the window.

## Training data

Every exchange is saved to a local SQLite database at `~/.local/share/jungey/jungey.db`.
Say `good answer`, `bad answer` or `the correct answer is …` straight after a reply to rate
or correct it, and `export training data` to write the conversations out as chat-format
JSONL for fine-tuning. [docs/TRAINING.md](docs/TRAINING.md) covers the rest - Ollama runs
models but does not train them.

## Settings

`~/.config/jungey/jungey.properties`, created on first run. Restart to apply.

| Key | Default | Notes |
|---|---|---|
| `user.name` | your login name | what Jungey calls you |
| `user.honorific` | `sir` | used for flourish |
| `voice.enabled` | `true` | what `voice on` / `voice off` writes |
| `voice.engine` | `auto` | `auto` / `piper` / `espeak` / `none` |
| `voice.rate` | `165` | espeak words per minute |
| `voice.input.enabled` | `true` | set `false` to stop listening entirely |
| `voice.input.wakeWord` | `purple` | what rouses it; must be in the model's vocabulary |
| `voice.input.wakeVariants` | *(blank)* | comma-separated near-misses to also accept |
| `voice.input.model` | `~/.local/share/vosk/model` | unpacked Vosk model |
| `camera.device` | `/dev/video0` | which webcam to use |
| `weather.location` | *(blank)* | blank = detect by IP |
| `llm.model` | `llama3.2:3b` | any model you've pulled |
| `llm.visionModel` | `moondream` | used for screen and camera questions |
| `llm.url` | `http://localhost:11434` | Ollama endpoint |
| `llm.keepAlive` | `60m` | how long Ollama keeps a model loaded; reloading from disk is the slow part |
| `ui.alwaysOnTop` | `false` | pin above other windows |

## Roadmap

- [x] **v0.1** — HUD, boot sequence, command router, 7 skills
- [x] **v0.2** — speech input (Vosk, offline) and a wake word
- [x] **v0.3** — memory: notes, reminders, timers (markdown on disk, timers in memory)
- [x] **v0.4** — system control (volume, lock, windows, network)
- [ ] **v0.5** — tray icon, global hotkey, autostart
- [ ] **v1.0** — packaged `.deb`
