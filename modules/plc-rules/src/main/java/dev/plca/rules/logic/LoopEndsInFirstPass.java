package dev.plca.rules.logic;

import dev.plca.ast.Statement;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.rules.Spans;
import dev.plca.semantic.Statements;
import java.util.List;

/**
 * PLCA-L022: a FOR, WHILE or REPEAT whose body always ends with EXIT or RETURN, so it runs at most once. Not reported
 * when the body has CONTINUE (another pass is possible), a conditional EXIT besides the last one (the "breakable block"
 * idiom: a loop used to leave a sequence of steps early), or labels and jumps.
 */
public final class LoopEndsInFirstPass implements PouRule {

    @Override
    public String id() {
        return "PLCA-L022";
    }

    @Override
    public void check(PouContext ctx) {
        Structured.forEach(ctx.body(), s -> {
            List<Statement> body = switch (s) {
                case Statement.For f -> f.body();
                case Statement.While w -> w.body();
                case Statement.Repeat r -> r.body();
                default -> null;
            };
            if (body == null) {
                return;
            }
            List<Statement> real = Structured.real(body);
            if (real.isEmpty()
                    || !(real.getLast() instanceof Statement.Exit || real.getLast() instanceof Statement.Return)) {
                return;
            }
            int[] counts = new int[2]; // EXIT, CONTINUE / label / jump
            count(body, counts);
            if (counts[0] == (real.getLast() instanceof Statement.Exit ? 1 : 0) && counts[1] == 0) {
                ctx.report(
                        Spans.header(s),
                        "The loop body always ends with "
                                + (real.getLast() instanceof Statement.Exit ? "EXIT" : "RETURN")
                                + ": the loop runs at most once");
            }
        });
    }

    /** EXITs of this loop, and CONTINUE / labels / jumps, not counting nested loops. */
    private static void count(List<Statement> body, int[] counts) {
        if (body == null) {
            return;
        }
        for (Statement s : body) {
            switch (s) {
                case Statement.Exit e -> counts[0]++;
                case Statement.Continue c -> counts[1]++;
                case Statement.Label l -> counts[1]++;
                case Statement.Jump j -> counts[1]++;
                case Statement.For f -> {}
                case Statement.While w -> {}
                case Statement.Repeat r -> {}
                default -> Statements.forEachBody(s, b -> count(b, counts));
            }
        }
    }
}
