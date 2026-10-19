package dev.plca.rules.quality;

import dev.plca.ast.Expression;
import dev.plca.ast.Statement;
import dev.plca.ast.VarBlock;
import dev.plca.core.source.Span;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.rules.Walk;
import dev.plca.semantic.BuiltinFunction;
import dev.plca.semantic.Constant;
import dev.plca.semantic.ElementaryKind;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Statements;
import dev.plca.semantic.Type;
import dev.plca.semantic.VariableSymbol;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * PLCA-Q011 (PLCopen CP25): an implicit conversion that can lose data — an assignment, input argument or output
 * argument from a wider to a narrower integer or bit-string type, from a real to an integer, or from LREAL to REAL —
 * and integer constants that do not fit the target type.
 *
 * <p>The width of an expression is that of its widest typed operand; untyped literals adapt to the other operand (as
 * IEC 61131-3 says) and so never widen it. Conversions between signed and unsigned of the same size, and between
 * integers and reals of any size, are not reported. Named constants count by value: one that fits is fine. Rockwell
 * code is not checked: Logix converts on assignment by design and has no conversion functions to make it explicit.
 */
public final class ImplicitNarrowing implements PouRule {

    @Override
    public String id() {
        return "PLCA-Q011";
    }

    @Override
    public void check(PouContext ctx) {
        SemanticModel m = ctx.model();
        textual(ctx.body(), s -> {
            if (s instanceof Statement.Assignment a
                    && (a.op() == Statement.AssignOp.ASSIGN || a.op() == Statement.AssignOp.NON_RETENTIVE)) {
                check(ctx, m.type(a.target()), a.value(), a.value().span());
            }
            Walk.ownExpressions(
                    s,
                    root -> Walk.deep(root, e -> {
                        if (e instanceof Expression.Call c) {
                            for (Expression.Argument arg : c.arguments()) {
                                VariableSymbol p = m.parameter(arg).orElse(null);
                                if (p == null || arg.value() == null) {
                                    continue;
                                }
                                if (arg.kind() == Expression.ArgumentKind.OUTPUT) {
                                    ElementaryKind from = Type.elementary(p.type());
                                    ElementaryKind to = Type.elementary(m.type(arg.value()));
                                    if (from != null && to != null && narrowing(from, to)) {
                                        report(ctx, from, to, arg.span());
                                    }
                                } else if (p.section() == VarBlock.Section.INPUT) {
                                    check(
                                            ctx,
                                            p.type(),
                                            arg.value(),
                                            arg.value().span());
                                }
                            }
                        }
                    }));
        });
    }

    /** Every statement outside ladder rungs, pre-order. */
    private static void textual(List<Statement> body, Consumer<Statement> action) {
        for (Statement s : body) {
            if (s instanceof Statement.Rung) {
                continue;
            }
            action.accept(s);
            Statements.forEachBody(s, b -> textual(b, action));
        }
    }

    private static void check(PouContext ctx, Type target, Expression value, Span span) {
        ElementaryKind to = Type.elementary(target);
        if (to == null || !(to.isNumeric() || to.isBitString())) {
            return;
        }
        Constant constant = ctx.model().constant(value).orElse(null);
        if (!hasName(value) || constant != null) {
            // a constant (literal or named): it only has to fit; 64-bit hex literals do not fit a Java long
            if ((to.isInteger() || to.isBitString())
                    && to.bits() < 64
                    && constant instanceof Constant.Int i
                    && (i.value() < to.minValue() || i.value() > to.maxValue())) {
                ctx.report(
                        span,
                        "Constant " + i.value() + " does not fit in " + to + " (" + to.minValue() + ".." + to.maxValue()
                                + ")");
            }
            return;
        }
        ElementaryKind from = width(ctx.model(), value);
        if (from != null && narrowing(from, to)) {
            report(ctx, from, to, span);
        }
    }

