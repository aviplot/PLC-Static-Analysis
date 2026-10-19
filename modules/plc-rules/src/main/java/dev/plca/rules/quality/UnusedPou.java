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
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * PLCA-Q008 (PLCopen CP2): a POU that nothing runs — not scheduled in a task, not called, not instantiated, not
 * referenced. Programs, function blocks, functions, classes and Rockwell routines are checked; methods, actions and
 * properties are not (they belong to their owner's interface).
 *
 * <p>Only dialects whose code in the project has an entry point are checked: a task, a PROGRAM (when no task
 * configuration is known), a Siemens OB or a Rockwell program. A folder of library blocks has none and stays silent.
 * Entry points are the programs of the tasks when tasks are known, else every program; OBs always.
 */
public final class UnusedPou implements ProjectRule {

    @Override
    public String id() {
        return "PLCA-Q008";
    }

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
            boolean entry = p.kind() == PouKind.ORGANIZATION_BLOCK
                    || p.kind() == PouKind.PROGRAM && (!tasksKnown || !withEntry.contains(p.dialect()));
            if (entry) {
                todo.add(p);
                if (p.dialect() != null) {
                    withEntry.add(p.dialect());
                }
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

        for (PouSymbol p : m.pous()) {
            if (p.isBuiltin()
                    || used.contains(p)
                    || !CHECKED.contains(p.kind())
                    || p.declaration() == null
                    || p.dialect() == null
                    || !withEntry.contains(p.dialect())
                    || p.kind() == PouKind.ROUTINE && (p.owner() == null || !used.contains(p.owner()))) {
                continue;
            }
            ctx.report(p, p.declaration().name().span(), message(p, tasksKnown), List.of());
        }
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
