package dev.plca.rules.logic;

import dev.plca.semantic.Access;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Statements;
import dev.plca.semantic.TaskModel;
import dev.plca.semantic.VariableSymbol;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;

/**
 * Globals that another task writes, as seen from one POU: a higher-priority task may change them between two reads in
 * this POU, so a proof that relies on such a value staying put is only reported at {@code info}. Empty when the project
 * has no task configuration (or only one task).
 */
final class Races {

    private static final Races NONE = new Races(Map.of(), Set.of());

    /**
     * Per model: each global written by code that runs in a task, with those tasks. The map lock only covers finding
     * the holder; the walk over the model runs under the holder's own lock, so other models are not held up.
     */
    private static final Map<SemanticModel, Holder> WRITERS = Collections.synchronizedMap(new WeakHashMap<>());

    private static final class Holder {
        private Map<VariableSymbol, Set<String>> writers;

        synchronized Map<VariableSymbol, Set<String>> get(SemanticModel model) {
            if (writers == null) {
                writers = writers(model);
            }
            return writers;
        }
    }

    private final Map<VariableSymbol, Set<String>> writers;
    /** This POU's tasks, lower case. */
    private final Set<String> own;

    private Races(Map<VariableSymbol, Set<String>> writers, Set<String> own) {
        this.writers = writers;
        this.own = own;
    }

    static Races of(SemanticModel model, PouSymbol pou) {
        TaskModel tasks = model.tasks();
        if (tasks == null || !tasks.isKnown() || tasks.tasks().size() < 2) {
            return NONE;
        }
        Set<String> own = new TreeSet<>();
        tasks.tasksOf(pou).forEach(t -> own.add(t.name().toLowerCase(Locale.ROOT)));
        if (pou.instanceOwner() != null) {
            tasks.tasksOf(pou.instanceOwner()).forEach(t -> own.add(t.name().toLowerCase(Locale.ROOT)));
        }
        return new Races(WRITERS.computeIfAbsent(model, m -> new Holder()).get(model), own);
    }

    private static Map<VariableSymbol, Set<String>> writers(SemanticModel model) {
        Map<VariableSymbol, Set<String>> out = new IdentityHashMap<>();
        for (PouSymbol p : model.pous()) {
            if (p.declaration() == null || p.declaration().body() == null) {
                continue;
            }
            Set<TaskModel.Task> ts = model.tasks().tasksOf(p);
            if (ts.isEmpty()) {
                continue;
            }
            Statements.forEachDeep(p.declaration().body(), s -> {
                for (Access a : model.effects(s).accesses()) {
                    if (a.isWrite() && a.variable().isGlobal()) {
                        Set<String> names = out.computeIfAbsent(a.variable(), k -> new TreeSet<>());
                        ts.forEach(t -> names.add(t.name()));
                    }
                }
            });
        }
        return out;
    }

    /** For each global in {@code c} that a task other than this POU's writes: {@code 'g' is not changed by task T}. */
    Set<String> of(Cond c) {
        if (writers.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new TreeSet<>();
        for (Operand o : c.operands()) {
            for (VariableSymbol v : o.deps()) {
                Set<String> others = new TreeSet<>();
                for (String t : writers.getOrDefault(v, Set.of())) {
                    if (!own.contains(t.toLowerCase(Locale.ROOT))) {
                        others.add(t);
                    }
                }
                if (!others.isEmpty()) {
                    out.add("'" + v.name() + "' is not changed by task " + String.join(", ", others));
                }
            }
        }
        return out;
    }
}
