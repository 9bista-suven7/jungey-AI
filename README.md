# Jungey

*Just a Unified Neural Gateway Engineered for You.*

A local-first desktop assistant for Linux, in the spirit of JARVIS. Java 21 + JavaFX.
Simple commands are answered instantly on-device; it reaches the network only when
the question actually needs live data.

---

## Run it

```bash
cd ~/Documents/noname
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
clear
exit
```

## How it's put together

Everything routes through `Brain`, which asks each skill in priority order whether it
wants the utterance. First one to claim it wins.

| Tier | Priority | Skills | Cost |
|---|---|---|---|
| Local | 5–99 | help, time, system, launcher | instant, offline |
| Online | 200–999 | weather, lookup, news | one HTTP call |
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
└── voice/Speaker.java    text to speech
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

For a much better voice, install [Piper](https://github.com/rhasspy/piper) and drop a
`.onnx` model at `~/.local/share/piper/en_GB-alan-medium.onnx`. Jungey prefers Piper
when it finds it.

**Conversation** — anything no skill matched goes to a local model via Ollama.

```bash
curl -fsSL https://ollama.com/install.sh | sh
ollama pull llama3.2:3b
```

`llama3.2:3b` is about 2 GB and runs on modest hardware. If it feels slow, drop to
`llama3.2:1b` in the config. Without Ollama everything else still works — unmatched
input just says the reasoning core is offline.

## Settings

`~/.config/jungey/jungey.properties`, created on first run. Restart to apply.

| Key | Default | Notes |
|---|---|---|
| `user.name` | your login name | what Jungey calls you |
| `user.honorific` | `sir` | used for flourish |
| `voice.enabled` | `true` | set `false` to mute |
| `voice.engine` | `auto` | `auto` / `piper` / `espeak` / `none` |
| `voice.rate` | `165` | espeak words per minute |
| `weather.location` | *(blank)* | blank = detect by IP |
| `llm.model` | `llama3.2:3b` | any model you've pulled |
| `llm.url` | `http://localhost:11434` | Ollama endpoint |
| `ui.alwaysOnTop` | `false` | pin above other windows |

## Roadmap

- [x] **v0.1** — HUD, boot sequence, command router, 7 skills
- [ ] **v0.2** — speech input (Vosk, offline) and the "Jungey" wake word
- [ ] **v0.3** — memory: notes, reminders, timers in SQLite
- [ ] **v0.4** — media and system control (volume, brightness, playerctl)
- [ ] **v0.5** — tray icon, global hotkey, autostart
- [ ] **v1.0** — packaged `.deb`
