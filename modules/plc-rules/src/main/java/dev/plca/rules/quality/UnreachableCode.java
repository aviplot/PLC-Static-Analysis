package dev.plca.rules.quality;

import dev.plca.ast.Expression;
import dev.plca.ast.Ladder;
import dev.plca.ast.Statement;
import dev.plca.core.diag.Diagnostic;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.rules.Spans;
import dev.plca.rules.Walk;
import dev.plca.semantic.Constant;
import dev.plca.semantic.Statements;
import java.util.List;

/**
 * PLCA-Q009 (PLCopen CP2): code that can never run — statements after RETURN, EXIT, CONTINUE or JMP in the same
 * statement list (up to the next label), branches of IF/ELSIF/WHILE whose condition is a constant FALSE, branches after
 * a constant TRUE condition, and Rockwell rungs disabled with AFI. Statements generated from graphical code (rungs,
 * networks, charts) are not checked for constant conditions: their conditions are synthetic.
 */
public final class UnreachableCode implements PouRule {

    @Override
    public String id() {
        return "PLCA-Q009";
    }

    @Override
    public void check(PouContext ctx) {
        boolean namedConstants = ctx.boolParam("includeNamedConstants");
        list(ctx, ctx.body(), namedConstants);
    }

    private void list(PouContext ctx, List<Statement> body, boolean namedConstants) {
        Statement exit = null;
        for (Statement s : body) {
            if (s instanceof Statement.Label) {
                exit = null;
                continue;
            }
            if (exit != null && !(s instanceof Statement.Empty)) {
                ctx.report(
                        Spans.header(s),
                        "Unreachable code: it follows " + describe(exit) + " in the same statement list",
                        new Diagnostic.Related(Spans.header(exit), describe(exit)));
                exit = null; // one finding per unreachable run
                // keep scanning: a later label makes code reachable again, a later exit starts a new run
                continue;
            }
            if (exit == null && terminates(s)) {
                exit = s;
            }
            nested(ctx, s, namedConstants);
        }
    }

    private void nested(PouContext ctx, Statement s, boolean namedConstants) {
        switch (s) {
            case Statement.If i -> {
                boolean alwaysTaken = false;
                for (Statement.Branch b : i.branches()) {
                    Boolean c = alwaysTaken ? null : constant(ctx, b.condition(), namedConstants);
                    if (alwaysTaken) {
                        // reported once, at the constant TRUE condition
                    } else if (Boolean.FALSE.equals(c) && !b.body().isEmpty()) {
                        ctx.report(b.condition().span(), "Condition is always FALSE: the branch below it never runs");
                    } else if (Boolean.TRUE.equals(c)
                            && (i.branches().indexOf(b) < i.branches().size() - 1 || i.elseBody() != null)) {
                        ctx.report(
                                b.condition().span(),
                                "Condition is always TRUE: the ELSIF/ELSE branches after it never run");
                        alwaysTaken = true;
                    }
                    list(ctx, b.body(), namedConstants);
                }
                if (i.elseBody() != null) {
                    list(ctx, i.elseBody(), namedConstants);
                }
            }
            case Statement.While w -> {
                if (Boolean.FALSE.equals(constant(ctx, w.condition(), namedConstants))
                        && !w.body().isEmpty()) {
                    ctx.report(w.condition().span(), "WHILE condition is always FALSE: the loop body never runs");
                }
                list(ctx, w.body(), namedConstants);
            }
            case Statement.Rung r -> {
                if (r.ladder() != null && disabledByAfi(r.ladder())) {
                    ctx.report(r.span(), "Rung " + r.number() + " is disabled by AFI (always false): it never runs");
                }
            }
            case Statement.Network n -> {}
            case Statement.Chart c -> {}
            default -> Statements.forEachBody(s, b -> list(ctx, b, namedConstants));
        }
    }

    /** An AFI in the rung's main series (not inside a branch) makes every output of the rung dead. */
    private static boolean disabledByAfi(Ladder l) {
        for (Ladder.Element e : l.elements()) {
            if (e instanceof Ladder.Instruction i && i.mnemonic().name().equalsIgnoreCase("AFI")) {
                return true;
            }
        }
        return false;
    }

    /** Control never reaches the next statement of the same list. */
    static boolean terminates(Statement s) {
        return switch (s) {
            // RETURN(expr) is TwinCAT's conditional or value-returning form: code after it may run
            case Statement.Return r -> r.value() == null;
            case Statement.Exit e -> true;
            case Statement.Continue c -> true;
            case Statement.Jump j -> true;
            case Statement.If i ->
                i.elseBody() != null
                        && i.branches().stream().allMatch(b -> endsWithExit(b.body()))
                        && endsWithExit(i.elseBody());
            case Statement.Case c ->
                c.elseBody() != null
                        && c.branches().stream().allMatch(b -> endsWithExit(b.body()))
                        && endsWithExit(c.elseBody());
            case Statement.Region r -> endsWithExit(r.body());
            default -> false;
        };
    }

    private static boolean endsWithExit(List<Statement> body) {
        for (Statement s : body) {
            if (s instanceof Statement.Label) {
                return false; // a jump may enter here
            }
            if (terminates(s)) {
                return true;
            }
        }
        return false;
    }

    /** The constant value of a condition, or null; literal-only unless named constants are included. */
    private static Boolean constant(PouContext ctx, Expression e, boolean namedConstants) {
        if (!namedConstants && hasName(e)) {
            return null;
        }
        return ctx.model().constant(e).orElse(null) instanceof Constant.Bool b ? b.value() : null;
    }

    private static boolean hasName(Expression e) {
        boolean[] found = {false};
        Walk.deep(e, x -> found[0] |= x instanceof Expression.NameRef || x instanceof Expression.MemberAccess);
        return found[0];
    }

    private static String describe(Statement s) {
        return switch (s) {
            case Statement.Return r -> "RETURN";
            case Statement.Exit e -> "EXIT";
            case Statement.Continue c -> "CONTINUE";
            case Statement.Jump j -> "a jump to " + j.label().name();
            case Statement.If i -> "an IF whose every branch ends with RETURN, EXIT, CONTINUE or JMP";
            case Statement.Case c -> "a CASE whose every branch ends with RETURN, EXIT, CONTINUE or JMP";
            default -> "a REGION that ends with RETURN, EXIT, CONTINUE or JMP";
        };
    }
}
