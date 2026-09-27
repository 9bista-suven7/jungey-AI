package dev.suven.jungeynotepad.notes;

/** Counts for the status bar: words, characters, lines, and minutes to read. */
public record TextStats(int words, int characters, int lines, int tasks, int tasksDone) {

    public static TextStats of(String text) {
        int words = 0;
        boolean inWord = false;
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') lines++;
            boolean letter = Character.isLetterOrDigit(c) || c == '\'' || c == '’';
            if (letter && !inWord) words++;
            inWord = letter;
        }
        int tasks = 0;
        int done = 0;
        for (String line : text.split("\n")) {
            String t = line.stripLeading();
            if (t.matches("^[-*+]\\s+\\[[ xX]\\].*")) {
                tasks++;
                if (!t.matches("^[-*+]\\s+\\[ \\].*")) done++;
            }
        }
        return new TextStats(words, text.codePointCount(0, text.length()), lines, tasks, done);
    }

    /** At an unhurried 230 words a minute, never less than one. */
    public int readingMinutes() {
        return Math.max(1, Math.round(words / 230f));
    }
}
