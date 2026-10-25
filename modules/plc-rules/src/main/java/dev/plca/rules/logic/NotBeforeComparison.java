package dev.plca.rules.logic;

import dev.plca.ast.Expression;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.rules.Walk;

/** PLCA-L024 (off by default): {@code NOT (a = b)} reads better as {@code a <> b}. */
public final class NotBeforeComparison implements PouRule {

    @Override
    public String id() {
        return "PLCA-L024";
    }

    @Override
    public void check(PouContext ctx) {
        Structured.forEach(
                ctx.body(),
                s -> Walk.ownExpressions(
                        s,
                        e -> Walk.deep(e, x -> {
                            if (x instanceof Expression.Unary u
                                    && u.op() == Expression.UnaryOp.NOT
                                    && u.operand() instanceof Expression.Binary b
                                    && Normalizer.isComparison(b.op())) {
                                ctx.report(
                                        u.span(),
                                        "NOT before a comparison; write "
                                                + ctx.text(b.left()).trim() + " " + inverse(b.op()) + " "
                                                + ctx.text(b.right()).trim());
                            }
                        })));
    }

    private static String inverse(Expression.BinaryOp op) {
        return switch (op) {
            case EQ -> "<>";
            case NE -> "=";
            case LT -> ">=";
            case LE -> ">";
            case GT -> "<=";
            case GE -> "<";
            default -> op.toString();
        };
    }
}
