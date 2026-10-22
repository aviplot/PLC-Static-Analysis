package dev.plca.frontend.st.kit;

import dev.plca.ast.Expression;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import java.util.function.Function;

/**
 * What a translator of graphical bodies embedded in XML needs from its frontend: the file, spans, ST expressions parsed
 * from file ranges (designators and numbers without the parser), diagnostics, and synthetic names unique in the file.
 * One per file; not thread-safe.
 */
public final class GraphContext {

    public final SourceFile file;
    public final SourceRegistry registry;
    /** The whole file as one text: spans of elements and of plain expressions refer to it. */
    public final SourceText whole;

    public final DiagnosticSink sink;
    private final Function<SourceText, Expression> parser;
    private int temps;

    /** {@code parser}: parses one registered text as an ST expression. */
    public GraphContext(
            SourceFile file,
            SourceRegistry registry,
            SourceText whole,
            DiagnosticSink sink,
            Function<SourceText, Expression> parser) {
        this.file = file;
        this.registry = registry;
        this.whole = whole;
        this.sink = sink;
        this.parser = parser;
    }

    /** The ST expression in the file range {@code [start, end)} (raw XML content: entities are decoded). */
    public Expression expression(int start, int end) {
        String t = file.text();
        int s = start;
        int e = end;
        boolean plain = true;
        for (int i = s; i < e; i++) {
            char c = t.charAt(i);
            if (c == '<' || c == '&') {
                plain = false;
                break;
            }
        }
        if (plain) {
            while (s < e && Character.isWhitespace(t.charAt(s))) {
                s++;
            }
            while (e > s && Character.isWhitespace(t.charAt(e - 1))) {
                e--;
            }
            if (s == e) {
                return new Expression.Invalid(whole.span(start, Math.max(start, end)));
            }
            Expression fast = SimpleExpressions.simple(whole, s, e);
            if (fast != null) {
                return fast;
            }
        }
        SourceText text = registry.registerXmlContent(file, start, end);
        if (text.text().isBlank()) {
            return new Expression.Invalid(whole.span(start, Math.max(start, end)));
        }
        return parser.apply(text);
    }

    /** The span of the start tag at {@code offset}. */
    public Span elementSpan(int offset) {
        return whole.span(offset, XmlInput.endOfStartTag(file.text(), offset));
    }

    public Span span(int start, int end) {
        return whole.span(start, end);
    }

    /** A synthetic variable name ({@code fbd7}), unique within the file. */
    public String nextTemp(String prefix) {
        return prefix + (++temps);
    }
}
