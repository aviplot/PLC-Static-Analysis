package dev.plca.rules.quality;

import dev.plca.ast.Expression;
import dev.plca.ast.Statement;
import dev.plca.ast.VarBlock;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.rules.Spans;
import dev.plca.rules.Walk;
import dev.plca.semantic.Access;
import dev.plca.semantic.CallSite;
import dev.plca.semantic.Constant;
import dev.plca.semantic.Effects;
import dev.plca.semantic.Statements;
import dev.plca.semantic.VariableSymbol;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PLCA-Q012: a WHILE or REPEAT loop with no evident way out. The condition is constant (WHILE TRUE, UNTIL FALSE) or
 * reads only variables the loop body never writes, and the body has no EXIT, RETURN or JMP out of the loop. A PLC cycle
 * that never ends trips the watchdog and stops the controller.
 *
 * <p>Errs towards silence: not reported when the condition calls a function, method or property or assigns a variable,
 * reads through a pointer or reference, reads a VAR_IN_OUT, or reads a global, an instance member or a function block
 * instance while the body calls other code (which may change it), or when a read variable has its address taken
 * anywhere in the POU.
 */
public final class LoopWithoutExit implements PouRule {

    @Override
    public String id() {
        return "PLCA-Q012";
    }

    @Override
    public void check(PouContext ctx) {
        Set<VariableSymbol> aliased = new HashSet<>();
        ctx.forEachStatement(s -> {
            for (Access a : ctx.model().effects(s).accesses()) {
                if (a.kind() == Access.Kind.ADDRESS) {
                    aliased.add(a.variable());
                }
            }
        });
        ctx.forEachStatement(s -> {
            Expression condition;
            List<Statement> body;
            boolean runsWhileTrue;
            switch (s) {
                case Statement.While w -> {
                    condition = w.condition();
                    body = w.body();
                    runsWhileTrue = true;
                }
                case Statement.Repeat r -> {
                    condition = r.condition();
                    body = r.body();
                    runsWhileTrue = false;
                }
                default -> {
                    return;
                }
            }
            if (leaves(body)) {
                return;
            }
            String keyword = runsWhileTrue ? "WHILE" : "REPEAT";
            Constant c = ctx.model().constant(condition).orElse(null);
            if (c instanceof Constant.Bool b) {
                if (b.value() == runsWhileTrue) {
                    ctx.report(
                            Spans.header(s),
                            keyword + " loop never ends: its condition is always " + (runsWhileTrue ? "TRUE" : "FALSE")
                                    + " and the body has no EXIT, RETURN or JMP");
                }
                return;
            }
            if (c != null || callsCode(condition)) {
                return;
            }
            Effects own = ctx.model().effects(s);
            if (!own.calls().isEmpty() || own.accesses().stream().anyMatch(Access::isWrite)) {
                return; // a property or method in the condition, or an assignment inside it
            }
            List<Access> reads = new ArrayList<>();
            for (Access a : own.accesses()) {
                if (a.isRead()) {
                    reads.add(a);
                }
            }
            if (reads.isEmpty()) {
                return; // nothing we can track (an unresolved name)
            }
            Effects inside = effects(ctx, body);
            boolean bodyCalls = !inside.calls().isEmpty() || callsCode(body);
            for (Access r : reads) {
                VariableSymbol v = r.variable();
                if (aliased.contains(v)
                        || r.path().contains("^")
                        || v.section() == VarBlock.Section.IN_OUT
                        || v.type().resolved() instanceof dev.plca.semantic.Type.Pointer
                        || bodyCalls
                                && (v.isGlobal()
                                        || !r.path().isEmpty()
                                        || isInstanceMember(ctx, v)
                                        || v.type().resolved() instanceof dev.plca.semantic.Type.Instance)
                        || inside.accesses().stream()
                                .anyMatch(a -> a.variable() == v && (a.isWrite() || a.kind() == Access.Kind.UNKNOWN))) {
                    return;
                }
            }
            ctx.report(
                    Spans.header(s),
                    keyword + " loop has no evident exit: the body never changes "
                            + String.join(
                                    ", ",
                                    reads.stream()
                                            .map(Access::target)
                                            .distinct()
                                            .toList())
                            + ", which the condition reads, and has no EXIT, RETURN or JMP");
        });
    }

    /** EXIT/RETURN/JMP that leaves this loop (EXIT of a nested loop does not). */
    private static boolean leaves(List<Statement> body) {
        boolean[] found = {false};
        for (Statement s : body) {
            leaves(s, found);
        }
        return found[0];
    }

    private static void leaves(Statement s, boolean[] found) {
        switch (s) {
            case Statement.Exit e -> found[0] = true;
            case Statement.Return r -> found[0] = true;
            case Statement.Jump j -> found[0] = true;
            case Statement.For f -> returnsOrJumps(f.body(), found);
            case Statement.While w -> returnsOrJumps(w.body(), found);
            case Statement.Repeat r -> returnsOrJumps(r.body(), found);
            default -> Statements.forEachBody(s, b -> b.forEach(x -> leaves(x, found)));
        }
    }

    /** Inside a nested loop only RETURN and JMP leave the outer loop. */
    private static void returnsOrJumps(List<Statement> body, boolean[] found) {
        Statements.forEachDeep(body, x -> found[0] |= x instanceof Statement.Return || x instanceof Statement.Jump);
    }

    private static Effects effects(PouContext ctx, List<Statement> body) {
        List<Access> accesses = new ArrayList<>();
        List<CallSite> calls = new ArrayList<>();
        for (Statement s : body) {
            Effects e = ctx.model().deepEffects(s);
            accesses.addAll(e.accesses());
            calls.addAll(e.calls());
        }
        return new Effects(accesses, calls);
    }

    /** Any call, including calls the model could not bind (library code). */
    private static boolean callsCode(List<Statement> body) {
        boolean[] found = {false};
        Walk.expressions(body, x -> found[0] |= x instanceof Expression.Call);
        return found[0];
    }

    private static boolean callsCode(Expression condition) {
        boolean[] found = {false};
        Walk.deep(condition, x -> found[0] |= x instanceof Expression.Call);
        return found[0];
    }

    /** A variable of a function block or class: methods called from the body may change it. */
    private static boolean isInstanceMember(PouContext ctx, VariableSymbol v) {
        return v.owner() instanceof dev.plca.semantic.PouSymbol p && p.isInstantiable();
    }
}
