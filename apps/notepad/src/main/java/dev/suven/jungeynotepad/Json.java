package dev.suven.jungeynotepad;

import java.util.Collection;
import java.util.Map;

/**
 * Just enough JSON to answer Jungey: maps, lists, strings, numbers, booleans and null. A
 * library for this would cost every command its start-up time.
 */
final class Json {

    private Json() {
    }

    static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value);
        return b.toString();
    }

    private static void write(StringBuilder b, Object value) {
        switch (value) {
            case null -> b.append("null");
            case Boolean v -> b.append(v);
            case Integer v -> b.append(v);
            case Long v -> b.append(v);
            case Number v -> b.append(Double.isFinite(v.doubleValue()) ? v.toString() : "null");
            case Map<?, ?> map -> {
                b.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    string(b, String.valueOf(e.getKey()));
                    b.append(':');
                    write(b, e.getValue());
                }
                b.append('}');
            }
            case Collection<?> list -> {
                b.append('[');
                boolean first = true;
                for (Object o : list) {
                    if (!first) b.append(',');
                    first = false;
                    write(b, o);
                }
                b.append(']');
            }
            default -> string(b, value.toString());
        }
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
