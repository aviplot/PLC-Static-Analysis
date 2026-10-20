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

    /**
     * {@link #print} plus every side table entry (bindings, types, parameters, owners, effects), the unresolved names
     * and the statistics, sorted: two models print the same exactly when they hold the same. A span the resolver cannot
     * place (a symbol of an earlier version of a file) prints as {@code STALE}. For checking incremental builds against
     * full ones.
     */
    public static String printAll(SemanticModel model, Function<Span, Location> resolver) {
        Function<Span, Location> safe = span -> {
            try {
                return resolver.apply(span);
            } catch (RuntimeException e) {
                return new Location("STALE", 0, 0, 0, 0);
            }
        };
        ModelPrinter p = new ModelPrinter(model, safe);
        StringBuilder out = new StringBuilder(print(model, safe));
        List<String> lines = new java.util.ArrayList<>();
        model.symbols.forEach((e, s) -> lines.add("symbol " + p.node(e) + " -> " + p.symbol(s)));
        model.types.forEach((e, t) -> lines.add("type " + p.node(e) + " : " + p.type(t)));
        model.parameters.forEach((a, v) -> lines.add("param " + p.node(a) + " -> " + p.symbol(v)));
        model.owners.forEach((st, o) -> lines.add("owner " + p.node(st) + " -> " + p.symbol(o)));
        model.effects.forEach((st, e) -> lines.add("effects " + p.node(st) + " "
                + e.accesses().size() + "/" + e.calls().size()));
        lines.sort(null);
        lines.forEach(l -> out.append(l).append('\n'));
        out.append("sizes ")
                .append(List.of(
                        model.symbols.size(),
                        model.types.size(),
                        model.parameters.size(),
                        model.owners.size(),
                        model.effects.size()))
                .append('\n');
        out.append("unresolved");
        model.unresolved().forEach(e -> out.append(' ').append(p.node(e)));
        out.append('\n').append(model.resolutionStats()).append('\n');
        return out.toString();
    }

    private String range(Span s) {
        Location l = resolver.apply(s);
        return l.path() + ":" + l.startLine() + ":" + l.startColumn() + "-" + l.endLine() + ":" + l.endColumn();
    }

    private String node(dev.plca.ast.Node n) {
        return range(n.span()) + " " + n.getClass().getSimpleName();
    }

    private String symbol(Symbol s) {
        String where =
                s.declaration() == null ? "" : " @" + range(s.declaration().span());
        String owner = s instanceof VariableSymbol v && v.owner() != null
                ? " in " + v.owner().name()
                        + (v.owner().declaration() == null
                                ? ""
                                : "@" + range(v.owner().declaration().span()))
                : "";
        return s.getClass().getSimpleName() + " " + s.name() + where + owner;
    }

    private String type(Type t) {
        StringBuilder b = new StringBuilder(t.display());
        typeSymbols(t, b);
        return b.toString();
    }

    private void typeSymbols(Type t, StringBuilder b) {
        switch (t) {
            case Type.Instance i -> b.append(" [").append(symbol(i.pou())).append(']');
            case Type.Struct st -> b.append(" [").append(symbol(st.symbol())).append(']');
            case Type.Enum en -> b.append(" [").append(symbol(en.symbol())).append(']');
            case Type.Alias a -> {
                b.append(" [").append(symbol(a.symbol())).append(']');
                typeSymbols(a.target(), b);
            }
            case Type.Array a -> typeSymbols(a.element(), b);
            case Type.Pointer ptr -> typeSymbols(ptr.target(), b);
            default -> {}
        }
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
                .append(s.unresolved());
        if (s.external() > 0) {
            out.append(" external=").append(s.external());
        }
        out.append('\n');
    }

    private String pos(Span s) {
        Location l = resolver.apply(s);
        return l.path() + ":" + l.startLine() + ":" + l.startColumn();
    }

    private StringBuilder indent(int depth) {
        return out.append("  ".repeat(depth));
    }
}
