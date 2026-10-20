package dev.plca.report;

import dev.plca.ast.Network;
import dev.plca.ast.Sfc;
import dev.plca.ast.Sfc.Element;
import dev.plca.ast.Sfc.Kind;
import dev.plca.ast.Statement;
import dev.plca.core.source.SourceRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a sequential function chart as SVG (M10), at the coordinates its author gave: steps as boxes (an initial step
 * with a double border), transitions as bars with their condition, selection branches as single lines and simultaneous
 * ones as double lines, action blocks beside their step, jumps as arrows with the target's name. Links follow their
 * stored route, else run as orthogonal elbows. Charts without positions are laid out top-down by their links. Steps,
 * transitions and action blocks carry {@code data-range}.
 */
public final class SfcSvg {

    static final int MARGIN = 24;
    static final int MAX_TEXT = 40;
    static final int STEP_W = 80;
    static final int STEP_H = 30;
    static final int TRANSITION_W = 20;
    static final int TRANSITION_H = 10;
    static final int ROW_H = 18;

    private final Svg svg;
    private final Map<Integer, Element> byId = new HashMap<>();
    private double minX = Double.MAX_VALUE;
    private double minY = Double.MAX_VALUE;
    private double maxX = -Double.MAX_VALUE;
    private double maxY = -Double.MAX_VALUE;

    private SfcSvg(SourceRegistry registry) {
        this.svg = new Svg(registry);
    }

    public static String chart(Statement.Chart c, SourceRegistry registry) {
        return new SfcSvg(registry).render(c.chart());
    }

    private String render(Sfc chart) {
        List<Element> elements =
                chart.drawing() == null ? List.of() : chart.drawing().elements();
        boolean placed = elements.stream().anyMatch(e -> e.x() != 0 || e.y() != 0);
        elements = sized(placed ? elements : layout(elements));
        for (Element e : elements) {
            byId.put(e.id(), e);
        }
        elements = branches(elements);
        for (Element e : elements) {
            byId.put(e.id(), e);
        }
        svg.open("sfc", chart.span());
        for (Element e : elements) {
            for (Sfc.Link l : e.inputs()) {
                link(byId.get(l.source()), e, l);
            }
        }
        for (Element e : elements) {
            element(e);
        }
        svg.close();
        if (minX > maxX) {
            minX = minY = 0;
            maxX = maxY = 40;
        }
        return svg.document(minX - MARGIN, minY - MARGIN, maxX - minX + 2 * MARGIN, maxY - minY + 2 * MARGIN, "chart");
    }

    // ---- sizes and positions --------------------------------------------------------------------

    /** Elements without a size get the usual one of their kind. */
    private static List<Element> sized(List<Element> elements) {
        List<Element> out = new ArrayList<>(elements.size());
        for (Element e : elements) {
            int w = e.width();
            int h = e.height();
            if (w <= 0 || h <= 0) {
                switch (e.kind()) {
                    case STEP, MACRO_STEP, STOP -> {
                        w = w > 0 ? w : Math.max(STEP_W, Svg.textWidth(nonNull(e.name())) + 16);
                        h = h > 0 ? h : STEP_H;
                    }
                    case TRANSITION -> {
                        w = w > 0 ? w : TRANSITION_W;
                        h = h > 0 ? h : TRANSITION_H;
                    }
                    case JUMP -> {
                        w = w > 0 ? w : 12;
                        h = h > 0 ? h : 14;
                    }
                    case ACTION_BLOCK -> {
                        int text = e.lines().stream()
                                .mapToInt(l -> Svg.textWidth(Svg.elide(l, MAX_TEXT)))
                                .max()
                                .orElse(40);
                        w = w > 0 ? w : text + 16;
                        h = h > 0 ? h : Math.max(1, e.lines().size()) * ROW_H;
                    }
                    default -> h = h > 0 ? h : 3; // branches: their width follows their links
                }
            }
            out.add(with(e, e.x(), e.y(), w, h));
        }
        return out;
    }

