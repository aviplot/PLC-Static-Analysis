package dev.plca.rules.logic;

import dev.plca.ast.Statement;
import dev.plca.core.diag.Diagnostic;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.rules.Spans;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PLCA-L013: branches with the same statements. An IF whose every branch (ELSE included) does the same has conditions
 * without effect; a branch that repeats the one before it can be merged into it with OR; two CASE branches with the
 * same statements can share one label list. Empty branches are PLCA-L021's.
 */
public final class IdenticalBranches implements PouRule {

    @Override
    public String id() {
        return "PLCA-L013";
    }

    @Override
    public void check(PouContext ctx) {
        Structured.forEach(ctx.body(), s -> {
            switch (s) {
                case Statement.If i -> ifChain(ctx, i);
                case Statement.Case c -> caseOf(ctx, c);
                default -> {}
            }
        });
    }

    private static void ifChain(PouContext ctx, Statement.If s) {
        List<List<Statement>> bodies = new ArrayList<>();
        s.branches().forEach(b -> bodies.add(Structured.real(b.body())));
        if (s.elseBody() != null) {
            bodies.add(Structured.real(s.elseBody()));
        }
        if (s.elseBody() != null
                && !bodies.getFirst().isEmpty()
                && bodies.stream().allMatch(b -> Structural.equal(b, bodies.getFirst()))) {
            ctx.report(Spans.header(s), "Every branch of this IF does the same; its conditions have no effect");
            return;
        }
        for (int j = 1; j < bodies.size(); j++) {
            if (bodies.get(j).isEmpty() || !Structural.equal(bodies.get(j), bodies.get(j - 1))) {
                continue;
            }
            Diagnostic.Related before =
                    new Diagnostic.Related(s.branches().get(j - 1).condition().span(), "same statements");
            if (j < s.branches().size()) {
                ctx.report(
                        s.branches().get(j).condition().span(),
                        "This branch does the same as the one before it; merge the two conditions with OR",
                        before);
            } else {
                ctx.report(
                        Structured.elseSpan(ctx, s),
                        "ELSE does the same as the branch before it; that branch's condition has no effect",
                        before);
            }
        }
    }

    private static void caseOf(PouContext ctx, Statement.Case s) {
        List<Statement.CaseBranch> bs = s.branches();
        // branches bucketed by hash, so a long CASE is not compared pairwise
        Map<Integer, List<Integer>> seen = new HashMap<>();
        for (int j = 0; j < bs.size(); j++) {
            List<Statement> body = Structured.real(bs.get(j).body());
            if (body.isEmpty() || bs.get(j).labels().isEmpty()) {
                continue;
            }
            List<Integer> bucket = seen.computeIfAbsent(Structural.hash(body), k -> new ArrayList<>());
            bucket.add(j);
            for (int i : bucket) {
                if (i < j && Structural.equal(body, Structured.real(bs.get(i).body()))) {
                    ctx.report(
                            Structured.labels(bs.get(j)),
                            "Same statements as the branch for " + ctx.text(Structured.labels(bs.get(i)))
                                    + "; list the labels together",
                            new Diagnostic.Related(Structured.labels(bs.get(i)), "same statements"));
                    break;
                }
            }
        }
    }
}
