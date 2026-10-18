package dev.plca.semantic;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decoding of IEC literal texts (as stored in the AST without type prefix). */
final class Literals {

    private static final Pattern DURATION_PART =
            Pattern.compile("(?i)([0-9][0-9_]*(?:\\.[0-9_]+)?)(ms|us|ns|d|h|m|s)_?");

    private Literals() {}

    /** {@code 123}, {@code -5}, {@code 1_000}, {@code 16#FF}, {@code 2#1010}; null if not an integer or too big. */
    static Long integer(String text) {
        String t = text.replace("_", "");
        boolean neg = t.startsWith("-");
        if (neg || t.startsWith("+")) {
            t = t.substring(1);
        }
        int radix = 10;
        int hash = t.indexOf('#');
        if (hash > 0) {
            try {
                radix = Integer.parseInt(t.substring(0, hash));
            } catch (NumberFormatException e) {
                return null;
            }
            t = t.substring(hash + 1);
        }
        try {
            long v = Long.parseUnsignedLong(t, radix);
            return neg ? -v : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double real(String text) {
        try {
            return Double.parseDouble(text.replace("_", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Boolean bool(String text) {
        String t = text.toUpperCase(Locale.ROOT);
        return switch (t) {
            case "TRUE", "1" -> Boolean.TRUE;
            case "FALSE", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** {@code 1h2m3s4ms}, {@code -5us}, {@code 1.5s} to nanoseconds; null if malformed. */
    static Long durationNanos(String text) {
        String t = text;
        boolean neg = t.startsWith("-");
        if (neg) {
            t = t.substring(1);
        }
        Matcher m = DURATION_PART.matcher(t);
        double total = 0;
        int pos = 0;
        while (m.find()) {
            if (m.start() != pos) {
                return null;
            }
            double v = Double.parseDouble(m.group(1).replace("_", ""));
            total += v
                    * switch (m.group(2).toLowerCase(Locale.ROOT)) {
                        case "d" -> 86_400e9;
                        case "h" -> 3_600e9;
                        case "m" -> 60e9;
                        case "s" -> 1e9;
                        case "ms" -> 1e6;
                        case "us" -> 1e3;
                        default -> 1;
                    };
            pos = m.end();
        }
        if (pos != t.length() || pos == 0) {
            return null;
        }
        long nanos = Math.round(total);
        return neg ? -nanos : nanos;
    }
}
