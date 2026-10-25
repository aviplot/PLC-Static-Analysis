package dev.plca.rules.logic;

import dev.plca.ast.Expression;
import dev.plca.ast.Statement;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.rules.Walk;
import dev.plca.semantic.Access;
import dev.plca.semantic.VariableSymbol;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PLCA-L020: every branch of an IF with ELSE (or of a CASE with ELSE) ends with the same statement, or starts with it,
 * so it can be written once after (or before) the block. "Starts with" is reported only when the statement writes
 * nothing the conditions read, so moving it before the block keeps the meaning. Branches that are entirely alike are
 * PLCA-L013's.
 */
public final class SameStatementInBranches implements PouRule {

    @Override
    public String id() {
        return "PLCA-L020";
    }

    @Override
    public void check(PouContext ctx) {
        Structured.forEach(ctx.body(), s -> {
            List<List<Statement>> bodies = new ArrayList<>();
            List<Expression> conditions = new ArrayList<>();
            String block;
            switch (s) {
                case Statement.If i
                when i.elseBody() != null -> {
                    i.branches().forEach(b -> {
                        bodies.add(Structured.real(b.body()));
                        conditions.add(b.condition());
                    });
                    bodies.add(Structured.real(i.elseBody()));
                    block = "IF";
                }
                case Statement.Case c
                when c.elseBody() != null && !c.branches().isEmpty() -> {
                    c.branches().forEach(b -> bodies.add(Structured.real(b.body())));
                    bodies.add(Structured.real(c.elseBody()));
                    conditions.add(c.selector());
                    block = "CASE";
                }
                default -> {
                    return;
                }
            }
            if (bodies.stream().anyMatch(List::isEmpty)) {
                return;
            }
            if (bodies.stream().allMatch(b -> Structural.equal(b, bodies.getFirst()))) {
                return;
            }
            if (same(bodies, true) && hoistable(ctx, bodies.getFirst().getFirst(), conditions)) {
                ctx.report(
                        bodies.getFirst().getFirst().span(),
                        "Every branch of this " + block + " starts with this statement; write it once before the "
                                + block);
            } else if (same(bodies, false)) {
                ctx.report(
                        bodies.getFirst().getLast().span(),
                        "Every branch of this " + block + " ends with this statement; write it once after END_"
                                + block);
            }
        });
    }

    private static boolean same(List<List<Statement>> bodies, boolean first) {
        Statement s = pick(bodies.getFirst(), first);
        return bodies.stream().allMatch(b -> Structural.equal(pick(b, first), s));
    }

    private static Statement pick(List<Statement> body, boolean first) {
        return first ? body.getFirst() : body.getLast();
    }

    /** The statement writes nothing the conditions read, and does not leave. */
    private static boolean hoistable(PouContext ctx, Statement s, List<Expression> conditions) {
        if (Structured.leaves(s)) {
            return false;
        }
        Set<VariableSymbol> written = new HashSet<>();
        for (Access a : ctx.model().deepEffects(s).accesses()) {
            if (a.isWrite() || a.kind() == Access.Kind.UNKNOWN) {
                written.add(a.variable());
            }
        }
        if (!ctx.model().deepEffects(s).calls().isEmpty()) {
            return false;
        }
        boolean[] ok = {true};
        for (Expression c : conditions) {
            Walk.deep(c, x -> {
                if (x instanceof Expression.Call
                        || ctx.model().symbol(x).orElse(null) instanceof VariableSymbol v && written.contains(v)) {
                    ok[0] = false;
                }
            });
        }
        return ok[0];
    }
}
