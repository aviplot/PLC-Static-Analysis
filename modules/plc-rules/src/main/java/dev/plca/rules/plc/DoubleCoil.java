package dev.plca.rules.plc;

import dev.plca.ast.Declaration;
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
import dev.plca.semantic.Statements;
import dev.plca.semantic.VariableSymbol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PLCA-P001 (PLCopen CP12): double coil — an output-like variable driven unconditionally from more than one place, so
 * that only the last write of the cycle takes effect.
 *
 * <p>Deliberately narrow, the way a controls engineer reads "double coil":
 *
 * <ul>
 *   <li>Only <em>unconditional</em> writes count: {@code :=} or {@code =>} in a top-level statement of a body. Writes
 *       inside IF/CASE/loops, {@code S=}/{@code R=} and {@code REF=} never count, which also keeps the "default, then
 *       override in an IF" idiom silent. Read-modify-write statements ({@code n := n + 1}) update a value rather than
 *       drive it and do not count either.
 *   <li>Only output-like variables: {@code AT %Q…} variables, globals, and the POU's own VAR_OUTPUTs (plus FB/PROGRAM
 *       locals with {@code includeLocals}).
 *   <li>Within one body, a second write is not reported when the variable is read in between (directly, or by a POU
 *       called in between): that is sequential use of a working variable, not a coil.
 *   <li>Within one body, an early exit (RETURN, JMP) between two writes also keeps the first write meaningful.
 *   <li>Function and method results are return values, not coils.
 *   <li>Across POUs, only globals and {@code %Q} variables are compared, and only between program and FB bodies:
 *       methods and actions run on demand, often as alternatives.
 * </ul>
 *
 * <p>Ladder (Rockwell): an {@code OTE} is a coil by definition, so its lowered assignment counts whatever the rung
 * reads (a seal-in reads its own coil) and whatever happens between two OTEs; any tag counts, and routines are compared
 * like program bodies — the classic "duplicate destructive bit" check. {@code OTL}/{@code OTU} latches never count.
 * REGION and rung containers are opened: their statements run in sequence like top-level ones.
 */
public final class DoubleCoil implements ProjectRule {

    @Override
    public String id() {
        return "PLCA-P001";
    }

    private record Key(VariableSymbol variable, String path) {}

    private record Write(PouSymbol pou, int index, Access access, boolean coil) {}

