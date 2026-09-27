package dev.suven.jungeynotepad.notes;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The editor's small kindnesses, as plain functions of a line so they can be tested: lists
 * that carry on when Enter is pressed, and tasks ticked with a key.
 */
public final class SmartEdit {

    private SmartEdit() {
    }

    private static final Pattern ITEM = Pattern.compile("^(\\s*)(?:([-*+])\\s+(\\[[ xX]\\]\\s+)?|(\\d{1,9})([.)])\\s+|(>+)\\s?)(.*)$");
    private static final Pattern TASK = Pattern.compile("^(\\s*)([-*+])\\s+\\[([ xX])\\](.*)$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)([-*+])\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,9}[.)])\\s+(.*)$");

    /**
     * What Enter should do at the end of {@code line}.
     *
     * @param prefix what to type on the new line - an empty task for a task, the next number
     *               for a numbered item, the same bullet or quote mark otherwise
     * @param endList true when the item was empty: pressing Enter on it again ends the list,
     *               so the marker is removed instead of repeated
     */
    public record Enter(String prefix, boolean endList) {
    }

    public static Enter onEnter(String line) {
        Matcher m = ITEM.matcher(line);
        if (!m.matches()) {
            // Keep the indent, which is what code and nested thoughts want.
            return new Enter(line.replaceFirst("^(\\s*).*$", "$1"), false);
        }
        String indent = m.group(1);
        boolean empty = m.group(7).isBlank();
        if (empty) return new Enter("", true);
        if (m.group(2) != null) {
            return new Enter(indent + m.group(2) + " " + (m.group(3) != null ? "[ ] " : ""), false);
        }
        if (m.group(4) != null) {
            return new Enter(indent + (Long.parseLong(m.group(4)) + 1) + m.group(5) + " ", false);
        }
        return new Enter(indent + m.group(6) + " ", false);
    }

    /** Ticks or unticks a task; any other line becomes a task first. */
    public static String toggleTask(String line) {
        Matcher m = TASK.matcher(line);
        if (m.matches()) {
            return m.group(1) + m.group(2) + " [" + (m.group(3).equals(" ") ? "x" : " ") + "]" + m.group(4);
        }
        if ((m = BULLET.matcher(line)).matches() || (m = NUMBERED.matcher(line)).matches()) {
            return m.group(1) + "- [ ] " + m.group(3);
        }
        String indent = line.replaceFirst("^(\\s*).*$", "$1");
        return indent + "- [ ] " + line.substring(indent.length());
    }

    /** The line as a heading one level deeper, cycling back to plain text after ######. */
    public static String cycleHeading(String line) {
        Matcher m = Pattern.compile("^(#{1,6})\\s+(.*)$").matcher(line);
        if (!m.matches()) return "# " + line.stripLeading();
        return m.group(1).length() == 6 ? m.group(2) : "#" + line;
    }

    /** Wraps {@code selected} in {@code mark}, or takes the mark away if it is already there. */
    public static String wrap(String selected, String mark) {
        if (selected.length() >= mark.length() * 2 && selected.startsWith(mark) && selected.endsWith(mark)) {
            return selected.substring(mark.length(), selected.length() - mark.length());
        }
        return mark + selected + mark;
    }
}
