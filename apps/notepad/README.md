# Jungey Notepad

A notepad for plain Markdown notes, and the command Jungey can use to read and write them.

It is its own app, beside Jungey rather than inside it: a window of its own, a jar of its
own, its own settings. The notes are ordinary `.md` files in `~/Documents/Jungey`, the same
folder where Jungey keeps what it is told to note down, so its `notes.md` and `todo.md`
show up here too. Any editor, `grep` or sync tool works on the same files.

## Install

```bash
apps/notepad/install.sh
```

That builds it and adds the `jungey-notepad` command and **Jungey Notepad** to the app menu,
for your account. It also offers itself for opening `.md` and `.txt` files.
`apps/notepad/install.sh --uninstall` takes it away again; the notes stay where they are.

## The window

Open **Jungey Notepad** from the menu, or run `jungey-notepad` with nothing after it (or with
files to open). The notes are down the side, newest first, with their tags above them; the
open ones are tabs.

- **Nothing to save.** A note is written a moment after you stop typing, when its tab is
  closed and when you leave the window. The status bar says *Saved*.
- **Nothing lost.** A copy is kept every few minutes while you write and whenever a note is
  closed. *History* (Ctrl+Shift+Y) shows them, and brings one back; Ctrl+Z undoes that too.
  A note deleted goes to `.trash` inside the notes folder.
- **Preview** (Ctrl+E) shows the note as it reads, beside the text. Ticking a box there
  ticks it in the note, and clicking a heading goes to it.
- **Today** (Ctrl+Shift+D) opens today's page in `Journal/`, made with its date the first time.
- **Ctrl+P** finds any note, heading or command by a few of its letters: `mtg` finds
  *Meeting notes*. Start with `>` for commands, `#` for headings in this note.
- **Tags**: write `#anything` in a note and it becomes a chip at the top of the list; click
  one to see only those notes. Ctrl+Shift+F searches the words of every note.
- A new note is named after its first line when you leave it, and removed if you left it
  empty.

If another program changes an open note, the window takes the new text; if you had changed
it too, yours is kept and theirs goes into the note's history.

## Writing

```
Enter on a list    carries the list on; on an empty item, ends it
Ctrl+Enter         ticks a task, or makes the line one
Tab / Shift+Tab    indent / outdent list items
Ctrl+B / Ctrl+I    bold / italic       Ctrl+`  code     Ctrl+Shift+X  strike
Ctrl+K             link (with an address you copied)
Ctrl+Shift+H       heading level
Ctrl+D             duplicate line      Alt+Up/Down  move lines
F5                 the date and time
Ctrl+F / Ctrl+H    find / replace (case, whole words, regular expressions)
Ctrl+= / Ctrl+-    text size           F11  focus mode
```

Pasting an address over selected words makes them a link. F1 in the window lists every key.

## Commands

```
jungey-notepad list [N]             the latest notes, 10 if not given
jungey-notepad read NOTE            what a note says
jungey-notepad search WORDS         the notes with all these words in them
jungey-notepad new TITLE [TEXT]     start a note
jungey-notepad add NOTE TEXT        add a line to a note; started if there is none
jungey-notepad today [TEXT]         today's journal page; with TEXT, add it there
jungey-notepad tasks [NOTE]         what is not ticked yet, in every note or one
jungey-notepad open [NOTE]          show a note in the window ("open today" for today's page)
jungey-notepad folder               where the notes are
```

`NOTE` is a note's title or file name, or enough of it to tell which: `add shopping eggs`
adds to *Shopping list*. `add` goes on the end of the list the note ends with, as the
same kind of item: a bullet, an empty box after a task, the next number. It never guesses
which note is meant; if no title has those words, it starts a note with that name.

Add `--json` for `{"ok": true, "message": "…", "data": …}` instead of plain text. The
message is worded to be spoken, the note read out without its marks and its lists as
"eggs, bread and jam", which is what Jungey would do with it. Without `--json`, `read`,
`list`, `search` and `tasks` print the notes themselves.

`open` and opening files go to the window already open, if there is one, as new tabs.

## Where things are

- Notes: `~/Documents/Jungey`, or `$JUNGEY_NOTES_DIR`
- Earlier versions: `~/.local/share/jungey-notepad/history`, outside the notes folder so
  sync tools and `grep` do not see them
- Settings (theme, text size, open and pinned notes): `~/.config/jungey-notepad/settings.properties`
