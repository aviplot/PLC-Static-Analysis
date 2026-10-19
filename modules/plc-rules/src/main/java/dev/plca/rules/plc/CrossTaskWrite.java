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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PLCA-P004 (PLCopen CP10): a global variable written by code that runs in more than one task. A higher-priority task
 * can interrupt a lower one between reading and writing the variable, so updates get lost or a value is seen half
 * written. Needs the project's task configuration; without one the rule is silent. One finding per variable, at a write
 * from the second task. Writes from POUs no task reaches are ignored, tasks are told apart by name (duplicate copies of
 * a project declare the same task twice), and startup tasks ({@code startupTaskPattern}, e.g. Siemens OB100) do not
 * count: they finish before cyclic tasks start.
 */
public final class CrossTaskWrite implements ProjectRule {

    @Override
    public String id() {
        return "PLCA-P004";
    }

    private record Write(PouSymbol pou, Access access, Set<String> tasks) {}

    /** A global, or one top-level member of a structured global (data block fields are separate variables). */
    private record Key(VariableSymbol variable, String member) {
        String display() {
            return variable.name() + member;
        }
    }

    private static Key key(Access a) {
        String p = a.path();
        if (p.startsWith(".")) {
            int end = 1;
            while (end < p.length() && p.charAt(end) != '.' && p.charAt(end) != '[') {
                end++;
            }
            return new Key(a.variable(), p.substring(0, end));
        }
        return new Key(a.variable(), "");
    }

    @Override
    public void check(ProjectContext ctx) {
        SemanticModel m = ctx.model();
        TaskModel tasks = m.tasks();
        if (tasks == null || !tasks.isKnown() || tasks.tasks().size() < 2) {
            return;
        }
        Pattern ignore = ctx.patternParam("ignorePattern");
        Pattern startup = ctx.patternParam("startupTaskPattern");
        Map<Key, List<Write>> writes = new LinkedHashMap<>();
        for (PouSymbol pou : m.pous()) {
            if (pou.declaration() == null || pou.declaration().body() == null) {
                continue;
            }
            Set<String> running = new LinkedHashSet<>();
            for (TaskModel.Task t : tasks.tasksOf(pou)) {
                if (!startup.matcher(t.name()).find()) {
                    running.add(t.name().toLowerCase(Locale.ROOT));
                }
            }
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
                        writes.computeIfAbsent(key(a), k -> new ArrayList<>()).add(new Write(pou, a, running));
                    }
                }
            });
        }
        Map<String, String> display = new LinkedHashMap<>();
        tasks.tasks().forEach(t -> display.putIfAbsent(t.name().toLowerCase(Locale.ROOT), t.name()));
        for (Map.Entry<Key, List<Write>> e : writes.entrySet()) {
            List<Write> list = e.getValue();
            Write first = list.getFirst();
            String firstTask = first.tasks().iterator().next();
            Write other = null;
            String otherTask = null;
            for (Write w : list) {
                for (String t : w.tasks()) {
                    if (!t.equals(firstTask)) {
                        other = w;
                        otherTask = t;
                        break;
                    }
                }
                if (other != null) {
                    break;
                }
            }
            if (other == null) {
                continue;
            }
            ctx.report(
                    other.pou(),
                    other.access().span(),
                    "Global '" + e.getKey().display() + "' is written from task " + display.get(otherTask) + " ("
                            + other.pou().qualifiedName() + ") and from task " + display.get(firstTask) + " ("
                            + first.pou().qualifiedName() + "); one task can interrupt the other mid-update (CP10)",
                    other == first
                            ? List.of()
                            : List.of(new Diagnostic.Related(
                                    first.access().span(), "write in task " + display.get(firstTask))));
        }
    }
}
