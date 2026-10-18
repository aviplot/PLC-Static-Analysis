package dev.plca.rules.quality;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.BinaryOp;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.semantic.ElementaryKind;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Type;

/** PLCA-Q003 (PLCopen CP8): {@code =} or {@code <>} with a REAL/LREAL operand. */
public final class FloatEquality implements PouRule {

    @Override
    public String id() {
        return "PLCA-Q003";
    }

    @Override
    public void check(PouContext ctx) {
        SemanticModel m = ctx.model();
        ctx.forEachExpression(e -> {
            if (!(e instanceof Expression.Binary b) || b.op() != BinaryOp.EQ && b.op() != BinaryOp.NE) {
                return;
            }
            if (!isReal(m.type(b.left())) && !isReal(m.type(b.right()))) {
                return;
            }
            if (m.constant(b.left()).isPresent() && m.constant(b.right()).isPresent()) {
                return; // folded at compile time, nothing to compare at run time
            }
            ctx.report(
                    b.span(),
                    "Exact floating-point comparison with '" + (b.op() == BinaryOp.EQ ? "=" : "<>")
                            + "'; compare the difference against a tolerance, e.g. ABS(a - b) < eps");
        });
    }

    static boolean isReal(Type t) {
        if (t.resolved() instanceof Type.RealLiteral) {
            return true;
        }
        ElementaryKind k = Type.elementary(t);
        return k != null && k.isReal();
    }
}