    private static void report(PouContext ctx, ElementaryKind from, ElementaryKind to, Span span) {
        ctx.report(
                span,
                "Implicit conversion from " + from + " to " + to + " may lose data; convert explicitly (" + from
                        + "_TO_" + to + " or TO_" + to + ")");
    }

    /** The type an expression's value has before conversion: the widest typed operand; null when unknown. */
    static ElementaryKind width(SemanticModel m, Expression e) {
        return switch (e) {
            case Expression.Literal l -> null;
            case Expression.Unary u -> u.op() == Expression.UnaryOp.NOT ? kind(m, e) : width(m, u.operand());
            case Expression.Binary b ->
                switch (b.op()) {
                    case ADD, SUB, MUL, DIV, MOD, AND, OR, XOR ->
                        wider(width(m, b.left()), width(m, b.right()), kind(m, e));
                    default -> kind(m, e);
                };
            case Expression.Call c
            when m.symbol(c.callee()).orElse(null) instanceof BuiltinFunction f -> builtin(m, c, f);
            default -> kind(m, e);
        };
    }

    /**
     * Generic standard functions ({@code MAX}, {@code SEL}, {@code ADD}, …) take the type of their typed arguments; the
     * model types them from literal arguments too ({@code MAX(0.0, r)} as LREAL), which is not what the compiler does.
     * Conversions keep their declared result; SIZEOF fits whatever holds it.
     */
    private static ElementaryKind builtin(SemanticModel m, Expression.Call c, BuiltinFunction f) {
        String name = f.name().toUpperCase(Locale.ROOT);
        if (name.equals("SIZEOF") || name.equals("XSIZEOF")) {
            return null;
        }
        if (name.contains("_TO_") || name.startsWith("TO_") || name.startsWith("TRUNC")) {
            return kind(m, c);
        }
        boolean literalArgument = false;
        ElementaryKind widest = null;
        for (int i = 0; i < c.arguments().size(); i++) {
            Expression.Argument a = c.arguments().get(i);
            if (a.value() == null || a.kind() == Expression.ArgumentKind.OUTPUT) {
                continue;
            }
            if (i == 0 && (name.equals("SEL") || name.equals("MUX"))) {
                continue; // the selector is not part of the result
            }
            if (a.value() instanceof Expression.Literal || m.constant(a.value()).isPresent() && !hasName(a.value())) {
                literalArgument = true;
                continue;
            }
            widest = wider(widest, width(m, a.value()), widest);
        }
        return literalArgument ? widest : kind(m, c);
    }

    private static ElementaryKind kind(SemanticModel m, Expression e) {
        Type t = m.type(e);
        if (t instanceof Type.IntLiteral || t instanceof Type.RealLiteral) {
            return null;
        }
        return Type.elementary(t);
    }

    private static ElementaryKind wider(ElementaryKind a, ElementaryKind b, ElementaryKind fallback) {
        if (a == null || b == null) {
            return a != null ? a : b;
        }
        if (!(a.isNumeric() || a.isBitString()) || !(b.isNumeric() || b.isBitString())) {
            return fallback;
        }
        if (a.isReal() || b.isReal()) {
            return a == ElementaryKind.LREAL || b == ElementaryKind.LREAL ? ElementaryKind.LREAL : a.isReal() ? a : b;
        }
        return a.bits() >= b.bits() ? a : b;
    }

    static boolean narrowing(ElementaryKind from, ElementaryKind to) {
        boolean fromInt = from.isInteger() || from.isBitString();
        boolean toInt = to.isInteger() || to.isBitString();
        if (fromInt && toInt) {
            return to.bits() < from.bits();
        }
        if (from.isReal() && toInt) {
            return true;
        }
        return from == ElementaryKind.LREAL && to == ElementaryKind.REAL;
    }

    private static boolean hasName(Expression e) {
        boolean[] found = {false};
        Walk.deep(
                e,
                x -> found[0] |= x instanceof Expression.NameRef
                        || x instanceof Expression.MemberAccess
                        || x instanceof Expression.Call);
        return found[0];
    }
}
