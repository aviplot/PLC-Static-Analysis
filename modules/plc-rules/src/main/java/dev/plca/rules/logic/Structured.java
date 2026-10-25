package dev.plca.rules.logic;

import dev.plca.ast.Statement;
import dev.plca.core.source.Span;
import dev.plca.rules.PouContext;
import dev.plca.rules.Spans;
import dev.plca.semantic.Statements;
import java.util.List;
import java.util.function.Consumer;

/** Traversal of the structured (ST) statements of a body: graphical bodies (rungs, networks, charts) are skipped. */
final class Structured {

    private Structured() {}

    /** Every statement written as ST, pre-order. */
    static void forEach(List<Statement> body, Consumer<Statement> action) {
        if (body == null) {
            return;
        }
        for (Statement s : body) {
            if (LogicAnalysis.graphical(s)) {
                continue;
            }
            action.accept(s);
            Statements.forEachBody(s, b -> forEach(b, action));
        }
    }

    /** A body with no statements other than empty ones ({@code ;}). */
    static boolean isEmpty(List<Statement> body) {
        return body == null || body.stream().allMatch(s -> s instanceof Statement.Empty);
    }

    /** Statements other than empty ones. */
    static List<Statement> real(List<Statement> body) {
        return body == null
                ? List.of()
                : body.stream().filter(s -> !(s instanceof Statement.Empty)).toList();
    }

    /** RETURN, EXIT, CONTINUE or JMP: control never reaches the next statement. */
    static boolean leaves(Statement s) {
        return s instanceof Statement.Return
                || s instanceof Statement.Exit
                || s instanceof Statement.Continue
                || s instanceof Statement.Jump;
    }

    /** The ELSE keyword of an IF (its header when the keyword cannot be found). */
    static Span elseSpan(PouContext ctx, Statement.If s) {
        return keywordAfter(ctx, s, s.branches().getLast().span().end(), "ELSE");
    }

    /** The ELSE keyword of a CASE (its header when the keyword cannot be found). */
    static Span elseSpan(PouContext ctx, Statement.Case s) {
        int from = s.branches().isEmpty()
                ? s.selector().span().end()
                : s.branches().getLast().span().end();
        return keywordAfter(ctx, s, from, "ELSE");
    }

    private static Span keywordAfter(PouContext ctx, Statement s, int from, String keyword) {
        Span tail = Span.of(
                s.span().textId(), Math.min(from, s.span().end()), s.span().end());
        int at = SourceScan.keyword(ctx.text(tail), keyword);
        return at < 0
                ? Spans.header(s)
                : Span.of(tail.textId(), tail.start() + at, tail.start() + at + keyword.length());
    }

    /** The labels of a CASE branch. */
    static Span labels(Statement.CaseBranch b) {
        return Span.of(
                b.span().textId(),
                b.labels().getFirst().span().start(),
                b.labels().getLast().span().end());
    }

    /** Whether the source between two offsets of {@code s}'s text holds a comment. */
    static boolean commentBetween(PouContext ctx, Statement s, int from, int to) {
        if (to <= from) {
            return false;
        }
        return SourceScan.hasComment(ctx.text(Span.of(s.span().textId(), from, to)));
    }
}
