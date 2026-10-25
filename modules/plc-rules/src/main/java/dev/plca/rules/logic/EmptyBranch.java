package dev.plca.rules.logic;

import dev.plca.ast.Statement;
import dev.plca.core.source.Span;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import java.util.List;

/**
 * PLCA-L021: an IF / ELSIF / ELSE or CASE branch with no statements and no comment saying why. An empty THEN with an
 * ELSE is PLCA-L018's.
 */
public final class EmptyBranch implements PouRule {

    @Override
    public String id() {
        return "PLCA-L021";
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
        List<Statement.Branch> bs = s.branches();
        Span elseKw = s.elseBody() == null ? null : Structured.elseSpan(ctx, s);
        boolean emptyThen = bs.size() == 1 && !Structured.isEmpty(s.elseBody());
        for (int i = 0; i < bs.size(); i++) {
            Statement.Branch b = bs.get(i);
            if (!Structured.isEmpty(b.body()) || i == 0 && emptyThen) {
                continue;
            }
            int end = i + 1 < bs.size()
                    ? bs.get(i + 1).condition().span().start()
                    : elseKw != null ? elseKw.start() : s.span().end();
            if (!Structured.commentBetween(ctx, s, b.condition().span().end(), end)) {
                ctx.report(
                        b.condition().span(),
                        "Empty " + (i == 0 ? "THEN" : "ELSIF") + " branch with no comment saying why");
            }
        }
        if (s.elseBody() != null
                && Structured.isEmpty(s.elseBody())
                && !Structured.commentBetween(ctx, s, elseKw.end(), s.span().end())) {
            ctx.report(elseKw, "Empty ELSE with no comment saying why");
        }
    }

    private static void caseOf(PouContext ctx, Statement.Case s) {
        List<Statement.CaseBranch> bs = s.branches();
        Span elseKw = s.elseBody() == null ? null : Structured.elseSpan(ctx, s);
        for (int i = 0; i < bs.size(); i++) {
            Statement.CaseBranch b = bs.get(i);
            if (!Structured.isEmpty(b.body()) || b.labels().isEmpty()) {
                continue;
            }
            int end = i + 1 < bs.size() && !bs.get(i + 1).labels().isEmpty()
                    ? bs.get(i + 1).labels().getFirst().span().start()
                    : elseKw != null ? elseKw.start() : s.span().end();
            if (!Structured.commentBetween(ctx, s, b.labels().getLast().span().end(), end)) {
                ctx.report(Structured.labels(b), "Empty CASE branch with no comment saying why");
            }
        }
        if (s.elseBody() != null
                && Structured.isEmpty(s.elseBody())
                && !Structured.commentBetween(ctx, s, elseKw.end(), s.span().end())) {
            ctx.report(elseKw, "Empty ELSE with no comment saying why");
        }
    }
}
