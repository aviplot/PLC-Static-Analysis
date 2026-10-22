package dev.plca.frontend.st.kit;

import dev.plca.ast.Expression;
import dev.plca.ast.Identifier;
import dev.plca.core.source.SourceText;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain designators and numbers built directly, with spans in the file, without running the parser: most variables of
 * graphical bodies (PLCopen, TwinCAT NWL/CFC, L5X FBD) are of this form, and parsing each would dominate load time.
 */
public final class SimpleExpressions {

    private SimpleExpressions() {}

    /**
     * {@code a}, {@code a.b.c}, {@code a[3].b}, {@code a.5}, integers, reals, TRUE/FALSE in {@code text[s, e)}; null
     * for anything else (then the parser decides).
     */
    public static Expression simple(SourceText text, int s, int e) {
        String t = text.text();
        if (s >= e) {
            return null;
        }
        int i = s;
        char c0 = t.charAt(i);
        if (Character.isDigit(c0) || (c0 == '-' && i + 1 < e && Character.isDigit(t.charAt(i + 1)))) {
            int j = i + 1;
            while (j < e && Character.isDigit(t.charAt(j))) {
                j++;
            }
            boolean real = false;
            if (j < e && t.charAt(j) == '.') {
                real = true;
                j++;
                while (j < e && Character.isDigit(t.charAt(j))) {
                    j++;
                }
            }
            if (j != e) {
                return null;
            }
            return new Expression.Literal(
                    real ? Expression.LiteralKind.REAL : Expression.LiteralKind.INTEGER,
                    t.substring(s, e),
                    null,
                    text.span(s, e));
        }
        int j = identEnd(t, i, e);
        if (j < 0) {
            return null;
        }
        String first = t.substring(i, j);
        if (j == e && (first.equalsIgnoreCase("TRUE") || first.equalsIgnoreCase("FALSE"))) {
            return new Expression.Literal(Expression.LiteralKind.BOOL, first, null, text.span(s, e));
        }
        if (isKeyword(first)) {
            return null;
        }
        Expression ex = new Expression.NameRef(new Identifier(first, text.span(i, j)), false, text.span(i, j));
        while (j < e) {
            char c = t.charAt(j);
            if (c == '.') {
                int k = j + 1;
                if (k < e && Character.isDigit(t.charAt(k))) {
                    int m = k;
                    while (m < e && Character.isDigit(t.charAt(m))) {
                        m++;
                    }
                    ex = new Expression.BitAccess(
                            ex, Integer.parseInt(t.substring(k, Math.min(m, k + 9))), text.span(s, m));
                    j = m;
                    continue;
                }
                int m = identEnd(t, k, e);
                if (m < 0) {
                    return null;
                }
                ex = new Expression.MemberAccess(
                        ex, new Identifier(t.substring(k, m), text.span(k, m)), text.span(s, m));
                j = m;
            } else if (c == '[') {
                int close = t.indexOf(']', j);
                if (close < 0 || close >= e) {
                    return null;
                }
                List<Expression> indices = new ArrayList<>();
                int from = j + 1;
                for (int k = j + 1; k <= close; k++) {
                    if (k == close || t.charAt(k) == ',') {
                        Expression idx = simple(text, from, k);
                        if (idx == null) {
                            return null;
                        }
                        indices.add(idx);
                        from = k + 1;
                    }
                }
                ex = new Expression.Index(ex, indices, text.span(s, close + 1));
                j = close + 1;
            } else {
                return null;
            }
        }
        return ex;
    }

    private static int identEnd(String t, int i, int e) {
        if (i >= e || !(Character.isLetter(t.charAt(i)) || t.charAt(i) == '_')) {
            return -1;
        }
        int j = i + 1;
        while (j < e && (Character.isLetterOrDigit(t.charAt(j)) || t.charAt(j) == '_')) {
            j++;
        }
        return j;
    }

    /** Words that make the text more than a designator ({@code NOT a}, {@code a AND b}, typed literals). */
    private static boolean isKeyword(String w) {
        return switch (w.toUpperCase(java.util.Locale.ROOT)) {
            case "NOT", "AND", "OR", "XOR", "MOD", "THIS", "SUPER" -> true;
            default -> false;
        };
    }
}
