package dev.plca.rules;

import dev.plca.ast.Statement;
import dev.plca.core.source.Span;

/** Spans for findings that point at a statement without covering its whole body. */
public final class Spans {

    private Spans() {}

    /**
     * The statement's header: {@code IF cond}, {@code CASE sel}, {@code FOR i := a TO b BY c}, {@code WHILE cond},
     * {@code REPEAT}; the whole span for other statements.
     */
    public static Span header(Statement s) {
        Span sp = s.span();
        int end = switch (s) {
            case Statement.If i -> i.branches().getFirst().condition().span().end();
            case Statement.Case c -> c.selector().span().end();
            case Statement.For f -> (f.by() != null ? f.by() : f.to()).span().end();
            case Statement.While w -> w.condition().span().end();
            case Statement.Repeat r -> Math.min(sp.end(), sp.start() + "REPEAT".length());
            default -> sp.end();
        };
        return end <= sp.start() || end > sp.end() ? sp : Span.of(sp.textId(), sp.start(), end);
    }
}