    @Override
    public void check(ProjectContext ctx) {
        SemanticModel m = ctx.model();
        boolean includeLocals = ctx.boolParam("includeLocals");
        Readers readers = new Readers(m);

        Map<Key, List<Write>> survivors = new LinkedHashMap<>();
        for (PouSymbol pou : m.pous()) {
            List<Statement> declared =
                    pou.declaration() == null ? null : pou.declaration().body();
            if (declared == null) {
                continue;
            }
            Set<Statement> coils = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Statement> body = sequence(declared, coils);
            Map<Key, Write> previous = new HashMap<>();
            for (int i = 0; i < body.size(); i++) {
                boolean coil = coils.contains(body.get(i));
                for (Access a : unconditionalWrites(m, body.get(i), coil)) {
                    if (!coil && !eligible(a.variable(), pou, includeLocals)) {
                        continue;
                    }
                    Key key = new Key(a.variable(), a.path());
                    Write w = new Write(pou, i, a, coil);
                    Write prev = previous.put(key, w);
                    boolean bothCoils = coil && prev != null && prev.coil();
                    if (prev != null && (bothCoils || !readBetween(m, readers, body, prev, w))) {
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
            if (!crossPou(key.variable()) && writes.stream().noneMatch(Write::coil)) {
                return;
            }
            List<Write> cyclic = writes.stream().filter(w -> cyclic(w.pou())).toList();
            if (cyclic.size() < 2) {
                return;
            }
            Write first = cyclic.getFirst();
            Set<String> seen = new HashSet<>();
            seen.add(first.pou().qualifiedName().toLowerCase(Locale.ROOT));
            for (Write w : cyclic) {
                // same name twice: duplicate copies of one POU (backup files), not two drivers
                if (seen.add(w.pou().qualifiedName().toLowerCase(Locale.ROOT))) {
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

    /**
     * Bodies that run every cycle they are called: programs, Siemens OBs and FB bodies. Methods, actions, property
     * accessors and SFC step actions run on demand, often as alternatives ({@code On}/{@code Off}), so they are only
     * compared within their own body. Siemens startup OBs run once before the cycle starts: their writes initialise
     * (like a first-scan block) and are not compared with the cyclic ones.
     */
    private static boolean cyclic(PouSymbol p) {
        return p.kind() == Declaration.PouKind.PROGRAM
                || p.kind() == Declaration.PouKind.ROUTINE
                || p.kind() == Declaration.PouKind.ORGANIZATION_BLOCK && !startupOb(p)
                || p.kind() == Declaration.PouKind.FUNCTION_BLOCK;
    }

    /** TIA/STEP 7 default names of startup OBs; the OB's event class itself is not part of SCL exports. */
    private static final Pattern STARTUP =
            Pattern.compile("(?i)start[ _-]?up|restart|anlauf|neustart|\\bOB ?10[0-2]\\b");

    private static boolean startupOb(PouSymbol p) {
        Declaration.BlockInfo info =
                p.declaration() == null ? null : p.declaration().info();
        return STARTUP.matcher(p.name()).find()
                || info != null
                        && info.title() != null
                        && STARTUP.matcher(info.title()).find();
    }

    /**
     * The statements of a body in execution order, with REGION and rung containers opened; {@code coils} receives the
     * assignments lowered from ladder {@code OTE} instructions (same span as the instruction).
     */
    static List<Statement> sequence(List<Statement> body, Set<Statement> coils) {
        List<Statement> out = new ArrayList<>(body.size());
        for (Statement s : body) {
            switch (s) {
                case Statement.Region r -> out.addAll(sequence(r.body(), coils));
                case Statement.Rung r -> {
                    if (r.ladder() != null) {
                        Set<dev.plca.core.source.Span> ote = new HashSet<>();
                        oteSpans(r.ladder().elements(), ote);
                        for (Statement x : r.body()) {
                            if (x instanceof Statement.Assignment a && ote.contains(a.span())) {
                                coils.add(x);
                            }
                        }
                    }
                    out.addAll(r.body());
                }
                default -> out.add(s);
            }
        }
        return out;
    }

    private static void oteSpans(List<dev.plca.ast.Ladder.Element> elements, Set<dev.plca.core.source.Span> out) {
        for (dev.plca.ast.Ladder.Element e : elements) {
            switch (e) {
                case dev.plca.ast.Ladder.Instruction i -> {
                    if (i.mnemonic().is("OTE")) {
                        out.add(i.span());
                    }
                }
                case dev.plca.ast.Ladder.Branch b -> b.legs().forEach(l -> oteSpans(l.elements(), out));
            }
        }
    }

    /**
     * {@code :=} targets and {@code =>} outputs of one statement; nothing for compound statements. A ladder coil is a
     * coil even when its rung reads it (seal-in), so the read-modify-write exemption does not apply to it.
     */
    private static List<Access> unconditionalWrites(SemanticModel m, Statement s, boolean coil) {
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
        List<Access> accesses = m.effects(s).accesses();
        List<Access> r = new ArrayList<>();
        for (Access a : accesses) {
            if (a.kind() == Access.Kind.WRITE
                    && targets.contains(a.expression())
                    && !a.path().contains("*")
                    && !a.path().contains("^")
                    && (coil || !readModifyWrite(a, accesses))) {
                r.add(a);
            }
        }
        return r;
    }

    /** {@code x := x + 1}, {@code x := x OR b}: the statement updates the variable rather than driving it. */
    private static boolean readModifyWrite(Access write, List<Access> accesses) {
        for (Access a : accesses) {
            if (a.variable() == write.variable() && a.kind() != Access.Kind.WRITE && overlaps(a.path(), write.path())) {
                return true;
            }
        }
        return false;
    }

    private static boolean eligible(VariableSymbol v, PouSymbol pou, boolean includeLocals) {
        if (v.isResult()) {
            return false; // a function/method result is a return value, not a coil
        }
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
        for (int i = prev.index() + 1; i < w.index(); i++) {
            if (leavesEarly(body.get(i))) {
                return true; // the first write is what the caller sees on an early RETURN
            }
        }
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

    /** Contains a RETURN, JMP or jump label: control may not reach the next statement in order. */
    private static boolean leavesEarly(Statement s) {
        boolean[] found = {false};
        Statements.forEachDeep(s, x -> {
            if (x instanceof Statement.Return || x instanceof Statement.Jump || x instanceof Statement.Label) {
                found[0] = true;
            }
        });
        return found[0];
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