    /** A branch drawn without a width spans the elements linked to it (Rockwell gives only its Y). */
    private List<Element> branches(List<Element> elements) {
        Map<Integer, List<Element>> into = new HashMap<>();
        for (Element e : elements) {
            for (Sfc.Link l : e.inputs()) {
                into.computeIfAbsent(l.source(), k -> new ArrayList<>()).add(e);
            }
        }
        List<Element> out = new ArrayList<>(elements.size());
        for (Element e : elements) {
            if (isBranch(e.kind()) && e.width() <= 0) {
                double lo = Double.MAX_VALUE;
                double hi = -Double.MAX_VALUE;
                List<Element> linked = new ArrayList<>(into.getOrDefault(e.id(), List.of()));
                for (Sfc.Link l : e.inputs()) {
                    Element src = byId.get(l.source());
                    if (src != null) {
                        linked.add(src);
                    }
                }
                for (Element x : linked) {
                    lo = Math.min(lo, centerX(x));
                    hi = Math.max(hi, centerX(x));
                }
                if (lo > hi) {
                    lo = hi = e.x();
                }
                out.add(with(e, (int) lo - 10, e.y(), (int) (hi - lo) + 20, e.height()));
            } else {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * A chart whose producer gave no positions: rows by the longest path from the chart's starts (initial steps and
     * elements nothing links into; links back up ignored), elements of a row side by side, action blocks beside their
     * step.
     */
    static List<Element> layout(List<Element> elements) {
        Map<Integer, Element> ids = new LinkedHashMap<>();
        Map<Integer, List<Integer>> next = new HashMap<>();
        for (Element e : elements) {
            ids.put(e.id(), e);
            for (Sfc.Link l : e.inputs()) {
                next.computeIfAbsent(l.source(), k -> new ArrayList<>()).add(e.id());
            }
        }
        Map<Integer, Integer> row = new HashMap<>();
        ArrayDeque<Integer> work = new ArrayDeque<>();
        for (Element e : elements) {
            if (e.initial() || e.inputs().isEmpty()) {
                row.put(e.id(), 0);
                work.add(e.id());
            }
        }
        int guard = elements.size() * elements.size() + 16;
        while (!work.isEmpty() && guard-- > 0) {
            int id = work.poll();
            for (int n : next.getOrDefault(id, List.of())) {
                Element target = ids.get(n);
                if (target == null || target.kind() == Kind.ACTION_BLOCK) {
                    continue;
                }
                int r = row.get(id) + 1;
                if (!row.containsKey(n)) {
                    row.put(n, r);
                    work.add(n);
                }
            }
        }
        Map<Integer, Integer> used = new HashMap<>();
        List<Element> out = new ArrayList<>(elements.size());
        for (Element e : elements) {
            if (e.kind() == Kind.ACTION_BLOCK) {
                continue;
            }
            int r = row.getOrDefault(e.id(), 0);
            int col = used.merge(r, 1, Integer::sum) - 1;
            out.add(with(e, col * 200 + 40, r * 60, 0, 0));
        }
        for (Element e : elements) {
            if (e.kind() == Kind.ACTION_BLOCK) {
                Element step = e.inputs().isEmpty()
                        ? null
                        : ids.get(e.inputs().getFirst().source());
                int r = step == null ? 0 : row.getOrDefault(step.id(), 0);
                int col = step == null
                        ? 0
                        : (out.stream()
                                                .filter(x -> x.id() == step.id())
                                                .findFirst()
                                                .map(Element::x)
                                                .orElse(40)
                                        - 40)
                                / 200;
                out.add(with(e, col * 200 + 40 + STEP_W + 20, r * 60, 0, 0));
            }
        }
        return strip(out);
    }

    private static List<Element> strip(List<Element> elements) {
        List<Element> out = new ArrayList<>(elements.size());
        for (Element e : elements) {
            List<Sfc.Link> links = e.inputs().stream()
                    .map(l -> new Sfc.Link(l.source(), List.of()))
                    .toList();
            out.add(new Element(
                    e.id(),
                    e.kind(),
                    e.name(),
                    e.initial(),
                    e.lines(),
                    e.x(),
                    e.y(),
                    e.width(),
                    e.height(),
                    links,
                    e.span()));
        }
        return out;
    }

    private static Element with(Element e, int x, int y, int w, int h) {
        return new Element(e.id(), e.kind(), e.name(), e.initial(), e.lines(), x, y, w, h, e.inputs(), e.span());
    }

    // ---- links ----------------------------------------------------------------------------------

    private void link(Element from, Element to, Sfc.Link l) {
        if (from == null || to == null) {
            return;
        }
        if (!l.route().isEmpty()) {
            double[] xy = new double[l.route().size() * 2];
            int i = 0;
            for (Network.Point p : l.route()) {
                xy[i++] = p.x();
                xy[i++] = p.y();
                extend(p.x(), p.y());
            }
            svg.polyline("w", xy);
            return;
        }
        if (to.kind() == Kind.ACTION_BLOCK) {
            double y = from.y() + from.height() / 2.0;
            svg.line("w", from.x() + from.width(), y, to.x(), Math.max(y, to.y() + ROW_H / 2.0));
            return;
        }
        double sx = isBranch(from.kind()) ? centerX(to) : centerX(from);
        double sy = from.y() + from.height();
        double tx = isBranch(to.kind()) ? sx : centerX(to);
        double ty = to.y();
        if (ty >= sy) {
            double my = isBranch(to.kind()) ? ty : (sy + ty) / 2;
            svg.polyline("w", new double[] {sx, sy, sx, my, tx, my, tx, ty});
        } else {
            // back up to an earlier step: drawn as IEC draws a jump, an arrow with the target's name
            svg.line("w", sx, sy, sx, sy + 10);
            svg.path(
                    "sym",
                    "M" + Svg.n(sx - 6) + " " + Svg.n(sy + 10) + " L" + Svg.n(sx + 6) + " " + Svg.n(sy + 10) + " L"
                            + Svg.n(sx) + " " + Svg.n(sy + 22) + " Z");
            String target = to.name() == null ? "" : to.name();
            svg.text("", sx + 10, sy + 20, null, target, null, null);
            extend(sx + 10 + Svg.textWidth(target), sy + 22);
        }
    }

    // ---- elements -------------------------------------------------------------------------------

    private void element(Element e) {
        double x = e.x();
        double y = e.y();
        double w = e.width();
        double h = e.height();
        String cls = "el " + e.kind().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        svg.open(cls, e.span());
        switch (e.kind()) {
            case STEP, MACRO_STEP, STOP -> {
                svg.rect("box", x, y, w, h, 0);
                if (e.initial()) {
                    svg.rect("box", x + 3, y + 3, w - 6, h - 6, 0);
                }
                if (e.kind() == Kind.MACRO_STEP) {
                    svg.line("sym", x, y + 4, x + w, y + 4).line("sym", x, y + h - 4, x + w, y + h - 4);
                }
                String name = e.kind() == Kind.STOP && e.name() == null ? "Stop" : nonNull(e.name());
                String shown = Svg.elide(name, (int) Math.max(4, (w - 8) / Svg.CHAR_W));
                svg.text("mn", x + w / 2, y + h / 2 + 4, "middle", shown, name, null);
            }
            case TRANSITION -> {
                double cx = x + w / 2;
                svg.line("sym", cx, y, cx, y + h);
                svg.line("rail", x, y + h / 2, x + w, y + h / 2);
                String cond = e.lines().isEmpty() ? "" : e.lines().getFirst();
                if (!cond.isEmpty()) {
                    svg.text("", x + w + 8, y + h / 2 + 4, null, Svg.elide(cond, MAX_TEXT), cond, null);
                    extend(x + w + 8 + Svg.textWidth(Svg.elide(cond, MAX_TEXT)), y + h);
                }
            }
            case SELECTION_DIVERGENCE, SELECTION_CONVERGENCE -> svg.line("sym", x, y, x + w, y);
            case SIMULTANEOUS_DIVERGENCE, SIMULTANEOUS_CONVERGENCE ->
                svg.line("sym", x, y, x + w, y).line("sym", x, y + 3, x + w, y + 3);
            case JUMP -> {
                svg.path(
                        "sym",
                        "M" + Svg.n(x) + " " + Svg.n(y) + " L" + Svg.n(x + w) + " " + Svg.n(y) + " L" + Svg.n(x + w / 2)
                                + " " + Svg.n(y + h) + " Z");
                String target = nonNull(e.name());
                svg.text("", x + w + 6, y + h / 2 + 4, null, target, null, null);
                extend(x + w + 6 + Svg.textWidth(target), y + h);
            }
            case ACTION_BLOCK -> {
                svg.rect("box", x, y, w, h, 0);
                int row = 0;
                for (String line : e.lines()) {
                    double ry = y + row * ROW_H;
                    if (row > 0) {
                        svg.line("sym", x, ry, x + w, ry);
                    }
                    svg.text("", x + 6, ry + ROW_H / 2.0 + 4, null, Svg.elide(line, MAX_TEXT), line, null);
                    row++;
                }
            }
        }
        // where a viewer outlines a finding (and the element's hover area)
        svg.rect("hit", x - 4, y - 3, w + 8, h + 6, 0);
        svg.close();
        extend(x, y);
        extend(x + w, y + h);
    }

    private void extend(double x, double y) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
    }

    private static boolean isBranch(Kind k) {
        return k == Kind.SELECTION_DIVERGENCE
                || k == Kind.SELECTION_CONVERGENCE
                || k == Kind.SIMULTANEOUS_DIVERGENCE
                || k == Kind.SIMULTANEOUS_CONVERGENCE;
    }

    private static double centerX(Element e) {
        return e.x() + e.width() / 2.0;
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
