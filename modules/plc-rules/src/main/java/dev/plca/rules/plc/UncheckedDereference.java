package dev.plca.rules.plc;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.BinaryOp;
import dev.plca.ast.Statement;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.rules.Walk;
import dev.plca.semantic.Access;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Statements;
import dev.plca.semantic.Type;
import dev.plca.semantic.VariableSymbol;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PLCA-P005: a pointer dereferenced ({@code p^}) without any check that it is not zero. A dereference counts as checked
 * when the body compares the pointer with 0 ({@code p <> 0}, {@code p = 0}, {@code 0 < p}, …) or tests it with
 * {@code __ISVALIDREF} before the dereference or in a condition around it, or assigns it from {@code ADR(…)} before.
 * The check is per POU body and in text order, a deliberately simple approximation of "on the path". A pointer member
 * of a function block that the block's own code assigns (in FB_init or a setter method) is the object's contract and
 * counts as checked. One finding per pointer and body.
 */
public final class UncheckedDereference implements PouRule {

    @Override
    public String id() {
        return "PLCA-P005";
    }

    @Override
    public void check(PouContext ctx) {
        SemanticModel m = ctx.model();
        Set<VariableSymbol> safe = new HashSet<>();
        Set<VariableSymbol> reported = new HashSet<>();
        // pointers of the function block that its own code sets (FB_init, a setter method): the object's contract
        PouSymbol owner = ctx.pou().instanceOwner();
        if (owner != null && owner.isInstantiable()) {
            assignedIn(m, owner, safe);
            owner.members().values().forEach(member -> assignedIn(m, member, safe));
        }
        statements(ctx, m, ctx.body(), safe, reported);
    }

    private static void assignedIn(SemanticModel m, PouSymbol p, Set<VariableSymbol> safe) {
        if (p.declaration() == null || p.declaration().body() == null) {
            return;
        }
        Statements.forEachDeep(p.declaration().body(), s -> {
            for (Access a : m.effects(s).accesses()) {
                if (a.isWrite()
                        && a.path().isEmpty()
                        && a.variable().owner() instanceof PouSymbol o
                        && o.isInstantiable()
                        && m.type(a.expression()).resolved() instanceof Type.Pointer) {
                    safe.add(a.variable());
                }
            }
        });
    }

    private void statements(
            PouContext ctx,
            SemanticModel m,
            List<Statement> body,
            Set<VariableSymbol> safe,
            Set<VariableSymbol> reported) {
        for (Statement s : body) {
            // checks in this statement's own expressions (conditions) guard everything inside it
            Walk.ownExpressions(s, e -> guards(m, e, safe));
            if (s instanceof Statement.Assignment a && isAdr(m, a.value()) && pointer(m, a.target()) != null) {
                // the dereferences in this statement's value come before the assignment: checked below first
                derefs(ctx, m, a.value(), safe, reported);
                safe.add(pointer(m, a.target()));
                continue;
            }
            Walk.ownExpressions(s, e -> derefs(ctx, m, e, safe, reported));
            Statements.forEachBody(s, b -> statements(ctx, m, b, safe, reported));
        }
    }

    private static void derefs(
            PouContext ctx, SemanticModel m, Expression root, Set<VariableSymbol> safe, Set<VariableSymbol> reported) {
        Walk.deep(root, e -> {
            if (e instanceof Expression.Deref d) {
                VariableSymbol p = pointer(m, d.target());
                if (p != null && !safe.contains(p) && reported.add(p)) {
                    ctx.report(
                            d.span(),
                            "Pointer '" + ctx.text(d.target()).strip() + "' is dereferenced without a check that it is"
                                    + " not 0; test it (" + ctx.text(d.target()).strip() + " <> 0) first");
                }
            }
        });
    }

    /** Pointers this expression compares with zero or tests with __ISVALIDREF. */
    private static void guards(SemanticModel m, Expression root, Set<VariableSymbol> safe) {
        Walk.deep(root, e -> {
            switch (e) {
                case Expression.Binary b
                when comparison(b.op()) -> {
                    VariableSymbol l = pointer(m, b.left());
                    VariableSymbol r = pointer(m, b.right());
                    if (l != null && zero(m, b.right())) {
                        safe.add(l);
                    }
                    if (r != null && zero(m, b.left())) {
                        safe.add(r);
                    }
                }
                case Expression.Call c
                when "__ISVALIDREF".equalsIgnoreCase(Walk.lastName(c.callee()))
                        && !c.arguments().isEmpty() -> {
                    VariableSymbol p = pointer(m, c.arguments().getFirst().value());
                    if (p != null) {
                        safe.add(p);
                    }
                }
                default -> {}
            }
        });
    }

    private static boolean comparison(BinaryOp op) {
        return op == BinaryOp.EQ
                || op == BinaryOp.NE
                || op == BinaryOp.GT
                || op == BinaryOp.LT
                || op == BinaryOp.GE
                || op == BinaryOp.LE;
    }

    private static boolean zero(SemanticModel m, Expression e) {
        return m.constant(e).map(c -> c.isZero()).orElse(false)
                || e instanceof Expression.NameRef n
                        && n.name().name().toUpperCase(Locale.ROOT).equals("NULL");
    }

    private static boolean isAdr(SemanticModel m, Expression e) {
        String name = e instanceof Expression.Call c ? Walk.lastName(c.callee()) : null;
        return name != null
                && (name.equalsIgnoreCase("ADR") || name.equalsIgnoreCase("REF") || name.equalsIgnoreCase("__NEW"));
    }

    /** The pointer variable an expression names ({@code p}, {@code stData.p}), or null. */
    private static VariableSymbol pointer(SemanticModel m, Expression e) {
        if (e == null
                || !(m.type(e).resolved() instanceof Type.Pointer ptr)
                || ptr.kind() != dev.plca.ast.TypeRef.PointerKind.POINTER
                        && ptr.kind() != dev.plca.ast.TypeRef.PointerKind.REF_TO) {
            return null;
        }
        return switch (e) {
            case Expression.NameRef n -> m.symbol(n).orElse(null) instanceof VariableSymbol v ? v : null;
            case Expression.MemberAccess ma -> m.symbol(ma).orElse(null) instanceof VariableSymbol v ? v : null;
            default -> null;
        };
    }
}
