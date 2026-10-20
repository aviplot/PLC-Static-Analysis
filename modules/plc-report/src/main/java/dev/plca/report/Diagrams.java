package dev.plca.report;

import dev.plca.ast.Declaration;
import dev.plca.ast.SourceUnit;
import dev.plca.ast.Statement;
import dev.plca.core.source.SourceRegistry;
import java.util.ArrayList;
import java.util.List;

/**
 * The graphical bodies of a source unit (Rockwell ladder routines, PLCopen LD/FBD bodies, SFC charts) and their
 * drawings: one SVG per rung or network ({@link LadderSvg}, {@link NetworkSvg}) or per chart ({@link SfcSvg}), so a
 * viewer can show long routines lazily.
 */
public final class Diagrams {

    /** A POU body with rungs or networks. {@code name}: the POU path ({@code MainProgram/MainRoutine}). */
    public record Diagram(String name, String kind, String language, List<Statement> parts) {
        public Diagram {
            parts = List.copyOf(parts);
        }
    }

    /** One drawn rung or network: its number as the engineer sees it and the SVG. */
    public record Part(int number, String svg) {}

    private Diagrams() {}

    public static List<Diagram> of(SourceUnit unit) {
        List<Diagram> out = new ArrayList<>();
        if (unit != null) {
            declarations(unit.declarations(), "", out);
        }
        return out;
    }

    private static void declarations(List<Declaration> ds, String prefix, List<Diagram> out) {
        for (Declaration d : ds) {
            switch (d) {
                case Declaration.Pou p -> pou(p, prefix, out);
                case Declaration.Namespace n -> declarations(n.members(), prefix + n.name() + "/", out);
                default -> {}
            }
        }
    }

    private static void pou(Declaration.Pou p, String prefix, List<Diagram> out) {
        String name = prefix + p.name().name();
        if (p.body() != null) {
            List<Statement> parts = new ArrayList<>();
            collect(p.body(), parts);
            if (!parts.isEmpty()) {
                String language = switch (parts.getFirst()) {
                    case Statement.Network n -> n.network().language().name();
                    case Statement.Chart c -> "SFC";
                    default -> "RLL";
                };
                out.add(new Diagram(name, p.kind().name(), language, parts));
            }
        }
        for (Declaration.Pou m : p.members()) {
            pou(m, name + "/", out);
        }
    }

    private static void collect(List<Statement> body, List<Statement> parts) {
        for (Statement s : body) {
            switch (s) {
                case Statement.Rung r -> parts.add(r);
                case Statement.Network n -> parts.add(n);
                case Statement.Chart c -> {
                    if (c.chart().drawing() != null
                            && !c.chart().drawing().elements().isEmpty()) {
                        parts.add(c); // the chart, drawn as one part
                    } else {
                        collect(c.body(), parts);
                    }
                }
                default -> {}
            }
        }
    }

    /** All parts of a diagram drawn; rungs share one width so their rails line up. */
    public static List<Part> render(Diagram d, SourceRegistry registry) {
        return render(d, registry, 0, d.parts().size());
    }

    /** Parts {@code from} to {@code from + count} (clamped) of a diagram, as wide as the widest rung of all. */
    public static List<Part> render(Diagram d, SourceRegistry registry, int from, int count) {
        int start = Math.max(0, Math.min(from, d.parts().size()));
        int end = (int) Math.min(d.parts().size(), (long) start + Math.max(0, count));
        int width = 0;
        for (Statement s : d.parts()) {
            if (s instanceof Statement.Rung r) {
                width = Math.max(width, LadderSvg.naturalWidth(r));
            }
        }
        List<Part> out = new ArrayList<>(end - start);
        for (Statement s : d.parts().subList(start, end)) {
            switch (s) {
                case Statement.Rung r -> out.add(new Part(r.number(), LadderSvg.rung(r, registry, width)));
                case Statement.Network n -> out.add(new Part(n.number(), NetworkSvg.network(n, registry)));
                case Statement.Chart c -> out.add(new Part(1, SfcSvg.chart(c, registry)));
                default -> {}
            }
        }
        return out;
    }
}
