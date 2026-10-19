package dev.plca.rules;

import dev.plca.ast.Expression;
import dev.plca.ast.Initializer;
import dev.plca.ast.Statement;
import dev.plca.semantic.Statements;
import java.util.List;
import java.util.function.Consumer;

/** Expression traversal for rules (statements: {@link Statements}). */
public final class Walk {

    private Walk() {}

    /** The expressions a statement holds itself (not those of nested statements). */
    public static void ownExpressions(Statement s, Consumer<Expression> action) {
        switch (s) {
            case Statement.Assignment a -> {
                action.accept(a.target());
                action.accept(a.value());
            }
            case Statement.CallStatement c -> action.accept(c.call());
            case Statement.If i -> i.branches().forEach(b -> action.accept(b.condition()));
            case Statement.Case c -> {
                action.accept(c.selector());
                c.branches()
                        .forEach(b -> b.labels().forEach(l -> {
                            action.accept(l.low());
                            if (l.high() != null) {
                                action.accept(l.high());
                            }
                        }));
            }
            case Statement.For f -> {
                action.accept(f.variable());
                action.accept(f.from());
                action.accept(f.to());
                if (f.by() != null) {
                    action.accept(f.by());
                }
            }
            case Statement.While w -> action.accept(w.condition());
            case Statement.Repeat r -> action.accept(r.condition());
            case Statement.Return r -> {
                if (r.value() != null) {
                    action.accept(r.value());
                }
            }
            default -> {}
        }
    }

    /** {@code e} and all its subexpressions (call callees and argument values included), pre-order. */
    public static void deep(Expression e, Consumer<Expression> action) {
        if (e == null) {
            return;
        }
        action.accept(e);
        switch (e) {
            case Expression.MemberAccess m -> deep(m.target(), action);
            case Expression.BitAccess b -> deep(b.target(), action);
            case Expression.Slice s -> deep(s.target(), action);
            case Expression.Deref d -> deep(d.target(), action);
            case Expression.Index i -> {
                deep(i.target(), action);
                i.indices().forEach(x -> deep(x, action));
            }
            case Expression.Call c -> {
                deep(c.callee(), action);
                c.arguments().forEach(a -> deep(a.value(), action));
            }
            case Expression.Unary u -> deep(u.operand(), action);
            case Expression.Binary b -> {
                deep(b.left(), action);
                deep(b.right(), action);
            }
            default -> {}
        }
    }

    /** Every expression in a statement tree. */
    public static void expressions(Statement s, Consumer<Expression> action) {
        Statements.forEachDeep(s, st -> ownExpressions(st, e -> deep(e, action)));
    }

    /** Every expression in a body ({@code null} bodies are empty). */
    public static void expressions(List<Statement> body, Consumer<Expression> action) {
        Statements.forEachDeep(body, st -> ownExpressions(st, e -> deep(e, action)));
    }

    /** Every expression in an initial value. */
    public static void expressions(Initializer init, Consumer<Expression> action) {
        switch (init) {
            case null -> {}
            case Expression e -> deep(e, action);
            case Initializer.ArrayInit a -> a.elements().forEach(el -> expressions(el.value(), action));
            case Initializer.StructInit s -> s.fields().forEach(f -> expressions(f.value(), action));
        }
    }

    /** The last name of a designator: {@code x}, {@code fb.x}, {@code a[i]} and {@code p^} give the variable's name. */
    public static String lastName(Expression e) {
        return switch (e) {
            case Expression.NameRef n -> n.name().name();
            case Expression.MemberAccess m -> m.member().name();
            case Expression.Index i -> lastName(i.target());
            case Expression.Deref d -> lastName(d.target());
            case Expression.BitAccess b -> lastName(b.target());
            case Expression.Slice s -> lastName(s.target());
            case null, default -> null;
        };
    }
}
