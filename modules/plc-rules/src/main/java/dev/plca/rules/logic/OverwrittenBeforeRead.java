package dev.plca.rules.logic;

import dev.plca.ast.Declaration;
import dev.plca.ast.Expression;
import dev.plca.ast.Statement;
import dev.plca.ast.VarBlock;
import dev.plca.core.diag.Diagnostic;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.semantic.Access;
import dev.plca.semantic.Statements;
import dev.plca.semantic.VariableSymbol;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PLCA-L023: a temporary value assigned and then assigned again in the same statement list before anything reads it.
 * Only VAR_TEMP, and the VARs of a FUNCTION or METHOD (they start fresh on every call): a static VAR keeps its value
 * for the next cycle, and another task or the HMI may read it in between. Not reported when the first value comes from
 * a call (the call is the point), when the address of the variable is taken anywhere, or across labels, jumps and
 * loops.
 */
public final class OverwrittenBeforeRead implements PouRule {

    @Override
    public String id() {
        return "PLCA-L023";
    }

    @Override
    public void check(PouContext ctx) {
        Declaration.PouKind kind = ctx.pou().kind();
        boolean freshVars = kind == Declaration.PouKind.FUNCTION || kind == Declaration.PouKind.METHOD;
        Set<VariableSymbol> aliased = new HashSet<>();
        ctx.forEachStatement(s -> {
            for (Access a : ctx.model().effects(s).accesses()) {
                if (a.kind() == Access.Kind.ADDRESS) {
                    aliased.add(a.variable());
                }
            }
        });
        list(ctx, ctx.body(), freshVars, aliased);
        Structured.forEach(ctx.body(), s -> Statements.forEachBody(s, b -> list(ctx, b, freshVars, aliased)));
    }

    private static void list(PouContext ctx, List<Statement> body, boolean freshVars, Set<VariableSymbol> aliased) {
        if (body == null) {
            return;
        }
        for (int i = 0; i < body.size(); i++) {
            VariableSymbol v = temp(ctx, body.get(i), freshVars, aliased);
            if (v == null) {
                continue;
            }
            for (int j = i + 1; j < body.size(); j++) {
                Statement next = body.get(j);
                if (next instanceof Statement.Label || Structured.leaves(next) || reads(ctx, next, v)) {
                    break;
                }
                if (next instanceof Statement.Assignment a
                        && a.op() == Statement.AssignOp.ASSIGN
                        && ctx.model().symbol(a.target()).orElse(null) == v
                        && a.target() instanceof Expression.NameRef) {
                    ctx.report(
                            body.get(i).span(),
                            "'" + v.name() + "' is assigned again before anything reads this value",
                            new Diagnostic.Related(a.span(), "assigned again here"));
                    break;
                }
            }
        }
    }

    /** The temporary variable a plain whole-variable assignment without calls writes, or null. */
    private static VariableSymbol temp(PouContext ctx, Statement s, boolean freshVars, Set<VariableSymbol> aliased) {
        if (!(s instanceof Statement.Assignment a)
                || a.op() != Statement.AssignOp.ASSIGN
                || !(a.target() instanceof Expression.NameRef)
                || Normalizer.hasCall(a.value())
                || !(ctx.model().symbol(a.target()).orElse(null) instanceof VariableSymbol v)) {
            return null;
        }
        boolean temp = v.section() == VarBlock.Section.TEMP
                || freshVars
                        && v.section() == VarBlock.Section.VAR
                        && v.owner() == ctx.pou()
                        && !v.is(VarBlock.Qualifier.CONSTANT);
        if (!temp || aliased.contains(v) || v.overlayOf() != null || v.isResult()) {
            return null;
        }
        return v;
    }

    /** Whether {@code s} (with its nested statements) may read {@code v}, or takes its address. */
    private static boolean reads(PouContext ctx, Statement s, VariableSymbol v) {
        if (s instanceof Statement.For || s instanceof Statement.While || s instanceof Statement.Repeat) {
            return true; // a loop: keep it simple
        }
        for (Access a : ctx.model().deepEffects(s).accesses()) {
            if (a.variable() == v && (a.isRead() || a.kind() == Access.Kind.ADDRESS)
                    || a.variable().overlayOf() == v) {
                return true;
            }
        }
        boolean[] jump = {false};
        Statements.forEachDeep(s, x -> jump[0] |= x instanceof Statement.Label || x instanceof Statement.Jump);
        return jump[0];
    }
}
