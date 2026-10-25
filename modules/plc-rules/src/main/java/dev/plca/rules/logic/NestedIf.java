package dev.plca.rules.logic;

import dev.plca.ast.Expression;
import dev.plca.ast.Statement;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.rules.Spans;
import dev.plca.rules.Walk;
import java.util.List;

/**
 * PLCA-L016: an IF without ELSE whose only statement is another IF without ELSE; one IF with AND says the same. Not
 * reported when the inner condition is guarded by the outer one (it dereferences a pointer, indexes an array, divides
 * or calls something): ST's AND evaluates both sides.
 */
public final class NestedIf implements PouRule {

    @Override
    public String id() {
        return "PLCA-L016";
    }

    @Override
    public void check(PouContext ctx) {
        Structured.forEach(ctx.body(), s -> {
            if (!(s instanceof Statement.If outer) || !simple(outer)) {
                return;
            }
            List<Statement> body = Structured.real(outer.branches().getFirst().body());
            if (body.size() == 1
                    && body.getFirst() instanceof Statement.If inner
                    && simple(inner)
                    && safe(inner.branches().getFirst().condition())) {
                ctx.report(
                        Spans.header(inner),
                        "IF directly inside an IF without ELSE; merge the two conditions with AND");
            }
        });
    }

    private static boolean simple(Statement.If s) {
        return s.branches().size() == 1 && s.elseBody() == null;
    }

    private static boolean safe(Expression e) {
        boolean[] ok = {true};
        Walk.deep(e, x -> {
            switch (x) {
                case Expression.Deref d -> ok[0] = false;
                case Expression.Index i -> ok[0] = false;
                case Expression.Call c -> ok[0] = false;
                case Expression.Assign a -> ok[0] = false;
                case Expression.Binary b
                when b.op() == Expression.BinaryOp.DIV || b.op() == Expression.BinaryOp.MOD -> ok[0] = false;
                default -> {}
            }
        });
        return ok[0];
    }
}
