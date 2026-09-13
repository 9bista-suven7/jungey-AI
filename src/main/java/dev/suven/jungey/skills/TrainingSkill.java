package dev.suven.jungey.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.suven.jungey.core.Journal;
import dev.suven.jungey.core.Personality;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feedback on replies, and turning the conversation history into training data.
 *
 * <p>Ratings and corrections apply to the exchange just before, so "that was wrong, the
 * correct answer is Kathmandu" teaches the model what it should have said. The export is
 * chat-format JSONL - one conversation per line - which is what fine-tuning tools read.
 */
public class TrainingSkill implements Skill {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern GOOD = Pattern.compile(
            "^(good answer|good job|thumbs up|that was right|that'?s right|correct|perfect)$");
    private static final Pattern BAD = Pattern.compile(
            "^(bad answer|wrong answer|thumbs down|that was wrong|that'?s wrong|wrong)$");
    private static final Pattern CORRECTION = Pattern.compile(
            "(?i)^(?:correction:?|the (?:correct|right) answer (?:is|was):?|you should have said:?)\\s+(.+)$");
    private static final Pattern EXPORT = Pattern.compile(
            "^export (?:the |my )?training data$");
    private static final Pattern STATS = Pattern.compile(
            "^(?:training (?:stats|status)|how much training data(?: do i have| is there)?)$");

    /** The conversation window LlmSkill sends, so each example looks like a real request. */
    private static final int MAX_TURNS = 6;

    private final Journal journal;

    public TrainingSkill(Journal journal) {
        this.journal = journal;
    }

    @Override
    public String name() {
        return "training";
    }

    @Override
    public String description() {
        return "Rates and corrects replies, and exports them as training data.";
    }

    @Override
    public String[] examples() {
        return new String[]{"good answer", "the correct answer is Kathmandu", "training stats", "export training data"};
    }

    @Override
    public int priority() {
        return 8;
    }

    @Override
    public boolean matches(String input) {
        String s = tidy(input);
        return GOOD.matcher(s).matches() || BAD.matcher(s).matches() || CORRECTION.matcher(s).matches()
                || EXPORT.matcher(s).matches() || STATS.matcher(s).matches();
    }

    @Override
    public SkillResult run(String input) throws Exception {
        if (!journal.available()) {
            return SkillResult.error("The training database is unavailable, so there is nothing to work with.");
        }

        String s = tidy(input);
        if (EXPORT.matcher(s).matches()) return export();
        if (STATS.matcher(s).matches()) return stats();

        Journal.Exchange last = journal.last();
        if (last == null) return SkillResult.of("There is nothing to rate yet.");

        Matcher correction = CORRECTION.matcher(input.trim());
        if (correction.matches()) {
            journal.correct(last.id(), correction.group(1).trim());
            return SkillResult.of("Noted. I will learn that the answer to \"" + shorten(last.input()) + "\" was that.");
        }
        if (GOOD.matcher(s).matches()) {
            journal.rate(last.id(), 1);
            return SkillResult.of(Personality.affirm() + " Marked as a good answer.");
        }
        journal.rate(last.id(), -1);
        return SkillResult.of("Marked as a poor answer. Tell me \"the correct answer is\" and I will keep that instead.");
    }

    private SkillResult stats() {
        Journal.Stats st = journal.stats();
        int ready = conversations().stream().mapToInt(List::size).sum();
        return SkillResult.of(ready + " conversation turns are ready to export.",
                String.format("""
                        exchanges recorded   %d
                        conversational       %d
                        rated good           %d
                        rated bad            %d
                        corrected            %d
                        exportable turns     %d
                        database             %s""",
                        st.total(), st.conversation(), st.good(), st.bad(), st.corrected(), ready, Journal.FILE));
    }

    private SkillResult export() throws Exception {
        List<List<Journal.Exchange>> conversations = conversations();
        if (conversations.isEmpty()) {
            return SkillResult.of("There are no conversations to export yet. Talk to me first.");
        }

        Path dir = Path.of(System.getProperty("user.home"), "Documents", "Jungey", "training");
        Files.createDirectories(dir);
        Path file = dir.resolve("jungey-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".jsonl");

        int turns = 0;
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (List<Journal.Exchange> conversation : conversations) {
                ObjectNode line = MAPPER.createObjectNode();
                ArrayNode messages = line.putArray("messages");
                messages.addObject().put("role", "system").put("content", LlmSkill.systemPrompt());
                for (Journal.Exchange ex : conversation) {
                    messages.addObject().put("role", "user").put("content", ex.input());
                    messages.addObject().put("role", "assistant").put("content", ex.bestReply());
                    turns++;
                }
                out.write(MAPPER.writeValueAsString(line));
                out.newLine();
            }
        }

        return SkillResult.of("Exported " + turns + " turns in " + conversations.size()
                + " conversations.", file.toString());
    }

    /**
     * Conversations worth learning from: consecutive turns with the model from one launch,
     * split into windows the size LlmSkill sends. A turn rated bad and never corrected is
     * dropped, and breaks the conversation there, since what followed was built on it.
     */
    private List<List<Journal.Exchange>> conversations() {
        List<List<Journal.Exchange>> out = new ArrayList<>();
        List<Journal.Exchange> current = new ArrayList<>();
        String session = null;

        for (Journal.Exchange ex : journal.successfulBySkill("converse")) {
            boolean rejected = ex.rating() != null && ex.rating() < 0 && ex.correction() == null;

            if (!ex.session().equals(session) || rejected || current.size() == MAX_TURNS) {
                if (!current.isEmpty()) out.add(current);
                current = new ArrayList<>();
                session = ex.session();
            }
            if (!rejected) current.add(ex);
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }

    private static String tidy(String input) {
        return input.trim().toLowerCase().replaceAll("[.!]+$", "").trim();
    }

    private static String shorten(String text) {
        return text.length() > 60 ? text.substring(0, 57) + "..." : text;
    }
}
