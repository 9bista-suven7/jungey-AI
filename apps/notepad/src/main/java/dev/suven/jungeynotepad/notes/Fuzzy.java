package dev.suven.jungeynotepad.notes;

import java.util.Locale;

/**
 * How well a few typed letters fit a name, for the command palette: "mtg" finds "Meeting
 * notes". Letters must come in order; starts of words and runs of letters count for more.
 */
public final class Fuzzy {

    private Fuzzy() {
    }

    /** A score above zero when every letter of {@code query} is in {@code text} in order, else 0. */
    public static int score(String query, String text) {
        String q = query.toLowerCase(Locale.ROOT).replace(" ", "");
        String t = text.toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return 1;
        int score = 0;
        int ti = 0;
        int run = 0;
        for (int qi = 0; qi < q.length(); qi++) {
            char c = q.charAt(qi);
            int found = t.indexOf(c, ti);
            if (found < 0) return 0;
            boolean wordStart = found == 0 || !Character.isLetterOrDigit(t.charAt(found - 1));
            run = found == ti ? run + 1 : 1;
            score += 1 + (wordStart ? 8 : 0) + run * 3 - Math.min(found - ti, 6);
            ti = found + 1;
        }
        if (t.startsWith(q)) score += 25;
        else if (t.contains(q)) score += 12;
        // Of two equal fits, the shorter name is the likelier meaning.
        return Math.max(1, score * 4 - t.length() / 8);
    }
}
