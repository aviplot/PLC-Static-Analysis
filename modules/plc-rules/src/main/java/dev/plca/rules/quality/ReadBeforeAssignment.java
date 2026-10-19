package dev.plca.rules.quality;

import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.Statement;
import dev.plca.ast.VarBlock;
import dev.plca.core.Dialect;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.semantic.Access;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Statements;
import dev.plca.semantic.Type;
import dev.plca.semantic.VariableSymbol;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PLCA-Q010 (PLCopen CP3): a temporary variable read before it is assigned on some path through the body. Temporaries
 * are the variables whose value does not survive the call: locals of functions, methods and property accessors,
 * VAR_TEMP of any POU, and the function result. Only variables without an initial value and of elementary, string or
 * pointer type are tracked; structures and arrays are filled member by member and are left out.
 *
 * <p>Definite assignment over the structured body: IF and CASE merge their branches, loop bodies may not run (REPEAT
 * runs once), RETURN/EXIT/CONTINUE end a path. Passing a variable as VAR_IN_OUT, taking its address or writing part of
 * it counts as an assignment. Bodies with JMP or __TRY are skipped. One finding per variable, at its first unsafe read.
 */
public final class ReadBeforeAssignment implements PouRule {

    @Override
    public String id() {
        return "PLCA-Q010";
    }

    /** Assigned-variable sets; {@code null} stands for "no path reaches here". */
    private static final class Flow {
        final SemanticModel model;
        final Map<VariableSymbol, Integer> index;
        final Set<VariableSymbol> reported = new HashSet<>();
        final List<Access> unsafe = new ArrayList<>();

        Flow(SemanticModel model, Map<VariableSymbol, Integer> index) {
            this.model = model;
            this.index = index;
        }
    }

    @Override
    public void check(PouContext ctx) {
        PouSymbol pou = ctx.pou();
        if (pou.dialect() == Dialect.ROCKWELL) {
            return; // routines have no temporaries; AOI local tags keep their value
        }
        Map<VariableSymbol, Integer> index = new IdentityHashMap<>();
        boolean resetEachCall = switch (pou.kind()) {
            case FUNCTION, METHOD, PROPERTY_GET, PROPERTY_SET, TRANSITION -> true;
            default -> false;
        };
        for (VariableSymbol v : pou.variables().values()) {
            boolean temporary = v.section() == VarBlock.Section.TEMP
                    || resetEachCall && v.section() == VarBlock.Section.VAR && !v.isResult()
                    || v.isResult() && pou.kind() == PouKind.FUNCTION;
            if (temporary
                    && (v.declaration() != null || v.isResult())
                    && v.initializer() == null
                    && !v.isConstant()
                    && v.address() == null
                    && tracked(v.type())) {
                index.put(v, index.size());
            }
        }
        if (index.isEmpty() || hasJumpsOrTry(ctx.body())) {
            return;
        }
        Flow f = new Flow(ctx.model(), index);
        list(f, ctx.body(), new BitSet());
        for (Access a : f.unsafe) {
            VariableSymbol v = a.variable();
            ctx.report(
                    a.span(),
                    (v.isResult() ? "Function result '" : "'") + v.name() + "' is read before it is assigned on some"
                            + " path; it starts from its default value on every call, not from the last value");
        }
    }

    /** Runs {@code body} from {@code in}; returns the state after it, or null when no path falls through. */
    private BitSet list(Flow f, List<Statement> body, BitSet in) {
        BitSet state = in;
        for (Statement s : body) {
            if (state == null) {
                return null; // unreachable rest: Q009's business
            }
            state = statement(f, s, state);
        }
        return state;
    }

    private BitSet statement(Flow f, Statement s, BitSet in) {
        BitSet state = (BitSet) in.clone();
        switch (s) {
            case Statement.If i -> {
                own(f, s, state);
                BitSet out = null;
                for (Statement.Branch b : i.branches()) {
                    out = meet(out, list(f, b.body(), state));
                }
                out = meet(out, i.elseBody() == null ? state : list(f, i.elseBody(), state));
                return out;
            }
            case Statement.Case c -> {
                own(f, s, state);
                BitSet out = null;
                for (Statement.CaseBranch b : c.branches()) {
                    out = meet(out, list(f, b.body(), state));
                }
                out = meet(out, c.elseBody() == null ? state : list(f, c.elseBody(), state));
                return out;
            }
            case Statement.For loop -> {
                own(f, s, state); // bounds read, loop variable written
                list(f, loop.body(), state);
                return state;
            }
            case Statement.While w -> {
                own(f, s, state);
                list(f, w.body(), state);
                return state;
            }
            case Statement.Repeat r -> {
                BitSet after = list(f, r.body(), state);
                if (after == null) {
                    return state; // left through EXIT: the condition was not evaluated
                }
                own(f, s, after);
                return after;
            }
            case Statement.Return r -> {
                own(f, s, state);
                return null;
            }
            case Statement.Exit e -> {
                return null;
            }
            case Statement.Continue c -> {
                return null;
            }
            case Statement.Region r -> {
                return list(f, r.body(), state);
            }
            case Statement.Rung r -> {
                return list(f, r.body(), state);
            }
            case Statement.Network n -> {
                return list(f, n.body(), state);
            }
            case Statement.Chart c -> {
                // steps run in an order the body does not show: everything assigned in the chart counts
                Statements.forEachDeep(c.body(), x -> assignAll(f, x, state));
                return state;
            }
            default -> {
                own(f, s, state);
                return state;
            }
        }
    }

    /** The statement's own accesses: reads first, then writes (inputs are evaluated before outputs are stored). */
    private static void own(Flow f, Statement s, BitSet state) {
        List<Access> accesses = f.model.effects(s).accesses();
        for (Access a : accesses) {
            Integer i = f.index.get(a.variable());
            if (i == null || a.kind() != Access.Kind.READ) {
                continue;
            }
            if (!state.get(i) && f.reported.add(a.variable())) {
                f.unsafe.add(a);
            }
        }
        for (Access a : accesses) {
            Integer i = f.index.get(a.variable());
            if (i != null && (a.isWrite() || a.kind() == Access.Kind.ADDRESS || a.kind() == Access.Kind.UNKNOWN)) {
                state.set(i);
            }
        }
    }

    private static void assignAll(Flow f, Statement s, BitSet state) {
        for (Access a : f.model.effects(s).accesses()) {
            Integer i = f.index.get(a.variable());
            if (i != null && a.kind() != Access.Kind.READ) {
                state.set(i);
            }
        }
    }

    /** Intersection of two path states; null (no path) is the neutral element. */
    private static BitSet meet(BitSet a, BitSet b) {
        if (a == null) {
            return b == null ? null : (BitSet) b.clone();
        }
        if (b != null) {
            a.and(b);
        }
        return a;
    }

    private static boolean hasJumpsOrTry(List<Statement> body) {
        boolean[] found = {false};
        Statements.forEachDeep(
                body,
                s -> found[0] |=
                        s instanceof Statement.Jump || s instanceof Statement.Label || s instanceof Statement.Try);
        return found[0];
    }

    private static boolean tracked(Type t) {
        Type r = t.resolved();
        if (r instanceof Type.StringType || r instanceof Type.Pointer || r instanceof Type.Subrange) {
            return true;
        }
        return Type.elementary(r) != null;
    }
}
