package dev.suven.jungey.skills;

import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Finds files by name, via the system's locate index.
 *
 * <p>Searches your home directory only. The index covers the whole filesystem, and a
 * search for "key" returning half of /usr is noise rather than an answer.
 */
public class FileSearchSkill implements Skill {

    private static final int MAX_RESULTS = 12;

    @Override
    public String name() {
        return "find";
    }

    @Override
    public String description() {
        return "Finds files by name.";
    }

    @Override
    public String[] examples() {
        return new String[]{"find my invoice pdf", "where is my cv"};
    }

    @Override
    public int priority() {
        return 35;
    }

    @Override
    public boolean matches(String input) {
        String s = input.trim();
        return s.matches("^find (my |a |the )?.+")
                || s.matches("^where (is|are) (my |the )?.+")
                || s.matches("^search for .+");
    }

    @Override
    public SkillResult run(String input) throws Exception {
        if (!CameraSkill.onPath("locate")) {
            return SkillResult.error("File search needs locate. Try: sudo apt install plocate");
        }

        String query = input.trim().toLowerCase()
                .replaceFirst("^find (my |a |the )?", "")
                .replaceFirst("^where (is|are) (my |the )?", "")
                .replaceFirst("^search for ", "")
                .replaceFirst("\\?$", "")
                .trim();

        if (query.isBlank()) return SkillResult.error("Find what?");

        List<String> hits = locate(query);
        if (hits.isEmpty()) {
            // The index is a nightly snapshot, so anything recent is invisible until it runs.
            return SkillResult.error("Nothing found for \"" + query
                    + "\". If the file is new, the search index may be stale: sudo updatedb");
        }

        String detail = String.join("\n", hits);
        return SkillResult.of(hits.size() >= MAX_RESULTS
                ? "More than " + MAX_RESULTS + " matches for " + query + "."
                : hits.size() + " match" + (hits.size() == 1 ? "" : "es") + " for " + query + ".", detail);
    }

    /** Every word must appear in the path, so "invoice pdf" does not match every pdf. */
    private static List<String> locate(String query) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("locate", "-i", "--limit", "400"));
        for (String word : query.split("\\s+")) {
            cmd.add("-A");      // AND the patterns together
            cmd.add(word);
        }

        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> hits = new ArrayList<>();
        String home = System.getProperty("user.home");

        try (var reader = p.inputReader()) {
            for (String line : reader.lines().toList()) {
                if (line.startsWith(home) && hits.size() < MAX_RESULTS) {
                    hits.add(line.replaceFirst("^" + java.util.regex.Pattern.quote(home), "~"));
                }
            }
        }
        p.waitFor(20, TimeUnit.SECONDS);
        return hits;
    }
}
