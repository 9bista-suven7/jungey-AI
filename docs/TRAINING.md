# Training Jungey on your own conversations

Ollama *runs* models; it does not train them. Fine-tuning happens in a separate tool, and
the result comes back into Ollama as a new model name. Jungey's part is collecting good
data, which is the part that actually decides how well the fine-tune turns out.

```
talk to Jungey ──► jungey.db ──► "export training data" ──► .jsonl
                                                               │
                         GPU (Colab / Kaggle / rented) ◄───────┘
                         LoRA fine-tune of llama3.2:3b
                                   │
                              .gguf file ──► ollama create ──► llm.model=jungey
```

## 1. Collect

Every exchange is saved to `~/.local/share/jungey/jungey.db` (SQLite, local only): what you
said, which skill answered, the reply, the model, how long it took, and your feedback.

Feedback applies to the reply just before it:

| Say | Effect |
|---|---|
| `good answer` / `thumbs up` | marks the reply good |
| `bad answer` / `that was wrong` | marks it bad; left out of the export |
| `the correct answer is …` / `correction: …` | stores what it *should* have said; the export uses that instead |
| `training stats` | counts of what has been collected |
| `export training data` | writes `~/Documents/Jungey/training/jungey-<date>.jsonl` |

Only conversation with the model (`converse`) is exported. Time, weather, notes and the
other skills are answered by code, and there is nothing for a model to learn from them.

**Corrections are the most valuable thing you can give it.** A fine-tune copies the
replies it is shown, so a set of plain logged replies mostly teaches the model to sound
like itself. Aim for a few hundred rated or corrected turns before training; below about
a hundred, changing the system prompt will do more than a fine-tune.

Peek at the data at any time (`sudo apt install sqlite3` first):

```bash
sqlite3 ~/.local/share/jungey/jungey.db "SELECT id, skill, rating, substr(input,1,40) FROM exchange ORDER BY id DESC LIMIT 20"
```

## 2. Train (not on this laptop)

Fine-tuning needs an NVIDIA GPU. On a CPU with integrated graphics a 3B model takes days,
so use a free cloud GPU instead:

1. Open Unsloth's **Llama 3.2 (1B/3B) conversational** notebook in Google Colab
   (linked from github.com/unslothai/unsloth) and switch the runtime to a T4 GPU.
2. Upload your `.jsonl`. It is already in the `messages` chat format those notebooks
   read, so replace the notebook's sample dataset with
   `load_dataset("json", data_files="jungey-….jsonl", split="train")`.
3. Train a LoRA adapter on `unsloth/Llama-3.2-3B-Instruct`. For a few hundred examples,
   1-3 epochs is plenty; more makes it parrot the data.
4. Use the notebook's GGUF export with `q4_k_m` quantisation and download the `.gguf` file.

## 3. Run it in Ollama

Put the `.gguf` next to a file named `Modelfile`:

```
FROM ./jungey-3b.Q4_K_M.gguf
```

A raw GGUF needs Llama 3.2's chat template. Copy the `TEMPLATE` and `PARAMETER stop`
lines from the model you fine-tuned from:

```bash
ollama show llama3.2:3b --modelfile
```

Then build and try it:

```bash
ollama create jungey -f Modelfile
ollama run jungey "who are you?"
```

Point Jungey at it in `~/.config/jungey/jungey.properties` and restart:

```
llm.model=jungey
```

Exchanges from then on are saved with `model = jungey`, so the fine-tune's replies stay
separate from the original model's in the database.

## Before you train: cheaper things that often work

- **Change the system prompt** in `LlmSkill.systemPrompt()`. It sets tone and rules, and a
  change takes effect instantly.
- **Facts about you** (your name, your city, how you like answers) belong in the prompt,
  not in training. Models learn *style* from fine-tuning well and *facts* poorly.
