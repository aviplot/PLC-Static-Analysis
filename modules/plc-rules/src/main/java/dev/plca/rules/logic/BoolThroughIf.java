package dev.plca.rules.logic;

import dev.plca.ast.Expression;
import dev.plca.ast.Statement;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.rules.Spans;
import java.util.List;
import java.util.Locale;

/**
 * PLCA-L019: {@code IF c THEN b := TRUE; ELSE b := FALSE; END_IF} is {@code b := c;} (or {@code b := NOT c;} the other
 * way round).
 */
public final class BoolThroughIf implements PouRule {

    @Override
    public String id() {
        return "PLCA-L019";
    }

    @Override
    public void check(PouContext ctx) {
        Structured.forEach(ctx.body(), s -> {
            if (!(s instanceof Statement.If i) || i.branches().size() != 1 || i.elseBody() == null) {
                return;
            }
            Statement.Assignment then = single(i.branches().getFirst().body());
            Statement.Assignment other = single(i.elseBody());
            if (then == null || other == null || !Structural.equal(then.target(), other.target())) {
                return;
            }
            Boolean a = literal(then.value());
            Boolean b = literal(other.value());
            if (a == null || b == null || a.equals(b)) {
                return;
            }
            Expression c = i.branches().getFirst().condition();
            String target = ctx.text(then.target()).trim();
            String cond = ctx.text(c).trim();
            String value = a ? cond : Normalizer.designator(c) ? "NOT " + cond : "NOT (" + cond + ")";
            ctx.report(
                    Spans.header(i),
                    "This IF only sets '" + target + "' to TRUE or FALSE; write " + target + " := " + value + ";");
        });
    }

    private static Statement.Assignment single(List<Statement> body) {
        List<Statement> real = Structured.real(body);
        return real.size() == 1
                        && real.getFirst() instanceof Statement.Assignment a
                        && a.op() == Statement.AssignOp.ASSIGN
                ? a
                : null;
    }

    private static Boolean literal(Expression e) {
        if (e instanceof Expression.Literal l && l.kind() == Expression.LiteralKind.BOOL) {
            String t = l.text().toUpperCase(Locale.ROOT);
            if (t.equals("TRUE") || t.equals("1")) {
                return Boolean.TRUE;
            }
            if (t.equals("FALSE") || t.equals("0")) {
                return Boolean.FALSE;
            }
        }
        return null;
    }
}
