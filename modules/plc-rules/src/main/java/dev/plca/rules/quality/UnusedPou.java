package dev.plca.rules.quality;

import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.Expression;
import dev.plca.core.Dialect;
import dev.plca.rules.ProjectContext;
import dev.plca.rules.ProjectRule;
import dev.plca.rules.Walk;
import dev.plca.semantic.CallSite;
import dev.plca.semantic.GvlSymbol;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.TaskModel;
import dev.plca.semantic.Type;
import dev.plca.semantic.TypeSymbol;
import dev.plca.semantic.VariableSymbol;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PLCA-Q008 (PLCopen CP2): a POU that nothing runs — not scheduled in a task, not called, not instantiated, not
 * referenced. Programs, function blocks, functions, classes and Rockwell routines are checked; methods, actions and
 * properties are not (they belong to their owner's interface).
 *
 * <p>Unused code can only be told from code whose callers are missing when the export is complete, so a dialect is
 * checked only when the project shows its entry points: a task configuration with programs of that dialect, or Siemens
 * organisation blocks. It is skipped when calls may be hidden: POUs of the dialect with bodies we do not parse (LD,
 * FBD, CFC; for Rockwell, the routines of a program with an FBD or SFC routine), or Siemens calls through instance data
 * blocks the export lacks. Names declared twice (duplicate copies), the CODESYS implicit check functions and a dialect
 * where most POUs are unused (a library bundled with a small test program) are not reported.
 */
public final class UnusedPou implements ProjectRule {

    @Override
    public String id() {
        return "PLCA-Q008";
    }

    /** CODESYS calls these implicitly when they exist (bounds, range, division and pointer checks). */
    private static final Set<String> IMPLICIT = Set.of(
            "checkbounds",
            "checkpointer",
            "checkrangesigned",
            "checkrangeunsigned",
            "checklrangesigned",
            "checklrangeunsigned",
            "checkdivdint",
            "checkdivlint",
            "checkdivreal",
            "checkdivlreal",
            "checkdivint");

    private static final Set<PouKind> CHECKED =
            EnumSet.of(PouKind.PROGRAM, PouKind.FUNCTION, PouKind.FUNCTION_BLOCK, PouKind.CLASS, PouKind.ROUTINE);

    @Override
    public void check(ProjectContext ctx) {
        SemanticModel m = ctx.model();
        TaskModel tasks = m.tasks();
        boolean tasksKnown = tasks != null && tasks.isKnown();
        Set<PouSymbol> used = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<PouSymbol> todo = new ArrayDeque<>();
        Set<Dialect> withEntry = EnumSet.noneOf(Dialect.class);

        if (tasksKnown) {
            for (TaskModel.Task t : tasks.tasks()) {
                for (PouSymbol p : t.programs()) {
                    todo.add(p);
                    if (p.dialect() != null) {
                        withEntry.add(p.dialect());
                    }
                }
            }
        }
        for (PouSymbol p : m.pous()) {
            if (p.isBuiltin()) {
                continue;
            }
            if (p.kind() == PouKind.ORGANIZATION_BLOCK) {
                todo.add(p);
                if (p.dialect() != null) {
                    withEntry.add(p.dialect());
                }
            } else if (p.kind() == PouKind.PROGRAM && !tasksKnown) {
                todo.add(p); // reachability still starts here, but the dialect is not checked
            }
        }
        // instances declared anywhere (globals, data blocks, struct members) keep their FB
        for (GvlSymbol g : m.global().gvls()) {
            for (VariableSymbol v : g.variables().values()) {
                instanceOf(v.type(), todo);
            }
        }
        for (TypeSymbol t : m.global().types()) {
            for (VariableSymbol v : t.members().values()) {
                instanceOf(v.type(), todo);
            }
        }

        while (!todo.isEmpty()) {
            PouSymbol p = todo.pop();
            if (p == null || !used.add(p)) {
                continue;
            }
            if (p.owner() != null) {
                todo.push(p.owner());
            }
            if (p.extendsPou() != null) {
                todo.push(p.extendsPou());
            }
            p.implementsList().forEach(todo::push);
            for (PouSymbol member : p.members().values()) {
                // a used FB's methods and actions may run; a Rockwell routine runs only when called
                if (member.kind() != PouKind.ROUTINE) {
                    todo.push(member);
                }
            }
            for (CallSite c : m.callGraph().callsFrom(p)) {
                todo.push(c.callee());
            }
            for (VariableSymbol v : p.variables().values()) {
                instanceOf(v.type(), todo);
            }
            if (p.declaration() != null && p.declaration().body() != null) {
                Walk.expressions(p.declaration().body(), e -> {
                    if (e instanceof Expression.NameRef || e instanceof Expression.MemberAccess) {
                        if (m.symbol(e).orElse(null) instanceof PouSymbol ref) {
                            todo.push(ref);
                        }
                    }
                });
            }
        }

        Map<String, Integer> declared = new HashMap<>();
        Set<Dialect> hidden = EnumSet.noneOf(Dialect.class);
        Set<PouSymbol> hiddenPrograms = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PouSymbol p : m.pous()) {
            declared.merge(p.qualifiedName().toLowerCase(Locale.ROOT), 1, Integer::sum);
            if (p.declaration() != null
                    && p.declaration().body() == null
                    && p.declaration().bodyLanguage() != null) {
                if (p.dialect() == Dialect.ROCKWELL && p.kind() == PouKind.ROUTINE && p.owner() != null) {
                    hiddenPrograms.add(p.owner());
                } else if (p.dialect() != null) {
                    hidden.add(p.dialect());
                }
            }
        }
        if (unresolvedCalls(m, used, Dialect.SIEMENS)) {
            hidden.add(Dialect.SIEMENS);
        }
        List<PouSymbol> unused = new ArrayList<>();
        Map<Dialect, Integer> checked = new EnumMap<>(Dialect.class);
        for (PouSymbol p : m.pous()) {
            if (p.isBuiltin()
                    || !CHECKED.contains(p.kind())
                    || p.declaration() == null
                    || p.dialect() == null
                    || !withEntry.contains(p.dialect())
                    || hidden.contains(p.dialect())) {
                continue;
            }
            checked.merge(p.dialect(), 1, Integer::sum);
            if (used.contains(p)
                    || declared.get(p.qualifiedName().toLowerCase(Locale.ROOT)) > 1
                    || IMPLICIT.contains(p.name().toLowerCase(Locale.ROOT))
                    || p.kind() == PouKind.ROUTINE
                            && (p.owner() == null || !used.contains(p.owner()) || hiddenPrograms.contains(p.owner()))
                    || !hiddenPrograms.isEmpty() && p.dialect() == Dialect.ROCKWELL && p.kind() != PouKind.ROUTINE) {
                continue;
            }
            unused.add(p);
        }
        Map<Dialect, Integer> unusedPerDialect = new EnumMap<>(Dialect.class);
        unused.forEach(p -> unusedPerDialect.merge(p.dialect(), 1, Integer::sum));
        for (PouSymbol p : unused) {
            int n = unusedPerDialect.get(p.dialect());
            if (n > LIBRARY_MIN && n * 2 > checked.get(p.dialect())) {
                continue; // most of the dialect's code is unused: a library with a test program
            }
            ctx.report(p, p.declaration().name().span(), message(p, tasksKnown), List.of());
        }
    }

    /** Below this many unused POUs a dialect is never treated as a library. */
    private static final int LIBRARY_MIN = 10;

    /** A call in reachable code whose callee is not in the project (an instance DB the export lacks). */
    private static boolean unresolvedCalls(SemanticModel m, Set<PouSymbol> used, Dialect dialect) {
        Set<Expression> unresolved = Collections.newSetFromMap(new IdentityHashMap<>());
        unresolved.addAll(m.unresolved());
        if (unresolved.isEmpty()) {
            return false;
        }
        boolean[] found = {false};
        for (PouSymbol p : used) {
            if (p.dialect() != dialect
                    || p.declaration() == null
                    || p.declaration().body() == null) {
                continue;
            }
            Walk.expressions(p.declaration().body(), e -> {
                if (e instanceof Expression.Call c && unresolved.contains(c.callee())) {
                    found[0] = true;
                }
            });
            if (found[0]) {
                return true;
            }
        }
        return false;
    }

    private static String message(PouSymbol p, boolean tasksKnown) {
        return switch (p.kind()) {
            case PROGRAM ->
                "PROGRAM " + p.qualifiedName()
                        + (tasksKnown ? " is not scheduled in any task and not called" : " is never called");
            case ROUTINE ->
                "Routine " + p.name() + " of " + p.owner().qualifiedName() + " is never called (no JSR reaches it)";
            case FUNCTION -> "FUNCTION " + p.qualifiedName() + " is never called";
            default -> p.kind().name() + " " + p.qualifiedName() + " is never instantiated or called";
        };
    }

    private static void instanceOf(Type t, Deque<PouSymbol> todo) {
        Type r = t == null ? null : t.resolved();
        while (r instanceof Type.Array a) {
            r = a.element().resolved();
        }
        if (r instanceof Type.Instance i) {
            todo.push(i.pou());
        } else if (r instanceof Type.Pointer p && p.target().resolved() instanceof Type.Instance i) {
            todo.push(i.pou());
        }
    }
}
