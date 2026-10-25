package dev.plca.rules.logic;

import java.util.Locale;

/** Small scans over ST source text that skip comments and string literals. */
final class SourceScan {

    private SourceScan() {}

    /** Offset of the first {@code keyword} (a whole word, any case) outside comments and strings, or -1. */
    static int keyword(String text, String keyword) {
        String upper = text.toUpperCase(Locale.ROOT);
        int i = 0;
        while (i < text.length()) {
            int skip = skip(text, i);
            if (skip > i) {
                i = skip;
                continue;
            }
            if (upper.startsWith(keyword, i)
                    && (i == 0 || !isWord(text.charAt(i - 1)))
                    && (i + keyword.length() == text.length() || !isWord(text.charAt(i + keyword.length())))) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** Whether {@code text} holds a comment: {@code //}, {@code (* … *)} or a C-style block comment. */
    static boolean hasComment(String text) {
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("//", i) || text.startsWith("(*", i) || text.startsWith("/*", i)) {
                return true;
            }
            int skip = skip(text, i);
            i = skip > i ? skip : i + 1;
        }
        return false;
    }

    /** The offset after a comment or string starting at {@code i}, or {@code i}. */
    private static int skip(String t, int i) {
        if (t.startsWith("//", i)) {
            int e = t.indexOf('\n', i);
            return e < 0 ? t.length() : e + 1;
        }
        if (t.startsWith("(*", i) || t.startsWith("/*", i)) {
            String close = t.charAt(i) == '(' ? "*)" : "*/";
            int e = t.indexOf(close, i + 2);
            return e < 0 ? t.length() : e + 2;
        }
        char c = t.charAt(i);
        if (c == '\'' || c == '"') {
            int j = i + 1;
            while (j < t.length() && t.charAt(j) != c) {
                j += t.charAt(j) == '$' ? 2 : 1;
            }
            return Math.min(t.length(), j + 1);
        }
        return i;
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
