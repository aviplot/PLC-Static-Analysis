package dev.plca.frontend.plcopen;

import dev.plca.ast.Expression;
import dev.plca.ast.Identifier;
import dev.plca.core.source.SourceMap;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.frontend.st.kit.SimpleExpressions;

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

    static Expression simple(SourceText text, int s, int e) {
        return SimpleExpressions.simple(text, s, e);
    }
}
