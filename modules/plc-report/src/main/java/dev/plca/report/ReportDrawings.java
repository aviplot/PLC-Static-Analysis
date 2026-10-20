package dev.plca.report;

import dev.plca.ast.Sfc;
import dev.plca.ast.SourceUnit;
import dev.plca.ast.Statement;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.Span;
import dev.plca.semantic.Statements;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drawings for the HTML report (M10): which rung, network or chart a span lies in, and that part's SVG with findings
 * outlined on the element that holds them (as the web workbench outlines them).
 */
public final class ReportDrawings {

    /** No drawings: the report as before M10. */
    public static final ReportDrawings NONE = new ReportDrawings(null);

    /** Per text: intervals sorted by start, and the part each belongs to. */
    private static final class Index {
        int[] starts = new int[16];
        int[] ends = new int[16];
        Statement[] parts = new Statement[16];
        int size;

        void add(int start, int end, Statement part) {
            if (size == starts.length) {
                starts = Arrays.copyOf(starts, size * 2);
                ends = Arrays.copyOf(ends, size * 2);
                parts = Arrays.copyOf(parts, size * 2);
            }
            starts[size] = start;
            ends[size] = end;
            parts[size] = part;
            size++;
        }

        void sort() {
            Integer[] order = new Integer[size];
            for (int i = 0; i < size; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Integer.compare(starts[a], starts[b]));
            int[] s = new int[size];
            int[] e = new int[size];
            Statement[] p = new Statement[size];
            for (int i = 0; i < size; i++) {
                s[i] = starts[order[i]];
                e[i] = ends[order[i]];
                p[i] = parts[order[i]];
            }
            starts = s;
            ends = e;
            parts = p;
        }

        Statement find(int start, int end) {
            int lo = 0;
            int hi = size - 1;
            int last = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (starts[mid] <= start) {
                    last = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            // nested intervals: the container starts at or before; look back a little
            for (int i = last; i >= 0 && i > last - 256; i--) {
                if (ends[i] >= end) {
                    return parts[i];
                }
            }
            return null;
        }
    }

    private final SourceRegistry registry;
    private final Map<Integer, Index> byText = new HashMap<>();
    private final Map<Statement, String> labels = new HashMap<>();

    private ReportDrawings(SourceRegistry registry) {
        this.registry = registry;
    }

    /** Drawings of the graphical bodies of {@code units}, whose spans resolve in {@code registry}. */
    public static ReportDrawings of(List<SourceUnit> units, SourceRegistry registry) {
        ReportDrawings d = new ReportDrawings(registry);
        for (SourceUnit u : units) {
            for (Diagrams.Diagram diagram : Diagrams.of(u)) {
                for (Statement part : diagram.parts()) {
                    d.labels.put(part, label(diagram, part));
                    d.index(part, part);
                    switch (part) {
                        case Statement.Network n -> n.network().elements().forEach(e -> d.add(e.span(), part));
                        case Statement.Chart c -> {
                            if (c.chart().drawing() != null) {
                                for (Sfc.Element e : c.chart().drawing().elements()) {
                                    d.add(e.span(), part);
                                }
                            }
                            Statements.forEachDeep(c.body(), s -> d.add(s.span(), part));
                        }
                        default -> {}
                    }
                }
            }
        }
        d.byText.values().forEach(Index::sort);
        return d;
    }

    private void index(Statement s, Statement part) {
        add(s.span(), part);
    }

    private void add(Span span, Statement part) {
        if (span != null) {
            byText.computeIfAbsent(span.textId(), k -> new Index()).add(span.start(), span.end(), part);
        }
    }

    private static String label(Diagrams.Diagram d, Statement part) {
        return switch (part) {
            case Statement.Rung r -> d.name() + ", rung " + r.number();
            case Statement.Network n -> d.name() + ", network " + n.number();
            default -> d.name() + ", chart";
        };
    }

    public boolean isEmpty() {
        return byText.isEmpty();
    }

    /** The rung, network or chart {@code span} lies in, or null. */
    Statement partOf(Span span) {
        Index i = byText.get(span.textId());
        return i == null ? null : i.find(span.start(), span.end());
    }

    /** "MainProgram/MainRoutine, rung 12". */
    String label(Statement part) {
        return labels.get(part);
    }

    /** A finding to outline: its span and severity. */
    record Mark(Span span, Severity severity) {}

    private static final Pattern GROUP =
            Pattern.compile("<g class=\"((?:ins|el)\\b[^\"]*)\" data-range=\"(\\d+),(\\d+),(\\d+),(\\d+)\"");

    /** {@code part} drawn, each mark outlined on the innermost element that holds it. */
    String svg(Statement part, List<Mark> marks) {
        String svg = switch (part) {
            case Statement.Rung r -> LadderSvg.rung(r, registry, LadderSvg.naturalWidth(r));
            case Statement.Network n -> NetworkSvg.network(n, registry);
            case Statement.Chart c -> SfcSvg.chart(c, registry);
            default -> "";
        };
        record Group(int classStart, int classEnd, long[] range) {}
        List<Group> groups = new ArrayList<>();
        Matcher m = GROUP.matcher(svg);
        while (m.find()) {
            groups.add(new Group(m.start(1), m.end(1), new long[] {
                Long.parseLong(m.group(2)),
                Long.parseLong(m.group(3)),
                Long.parseLong(m.group(4)),
                Long.parseLong(m.group(5))
            }));
        }
        Map<Group, Severity> hits = new HashMap<>();
        for (Mark mark : marks) {
            Location l = registry.resolve(mark.span());
            long[] f = {l.startLine() - 1, l.startColumn() - 1, l.endLine() - 1, l.endColumn() - 1};
            Group best = null;
            for (Group g : groups) {
                if (contains(g.range(), f) || overlaps(g.range(), f)) {
                    if (best == null || contains(best.range(), g.range())) {
                        best = g;
                    }
                }
            }
            if (best == null) {
                // elements carry their start tag only (L5X steps, actions): the last one starting before the finding
                for (Group g : groups) {
                    if (!before(f[0], f[1], g.range()[0], g.range()[1])
                            && (best == null || before(best.range()[0], best.range()[1], g.range()[0], g.range()[1]))) {
                        best = g;
                    }
                }
            }
            if (best != null) {
                hits.merge(best, mark.severity(), (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
            }
        }
        StringBuilder out = new StringBuilder(svg.length() + 64 * hits.size());
        int at = 0;
        for (Group g : groups) {
            Severity sev = hits.get(g);
            if (sev != null) {
                out.append(svg, at, g.classEnd())
                        .append(" has-finding sev-")
                        .append(sev.name().toLowerCase(java.util.Locale.ROOT));
                at = g.classEnd();
            }
        }
        return out.append(svg, at, svg.length()).toString();
    }

    private static boolean before(long l1, long c1, long l2, long c2) {
        return l1 < l2 || l1 == l2 && c1 < c2;
    }

    private static boolean contains(long[] outer, long[] inner) {
        return !before(inner[0], inner[1], outer[0], outer[1]) && !before(outer[2], outer[3], inner[2], inner[3]);
    }

    private static boolean overlaps(long[] a, long[] b) {
        return !before(a[2], a[3], b[0], b[1]) && !before(b[2], b[3], a[0], a[1]);
    }
}
