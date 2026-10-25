package dev.plca.rules.logic;

import dev.plca.ast.Expression;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.rules.Walk;
import dev.plca.semantic.ElementaryKind;
import dev.plca.semantic.Type;
import java.util.Locale;

/** PLCA-L025 (off by default): {@code b = TRUE} is {@code b}, {@code b = FALSE} is {@code NOT b}. */
public final class BoolComparedWithLiteral implements PouRule {

    @Override
    public String id() {
        return "PLCA-L025";
    }

    @Override
    public void check(PouContext ctx) {
        Structured.forEach(
                ctx.body(),
                s -> Walk.ownExpressions(
                        s,
                        e -> Walk.deep(e, x -> {
                            if (!(x instanceof Expression.Binary b)
                                    || b.op() != Expression.BinaryOp.EQ && b.op() != Expression.BinaryOp.NE) {
                                return;
                            }
                            Boolean l = literal(b.left());
                            Boolean r = literal(b.right());
                            if (l == null == (r == null)) {
                                return;
                            }
                            Expression other = l == null ? b.left() : b.right();
                            if (!isBool(ctx, other)) {
                                return;
                            }
                            boolean value = (l == null ? r : l) == (b.op() == Expression.BinaryOp.EQ);
                            String t = ctx.text(other).trim();
                            ctx.report(
                                    b.span(),
                                    "BOOL compared with "
                                            + (l == null ? r : l).toString().toUpperCase(Locale.ROOT) + "; write "
                                            + (value
                                                    ? t
                                                    : Normalizer.designator(other) ? "NOT " + t : "NOT (" + t + ")"));
                        })));
    }

    private static Boolean literal(Expression e) {
        if (e instanceof Expression.Literal l && l.kind() == Expression.LiteralKind.BOOL) {
            String t = l.text().toUpperCase(Locale.ROOT);
            if (t.equals("TRUE")) {
                return Boolean.TRUE;
            }
            if (t.equals("FALSE")) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static boolean isBool(PouContext ctx, Expression e) {
        Type t = ctx.model().type(e);
        ElementaryKind k = t == null ? null : Type.elementary(t.resolved());
        return k != null && k.category() == ElementaryKind.Category.BOOL;
    }
}
