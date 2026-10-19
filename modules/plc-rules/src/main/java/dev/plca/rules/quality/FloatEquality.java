package dev.plca.rules.quality;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.BinaryOp;
import dev.plca.ast.StText;
import dev.plca.ast.Statement;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.semantic.Access;
import dev.plca.semantic.Constant;
import dev.plca.semantic.ElementaryKind;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Statements;
import dev.plca.semantic.Type;
import dev.plca.semantic.VariableSymbol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * PLCA-Q003 (PLCopen CP8): {@code =} or {@code <>} with a REAL/LREAL operand. Comparisons with zero are CP8's own
 * exception ({@code allowZero}); {@code x = x} (the NaN check) is not reported, and neither is comparing a value with
 * its own copy — change detection such as {@code IF a <> last THEN last := a;} or ladder {@code NEQ(a,last)
 * MOV(a,last)}, which is exact by construction. Rockwell: comparing two variables that no code of the POU writes (two
 * settings: {@code EQU(Cfg_InpMax, Cfg_InpMin)} to flag an empty range) is exact by intent and not reported either.
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
        boolean rockwell = ctx.pou().dialect() == dev.plca.core.Dialect.ROCKWELL;
        // computed only when a comparison gets this far, and shared by the routines of one program or AOI
        Supplier<Set<VariableSymbol>> written = () -> WRITTEN.computeIfAbsent(owner(ctx.pou()), o -> written(ctx, o));
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
            if (rockwell && setting(m, b.left(), written.get()) && setting(m, b.right(), written.get())) {
                return; // two settings: nothing computed, exact by intent
            }
            ctx.report(
                    b.span(),
                    "Exact floating-point comparison with '" + (b.op() == BinaryOp.EQ ? "=" : "<>")
                            + "'; compare the difference against a tolerance, e.g. ABS(a - b) < eps");
        });
    }

    /** Written variables per program/AOI; weak keys, so models of earlier runs are not kept alive. */
    private static final Map<PouSymbol, Set<VariableSymbol>> WRITTEN = Collections.synchronizedMap(new WeakHashMap<>());

    private static PouSymbol owner(PouSymbol pou) {
        return pou.instanceOwner() != null ? pou.instanceOwner() : pou;
    }

    /** Variables written anywhere in the POU's instance (the FB/program and its members: methods, routines). */
    private static Set<VariableSymbol> written(PouContext ctx, PouSymbol owner) {
        Set<VariableSymbol> out = Collections.newSetFromMap(new IdentityHashMap<>());
        List<PouSymbol> scope = new ArrayList<>(owner.members().values());
        scope.add(owner);
        for (PouSymbol p : scope) {
            if (p.declaration() == null || p.declaration().body() == null) {
                continue;
            }
            Statements.forEachDeep(p.declaration().body(), s -> {
                for (Access a : ctx.model().effects(s).accesses()) {
                    if (a.isWrite()) {
                        out.add(a.variable());
                    }
                }
            });
        }
        return out;
    }

    /** A variable (or member of one) that no code of the POU writes. */
    private static boolean setting(SemanticModel m, Expression e, Set<VariableSymbol> written) {
        Expression root = e;
        while (root instanceof Expression.MemberAccess || root instanceof Expression.Index) {
            root = root instanceof Expression.MemberAccess ma ? ma.target() : ((Expression.Index) root).target();
        }
        return designator(e)
                && m.symbol(root).orElse(null) instanceof VariableSymbol v
                && !written.contains(v)
                && (v.overlayOf() == null || !written.contains(v.overlayOf()));
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
