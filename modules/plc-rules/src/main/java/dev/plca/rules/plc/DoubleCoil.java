package dev.plca.rules.plc;

import dev.plca.ast.Expression;
import dev.plca.ast.Statement;
import dev.plca.ast.VarBlock;
import dev.plca.core.diag.Diagnostic;
import dev.plca.rules.ProjectContext;
import dev.plca.rules.ProjectRule;
import dev.plca.rules.Walk;
import dev.plca.semantic.Access;
import dev.plca.semantic.CallSite;
import dev.plca.semantic.Effects;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.VariableSymbol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PLCA-P001 (PLCopen CP12): double coil — an output-like variable driven unconditionally from more than one place, so
 * that only the last write of the cycle takes effect.
 *
 * <p>Deliberately narrow, the way a controls engineer reads "double coil":
 *
 * <ul>
 *   <li>Only <em>unconditional</em> writes count: {@code :=} or {@code =>} in a top-level statement of a body. Writes
 *       inside IF/CASE/loops, {@code S=}/{@code R=} and {@code REF=} never count, which also keeps the "default, then
 *       override in an IF" idiom silent.
 *   <li>Only output-like variables: {@code AT %Q…} variables, globals, and the POU's own VAR_OUTPUTs (plus FB/PROGRAM
 *       locals with {@code includeLocals}).
 *   <li>Within one body, a second write is not reported when the variable is read in between (directly, or by a POU
 *       called in between): that is sequential use of a working variable, not a coil.
 *   <li>Across POUs, only globals and {@code %Q} variables are compared.
 * </ul>
 */
public final class DoubleCoil implements ProjectRule {

    @Override
    public String id() {
        return "PLCA-P001";
    }

    private record Key(VariableSymbol variable, String path) {}

    private record Write(PouSymbol pou, int index, Access access) {}

    @Override
    public void check(ProjectContext ctx) {
        SemanticModel m = ctx.model();
        boolean includeLocals = ctx.boolParam("includeLocals");
        Readers readers = new Readers(m);

        Map<Key, List<Write>> survivors = new LinkedHashMap<>();
        for (PouSymbol pou : m.pous()) {
            List<Statement> body =
                    pou.declaration() == null ? null : pou.declaration().body();
            if (body == null) {
                continue;
            }
            Map<Key, Write> previous = new HashMap<>();
            for (int i = 0; i < body.size(); i++) {
                for (Access a : unconditionalWrites(m, body.get(i))) {
                    if (!eligible(a.variable(), pou, includeLocals)) {
                        continue;
                    }
                    Key key = new Key(a.variable(), a.path());
                    Write w = new Write(pou, i, a);
                    Write prev = previous.put(key, w);
                    if (prev != null && !readBetween(m, readers, body, prev, w)) {
                        ctx.report(
                                pou,
                                a.span(),
                                "'" + a.target() + "' is written unconditionally more than once in "
                                        + pou.qualifiedName() + "; only the last write takes effect (double coil)",
                                List.of(new Diagnostic.Related(prev.access().span(), "previous write")));
                    } else {
                        survivors.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
                    }
                }
            }
        }

        // the same global or physical output driven from several POUs
        survivors.forEach((key, writes) -> {
            if (!crossPou(key.variable())) {
                return;
            }
            Write first = writes.getFirst();
            Set<PouSymbol> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            seen.add(first.pou());
            for (Write w : writes) {
                if (seen.add(w.pou())) {
                    ctx.report(
                            w.pou(),
                            w.access().span(),
                            "'" + w.access().target() + "' is written unconditionally in both "
                                    + first.pou().qualifiedName() + " and "
                                    + w.pou().qualifiedName()
                                    + "; the POU that runs last wins (double coil)",
                            List.of(new Diagnostic.Related(
                                    first.access().span(),
                                    "also written in " + first.pou().qualifiedName())));
                }
            }
        });
    }

