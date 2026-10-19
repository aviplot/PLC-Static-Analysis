package dev.plca.rules.plc;

import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.Expression;
import dev.plca.ast.Statement;
import dev.plca.core.Dialect;
import dev.plca.core.diag.Diagnostic;
import dev.plca.rules.ProjectContext;
import dev.plca.rules.ProjectRule;
import dev.plca.rules.Walk;
import dev.plca.semantic.InstructionSymbol;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Statements;
import dev.plca.semantic.Type;
import dev.plca.semantic.VariableSymbol;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PLCA-P003 (PLCopen CP20): a function block instance — timers and counters included — called more than once per cycle.
 * The second call sees the state the first one left, so edge detection, timing and counting go wrong; with a timer, the
 * two calls fight over IN and the timer restarts or never elapses.
 *
 * <ul>
 *   <li>IEC dialects: calls {@code fb(…)} of the same instance in one body that can both run in one cycle — calls in
 *       different branches of one IF or CASE are alternatives and do not count. Calls inside a loop, through a variable
 *       index ({@code afb[i]()}) or in SFC actions are not compared.
 *   <li>Siemens {@code "T_DB".TON(…)} calls on an IEC_TIMER count as calls of that timer.
 *   <li>Rockwell: the same TIMER tag in two TON/TOF/RTO instructions, or the same COUNTER in two CTU (or two CTD)
 *       instructions — CTU with CTD on one counter is the up/down counter idiom. Ladder instructions run every scan
 *       whatever the rung condition, so rungs are never alternatives.
 *   <li>Across POUs: a global instance (or Rockwell controller/program tag) called from two programs, function blocks
 *       or routines.
 * </ul>
 */
public final class MultipleInstanceCalls implements ProjectRule {

    @Override
    public String id() {
        return "PLCA-P003";
    }

    private static final Set<String> TIMERS = Set.of("TON", "TOF", "RTO", "TONR", "TOFR", "RTOR");
    private static final Set<String> COUNTERS = Set.of("CTU", "CTD", "CTUD");
    private static final Set<String> SIEMENS_TIMER_CALLS = Set.of("TON", "TOF", "TP", "TONR", "CTU", "CTD", "CTUD");

    /** Where a call sits: the IF/CASE statements around it and the branch taken in each. */
    private record Branch(Statement statement, int index) {}

    private record Call(PouSymbol pou, String key, String display, String kind, Expression call, List<Branch> path) {}

    @Override
    public void check(ProjectContext ctx) {
        SemanticModel m = ctx.model();
        Map<String, List<Call>> global = new LinkedHashMap<>();
        for (PouSymbol pou : m.pous()) {
            if (pou.isBuiltin()
                    || pou.declaration() == null
                    || pou.declaration().body() == null) {
                continue;
            }
            List<Call> calls = new ArrayList<>();
            collect(ctx, pou, pou.declaration().body(), new ArrayList<>(), calls);
            Map<String, List<Call>> byKey = new LinkedHashMap<>();
            for (Call c : calls) {
                byKey.computeIfAbsent(c.key(), k -> new ArrayList<>()).add(c);
            }
            byKey.forEach((key, list) -> {
                for (int i = 1; i < list.size(); i++) {
                    Call c = list.get(i);
                    for (int j = 0; j < i; j++) {
                        Call prev = list.get(j);
                        if (!exclusive(prev, c) && compatible(prev, c)) {
                            ctx.report(
                                    pou,
                                    c.call().span(),
                                    what(c) + " more than once in " + pou.qualifiedName()
                                            + "; each call advances its state (CP20), call it once per cycle",
                                    List.of(new Diagnostic.Related(prev.call().span(), "earlier call")));
                            break;
                        }
                    }
                }
                if (cyclic(pou) && key.startsWith("g:")) {
                    global.computeIfAbsent(key, k -> new ArrayList<>()).add(list.getFirst());
                }
            });
        }
        global.forEach((key, list) -> {
            for (int i = 1; i < list.size(); i++) {
                Call c = list.get(i);
                Call first = list.getFirst();
                if (c.pou() != first.pou()
                        && !c.pou().qualifiedName().equalsIgnoreCase(first.pou().qualifiedName())
                        && compatible(first, c)) {
                    ctx.report(
                            c.pou(),
                            c.call().span(),
                            what(c) + " in both "
                                    + first.pou().qualifiedName() + " and "
                                    + c.pou().qualifiedName()
                                    + "; each call advances its state (CP20), call it once per"
                                    + " cycle",
                            List.of(new Diagnostic.Related(
                                    first.call().span(),
                                    "call in " + first.pou().qualifiedName())));
                }
            }
        });
    }

    private void collect(ProjectContext ctx, PouSymbol pou, List<Statement> body, List<Branch> path, List<Call> out) {
        for (Statement s : body) {
            switch (s) {
                case Statement.If i -> {
                    for (int k = 0; k < i.branches().size(); k++) {
                        own(ctx, pou, i.branches().get(k).condition(), path, out);
                        collect(ctx, pou, i.branches().get(k).body(), with(path, s, k), out);
                    }
                    if (i.elseBody() != null) {
                        collect(
                                ctx,
                                pou,
                                i.elseBody(),
                                with(path, s, i.branches().size()),
                                out);
                    }
                }
                case Statement.Case c -> {
                    own(ctx, pou, c.selector(), path, out);
                    for (int k = 0; k < c.branches().size(); k++) {
                        collect(ctx, pou, c.branches().get(k).body(), with(path, s, k), out);
                    }
                    if (c.elseBody() != null) {
                        collect(
                                ctx,
                                pou,
                                c.elseBody(),
                                with(path, s, c.branches().size()),
                                out);
                    }
                }
                case Statement.For f -> {}
                case Statement.While w -> {}
                case Statement.Repeat r -> {}
                case Statement.Chart c -> {}
                default -> {
                    Walk.ownExpressions(s, e -> own(ctx, pou, e, path, out));
                    Statements.forEachBody(s, b -> collect(ctx, pou, b, path, out));
                }
            }
        }
    }

