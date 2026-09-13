package dev.suven.jungey.skills;

import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.text.DecimalFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Arithmetic, answered locally.
 *
 * <p>Without this, "what is 15% of 240" falls through to the language model - which is
 * both the slowest way to get the answer and the least reliable, since a model predicts
 * plausible digits rather than calculating them.
 */
public class MathSkill implements Skill {

    private static final DecimalFormat FORMAT = new DecimalFormat("#,##0.######");

    private static final Pattern PERCENT_OF =
            Pattern.compile("([\\d.]+)\\s*(?:%|percent)\\s*of\\s*([\\d.]+)");

    @Override
    public String name() {
        return "maths";
    }

    @Override
    public String description() {
        return "Works out sums.";
    }

    @Override
    public String[] examples() {
        return new String[]{"what is 15% of 240", "12 * 4 + 3", "calculate 2^10"};
    }

    @Override
    public int priority() {
        return 12;
    }

    @Override
    public boolean matches(String input) {
        String expr = normalise(input);
        if (expr.isBlank()) return false;

        // Must be arithmetic and nothing else, or "what is the square of France" would qualify.
        if (!expr.matches("[0-9.+\\-*/%^()]+")) return false;
        return expr.matches(".*\\d.*") && expr.matches(".*[+\\-*/%^].*");
    }

    @Override
    public SkillResult run(String input) {
        String expr = normalise(input);
        try {
            double value = new Parser(expr).parse();
            if (Double.isNaN(value)) return SkillResult.error("That is not a number.");
            if (Double.isInfinite(value)) return SkillResult.error("That divides by zero.");
            return SkillResult.of(FORMAT.format(value));
        } catch (IllegalArgumentException e) {
            return SkillResult.error("I could not work that out.");
        }
    }

    /** Turn spoken arithmetic into symbols, and strip the question around it. */
    static String normalise(String input) {
        String s = input.trim().toLowerCase()
                .replaceFirst("^(what'?s|what is|calculate|work out|compute|how much is)\\s+", "")
                .replaceFirst("[?.]$", "")
                .trim();

        Matcher percent = PERCENT_OF.matcher(s);
        if (percent.find()) {
            s = percent.replaceAll("($1/100)*$2");
        }

        return s.replace("plus", "+")
                .replace("minus", "-")
                .replace("divided by", "/")
                .replace("times", "*")
                .replace("multiplied by", "*")
                .replace("squared", "^2")
                .replace("x", "*")
                .replace("÷", "/")
                .replace("−", "-")
                .replaceAll("\\s+", "");
    }

    /** Recursive descent over + - * / % ^ and parentheses. */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        double parse() {
            double value = expression();
            if (i < s.length()) throw new IllegalArgumentException("trailing " + s.charAt(i));
            return value;
        }

        private double expression() {
            double value = term();
            while (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                value = s.charAt(i++) == '+' ? value + term() : value - term();
            }
            return value;
        }

        private double term() {
            double value = power();
            while (i < s.length() && (s.charAt(i) == '*' || s.charAt(i) == '/' || s.charAt(i) == '%')) {
                char op = s.charAt(i++);
                double right = power();
                value = op == '*' ? value * right : op == '/' ? value / right : value % right;
            }
            return value;
        }

        private double power() {
            double value = unary();
            if (i < s.length() && s.charAt(i) == '^') {
                i++;
                return Math.pow(value, power());   // right associative
            }
            return value;
        }

        private double unary() {
            if (i < s.length() && s.charAt(i) == '-') {
                i++;
                return -unary();
            }
            return atom();
        }

        private double atom() {
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                double value = expression();
                if (i >= s.length() || s.charAt(i) != ')') throw new IllegalArgumentException("unclosed (");
                i++;
                return value;
            }

            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (start == i) throw new IllegalArgumentException("expected a number");
            return Double.parseDouble(s.substring(start, i));
        }
    }
}
