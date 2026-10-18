package dev.plca.semantic;

import dev.plca.core.source.Location;
import dev.plca.core.source.Span;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Deterministic text dump of a {@link SemanticModel} for golden tests and debugging. */
public final class ModelPrinter {

    private final SemanticModel model;
    private final Function<Span, Location> resolver;
    private final StringBuilder out = new StringBuilder();

    private ModelPrinter(SemanticModel model, Function<Span, Location> resolver) {
        this.model = model;
        this.resolver = resolver;
    }

    public static String print(SemanticModel model, Function<Span, Location> resolver) {
        ModelPrinter p = new ModelPrinter(model, resolver);
        p.pous();
        p.types();
        p.gvls();
        p.effects();
        p.calls();
        p.instances();
        p.tasks();
        p.stats();
        return p.out.toString();
    }

    private void pous() {
        out.append("pous:\n");
        for (PouSymbol p : model.pous()) {
            if (p.owner() != null) {
                continue;
            }
            pou(p, 1);
        }
    }

    private void pou(PouSymbol p, int depth) {
        indent(depth).append(p.kind()).append(' ').append(p.name());
        if (!(p.returnType() instanceof Type.Void)) {
            out.append(" : ").append(p.returnType().display());
        }
        if (p.extendsPou() != null) {
            out.append(" EXTENDS ").append(p.extendsPou().name());
        }
        if (!p.implementsList().isEmpty()) {
            out.append(" IMPLEMENTS ")
                    .append(p.implementsList().stream().map(PouSymbol::name).collect(Collectors.joining(", ")));
        }
        out.append('\n');
        for (VariableSymbol v : p.variables().values()) {
            if (v.isResult()) {
                continue;
            }
            indent(depth + 1)
                    .append(v.section())
                    .append(' ')
                    .append(v.name())
                    .append(" : ")
                    .append(v.type().display());
            if (v.isConstant()) {
                out.append(" CONSTANT");
            }
            out.append('\n');
        }
        for (PouSymbol m : p.members().values()) {
            pou(m, depth + 1);
        }
    }

    private void types() {
        out.append("types:\n");
        for (TypeSymbol t : model.global().types()) {
            indent(1).append(t.kind()).append(' ').append(t.name());
            switch (t.kind()) {
                case STRUCT, UNION -> {
                    if (t.extendsType() != null) {
                        out.append(" EXTENDS ").append(t.extendsType().name());
                    }
                    out.append(" { ")
                            .append(t.members().values().stream()
                                    .map(m -> m.name() + " : " + m.type().display())
                                    .collect(Collectors.joining(", ")))
                            .append(" }");
                }
                case ENUM ->
                    out.append(" : ")
                            .append(t.baseType().display())
                            .append(" { ")
                            .append(t.values().values().stream()
                                    .map(v -> v.name() + "=" + v.value())
                                    .collect(Collectors.joining(", ")))
                            .append(" }");
                default -> out.append(" = ").append(t.type().resolved().display());
            }
            out.append('\n');
        }
    }

    private void gvls() {
        out.append("gvls:\n");
        for (GvlSymbol g : model.global().gvls()) {
            indent(1)
                    .append(g.name())
                    .append(g.qualifiedOnly() ? " (qualified_only)" : "")
                    .append(" { ")
                    .append(g.variables().values().stream()
                            .map(v -> v.name() + " : " + v.type().display())
                            .collect(Collectors.joining(", ")))
                    .append(" }\n");
        }
    }

    private void effects() {
        out.append("effects:\n");
        for (PouSymbol p : model.pous()) {
            if (p.declaration().body() == null) {
                continue;
            }
            Statements.forEachDeep(p.declaration().body(), s -> {
                Effects e = model.effects(s);
                if (e.accesses().isEmpty() && e.calls().isEmpty()) {
                    return;
                }
                indent(1)
                        .append(p.qualifiedName())
                        .append(' ')
                        .append(pos(s.span()))
                        .append(' ')
                        .append(s.getClass().getSimpleName())
                        .append(':');
                for (Access a : e.accesses()) {
                    out.append(' ')
                            .append(a.kind().name().toLowerCase(Locale.ROOT))
                            .append('(')
                            .append(a.target())
                            .append(')');
                }
                for (CallSite c : e.calls()) {
                    out.append(" call(").append(c).append(')');
                }
                out.append('\n');
            });
        }
    }

    private void calls() {
        out.append("call graph:\n");
        for (PouSymbol p : model.callGraph().callers()) {
            for (CallSite c : model.callGraph().callsFrom(p)) {
                indent(1)
                        .append(p.qualifiedName())
                        .append(" -> ")
                        .append(c.callee().qualifiedName())
                        .append(" (")
                        .append(c.kind())
                        .append(' ')
                        .append(pos(c.span()))
                        .append(")\n");
            }
        }
        List<List<PouSymbol>> cycles = model.callGraph().cycles();
        for (List<PouSymbol> cycle : cycles) {
            indent(1)
                    .append("cycle: ")
                    .append(cycle.stream().map(PouSymbol::qualifiedName).collect(Collectors.joining(" -> ")))
                    .append('\n');
        }
    }

    private void instances() {
        out.append("instances:")
                .append(model.instanceGraph().truncated() ? " (truncated)" : "")
                .append('\n');
        for (Instance i : model.instanceGraph().all()) {
            int depth = 1;
            for (Instance x = i.parent(); x != null; x = x.parent()) {
                depth++;
            }
            indent(depth).append(i).append('\n');
        }
    }

    private void tasks() {
        out.append("tasks:").append(model.tasks().isKnown() ? "" : " unknown").append('\n');
        for (TaskModel.Task t : model.tasks().tasks()) {
            indent(1)
                    .append(t.name())
                    .append(" priority=")
                    .append(t.priority())
                    .append(" interval=")
                    .append(t.interval() == null ? "?" : t.interval() / 1_000_000.0 + "ms")
                    .append(" programs=")
                    .append(t.programs().stream().map(PouSymbol::name).toList())
                    .append('\n');
        }
        for (PouSymbol p : model.pous().stream()
                .sorted(Comparator.comparing(PouSymbol::qualifiedName))
                .toList()) {
            var tasks = model.tasks().tasksOf(p);
            if (!tasks.isEmpty() && p.owner() == null) {
                indent(1)
                        .append(p.name())
                        .append(" runs in ")
                        .append(tasks.stream()
                                .map(TaskModel.Task::name)
                                .sorted()
                                .toList())
                        .append('\n');
            }
        }
    }

    private void stats() {
        SemanticModel.ResolutionStats s = model.resolutionStats();
        out.append("names: ")
                .append(s.names())
                .append(" resolved=")
                .append(s.resolved())
                .append(" library=")
                .append(s.library())
                .append(" unresolved=")
                .append(s.unresolved())
                .append('\n');
    }

    private String pos(Span s) {
        Location l = resolver.apply(s);
        return l.path() + ":" + l.startLine() + ":" + l.startColumn();
    }

    private StringBuilder indent(int depth) {
        return out.append("  ".repeat(depth));
    }
}
