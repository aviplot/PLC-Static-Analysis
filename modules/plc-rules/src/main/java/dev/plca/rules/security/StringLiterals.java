package dev.plca.rules.security;

/** Decodes IEC string literal source text ({@code 'it$'s'}, {@code "wide"}) to its value (IEC 6.3.3). */
final class StringLiterals {

    private StringLiterals() {}

    static String decode(String text) {
        if (text.length() < 2) {
            return text;
        }
        char q = text.charAt(0);
        boolean wide = q == '"';
        int end = text.charAt(text.length() - 1) == q ? text.length() - 1 : text.length();
        StringBuilder sb = new StringBuilder(end);
        for (int i = 1; i < end; i++) {
            char c = text.charAt(i);
            if (c != '$' || i + 1 >= end) {
                sb.append(c);
                continue;
            }
            char n = text.charAt(++i);
            switch (Character.toUpperCase(n)) {
                case 'L', 'N' -> sb.append('\n');
                case 'R' -> sb.append('\r');
                case 'T' -> sb.append('\t');
                case 'P' -> sb.append('\f');
                case '$', '\'', '"' -> sb.append(n);
                default -> {
                    int digits = wide ? 4 : 2;
                    if (i + digits <= end && isHex(text, i, i + digits)) {
                        sb.append((char) Integer.parseInt(text.substring(i, i + digits), 16));
                        i += digits - 1;
                    } else {
                        sb.append('$').append(n);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static boolean isHex(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