    private static List<Branch> with(List<Branch> path, Statement s, int index) {
        List<Branch> p = new ArrayList<>(path);
        p.add(new Branch(s, index));
        return p;
    }

    private void own(ProjectContext ctx, PouSymbol pou, Expression root, List<Branch> path, List<Call> out) {
        SemanticModel m = ctx.model();
        Walk.deep(root, e -> {
            if (!(e instanceof Expression.Call call)) {
                return;
            }
            Expression callee = call.callee();
            Expression instance = null;
            String kind = "fb";
            if (m.symbol(callee).orElse(null) instanceof InstructionSymbol instr) {
                String name = instr.name().toUpperCase(Locale.ROOT);
                if ((TIMERS.contains(name) || COUNTERS.contains(name))
                        && !call.arguments().isEmpty()) {
                    instance = call.arguments().getFirst().value();
                    kind = TIMERS.contains(name) ? "timer" : name;
                }
            } else if (m.type(callee).resolved() instanceof Type.Instance) {
                instance = callee;
            } else if (callee instanceof Expression.MemberAccess ma
                    && pou.dialect() == Dialect.SIEMENS
                    && SIEMENS_TIMER_CALLS.contains(ma.member().name().toUpperCase(Locale.ROOT))
                    && m.symbol(callee).orElse(null) instanceof PouSymbol method
                    && method.isBuiltin()) {
                instance = ma.target();
            }
            if (instance == null || !constantPath(m, instance)) {
                return;
            }
            VariableSymbol root0 = rootVariable(m, instance);
            if (root0 == null) {
                return;
            }
            String text =
                    ctx.text(instance).replaceAll("\\s+", "").replace("#", "").toLowerCase(Locale.ROOT);
            int dot = firstSelector(text);
            String rest = dot < 0 ? "" : text.substring(dot);
            boolean shared = root0.isGlobal() || pou.dialect() == Dialect.ROCKWELL && !(root0.owner() == pou);
            String key = (shared ? "g:" : "l:") + System.identityHashCode(root0) + rest;
            out.add(new Call(pou, key, ctx.text(instance).strip(), kind, call, List.copyOf(path)));
        });
    }

    private static String what(Call c) {
        return switch (c.kind()) {
            case "timer" -> "Timer '" + c.display() + "' is used by a timer instruction";
            case "fb" -> "'" + c.display() + "' is called";
            default -> "Counter '" + c.display() + "' is used by " + c.kind();
        };
    }

    private static int firstSelector(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '.' || c == '[') && i > 0) {
                return i;
            }
        }
        return -1;
    }

    /** No variable array index on the way to the instance. */
    private static boolean constantPath(SemanticModel m, Expression e) {
        boolean[] ok = {true};
        Walk.deep(e, x -> {
            if (x instanceof Expression.Index i) {
                for (Expression ix : i.indices()) {
                    if (m.constant(ix).isEmpty()) {
                        ok[0] = false;
                    }
                }
            }
            if (x instanceof Expression.Deref || x instanceof Expression.ThisRef || x instanceof Expression.Call) {
                ok[0] = false;
            }
        });
        return ok[0];
    }

    private static VariableSymbol rootVariable(SemanticModel m, Expression e) {
        Expression x = e;
        while (true) {
            switch (x) {
                case Expression.MemberAccess ma -> {
                    if (m.symbol(ma.target()).orElse(null) instanceof VariableSymbol
                            || ma.target() instanceof Expression.NameRef) {
                        x = ma.target();
                    } else {
                        return m.symbol(x).orElse(null) instanceof VariableSymbol v ? v : null;
                    }
                }
                case Expression.Index i -> x = i.target();
                case Expression.NameRef n -> {
                    return m.symbol(n).orElse(null) instanceof VariableSymbol v ? v : null;
                }
                default -> {
                    return null;
                }
            }
        }
    }

    /** CTU and CTD on one counter is the up/down counter idiom, not a second call. */
    private static boolean compatible(Call a, Call b) {
        if (a.kind().equals("CTU") && b.kind().equals("CTD")
                || a.kind().equals("CTD") && b.kind().equals("CTU")) {
            return false;
        }
        return true;
    }

    /** Two calls in different branches of the same IF or CASE never both run. */
    private static boolean exclusive(Call a, Call b) {
        int n = Math.min(a.path().size(), b.path().size());
        for (int i = 0; i < n; i++) {
            Branch x = a.path().get(i);
            Branch y = b.path().get(i);
            if (x.statement() != y.statement()) {
                return false;
            }
            if (x.index() != y.index()) {
                return true;
            }
        }
        return false;
    }

    private static boolean cyclic(PouSymbol p) {
        return p.kind() == PouKind.PROGRAM
                || p.kind() == PouKind.FUNCTION_BLOCK
                || p.kind() == PouKind.ROUTINE
                || p.kind() == PouKind.ORGANIZATION_BLOCK;
    }
}
