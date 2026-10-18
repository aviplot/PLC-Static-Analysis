package dev.plca.rules.quality;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.BinaryOp;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.rules.Walk;
import dev.plca.semantic.BuiltinFunction;
import dev.plca.semantic.Constant;
import dev.plca.semantic.SemanticModel;

/** PLCA-Q004: {@code /}, {@code MOD}, {@code DIV(…)} or {@code MOD(…)} whose divisor is a constant zero. */
public final class DivisionByZero implements PouRule {

    @Override
    public String id() {
        return "PLCA-Q004";
    }

    @Override
    public void check(PouContext ctx) {
        SemanticModel m = ctx.model();
        ctx.forEachExpression(e -> {
            Expression divisor = switch (e) {
                case Expression.Binary b when b.op() == BinaryOp.DIV || b.op() == BinaryOp.MOD -> b.right();
                case Expression.Call c when isDivision(m, c) -> secondArgument(c);
                default -> null;
            };
            if (divisor != null && isZero(m, divisor)) {
                ctx.report(divisor.span(), (isModulo(e) ? "Modulo" : "Division") + " by zero: the divisor is always 0");
            }
        });
    }

    private static boolean isDivision(SemanticModel m, Expression.Call c) {
        return m.symbol(c.callee()).orElse(null) instanceof BuiltinFunction f
                && (f.name().equalsIgnoreCase("DIV") || f.name().equalsIgnoreCase("MOD"));
    }

    /** {@code DIV(a, b)} or {@code DIV(IN1 := a, IN2 := b)}. */
    private static Expression secondArgument(Expression.Call c) {
        for (Expression.Argument a : c.arguments()) {
            if (a.name() != null && a.name().is("IN2")) {
                return a.value();
            }
        }
        return c.arguments().size() == 2 && c.arguments().get(1).name() == null
                ? c.arguments().get(1).value()
                : null;
    }

    private static boolean isZero(SemanticModel m, Expression e) {
        return switch (m.constant(e).orElse(null)) {
            case Constant.Int i -> i.value() == 0;
            case Constant.Real r -> r.value() == 0.0;
            case null, default -> false;
        };
    }

    private static boolean isModulo(Expression e) {
        return e instanceof Expression.Binary b
                ? b.op() == BinaryOp.MOD
                : "MOD".equalsIgnoreCase(Walk.lastName(((Expression.Call) e).callee()));
    }
}
