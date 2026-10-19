package dev.plca.rules.plc;

import dev.plca.ast.Statement;
import dev.plca.core.diag.Diagnostic;
import dev.plca.rules.ProjectContext;
import dev.plca.rules.ProjectRule;
import dev.plca.semantic.Access;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Statements;
import dev.plca.semantic.TaskModel;
import dev.plca.semantic.VariableSymbol;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PLCA-P004 (PLCopen CP10): a global variable written by code that runs in more than one task. A higher-priority task
 * can interrupt a lower one between reading and writing the variable, so updates get lost or a value is seen half
 * written. Needs the project's task configuration; without one the rule is silent. One finding per variable, at a write
 * from the second task. Writes from POUs no task reaches are ignored.
 */
public final class CrossTaskWrite implements ProjectRule {

    @Override
    public String id() {
        return "PLCA-P004";
    }

    private record Write(PouSymbol pou, Access access, Set<TaskModel.Task> tasks) {}

    @Override
    public void check(ProjectContext ctx) {
        SemanticModel m = ctx.model();
        TaskModel tasks = m.tasks();
        if (tasks == null || !tasks.isKnown() || tasks.tasks().size() < 2) {
            return;
        }
        Pattern ignore = ctx.patternParam("ignorePattern");
        Map<VariableSymbol, List<Write>> writes = new IdentityHashMap<>();
        Map<VariableSymbol, Integer> order = new LinkedHashMap<>();
        for (PouSymbol pou : m.pous()) {
            if (pou.declaration() == null || pou.declaration().body() == null) {
                continue;
            }
            Set<TaskModel.Task> running = tasks.tasksOf(pou);
            if (running.isEmpty()) {
                continue;
            }
            Statements.forEachDeep(pou.declaration().body(), (Statement s) -> {
                for (Access a : m.effects(s).accesses()) {
                    VariableSymbol v = a.variable();
                    if (a.isWrite()
                            && v.isGlobal()
                            && !v.isConstant()
                            && !ignore.matcher(v.name()).find()) {
                        writes.computeIfAbsent(v, k -> new ArrayList<>()).add(new Write(pou, a, running));
                        order.putIfAbsent(v, order.size());
                    }
                }
            });
        }
        for (VariableSymbol v : order.keySet()) {
            List<Write> list = writes.get(v);
            Write first = list.getFirst();
            TaskModel.Task firstTask = first.tasks().iterator().next();
            Write other = null;
            TaskModel.Task otherTask = null;
            if (first.tasks().size() > 1) {
                other = first;
                otherTask = first.tasks().stream().skip(1).findFirst().orElseThrow();
            } else {
                for (Write w : list) {
                    for (TaskModel.Task t : w.tasks()) {
                        if (t != firstTask) {
                            other = w;
                            otherTask = t;
                            break;
                        }
                    }
                    if (other != null) {
                        break;
                    }
                }
            }
            if (other == null) {
                continue;
            }
            ctx.report(
                    other.pou(),
                    other.access().span(),
                    "Global '" + v.name() + "' is written from task " + otherTask.name() + " ("
                            + other.pou().qualifiedName()
                            + ") and from task " + firstTask.name() + " ("
                            + first.pou().qualifiedName()
                            + "); one task can interrupt the other mid-update (CP10)",
                    other == first
                            ? List.of()
                            : List.of(new Diagnostic.Related(
                                    first.access().span(), "write in task " + firstTask.name())));
        }
    }
}
