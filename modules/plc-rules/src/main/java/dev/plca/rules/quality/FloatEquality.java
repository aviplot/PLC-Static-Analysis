package dev.plca.rules.quality;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.BinaryOp;
import dev.plca.ast.StText;
import dev.plca.ast.Statement;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.semantic.Constant;
import dev.plca.semantic.ElementaryKind;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Type;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * PLCA-Q003 (PLCopen CP8): {@code =} or {@code <>} with a REAL/LREAL operand. Comparisons with zero are CP8's own
 * exception ({@code allowZero}); {@code x = x} (the NaN check) is not reported, and neither is comparing a value with
 * its own copy — change detection such as {@code IF a <> last THEN last := a;} or ladder {@code NEQ(a,last)
 * MOV(a,last)}, which is exact by construction.
 */
public final class FloatEquality implements PouRule {

    @Override
    public String id() {
        return "PLCA-Q003";
    }

    @Override
    public void check(PouContext ctx) {
        SemanticModel m = ctx.model();
        boolean allowZero = ctx.boolParam("allowZero");
        Set<String> copies = new HashSet<>();
        ctx.forEachStatement(s -> {
            if (s instanceof Statement.Assignment a && designator(a.target()) && designator(a.value())) {
                copies.add(key(a.target(), a.value()));
            }
        });
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
            if (allowZero && (isZero(m, b.left()) || isZero(m, b.right()))) {
                return; // CP8 exception: comparison with 0.0 (division guards, "not set")
            }
            if (sameText(ctx, b.left(), b.right())) {
                return; // x = x is the NaN check
            }
            if (copies.contains(key(b.left(), b.right()))) {
                return; // compared with its own copy: change detection
            }
            ctx.report(
                    b.span(),
                    "Exact floating-point comparison with '" + (b.op() == BinaryOp.EQ ? "=" : "<>")
                            + "'; compare the difference against a tolerance, e.g. ABS(a - b) < eps");
        });
    }

    /** Order-independent key of two designators, by their normalised text. */
    private static String key(Expression a, Expression b) {
        String x = StText.expression(a).toLowerCase(Locale.ROOT);
        String y = StText.expression(b).toLowerCase(Locale.ROOT);
        return x.compareTo(y) <= 0 ? x + "\u0000" + y : y + "\u0000" + x;
    }

    private static boolean designator(Expression e) {
        return e instanceof Expression.NameRef
                || e instanceof Expression.MemberAccess
                || e instanceof Expression.Index
                || e instanceof Expression.BitAccess;
    }

    private static boolean isZero(SemanticModel m, Expression e) {
        return switch (m.constant(e).orElse(null)) {
            case Constant.Int i -> i.value() == 0;
            case Constant.Real r -> r.value() == 0.0;
            case null, default -> false;
        };
    }

    private static boolean sameText(PouContext ctx, Expression a, Expression b) {
        return a.span().textId() == b.span().textId()
                && ctx.text(a)
                        .replaceAll("\\s+", "")
                        .equalsIgnoreCase(ctx.text(b).replaceAll("\\s+", ""));
    }

    static boolean isReal(Type t) {
        if (t.resolved() instanceof Type.RealLiteral) {
            return true;
        }
        ElementaryKind k = Type.elementary(t);
        return k != null && k.isReal();
    }
}
