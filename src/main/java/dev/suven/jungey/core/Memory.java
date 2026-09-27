package dev.suven.jungey.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Things you have asked Jungey to remember about you, kept for good.
 *
 * <p>Plain markdown in ~/Documents/Jungey/memory.md, beside the notes and the todo list,
 * so it can be read, corrected and backed up without Jungey. Everything in it is handed to
 * the model with every question, which is how "where did I park?" gets an answer.
 *
 * <p>The file is read again whenever it changes on disk, so editing it by hand works.
 */
public final class Memory {

    private static final Path FILE = Path.of(System.getProperty("user.home"), "Documents", "Jungey", "memory.md");

    /** Words too common to say anything about which fact a question is after. */
    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "am", "i", "me", "my", "mine",
            "you", "your", "it", "its", "to", "of", "in", "on", "at", "for", "and", "or", "but",
            "what", "whats", "where", "wheres", "when", "who", "whom", "which", "how", "why",
            "do", "does", "did", "have", "has", "had", "that", "this", "these", "those", "there",
            "can", "could", "should", "would", "will", "tell", "remember", "know", "again",
            "please", "about", "with", "from", "into", "some", "any", "much", "many");

    private List<String> facts = List.of();
    private FileTime readAt;

    /** Everything remembered, oldest first, without the dates the file keeps beside each. */
    public synchronized List<String> facts() {
        refresh();
        return facts;
    }

    public synchronized void add(String fact) throws IOException {
        String line = "- " + fact.strip() + "  _(" + LocalDate.now() + ")_\n";
        Files.createDirectories(FILE.getParent());
        Files.writeString(FILE, line, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        readAt = null;
    }

    /**
     * Forget every fact that mentions all of these words.
     *
     * @return how many were forgotten
     */
    public synchronized int forget(String words) throws IOException {
        refresh();
        List<String> keys = keywords(words);
        if (keys.isEmpty()) return 0;

        List<String> kept = new ArrayList<>();
        int dropped = 0;
        for (String line : Files.readAllLines(FILE)) {
            List<String> have = keywords(line);
            if (line.startsWith("- ") && keys.stream().allMatch(k -> have.stream().anyMatch(h -> related(h, k)))) {
                dropped++;
            } else {
                kept.add(line);
            }
        }
        if (dropped > 0) {
            Files.write(FILE, kept, StandardCharsets.UTF_8);
            readAt = null;
        }
        return dropped;
    }

    /** Forget everything. The old file is kept beside it, in case that was said in anger. */
    public synchronized int forgetAll() throws IOException {
        int n = facts().size();
        if (Files.exists(FILE)) {
            Files.move(FILE, FILE.resolveSibling("memory.md.bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        readAt = null;
        facts = List.of();
        return n;
    }

    /** The facts sharing a meaningful word with the question, best match first. */
    public synchronized List<String> recall(String question) {
        return recall(question, 0);
    }

    /**
     * The facts that answer a question: those sharing at least this share of its meaningful
     * words. A fact that only has one word in common with a long question is about something
     * else - "my sister's birthday" does not answer "how do I say happy birthday to my sister
     * in Nepali".
     */
    public synchronized List<String> recall(String question, double coverage) {
        List<String> keys = keywords(question);
        if (keys.isEmpty()) return List.of();
        long needed = Math.max(1, (long) Math.ceil(coverage * keys.size()));

        record Scored(String fact, long score) { }
        return facts().stream()
                .map(f -> {
                    List<String> have = keywords(f);
                    return new Scored(f, keys.stream().filter(k -> have.stream().anyMatch(h -> related(h, k))).count());
                })
                .filter(s -> s.score() >= needed)
                .sorted((a, b) -> Long.compare(b.score(), a.score()))
                .map(Scored::fact)
                .toList();
    }

    public Path file() {
        return FILE;
    }

    private void refresh() {
        try {
            if (!Files.exists(FILE)) {
                facts = List.of();
                readAt = null;
                return;
            }
            FileTime modified = Files.getLastModifiedTime(FILE);
            if (modified.equals(readAt)) return;

            List<String> read = new ArrayList<>();
            for (String line : Files.readAllLines(FILE)) {
                if (!line.startsWith("- ")) continue;
                String fact = line.substring(2).replaceAll("\\s+_\\(.*?\\)_\\s*$", "").strip();
                if (!fact.isEmpty()) read.add(fact);
            }
            facts = List.copyOf(read);
            readAt = modified;
        } catch (IOException e) {
            // Keep what was read last time.
        }
    }

    static List<String> keywords(String text) {
        List<String> words = new ArrayList<>();
        for (String w : text.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9 ]", " ").split("\\s+")) {
            if (w.length() >= 3 && !STOP.contains(w)) words.add(w);
        }
        return words;
    }

    /** The same word, or one a suffix away from it: park, parked, parking. */
    private static boolean related(String a, String b) {
        if (a.equals(b)) return true;
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        return shorter.length() >= 4 && longer.startsWith(shorter) && longer.length() - shorter.length() <= 3;
    }
}
