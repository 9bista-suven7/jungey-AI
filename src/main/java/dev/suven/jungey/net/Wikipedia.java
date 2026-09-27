package dev.suven.jungey.net;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Looking things up on Wikipedia, so the model answers from it rather than from memory.
 *
 * <p>A small model knows roughly what Everest is and will confidently get its height wrong.
 * Handed the right two or three sentences of the article, it gets it right. So this finds
 * the article a question is about, and in it the sentences that bear on the question.
 *
 * <p>Those sentences are kept few on purpose. A laptop's CPU reads a prompt at about fifty
 * tokens a second, so every sentence handed to the model is time spent before the answer
 * begins: a hundred words cost two or three seconds, a whole section most of a minute.
 */
public final class Wikipedia {

    /** An article, as its sentences in order, with its opening ones first. */
    public record Article(String title, List<String> sentences) {
        public String url() {
            return "https://en.wikipedia.org/wiki/" + title.replace(' ', '_');
        }
    }

    /** The sentences of an article that answer a question, in the article's order. */
    public record Passage(Article article, String text) {
    }

    /** Words that count towards a sentence's score together: any one of them, once. */
    private record Term(Set<String> stems, double weight, boolean named) {
    }

    /** What an answer says, for what a question asks: asked how tall, it says "elevation". */
    private record Cue(Set<String> says, Set<String> units) {
    }

    private static final String API = "https://en.wikipedia.org/w/api.php?format=json&formatversion=2&redirects=1";

    /** A follow-up - "when did he die?" - refers to what was looked up this recently. */
    private static final long RECENT_MS = 10 * 60 * 1000;

    /**
     * A question of fact: who, what, when, where, which, how many / tall / old..., or a
     * yes-or-no question about something other than the people talking.
     */
    private static final Pattern FACTUAL = Pattern.compile(
            "^(?:(?:and|so|but|ok|okay|hey|jungey|purple)[, ]+)*"
                    + "(?:(?:do you know|can you tell me|could you tell me|tell me|i wonder)\\s+)?"
                    + "(?:who|whom|whose|what|what's|whats|when|where|where's|which"
                    + "|how (?:many|much|tall|high|big|old|long|far|deep|large|heavy|fast|wide|hot|cold|often)"
                    + "|(?:is|are|was|were|did|does|has|have|had) (?!(?:you|i|we|it|this|that|there|they|he|she)\\b))"
                    + "\\b.*");

    /** About the user, or Jungey, or the two of them: nothing Wikipedia knows. */
    private static final Pattern PERSONAL = Pattern.compile(
            "\\b(?:you|your|yours|yourself|i|i'm|i've|me|my|mine|myself|we|our|us)\\b");

    /** Opinions, advice and small talk look like questions of fact but are not. */
    private static final Pattern NOT_FACT = Pattern.compile(
            "^(?:what|how) (?:do|should|can|could|would|shall|will) |\\bthink\\b|\\bopinion\\b"
                    + "|\\bfavou?rite\\b|\\bjoke\\b|\\bweather\\b|\\b(?:time|date|day) is it\\b");

