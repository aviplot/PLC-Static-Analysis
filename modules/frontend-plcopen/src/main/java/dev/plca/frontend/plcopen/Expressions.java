package dev.plca.frontend.plcopen;

import dev.plca.ast.Expression;
import dev.plca.ast.Identifier;
import dev.plca.core.source.SourceMap;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import java.util.ArrayList;
import java.util.List;

/**
 * ST expressions embedded in PLCopen XML: variables of graphical elements, initial values, task intervals, inline SFC
 * conditions. Plain designators and numbers (most of them) are built directly with spans in the file; anything else is
 * parsed with the CODESYS grammar as a fragment.
 */
final class Expressions {

    private final PlcOpenFrontend.Run run;

    Expressions(PlcOpenFrontend.Run run) {
        this.run = run;
    }

    /** The text content of an element ({@code <expression>}, {@code <variable>}). */
    Expression content(XNode n) {
        String fileText = run.file.text();
        if (n.contentStart < 0) {
            return new Expression.Invalid(run.elementSpan(n));
        }
        int s = n.contentStart;
        int e = n.contentEnd;
        boolean plain = true;
        for (int i = s; i < e; i++) {
            char c = fileText.charAt(i);
            if (c == '<' || c == '&') {
                plain = false;
                break;
            }
        }
        if (plain) {
            while (s < e && Character.isWhitespace(fileText.charAt(s))) {
                s++;
            }
            while (e > s && Character.isWhitespace(fileText.charAt(e - 1))) {
                e--;
            }
            Expression fast = simple(run.whole, s, e);
            if (fast != null) {
                return fast;
            }
        }
        SourceText t = run.registry.registerXmlContent(run.file, n.contentStart, n.contentEnd);
        return parse(t);
    }

    /** An attribute value holding ST ({@code simpleValue value="T#1s"}, {@code interval}). */
    Expression attribute(XNode.Attr a, Span fallback) {
        if (a.start() < 0) {
            return literal(a.value(), fallback);
        }
        String raw = run.file.text().substring(a.start(), a.end());
        if (raw.indexOf('&') < 0) {
            int s = a.start();
            int e = a.end();
            while (s < e && Character.isWhitespace(raw.charAt(s - a.start()))) {
                s++;
            }
            while (e > s && Character.isWhitespace(raw.charAt(e - 1 - a.start()))) {
                e--;
            }
            Expression fast = simple(run.whole, s, e);
            if (fast != null) {
                return fast;
            }
        }
        SourceText t = run.registry.registerXmlContent(run.file, a.start(), a.end());
        return parse(t);
    }

    /** A value known only as a string at {@code span} (bounds, lengths, priorities). */
    Expression literal(String text, Span span) {
        String v = text.strip();
        if (v.matches("[-+]?\\d+")) {
            return new Expression.Literal(Expression.LiteralKind.INTEGER, v, null, span);
        }
        if (v.matches("[-+]?\\d+\\.\\d*([eE][-+]?\\d+)?")) {
            return new Expression.Literal(Expression.LiteralKind.REAL, v, null, span);
        }
        if (v.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            Identifier id = new Identifier(v, span);
            return new Expression.NameRef(id, false, span);
        }
        // a fragment whose characters all map to the span's start
        SourceText t = run.registry.registerFragment(
                run.file,
                v,
                SourceMap.builder()
                        .replacement(span.start(), span.length(), v.length())
                        .build());
        return parse(t);
    }

    private Expression parse(SourceText t) {
        if (t.text().isBlank()) {
            return new Expression.Invalid(t.span(0, t.text().length()));
        }
        return run.st().expression(t, run.sink);
    }

    /**
     * {@code a}, {@code a.b.c}, {@code a[3].b}, {@code a.5}, integers, reals, TRUE/FALSE in {@code text[s, e)}; null
     * for anything else (then the parser decides).
     */
    static Expression simple(SourceText text, int s, int e) {
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
