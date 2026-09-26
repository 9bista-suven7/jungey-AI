package dev.suven.jungey.voice;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns text written for a screen into text worth hearing.
 *
 * <p>A reply like {@code CPU 43% · 7.8/15.5 GB · /home/gemini/Documents/report.pdf} is
 * perfectly clear to read and sounds like a machine reading a spreadsheet. Half of
 * sounding human is having something human to say, so the words get fixed here before
 * any engine ever sees them.
 */
final class Spoken {

    private Spoken() {
    }

    private static final Pattern URL = Pattern.compile("\\bhttps?://\\S+|\\bwww\\.\\S+");
    private static final Pattern PATH = Pattern.compile("(?<![\\w/])(?:~|/[\\w.-]+)(?:/[\\w .-]+)+/?");
    private static final Pattern UNIT =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s?(GHz|MHz|KB|MB|GB|TB|kB|ms|km|cm|mm|kg)\\b");
    private static final Pattern DEGREES = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s?°\\s?([CF])?");
    private static final Pattern TABLE_RULE = Pattern.compile("^[\\s|:+-]{4,}$");

    /** Everything a screen needs and a voice does not. */
    private static final Pattern DECORATION = Pattern.compile("[\\[\\]{}#*_`|•·→←—–]");

    /**
     * Rewrite a reply as it should be said aloud, or "" if there is nothing worth saying.
     *
     * @param limit characters after which the rest is left on screen rather than read out
     */
    static String forSpeech(String text, int limit) {
        if (text == null || text.isBlank()) return "";

        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\n")) {
            // Table rules and ASCII art carry no meaning once spoken.
            if (TABLE_RULE.matcher(line).matches()) continue;
            kept.append(line).append('\n');
        }

        String s = kept.toString();
        // Speeds first: the unit rule below would otherwise stop at the slash and say
        // "twelve kilometres slash h".
        s = s.replaceAll("\\bkm/h\\b", "kilometres per hour")
                .replaceAll("\\bmph\\b", "miles per hour")
                .replaceAll("\\bm/s\\b", "metres per second");

        s = URL.matcher(s).replaceAll("a link");
        s = PATH.matcher(s).replaceAll(Spoken::lastPathSegment);
        s = DEGREES.matcher(s).replaceAll(m -> m.group(1) + " degrees"
                + (m.group(2) == null ? "" : m.group(2).equals("C") ? " celsius" : " fahrenheit"));
        s = UNIT.matcher(s).replaceAll(m -> m.group(1) + " " + unitName(m.group(1), m.group(2)));

        s = s.replace("%", " percent")
                .replace("&", " and ")
                .replace("...", ", ")
                .replace("…", ", ")
                .replaceAll("(?<=\\w) - (?=\\w)", ", ")     // dashes are pauses, not silence
                .replaceAll("(?<=\\d)/(?=\\d)", " of ");     // "7.8/15.5 GB" -> "7.8 of 15.5"

        s = DECORATION.matcher(s).replaceAll(" ");
        s = s.replaceAll("[ \\t]+", " ")
                .replaceAll(" ?\n ?", ". ")                  // a line break is a full stop to the ear
                .replaceAll("\\.\\s*\\.", ".")
                .replaceAll("\\s+([.,!?])", "$1")
                .trim();

        return limit > 0 && s.length() > limit ? trimToSentence(s, limit) : s;
    }

    /** Stop at the last full stop before the limit - a sentence cut in half sounds broken. */
    private static String trimToSentence(String s, int limit) {
        String head = s.substring(0, limit);
        int end = Math.max(head.lastIndexOf('.'), Math.max(head.lastIndexOf('!'), head.lastIndexOf('?')));
        if (end > limit / 3) head = head.substring(0, end + 1);
        return head.trim() + " The rest is on screen.";
    }

    private static String lastPathSegment(java.util.regex.MatchResult m) {
        String path = m.group().replaceAll("/$", "");
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String unitName(String amount, String unit) {
        boolean one = amount.equals("1");
        return switch (unit.toLowerCase(Locale.ENGLISH)) {
            case "ghz" -> "gigahertz";
            case "mhz" -> "megahertz";
            case "kb" -> one ? "kilobyte" : "kilobytes";
            case "mb" -> one ? "megabyte" : "megabytes";
            case "gb" -> one ? "gigabyte" : "gigabytes";
            case "tb" -> one ? "terabyte" : "terabytes";
            case "ms" -> one ? "millisecond" : "milliseconds";
            case "km" -> one ? "kilometre" : "kilometres";
            case "cm" -> one ? "centimetre" : "centimetres";
            case "mm" -> one ? "millimetre" : "millimetres";
            case "kg" -> one ? "kilogram" : "kilograms";
            default -> unit;
        };
    }
}