    /** {@code :=} targets and {@code =>} outputs of one statement; nothing for compound statements. */
    private static List<Access> unconditionalWrites(SemanticModel m, Statement s) {
        if (!(s instanceof Statement.Assignment a && a.op() == Statement.AssignOp.ASSIGN)
                && !(s instanceof Statement.CallStatement)) {
            return List.of();
        }
        Set<Expression> targets = Collections.newSetFromMap(new IdentityHashMap<>());
        if (s instanceof Statement.Assignment a) {
            targets.add(a.target());
        }
        Walk.ownExpressions(
                s,
                top -> Walk.deep(top, e -> {
                    if (e instanceof Expression.Call c) {
                        for (Expression.Argument arg : c.arguments()) {
                            if (arg.kind() == Expression.ArgumentKind.OUTPUT && arg.value() != null) {
                                targets.add(arg.value());
                            }
                        }
                    }
                }));
        List<Access> r = new ArrayList<>();
        for (Access a : m.effects(s).accesses()) {
            if (a.kind() == Access.Kind.WRITE
                    && targets.contains(a.expression())
                    && !a.path().contains("*")
                    && !a.path().contains("^")) {
                r.add(a);
            }
        }
        return r;
    }

    private static boolean eligible(VariableSymbol v, PouSymbol pou, boolean includeLocals) {
        if (crossPou(v) || v.section() == VarBlock.Section.EXTERNAL) {
            return true;
        }
        if (!(v.owner() instanceof PouSymbol owner) || owner != pou && owner != pou.instanceOwner()) {
            return false;
        }
        return v.section() == VarBlock.Section.OUTPUT
                || includeLocals
                        && owner.isInstantiable()
                        && (v.section() == VarBlock.Section.VAR || v.section() == VarBlock.Section.STAT);
    }

    private static boolean crossPou(VariableSymbol v) {
        return v.isGlobal()
                || v.address() != null && v.address().toUpperCase(Locale.ROOT).startsWith("%Q");
    }

    /** Is {@code w}'s variable read after {@code prev} and up to (including) {@code w}'s statement? */
    private static boolean readBetween(SemanticModel m, Readers readers, List<Statement> body, Write prev, Write w) {
        VariableSymbol v = w.access().variable();
        for (int i = prev.index() + 1; i <= w.index(); i++) {
            Effects e = m.deepEffects(body.get(i));
            for (Access a : e.accesses()) {
                if (a.variable() == v
                        && a.kind() != Access.Kind.WRITE
                        && overlaps(a.path(), w.access().path())) {
                    return true;
                }
            }
            for (CallSite c : e.calls()) {
                if (readers.reads(c.callee(), v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code .x} overlaps {@code ""}, {@code .x.y} and {@code [*]...}-style paths conservatively. */
    private static boolean overlaps(String a, String b) {
        return a.isEmpty() || b.isEmpty() || a.startsWith(b) || b.startsWith(a) || a.contains("*") || b.contains("*");
    }

    /** Which POUs read a variable, directly or through their callees. */
    private static final class Readers {
        private final SemanticModel model;
        private final Map<VariableSymbol, Set<PouSymbol>> direct = new IdentityHashMap<>();
        private final Map<PouSymbol, Set<PouSymbol>> reach = new IdentityHashMap<>();

        Readers(SemanticModel model) {
            this.model = model;
            for (PouSymbol p : model.pous()) {
                if (p.declaration() == null || p.declaration().body() == null) {
                    continue;
                }
                for (Statement s : p.declaration().body()) {
                    for (Access a : model.deepEffects(s).accesses()) {
                        if (a.kind() != Access.Kind.WRITE) {
                            direct.computeIfAbsent(
                                            a.variable(), k -> Collections.newSetFromMap(new IdentityHashMap<>()))
                                    .add(p);
                        }
                    }
                }
            }
        }

        boolean reads(PouSymbol callee, VariableSymbol v) {
            Set<PouSymbol> r = direct.get(v);
            if (r == null) {
                return false;
            }
            Set<PouSymbol> reachable =
                    reach.computeIfAbsent(callee, c -> model.callGraph().reachableFrom(c));
            for (PouSymbol p : reachable) {
                if (r.contains(p)) {
                    return true;
                }
            }
            return false;
        }
    }
}
