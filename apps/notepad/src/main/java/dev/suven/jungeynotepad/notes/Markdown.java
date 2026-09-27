package dev.suven.jungeynotepad.notes;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Just enough Markdown for notes: headings, lists and tasks, quotes, code, rules, and
 * bold, italic, code, links and #tags inside a line.
 *
 * <p>Parsed once into blocks of spans, which the preview draws and {@link #toHtml} writes
 * out, so the two can never disagree. Each block remembers its source line, which is how
 * ticking a box in the preview ticks it in the text.
 */
public final class Markdown {

    private Markdown() {
    }

    public enum Kind { HEADING, PARAGRAPH, BULLET, NUMBERED, TASK, QUOTE, CODE, RULE, BLANK }

    /** A run of text with one look. {@code link} is set for links and bare addresses. */
    public record Span(String text, boolean bold, boolean italic, boolean code, boolean strike, String link, boolean tag) {
        static Span plain(String text) {
            return new Span(text, false, false, false, false, null, false);
        }
    }

    /**
     * One block. {@code level} is the heading's level or the list item's indent depth;
     * {@code marker} a numbered item's number; {@code done} a task's tick.
     */
    public record Block(Kind kind, int line, int level, String marker, boolean done, List<Span> spans, String raw) {
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern TASK = Pattern.compile("^(\\s*)[-*+]\\s+\\[([ xX])\\]\\s?(.*)$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*$");

    public static List<Block> parse(String text) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m;
            if (FENCE.matcher(line).matches()) {
                String fence = line.trim().substring(0, 3);
                int start = i;
                StringBuilder code = new StringBuilder();
                while (++i < lines.length && !lines[i].trim().startsWith(fence)) {
                    if (!code.isEmpty()) code.append('\n');
                    code.append(lines[i]);
                }
                blocks.add(new Block(Kind.CODE, start, 0, line.trim().substring(3).trim(), false,
                        List.of(Span.plain(code.toString())), code.toString()));
            } else if (line.isBlank()) {
                blocks.add(new Block(Kind.BLANK, i, 0, "", false, List.of(), line));
            } else if ((m = HEADING.matcher(line)).matches()) {
                blocks.add(new Block(Kind.HEADING, i, m.group(1).length(), "", false, inline(m.group(2)), line));
            } else if (RULE.matcher(line).matches()) {
                blocks.add(new Block(Kind.RULE, i, 0, "", false, List.of(), line));
            } else if ((m = TASK.matcher(line)).matches()) {
                blocks.add(new Block(Kind.TASK, i, depth(m.group(1)), "", !m.group(2).equals(" "), inline(m.group(3)), line));
            } else if ((m = BULLET.matcher(line)).matches()) {
                blocks.add(new Block(Kind.BULLET, i, depth(m.group(1)), "", false, inline(m.group(2)), line));
            } else if ((m = NUMBERED.matcher(line)).matches()) {
                blocks.add(new Block(Kind.NUMBERED, i, depth(m.group(1)), m.group(2), false, inline(m.group(3)), line));
            } else if (line.startsWith(">")) {
                blocks.add(new Block(Kind.QUOTE, i, 0, "", false, inline(line.replaceFirst("^>+\\s?", "")), line));
            } else {
                // Lines of a paragraph run together, as Markdown means them to.
                Block last = blocks.isEmpty() ? null : blocks.getLast();
                if (last != null && last.kind() == Kind.PARAGRAPH) {
                    String joined = last.raw() + "\n" + line;
                    blocks.set(blocks.size() - 1, new Block(Kind.PARAGRAPH, last.line(), 0, "", false,
                            inline(joined.replace("\n", " ")), joined));
                } else {
                    blocks.add(new Block(Kind.PARAGRAPH, i, 0, "", false, inline(line), line));
                }
            }
        }
        return blocks;
    }

    private static int depth(String indent) {
        return indent.replace("\t", "    ").length() / 2;
    }

    private static final Pattern INLINE = Pattern.compile(
            "(?<code>`[^`\n]+`)"
                    + "|(?<bold>\\*\\*[^*\n]+?\\*\\*|__[^_\n]+?__)"
                    + "|(?<strike>~~[^~\n]+?~~)"
                    + "|(?<italic>(?<![*\\w])\\*[^*\\s][^*\n]*?\\*(?!\\*)|(?<![_\\w])_[^_\\s][^_\n]*?_(?![_\\w]))"
                    + "|(?<link>\\[(?<label>[^\\]\n]+)\\]\\((?<url>[^)\\s]+)\\))"
                    + "|(?<bare>https?://[^\\s<>()]+[^\\s<>().,;:!?'\"])"
                    + "|(?<tag>(?<![\\w#&/])#[\\p{L}][\\p{L}\\p{N}_/-]*)");

    public static List<Span> inline(String text) {
        List<Span> spans = new ArrayList<>();
        Matcher m = INLINE.matcher(text);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) spans.add(Span.plain(text.substring(at, m.start())));
            String s = m.group();
            if (m.group("code") != null) {
                spans.add(new Span(s.substring(1, s.length() - 1), false, false, true, false, null, false));
            } else if (m.group("bold") != null) {
                for (Span inner : inline(s.substring(2, s.length() - 2))) spans.add(with(inner, true, inner.italic(), inner.strike()));
            } else if (m.group("strike") != null) {
                for (Span inner : inline(s.substring(2, s.length() - 2))) spans.add(with(inner, inner.bold(), inner.italic(), true));
            } else if (m.group("italic") != null) {
                for (Span inner : inline(s.substring(1, s.length() - 1))) spans.add(with(inner, inner.bold(), true, inner.strike()));
            } else if (m.group("link") != null) {
                spans.add(new Span(m.group("label"), false, false, false, false, m.group("url"), false));
            } else if (m.group("bare") != null) {
                spans.add(new Span(s, false, false, false, false, s, false));
            } else {
                spans.add(new Span(s, false, false, false, false, null, true));
            }
            at = m.end();
        }
        if (at < text.length()) spans.add(Span.plain(text.substring(at)));
        return spans;
    }

    private static Span with(Span s, boolean bold, boolean italic, boolean strike) {
        return new Span(s.text(), bold, italic, s.code(), strike, s.link(), s.tag());
    }

    /** The first heading, or else the first line with words in it: what a note is called. */
    public static String title(String text) {
        String fallback = null;
        for (String line : text.split("\n", 40)) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) return plain(m.group(2));
            if (fallback == null && !line.isBlank()) fallback = plain(line.replaceFirst("^\\s*([-*+>]|\\d+[.)])\\s*(\\[[ xX]\\]\\s*)?", ""));
        }
        return fallback == null ? "" : fallback;
    }

    /** A line's words without the marks around them. */
    public static String plain(String line) {
        StringBuilder b = new StringBuilder();
        for (Span s : inline(line)) b.append(s.text());
        return b.toString().trim();
    }

    // ---------- HTML ----------

    public static String toHtml(String title, String text) {
        StringBuilder b = new StringBuilder("""
                <!doctype html>
                <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>""").append(escape(title)).append("""
                </title>
                <style>
                body{max-width:46rem;margin:3rem auto;padding:0 1rem;font:16px/1.65 system-ui,sans-serif;color:#1d2330}
                h1,h2,h3{line-height:1.25}code,pre{font-family:ui-monospace,monospace;background:#f1f4f8;border-radius:5px}
                code{padding:.1em .35em}pre{padding:.9rem 1rem;overflow:auto}blockquote{margin:0;padding-left:1rem;border-left:3px solid #38b6d8;color:#4a5566}
                ul{padding-left:1.4rem}li.task{list-style:none;margin-left:-1.4rem}.tag{color:#0a7d9c}a{color:#0a7d9c}
                </style></head><body>
                """);
        String openList = null;
        for (Block block : parse(text)) {
            String list = switch (block.kind()) {
                case BULLET, TASK -> "ul";
                case NUMBERED -> "ol";
                default -> null;
            };
            if (openList != null && !openList.equals(list)) {
                b.append("</").append(openList).append(">\n");
                openList = null;
            }
            if (list != null && openList == null) {
                b.append('<').append(list).append(">\n");
                openList = list;
            }
            String indent = block.level() > 0 ? " style=\"margin-left:" + (block.level() * 1.4) + "rem\"" : "";
            switch (block.kind()) {
                case HEADING -> b.append("<h").append(block.level()).append('>').append(html(block.spans()))
                        .append("</h").append(block.level()).append(">\n");
                case PARAGRAPH -> b.append("<p>").append(html(block.spans())).append("</p>\n");
                case BULLET, NUMBERED -> b.append("<li").append(indent).append('>').append(html(block.spans())).append("</li>\n");
                case TASK -> b.append("<li class=\"task\"").append(indent).append("><input type=\"checkbox\" disabled")
                        .append(block.done() ? " checked" : "").append("> ")
                        .append(block.done() ? "<s>" + html(block.spans()) + "</s>" : html(block.spans())).append("</li>\n");
                case QUOTE -> b.append("<blockquote>").append(html(block.spans())).append("</blockquote>\n");
                case CODE -> b.append("<pre><code>").append(escape(block.raw())).append("</code></pre>\n");
                case RULE -> b.append("<hr>\n");
                case BLANK -> { }
            }
        }
        if (openList != null) b.append("</").append(openList).append(">\n");
        return b.append("</body></html>\n").toString();
    }

    private static String html(List<Span> spans) {
        StringBuilder b = new StringBuilder();
        for (Span s : spans) {
            String t = escape(s.text());
            if (s.code()) t = "<code>" + t + "</code>";
            if (s.italic()) t = "<em>" + t + "</em>";
            if (s.bold()) t = "<strong>" + t + "</strong>";
            if (s.strike()) t = "<s>" + t + "</s>";
            if (s.tag()) t = "<span class=\"tag\">" + t + "</span>";
            if (s.link() != null) t = "<a href=\"" + escape(s.link()) + "\">" + t + "</a>";
            b.append(t);
        }
        return b.toString();
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
