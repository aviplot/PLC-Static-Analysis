package dev.plca.rules.quality;

import dev.plca.ast.Statement;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.rules.Spans;
import java.util.List;

/**
 * PLCA-Q013 (PLCopen CP9): a POU body nested deeper than {@code maxNesting} or with a cyclomatic complexity above
 * {@code maxComplexity}. Complexity is 1 plus one per IF/ELSIF condition, CASE branch, FOR, WHILE and REPEAT (the count
 * the CODESYS and TwinCAT metrics use; AND/OR are not counted). Code generated from graphical bodies is not measured: a
 * ladder routine is a list of rungs, not nested logic.
 */
public final class Complexity implements PouRule {

    @Override
    public String id() {
        return "PLCA-Q013";
    }

    @Override
    public void check(PouContext ctx) {
        int maxNesting = ctx.intParam("maxNesting");
        int maxComplexity = ctx.intParam("maxComplexity");
        Statement[] deepest = {null};
        int[] depth = {0};
        int complexity = 1 + measure(ctx.body(), 0, maxNesting, deepest, depth);
        if (deepest[0] != null) {
            ctx.report(
                    Spans.header(deepest[0]),
                    "Nesting depth " + depth[0] + " exceeds " + maxNesting + "; move inner logic into a method,"
                            + " function or separate statements");
        }
        if (complexity > maxComplexity) {
            ctx.report(
                    ctx.pou().declaration().name().span(),
                    "Cyclomatic complexity of " + ctx.pou().qualifiedName() + " is " + complexity + " (limit "
                            + maxComplexity + "); split it into smaller POUs or methods");
        }
    }

    /** Decisions in {@code body}; records the first statement deeper than the limit and the maximum depth. */
    private static int measure(List<Statement> body, int level, int limit, Statement[] deepest, int[] depth) {
        int decisions = 0;
        for (Statement s : body) {
            int d = switch (s) {
                case Statement.If i -> i.branches().size();
                case Statement.Case c -> c.branches().size();
                case Statement.For f -> 1;
                case Statement.While w -> 1;
                case Statement.Repeat r -> 1;
                default -> 0;
            };
            decisions += d;
            boolean nests = s instanceof Statement.If
                    || s instanceof Statement.Case
                    || s instanceof Statement.For
                    || s instanceof Statement.While
                    || s instanceof Statement.Repeat;
            int inner = nests ? level + 1 : level;
            if (nests && inner > depth[0]) {
                depth[0] = inner;
                if (inner > limit && deepest[0] == null) {
                    deepest[0] = s;
                }
            }
            if (s instanceof Statement.Rung || s instanceof Statement.Network || s instanceof Statement.Chart) {
                continue;
            }
            int[] sub = {0};
            dev.plca.semantic.Statements.forEachBody(s, b -> sub[0] += measure(b, inner, limit, deepest, depth));
            decisions += sub[0];
        }
        return decisions;
    }
}
