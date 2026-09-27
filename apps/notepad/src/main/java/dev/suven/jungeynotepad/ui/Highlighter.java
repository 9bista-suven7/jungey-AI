package dev.suven.jungeynotepad.ui;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Colours the Markdown as it is typed, keeping every mark visible: what is written is what
 * is saved, and the preview is there for the finished look.
 *
 * <p>Line-level styles (headings, quotes, code blocks, done tasks) are laid down first, and
 * inline ones (bold, links, tags) over them, so a link in a heading is both.
 */
final class Highlighter {

    private Highlighter() {
    }

    private static final Pattern FENCED = Pattern.compile("(?m)^\\s*```[^\\n]*\\n(?:.*\\n)*?\\s*```\\s*$|(?m)^\\s*```[^\\n]*(?s:.*)\\z");
    private static final Pattern LINE = Pattern.compile(
            "(?m)(?<h1>^#\\s.*$)|(?<h2>^##\\s.*$)|(?<h3>^#{3,6}\\s.*$)"
                    + "|(?<done>^\\s*[-*+]\\s+\\[[xX]\\].*$)"
                    + "|(?<quote>^>.*$)|(?<rule>^\\s*([-*_])(\\s*\\7){2,}\\s*$)");
    private static final Pattern MARKER = Pattern.compile("(?m)^(\\s*)(?<task>[-*+]\\s+\\[[ xX]\\])|^(\\s*)(?<bullet>[-*+]|\\d{1,9}[.)])(?=\\s)");
    private static final Pattern INLINE = Pattern.compile(
            "(?<code>`[^`\\n]+`)"
                    + "|(?<bold>\\*\\*[^*\\n]+?\\*\\*|__[^_\\n]+?__)"
                    + "|(?<strike>~~[^~\\n]+?~~)"
                    + "|(?<italic>(?<![*\\w])\\*[^*\\s][^*\\n]*?\\*(?!\\*)|(?<![_\\w])_[^_\\s][^_\\n]*?_(?![_\\w]))"
                    + "|(?<link>\\[[^\\]\\n]+\\]\\([^)\\s]+\\))"
                    + "|(?<url>https?://[^\\s<>()]+[^\\s<>().,;:!?'\"])"
                    + "|(?<tag>(?<![\\w#&/])#[\\p{L}][\\p{L}\\p{N}_/-]*)");

    static StyleSpans<Collection<String>> compute(String text) {
        int n = text.length();
        @SuppressWarnings("unchecked")
        List<String>[] styles = new List[n];

        Matcher m = FENCED.matcher(text);
        boolean[] code = new boolean[n];
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) code[i] = true;
            mark(styles, m.start(), m.end(), "md-codeblock");
        }

        m = LINE.matcher(text);
        while (m.find()) {
            if (code[m.start()]) continue;
            String style = m.group("h1") != null ? "md-h1" : m.group("h2") != null ? "md-h2" : m.group("h3") != null ? "md-h3"
                    : m.group("done") != null ? "md-done" : m.group("quote") != null ? "md-quote" : "md-rule";
            mark(styles, m.start(), m.end(), style);
        }

        m = MARKER.matcher(text);
        while (m.find()) {
            String g = m.group("task") != null ? "task" : "bullet";
            if (code[m.start(g)]) continue;
            mark(styles, m.start(g), m.end(g), g.equals("task") ? "md-checkbox" : "md-bullet");
        }

        m = INLINE.matcher(text);
        while (m.find()) {
            if (code[m.start()]) continue;
            String style = m.group("code") != null ? "md-code" : m.group("bold") != null ? "md-bold"
                    : m.group("strike") != null ? "md-strike" : m.group("italic") != null ? "md-italic"
                    : m.group("link") != null || m.group("url") != null ? "md-link" : "md-tag";
            mark(styles, m.start(), m.end(), style);
        }

        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        if (n == 0) {
            b.add(List.of(), 0);
            return b.create();
        }
        int start = 0;
        for (int i = 1; i <= n; i++) {
            if (i == n || !same(styles[i], styles[start])) {
                b.add(styles[start] == null ? List.of() : styles[start], i - start);
                start = i;
            }
        }
        return b.create();
    }

    private static void mark(List<String>[] styles, int from, int to, String style) {
        // Consecutive characters share one list until a new style changes it.
        List<String> previousIn = null;
        List<String> previousOut = null;
        for (int i = from; i < to; i++) {
            List<String> in = styles[i];
            if (in == previousIn && previousOut != null) {
                styles[i] = previousOut;
                continue;
            }
            List<String> out = new ArrayList<>(in == null ? List.of() : in);
            out.add(style);
            previousIn = in;
            previousOut = out;
            styles[i] = out;
        }
    }

    private static boolean same(List<String> a, List<String> b) {
        return a == b || (a != null && a.equals(b));
    }
}