    /** Leans on what was just said: "when did he die?", "and how old is it?". */
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "\\b(?:he|she|it|they|him|her|his|hers|its|their|them)\\b|^(?:and|what about|how about) ");

    /** What leads a question, before what it is about. */
    private static final Pattern SCAFFOLD = Pattern.compile(
            "^(?:(?:and|so|but|ok|okay|hey|jungey|purple)[, ]+)*"
                    + "(?:(?:do you know|can you tell me|could you tell me|tell me|i wonder)\\s+)?"
                    + "(?:who|whom|whose|what|what's|whats|when|where|where's|which"
                    + "|how(?: many| much| tall| high| big| old| long| far| deep| large| heavy| fast| wide"
                    + "| hot| cold| often)?)?\\s*"
                    + "(?:is|are|was|were|did|does|do|has|have|had|'s)?\\s+(?:the |a |an )?");

    /** "Who is the president of ..." asks who holds it now, which is how the article puts it. */
    private static final Pattern HOLDER = Pattern.compile(
            "^(?:who|what) (?:is|are) (?:the )?(?:current )?(?:president|prime minister|premier|leader|head"
                    + "|chief|ceo|king|queen|monarch|chair|chairman|mayor|governor|captain|coach|director"
                    + "|manager|owner|champion)\\b.*");

    /** The answer is a number or a date: sentences with digits in them are more likely it. */
    private static final Pattern WANTS_NUMBER = Pattern.compile(
            "^(?:when|what year)\\b|\\bhow (?:many|much|tall|high|big|old|long|far|deep|large|heavy|fast|wide|hot|cold|often)\\b"
                    + "|\\b(?:year|date|born|died|population|founded|height|elevation|speed|end|ended|start|began)\\b");

    /** Articles about a work of the same name are rarely what a question means. */
    private static final Pattern WORK = Pattern.compile(
            "\\((?:\\d{4} )?(?:[\\w ]+ )?(?:film|song|album|novel|book|band|single|play|musical|tv series"
                    + "|television series|video game|ep|opera|miniseries|soundtrack|episode)\\)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern ASKS_FOR_WORK = Pattern.compile(
            "\\b(?:film|movie|song|album|novel|book|band|single|play|musical|series|show|game|episode)\\b");

    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "do", "does", "did",
            "of", "in", "on", "at", "to", "for", "from", "by", "with", "about", "as", "and", "or",
            "what", "whats", "who", "whom", "whose", "when", "where", "which", "why", "how", "much",
            "many", "tell", "please", "jungey", "know", "can", "could", "would", "will", "it", "its",
            "this", "that", "there", "their", "they", "he", "she", "him", "her", "his", "has", "have",
            "had", "than", "then", "into", "some", "any", "all", "s", "up", "new", "now", "today",
            "going", "happening", "doing", "good", "exactly", "really", "ok", "okay", "so", "but",
            "hey", "purple", "wonder");

    private static final Map<String, Cue> CUES = new HashMap<>();

    static {
        cue(List.of("tall", "high", "height", "elevation"),
                List.of("height", "elevation", "tall", "high", "highest", "tallest"),
                List.of("metres", "meters", "feet", "ft"));
        cue(List.of("born", "birth", "birthday"), List.of("born", "birth"), List.of());
        cue(List.of("die", "died", "death", "dead"), List.of("died", "death", "dead", "killed"), List.of());
        cue(List.of("old", "age"), List.of("born", "age", "aged", "founded", "built", "established"), List.of());
        cue(List.of("population", "people", "live", "inhabitants"),
                List.of("population", "inhabitants", "residents"), List.of("people", "million"));
        cue(List.of("big", "large", "area", "size"), List.of("area", "size", "largest"),
                List.of("square", "km2", "kilometres", "miles"));
        cue(List.of("long", "length"), List.of("length", "long", "longest"), List.of("kilometres", "km", "miles"));
        cue(List.of("deep", "depth"), List.of("depth", "deep", "deepest"), List.of("metres", "meters", "feet"));
        cue(List.of("far", "distance"), List.of("distance", "away"), List.of("kilometres", "km", "miles"));
        cue(List.of("heavy", "weigh", "weight", "mass"), List.of("mass", "weight", "weighs"),
                List.of("tonnes", "kilograms", "kg", "pounds"));
        cue(List.of("founded", "started", "established", "created", "formed", "built"),
                List.of("founded", "established", "formed", "created", "built", "opened"), List.of());
        cue(List.of("invented", "invent", "inventor"), List.of("invented", "invention", "inventor", "patent"), List.of());
        cue(List.of("wrote", "written", "author"), List.of("wrote", "written", "author", "writer"), List.of());
        cue(List.of("discovered", "discover", "discovery"), List.of("discovered", "discovery"), List.of());
        cue(List.of("end", "ended", "finish", "over"), List.of("ended", "end", "surrender", "surrendered", "concluded"), List.of());
        cue(List.of("start", "started", "begin", "began"), List.of("began", "started", "start", "outbreak"), List.of());
        cue(List.of("capital"), List.of("capital"), List.of());
        cue(List.of("fast", "speed"), List.of("speed", "fastest"), List.of("second", "hour", "km", "mph"));
    }

    private static void cue(List<String> asked, List<String> says, List<String> units) {
        Cue c = new Cue(stemAll(says), stemAll(units));
        for (String a : asked) CUES.put(a, c);
    }

    /** Sections of an article that are not about its subject. */
    private static final Pattern END_MATTER = Pattern.compile(
            "^==\\s*(?:See also|References|Notes|Footnotes|Citations|Sources|Bibliography"
                    + "|Further reading|External links)\\s*==\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?])[\"')\\]]*\\s+(?=[A-Z0-9\"'(])");

    private static final Map<String, Article> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Article> eldest) {
            return size() > 12;
        }
    };

    private static volatile String recentTitle;
    private static volatile long recentAt;

    private Wikipedia() {
    }

    /** A question of fact worth looking up - not one about the user or Jungey, not a request. */
    public static boolean asksForFacts(String question) {
        String q = normalise(question);
        return FACTUAL.matcher(q).matches() && !PERSONAL.matcher(q).find() && !NOT_FACT.matcher(q).find()
                && !searchTerms(question).isEmpty();
    }

    /**
     * Leans on what was looked up just before, and that was recently enough to mean it:
     * "when did he die?", "how many children did she have?". A question that names
     * something new of its own - "how big is Japan and how does it compare" - is not one,
     * whatever pronouns it uses.
     */
    public static boolean followsUp(String question) {
        String q = normalise(question);
        if (recent().isEmpty() || !FOLLOW_UP.matcher(q).find() || PERSONAL.matcher(q).find()
                || NOT_FACT.matcher(q).find()) {
            return false;
        }
        int named = 0;
        for (String word : q.split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3 && !STOP.contains(word) && !CUES.containsKey(word)
                    && !FOLLOW_UP.matcher(word).matches()) {
                named++;
            }
        }
        return named <= 1;
    }

    /** The article most recently looked up, if it was within the last few minutes. */
    public static Optional<String> recent() {
        String title = recentTitle;
        return title != null && System.currentTimeMillis() - recentAt < RECENT_MS ? Optional.of(title) : Optional.empty();
    }

    /** Remember an article as the one being talked about, for follow-ups. */
    public static void noteRecent(String title) {
        recentTitle = title;
        recentAt = System.currentTimeMillis();
    }

    /** What to search for: the question without "how tall is" and the like. */
    static String searchTerms(String question) {
        String rest = SCAFFOLD.matcher(normalise(question)).replaceFirst("").trim();
        // Nothing but filler left - "what's up", "who is it" - is not a subject.
        for (String word : rest.split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3 && !STOP.contains(word)) return rest;
        }
        return "";
    }

    /**
     * The article that best answers the question: the first few search results, weighed by
     * how much of the question their titles and opening paragraphs share. Empty if none.
     */
    public static Optional<Article> find(String question) throws Exception {
        String terms = searchTerms(question);
        if (terms.isEmpty()) return Optional.empty();

        JsonNode pages = Http.getJson(API + "&action=query&generator=search&gsrlimit=4&gsrsearch=" + Http.enc(terms)
                + "&prop=extracts%7Cpageprops&ppprop=disambiguation&exintro=1&explaintext=1&exlimit=4")
                .path("query").path("pages");
        if (!pages.isArray() || pages.isEmpty()) return Optional.empty();

        List<Term> wanted = terms(question);
        boolean work = ASKS_FOR_WORK.matcher(normalise(question)).find();
        String best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (JsonNode page : pages) {
            String title = page.path("title").asText("");
            // A page listing things of the same name answers nothing.
            if (title.isEmpty() || page.path("pageprops").has("disambiguation")) continue;
            String intro = page.path("extract").asText("");
            if (intro.contains("may refer to") || intro.contains("can refer to")) continue;

            Set<String> titleWords = stems(title), introWords = stems(intro);
            double score = 0;
            for (Term t : wanted) {
                if (overlaps(titleWords, t.stems())) score += t.weight();
                if (overlaps(introWords, t.stems())) score += t.weight();
            }
            // Search ranks it for a reason; that counts, just not for everything.
            score += (4 - page.path("index").asInt(4)) * 0.8;
            if (!work && WORK.matcher(title).find()) score -= 3;
            if (score > bestScore) {
                bestScore = score;
                best = title;
            }
        }
        return best == null ? Optional.empty() : article(best);
    }

    /** A named article, in full - fetched once, then kept for the follow-ups. */
    public static Optional<Article> article(String title) throws Exception {
        synchronized (CACHE) {
            Article kept = CACHE.get(title);
            if (kept != null) return Optional.of(kept);
        }
        JsonNode pages = Http.getJson(API + "&action=query&prop=extracts&explaintext=1&exsectionformat=wiki&titles="
                + Http.enc(title)).path("query").path("pages");
        if (!pages.isArray() || pages.isEmpty() || pages.get(0).path("missing").asBoolean(false)) {
            return Optional.empty();
        }
        String found = pages.get(0).path("title").asText(title);
        List<String> sentences = sentences(pages.get(0).path("extract").asText(""));
        if (sentences.isEmpty()) return Optional.empty();

        Article article = new Article(found, sentences);
        synchronized (CACHE) {
            CACHE.put(title, article);
            CACHE.put(found, article);
        }
        return Optional.of(article);
    }

    /**
     * The opening sentence of the article - what the thing is - and the sentences that best
     * answer the question, within a budget of words.
     */
    public static Optional<Passage> passage(Article article, String question, int words) {
        List<String> all = article.sentences();
        List<Term> wanted = terms(question);
        if (all.isEmpty()) return Optional.empty();

        Set<String> titleWords = stems(article.title());
        boolean number = WANTS_NUMBER.matcher(normalise(question)).find();

        // How many sentences each term turns up in: one in every other sentence says little.
        List<Set<String>> sentenceWords = new ArrayList<>(all.size());
        Map<Term, Integer> spread = new IdentityHashMap<>();
        for (String s : all) {
            Set<String> w = stems(s);
            sentenceWords.add(w);
            for (Term t : wanted) if (overlaps(w, t.stems())) spread.merge(t, 1, Integer::sum);
        }

        double[] score = new double[all.size()];
        for (int i = 0; i < all.size(); i++) {
            double s = 0;
            for (Term t : wanted) {
                if (!overlaps(sentenceWords.get(i), t.stems())) continue;
                double rarity = Math.log(1 + all.size() / (double) spread.get(t));
                // The subject's own name is in half the article; it says nothing about which half.
                boolean name = t.named() && overlaps(titleWords, t.stems());
                s += t.weight() * rarity * (name ? 0.3 : 1);
            }
            if (s > 0 && number && all.get(i).matches(".*\\d.*")) s += 1.0;
            // The opening paragraph sums the subject up, and states its main facts.
            if (s > 0) s += i < 3 ? 1.2 : i < 8 ? 0.5 : 0;
            score[i] = s;
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 1; i < all.size(); i++) if (score[i] >= 1.0) order.add(i);
        order.sort(Comparator.comparingDouble((Integer i) -> -score[i]).thenComparingInt(i -> i));

        List<Integer> chosen = new ArrayList<>(List.of(0));
        int used = count(all.get(0));
        for (int i : order) {
            int n = count(all.get(i));
            if (used + n > words) continue;
            chosen.add(i);
            used += n;
            if (chosen.size() >= 4) break;
        }
        chosen.sort(Integer::compare);

        StringBuilder text = new StringBuilder();
        for (int i : chosen) {
            if (!text.isEmpty()) text.append(' ');
            text.append(all.get(i));
        }
        return Optional.of(new Passage(article, text.toString()));
    }

    /** The question's words that matter, with the words an answer would use for them. */
    private static List<Term> terms(String question) {
        String q = normalise(question);
        List<Term> out = new ArrayList<>();
        Set<Cue> cued = new HashSet<>();
        Set<String> plain = new HashSet<>();
        for (String word : q.split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) continue;
            Cue cue = CUES.get(word);
            if (cue != null && cued.add(cue)) {
                out.add(new Term(cue.says(), 0.9, false));
                if (!cue.units().isEmpty()) out.add(new Term(cue.units(), 0.3, false));
            }
            if (word.length() < 2 || STOP.contains(word) || CUES.containsKey(word)) continue;
            if (plain.add(stem(word))) out.add(new Term(Set.of(stem(word)), 1.0, true));
        }
        if (HOLDER.matcher(q).matches()) {
            out.add(new Term(stemAll(List.of("current", "incumbent", "since", "present", "serving")), 0.9, false));
        }
        return out;
    }

    /** The article's text as sentences, without its reference sections, headings, formulas and pronunciations. */
    private static List<String> sentences(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String raw : withoutFormulas(text).split("\n")) {
            // What is left of a formula is indented, a symbol to a line.
            if (raw.isBlank() || Character.isWhitespace(raw.charAt(0))) continue;
            String line = raw.trim();
            if (END_MATTER.matcher(line).matches()) break;
            if (line.startsWith("==")) continue;
            // A formula taken out of a sentence leaves it in two lines, the second starting mid-way.
            if (!paragraphs.isEmpty() && CONTINUES.matcher(line).lookingAt()) {
                int last = paragraphs.size() - 1;
                paragraphs.set(last, paragraphs.get(last) + " " + line);
            } else {
                paragraphs.add(line);
            }
        }
        List<String> out = new ArrayList<>();
        for (String paragraph : paragraphs) {
            for (String s : SENTENCE.split(clean(paragraph))) {
                s = s.trim();
                if (count(s) >= 4) out.add(s);
            }
        }
        return out;
    }

    private static final Pattern CONTINUES = Pattern.compile("[),;:.\\p{Ll}]");

    /** Wikipedia's plain text keeps each formula's LaTeX source; it reads as noise. */
    static String withoutFormulas(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (true) {
            int at = text.indexOf("{\\displaystyle", i);
            if (at < 0) {
                out.append(text, i, text.length());
                return out.toString();
            }
            out.append(text, i, at);
            int depth = 0, j = at;
            for (; j < text.length(); j++) {
                char c = text.charAt(j);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) break;
            }
            i = Math.min(text.length(), j + 1);
        }
    }

    /**
     * Drop the parts of brackets a voice should not read and a model need not: pronunciations,
     * other scripts, "listen". Dates and figures stay - "(10 July 1856 – 7 January 1943)" and
     * "299,792,458 m/s" are often the answer.
     */
    static String clean(String line) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            int end = c == '(' ? closing(line, i) : -1;
            if (end < 0) {
                out.append(c);
                i++;
                continue;
            }
            List<String> kept = new ArrayList<>();
            for (String part : line.substring(i + 1, end).split(";")) {
                if (readable(part)) kept.add(part.trim());
            }
            if (!kept.isEmpty()) {
                out.append('(').append(String.join("; ", kept)).append(')');
            } else if (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
                out.setLength(out.length() - 1);
            }
            i = end + 1;
        }
        return out.toString().replaceAll("\\s{2,}", " ").replace(" ,", ",").replace(" .", ".");
    }

    /** Where the bracket opened at i closes, or -1 if it does not within a sensible distance. */
    private static int closing(String line, int i) {
        int depth = 0;
        for (int j = i; j < line.length() && j - i < 400; j++) {
            char c = line.charAt(j);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return j;
        }
        return -1;
    }

    private static boolean readable(String part) {
        String p = part.toLowerCase(Locale.ROOT);
        if (p.isBlank() || p.contains("listen") || p.contains("pronounced") || p.contains("pronunciation")
                || p.contains("romanized") || p.contains("romanised")) {
            return false;
        }
        for (int i = 0; i < part.length(); ) {
            int cp = part.codePointAt(i);
            i += Character.charCount(cp);
            // Phonetic letters and stress marks mean a pronunciation; other scripts, a name
            // in another language. Neither is anything to read aloud.
            if (Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.IPA_EXTENSIONS
                    || Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.SPACING_MODIFIER_LETTERS) {
                return false;
            }
            if (Character.isLetter(cp) && Character.UnicodeScript.of(cp) != Character.UnicodeScript.LATIN) {
                return false;
            }
        }
        return true;
    }

    private static boolean overlaps(Set<String> words, Set<String> stems) {
        for (String s : stems) if (words.contains(s)) return true;
        return false;
    }

    private static Set<String> stems(String text) {
        Set<String> out = new HashSet<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) out.add(stem(word));
        }
        return out;
    }

    private static Set<String> stemAll(List<String> words) {
        Set<String> out = new HashSet<>();
        for (String w : words) out.add(stem(w));
        return out;
    }

    /** Close enough for matching "invented" to "invention" and "metres" to "metre". */
    static String stem(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.length() > 5 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 5 && w.endsWith("ion")) return w.substring(0, w.length() - 3);
        if (w.length() > 4 && w.endsWith("ed")) return w.substring(0, w.length() - 2);
        if (w.length() > 4 && w.endsWith("es")) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    private static int count(String sentence) {
        return sentence.isBlank() ? 0 : sentence.trim().split("\\s+").length;
    }

    private static String normalise(String question) {
        return question == null ? "" : question.toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replaceAll("[?!.]+$", "")
                .trim();
    }
}
