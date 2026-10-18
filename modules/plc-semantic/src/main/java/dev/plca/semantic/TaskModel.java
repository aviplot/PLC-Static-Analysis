package dev.plca.semantic;

import dev.plca.core.source.Span;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tasks and the POUs they execute (from IEC configurations or TwinCAT {@code .TcTTO} files). {@link #isKnown()} is
 * false when the project has no task configuration; rules must then not assume a single task.
 */
public final class TaskModel {

    /** {@code interval} in nanoseconds and {@code priority}; null when not configured. */
    public record Task(String name, Integer priority, Long interval, List<PouSymbol> programs, Span span) {
        public Task {
            programs = List.copyOf(programs);
        }
    }

    private final List<Task> tasks;
    private final Map<PouSymbol, Set<Task>> tasksByPou = new IdentityHashMap<>();

    TaskModel(List<Task> tasks, CallGraph calls) {
        this.tasks = List.copyOf(tasks);
        for (Task t : this.tasks) {
            for (PouSymbol program : t.programs()) {
                for (PouSymbol p : calls.reachableFrom(program)) {
                    tasksByPou.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(t);
                }
            }
        }
    }

    public boolean isKnown() {
        return !tasks.isEmpty();
    }

    public List<Task> tasks() {
        return tasks;
    }

    /** Tasks whose programs (transitively) call {@code pou}; members count via the call graph. */
    public Set<Task> tasksOf(PouSymbol pou) {
        return Collections.unmodifiableSet(tasksByPou.getOrDefault(pou, Set.of()));
    }
}
