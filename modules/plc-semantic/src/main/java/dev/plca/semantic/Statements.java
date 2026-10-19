package dev.plca.semantic;

import dev.plca.ast.Statement;
import java.util.List;
import java.util.function.Consumer;

/** Traversal helpers over statement trees. */
public final class Statements {

    private Statements() {}

    /** Direct child statement lists of a compound statement. */
    public static void forEachBody(Statement s, Consumer<List<Statement>> action) {
        switch (s) {
            case Statement.If i -> {
                i.branches().forEach(b -> action.accept(b.body()));
                if (i.elseBody() != null) {
                    action.accept(i.elseBody());
                }
            }
            case Statement.Case c -> {
                c.branches().forEach(b -> action.accept(b.body()));
                if (c.elseBody() != null) {
                    action.accept(c.elseBody());
                }
            }
            case Statement.For f -> action.accept(f.body());
            case Statement.While w -> action.accept(w.body());
            case Statement.Repeat r -> action.accept(r.body());
            case Statement.Region r -> action.accept(r.body());
            case Statement.Rung r -> action.accept(r.body());
            case Statement.Try t -> {
                action.accept(t.body());
                if (t.catchBody() != null) {
                    action.accept(t.catchBody());
                }
                if (t.finallyBody() != null) {
                    action.accept(t.finallyBody());
                }
            }
            default -> {}
        }
    }

    /** {@code s} and every statement nested in it, pre-order. */
    public static void forEachDeep(Statement s, Consumer<Statement> action) {
        action.accept(s);
        forEachBody(s, list -> list.forEach(child -> forEachDeep(child, action)));
    }

    /** Every statement of a body, pre-order. */
    public static void forEachDeep(List<Statement> body, Consumer<Statement> action) {
        if (body != null) {
            body.forEach(s -> forEachDeep(s, action));
        }
    }
}
