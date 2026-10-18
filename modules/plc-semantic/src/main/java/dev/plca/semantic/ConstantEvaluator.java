package dev.plca.semantic;

import dev.plca.ast.Expression;
import dev.plca.ast.Initializer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Function;

/**
 * Folds constant expressions: literals, {@code VAR CONSTANT} variables (through their initial value), enum values,
 * unary and binary operators on them. Returns null for anything not constant. Integer overflow and division by zero
 * yield null rather than a wrong value. Thread-safe: each evaluation tracks its own cycle guard.
 */
final class ConstantEvaluator {

    private final Function<Expression, Symbol> resolver;

    ConstantEvaluator(Function<Expression, Symbol> resolver) {
        this.resolver = resolver;
    }

    Constant evaluate(Expression e) {
        return evaluate(e, resolver);
    }

    Constant evaluate(Expression e, Function<Expression, Symbol> names) {
        return new Run(names).evaluate(e, 0);
    }

    Long integer(Expression e, Function<Expression, Symbol> names) {
        Constant c = evaluate(e, names);
        return c instanceof Constant.Int i ? i.value() : null;
    }

    /** One evaluation: its own guard against cyclic constant definitions. */
    private static final class Run {
        private final Function<Expression, Symbol> names;
        private final Set<VariableSymbol> inProgress = Collections.newSetFromMap(new IdentityHashMap<>());

        Run(Function<Expression, Symbol> names) {
            this.names = names;
        }

        private Constant evaluate(Expression e, int depth) {
            if (e == null || depth > 200) {
                return null;
            }
            return switch (e) {
                case Expression.Literal l -> literal(l);
                case Expression.NameRef n -> symbolValue(names.apply(n), depth);
                case Expression.MemberAccess m -> symbolValue(names.apply(m), depth);
                case Expression.Unary u -> unary(u.op(), evaluate(u.operand(), depth + 1));
                case Expression.Binary b ->
                    binary(b.op(), evaluate(b.left(), depth + 1), evaluate(b.right(), depth + 1));
                default -> null;
            };
        }

        private Constant symbolValue(Symbol s, int depth) {
            if (s instanceof EnumValueSymbol v) {
                return new Constant.Int(v.value());
            }
            if (s instanceof VariableSymbol v && v.isConstant() && v.initializer() instanceof Expression init) {
                if (!inProgress.add(v)) {
                    return null; // cyclic constant definitions
                }
                try {
                    return evaluate(init, depth + 1);
                } finally {
                    inProgress.remove(v);
                }
            }
            return null;
        }
    }

    static Constant literal(Expression.Literal l) {
        return switch (l.kind()) {
            case INTEGER -> {
                Long v = Literals.integer(l.text());
                yield v == null ? null : new Constant.Int(v);
            }
            case REAL -> {
                Double d = Literals.real(l.text());
                yield d == null ? null : new Constant.Real(d);
            }
            case BOOL -> {
                Boolean b = Literals.bool(l.text());
                yield b == null ? null : new Constant.Bool(b);
            }
            case TIME -> {
                Long ns = Literals.durationNanos(l.text());
                yield ns == null ? null : new Constant.Duration(ns);
            }
            default -> null;
        };
    }

    private static Constant unary(Expression.UnaryOp op, Constant c) {
        if (c == null) {
            return null;
        }
        return switch (op) {
            case PLUS -> c;
            case NEGATE ->
                switch (c) {
                    case Constant.Int i -> i.value() == Long.MIN_VALUE ? null : new Constant.Int(-i.value());
                    case Constant.Real r -> new Constant.Real(-r.value());
                    case Constant.Duration d -> new Constant.Duration(-d.nanos());
                    default -> null;
                };
            case NOT -> c instanceof Constant.Bool b ? new Constant.Bool(!b.value()) : null;
        };
    }

    private static Constant binary(Expression.BinaryOp op, Constant a, Constant b) {
        if (a == null || b == null) {
            return null;
        }
        if (a instanceof Constant.Bool x && b instanceof Constant.Bool y) {
            return switch (op) {
                case AND, AND_THEN -> new Constant.Bool(x.value() && y.value());
                case OR, OR_ELSE -> new Constant.Bool(x.value() || y.value());
                case XOR -> new Constant.Bool(x.value() ^ y.value());
                case EQ -> new Constant.Bool(x.value() == y.value());
                case NE -> new Constant.Bool(x.value() != y.value());
                default -> null;
            };
        }
        if (a instanceof Constant.Int x && b instanceof Constant.Int y) {
            long l = x.value();
            long r = y.value();
            try {
                return switch (op) {
                    case ADD -> new Constant.Int(Math.addExact(l, r));
                    case SUB -> new Constant.Int(Math.subtractExact(l, r));
                    case MUL -> new Constant.Int(Math.multiplyExact(l, r));
                    case DIV -> r == 0 ? null : new Constant.Int(l / r);
                    case MOD -> r == 0 ? null : new Constant.Int(l % r);
                    case AND -> new Constant.Int(l & r);
                    case OR -> new Constant.Int(l | r);
                    case XOR -> new Constant.Int(l ^ r);
                    default -> compare(op, Long.compare(l, r));
                };
            } catch (ArithmeticException overflow) {
                return null;
            }
        }
        if (a instanceof Constant.Duration x && b instanceof Constant.Duration y) {
            return switch (op) {
                case ADD -> new Constant.Duration(x.nanos() + y.nanos());
                case SUB -> new Constant.Duration(x.nanos() - y.nanos());
                default -> compare(op, Long.compare(x.nanos(), y.nanos()));
            };
        }
        Double x = numeric(a);
        Double y = numeric(b);
        if (x == null || y == null) {
            return null;
        }
        return switch (op) {
            case ADD -> new Constant.Real(x + y);
            case SUB -> new Constant.Real(x - y);
            case MUL -> new Constant.Real(x * y);
            case DIV -> y == 0 ? null : new Constant.Real(x / y);
            case POWER -> new Constant.Real(Math.pow(x, y));
            default -> compare(op, Double.compare(x, y));
        };
    }

    private static Double numeric(Constant c) {
        return c instanceof Constant.Int || c instanceof Constant.Real ? c.asDouble() : null;
    }

    private static Constant compare(Expression.BinaryOp op, int cmp) {
        return switch (op) {
            case EQ -> new Constant.Bool(cmp == 0);
            case NE -> new Constant.Bool(cmp != 0);
            case LT -> new Constant.Bool(cmp < 0);
            case LE -> new Constant.Bool(cmp <= 0);
            case GT -> new Constant.Bool(cmp > 0);
            case GE -> new Constant.Bool(cmp >= 0);
            default -> null;
        };
    }

    /** Unwraps an initializer that is a plain expression. */
    static Expression expressionOf(Initializer init) {
        return init instanceof Expression e ? e : null;
    }
}
