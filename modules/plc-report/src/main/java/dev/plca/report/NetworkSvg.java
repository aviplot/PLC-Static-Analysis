package dev.plca.report;

import dev.plca.ast.Network;
import dev.plca.ast.Network.Element;
import dev.plca.ast.Network.Kind;
import dev.plca.ast.Network.Link;
import dev.plca.ast.Network.Modifier;
import dev.plca.ast.Network.Pin;
import dev.plca.ast.Network.Point;
import dev.plca.ast.StText;
import dev.plca.ast.Statement;
import dev.plca.core.source.SourceRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a PLCopen LD or FBD network as SVG at the coordinates its author gave: element boxes from {@code position},
 * {@code width} and {@code height}, pins from {@code relPosition}, wires along the {@code connection} points when the
 * producer stored them, else as an orthogonal elbow between the two pins. Elements carry {@code data-range}.
 */
public final class NetworkSvg {

    static final int MARGIN = 24;
    static final int MAX_TEXT = 32;

    private final Svg svg;
    private final Map<Integer, Element> byId = new HashMap<>();
    private double minX = Double.MAX_VALUE;
    private double minY = Double.MAX_VALUE;
    private double maxX = -Double.MAX_VALUE;
    private double maxY = -Double.MAX_VALUE;

    private NetworkSvg(SourceRegistry registry) {
        this.svg = new Svg(registry);
    }

    public static String network(Statement.Network n, SourceRegistry registry) {
        return new NetworkSvg(registry).render(n.network());
    }

    private String render(Network network) {
        List<Element> elements = network.elements().stream().anyMatch(e -> e.width() > 0)
                ? network.elements()
                : layout(network.elements());
        for (Element e : elements) {
            byId.put(e.localId(), e);
        }
        svg.open("net " + network.language().name().toLowerCase(java.util.Locale.ROOT), network.span());
        for (Element e : elements) {
            wires(e);
        }
        for (Element e : elements) {
            element(e);
        }
        svg.close();
        if (minX > maxX) {
            minX = minY = 0;
            maxX = maxY = 40;
        }
        return svg.document(
                minX - MARGIN, minY - MARGIN, maxX - minX + 2 * MARGIN, maxY - minY + 2 * MARGIN, "network");
    }

    /**
     * Positions for a network whose producer gave no sizes (CODESYS writes grid cells, not pixels): columns by the
     * longest path from the network's sources (feedback edges ignored), elements stacked in their column in the
     * producer's order. Pin positions and wire routes are dropped; wires become elbows.
     */
    static List<Element> layout(List<Element> elements) {
        Map<Integer, Element> ids = new HashMap<>();
        for (Element e : elements) {
            ids.put(e.localId(), e);
        }
        Map<Integer, Integer> column = new HashMap<>();
        for (Element e : elements) {
            column(e, ids, column, new java.util.HashSet<>());
        }
        Map<Integer, Integer> nextY = new HashMap<>();
        List<Element> out = new java.util.ArrayList<>(elements.size());
        for (Element e : elements) {
            Element sized = new Element(
                    e.localId(),
                    e.kind(),
                    e.name(),
                    e.instance(),
                    e.value(),
                    e.negated(),
                    e.storage(),
                    e.edge(),
                    e.order(),
                    0,
                    0,
                    0,
                    0,
                    strip(e.inputs()),
                    strip(e.outputs()),
                    e.span());
            int w = autoWidth(sized);
            int h = height(sized);
            int c = column.getOrDefault(e.localId(), 0);
            int y = nextY.getOrDefault(c, 0);
            nextY.put(c, y + h + 30);
            out.add(new Element(
                    e.localId(),
                    e.kind(),
                    e.name(),
                    e.instance(),
                    e.value(),
                    e.negated(),
                    e.storage(),
                    e.edge(),
                    e.order(),
                    c * 170 + (170 - w) / 2,
                    y + 16,
                    w,
                    h,
                    sized.inputs(),
                    sized.outputs(),
                    e.span()));
        }
        return out;
    }

    private static int column(
            Element e, Map<Integer, Element> ids, Map<Integer, Integer> memo, java.util.Set<Integer> path) {
        Integer known = memo.get(e.localId());
        if (known != null) {
            return known;
        }
        if (!path.add(e.localId())) {
            return 0; // feedback
        }
        int c = 0;
        for (Pin p : e.inputs()) {
            for (Link l : p.links()) {
                Element source = ids.get(l.source());
                if (source != null) {
                    c = Math.max(c, column(source, ids, memo, path) + 1);
                }
            }
        }
        path.remove(e.localId());
        memo.put(e.localId(), c);
        return c;
    }

    private static List<Pin> strip(List<Pin> pins) {
        return pins.stream()
                .map(p -> new Pin(
                        p.name(),
                        p.negated(),
                        p.links().stream()
                                .map(l -> new Link(l.source(), l.sourcePin()))
                                .toList()))
                .toList();
    }

    private static int autoWidth(Element e) {
        return switch (e.kind()) {
            case IN_VARIABLE, OUT_VARIABLE, IN_OUT_VARIABLE, CONNECTOR, CONTINUATION -> {
                String v = e.value() == null ? String.valueOf(e.name()) : StText.expression(e.value());
                yield Math.min(MAX_TEXT, v.length()) * Svg.CHAR_W + 20;
            }
            case BLOCK -> {
                int pins = 0;
                for (Pin p : e.inputs()) {
                    pins = Math.max(pins, p.name() == null ? 0 : p.name().length());
                }
                int outs = 0;
                for (Pin p : e.outputs()) {
                    outs = Math.max(outs, p.name() == null ? 0 : p.name().length());
                }
                yield Math.max(
                        80,
                        Math.max(
                                (pins + outs) * Svg.CHAR_W + 24,
                                String.valueOf(e.name()).length() * Svg.CHAR_W + 24));
            }
            default -> width(e);
        };
    }

    private void extend(double x, double y) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
    }

    // ---- geometry ------------------------------------------------------------------------------

    static int width(Element e) {
        if (e.width() > 0) {
            return e.width();
        }
        return switch (e.kind()) {
            case LEFT_RAIL, RIGHT_RAIL -> 4;
            case CONTACT, COIL -> 30;
            case BLOCK -> 80;
            default -> 60;
        };
    }

    static int height(Element e) {
        if (e.height() > 0) {
            return e.height();
        }
        return switch (e.kind()) {
            case LEFT_RAIL, RIGHT_RAIL -> 40;
            case BLOCK -> 30 + 20 * Math.max(e.inputs().size(), e.outputs().size());
            default -> 20;
        };
    }

    /** Absolute position of input ({@code input = true}) or output pin {@code k}. */
    private static double[] pin(Element e, boolean input, int k) {
        List<Pin> pins = input ? e.inputs() : e.outputs();
        Pin p = k < pins.size() ? pins.get(k) : null;
        if (p != null && p.at() != null) {
            return new double[] {e.x() + p.at().x(), e.y() + p.at().y()};
        }
        double x = input ? e.x() : e.x() + width(e);
        if (e.kind() == Kind.BLOCK) {
            return new double[] {x, e.y() + 30 + 20 * k};
        }
        return new double[] {x, e.y() + height(e) / 2.0};
    }

    private static int outputIndex(Element source, String pinName) {
        if (pinName != null) {
            for (int k = 0; k < source.outputs().size(); k++) {
                if (pinName.equalsIgnoreCase(source.outputs().get(k).name())) {
                    return k;
                }
            }
        }
        return 0;
    }

    // ---- wires ---------------------------------------------------------------------------------

    private void wires(Element e) {
        for (int k = 0; k < e.inputs().size(); k++) {
            double[] to = pin(e, true, k);
            for (Link link : e.inputs().get(k).links()) {
                List<Point> route = link.route();
                double[] xy;
                if (route.size() >= 2) {
                    xy = new double[route.size() * 2];
                    for (int i = 0; i < route.size(); i++) {
                        xy[2 * i] = route.get(i).x();
                        xy[2 * i + 1] = route.get(i).y();
                    }
                } else {
                    Element source = byId.get(link.source());
                    if (source == null) {
                        continue;
                    }
                    double[] from = pin(source, false, outputIndex(source, link.sourcePin()));
                    if (from[0] < to[0] - 8) {
                        double mid = (from[0] + to[0]) / 2;
                        xy = new double[] {to[0], to[1], mid, to[1], mid, from[1], from[0], from[1]};
                    } else {
                        // feedback: around and below both elements instead of through them
                        double below = Math.max(e.y() + height(e), source.y() + height(source)) + 12;
                        xy = new double[] {
                            to[0],
                            to[1],
                            to[0] - 10,
                            to[1],
                            to[0] - 10,
                            below,
                            from[0] + 10,
                            below,
                            from[0] + 10,
                            from[1],
                            from[0],
                            from[1]
                        };
                    }
                }
                for (int i = 0; i + 1 < xy.length; i += 2) {
                    extend(xy[i], xy[i + 1]);
                }
                svg.polyline("w", xy);
            }
        }
    }

    // ---- elements ------------------------------------------------------------------------------

    private void element(Element e) {
        double x = e.x();
        double y = e.y();
        double w = width(e);
        double h = height(e);
        extend(x, y);
        extend(x + w, y + h);
        svg.open("el k-" + e.kind().name().toLowerCase(java.util.Locale.ROOT), e.span());
        svg.rect("hit", x - 2, y - 2, w + 4, h + 4, 0);
        String value = e.value() == null ? null : StText.expression(e.value());
        double cy = y + h / 2;
        double cx = x + w / 2;
        switch (e.kind()) {
            case LEFT_RAIL, RIGHT_RAIL -> svg.rect("rail", x, y, Math.min(w, 4), h, 0);
            case CONTACT -> {
                label(cx, y - 5, value, e);
                svg.line("w", x, cy, cx - 6, cy);
                svg.line("w", cx + 6, cy, x + w, cy);
                svg.line("sym", cx - 6, cy - 9, cx - 6, cy + 9);
                svg.line("sym", cx + 6, cy - 9, cx + 6, cy + 9);
                inner(e, cx, cy);
            }
            case COIL -> {
                label(cx, y - 5, value, e);
                svg.line("w", x, cy, cx - 9, cy);
                svg.line("w", cx + 9, cy, x + w, cy);
                svg.path("sym", "M" + Svg.n(cx - 5) + "," + Svg.n(cy - 9) + " a12,12 0 0,0 0,18");
                svg.path("sym", "M" + Svg.n(cx + 5) + "," + Svg.n(cy - 9) + " a12,12 0 0,1 0,18");
                inner(e, cx, cy);
            }
            case BLOCK -> {
                svg.rect("box", x, y, w, h, 2);
                svg.text("mn", cx, y + 14, "middle", e.name() == null ? "?" : e.name(), null, null);
                if (e.instance() != null) {
                    String inst = Svg.elide(e.instance(), MAX_TEXT);
                    svg.text(
                            "op",
                            cx,
                            y - 5,
                            "middle",
                            inst,
                            e.instance(),
                            e.value() == null ? null : e.value().span());
                }
                for (int k = 0; k < e.inputs().size(); k++) {
                    double[] p = pin(e, true, k);
                    pinLabel(e.inputs().get(k), p, true);
                }
                for (int k = 0; k < e.outputs().size(); k++) {
                    double[] p = pin(e, false, k);
                    pinLabel(e.outputs().get(k), p, false);
                }
            }
            case IN_VARIABLE, OUT_VARIABLE, IN_OUT_VARIABLE -> {
                svg.rect("box", x, y, w, h, Math.min(8, h / 2));
                String shown = value == null ? "" : Svg.elide(value, Math.max(4, (int) (w / Svg.CHAR_W) + 6));
                svg.text(
                        "op",
                        cx,
                        cy + 4,
                        "middle",
                        shown,
                        value,
                        e.value() == null ? null : e.value().span());
                negation(e);
            }
            case CONNECTOR, CONTINUATION -> {
                double tip = Math.min(10, w / 3);
                String d = e.kind() == Kind.CONNECTOR
                        ? "M" + Svg.n(x) + "," + Svg.n(y) + " H" + Svg.n(x + w - tip) + " L" + Svg.n(x + w) + ","
                                + Svg.n(cy) + " L" + Svg.n(x + w - tip) + "," + Svg.n(y + h) + " H" + Svg.n(x) + " Z"
                        : "M" + Svg.n(x) + "," + Svg.n(y) + " H" + Svg.n(x + w) + " V" + Svg.n(y + h) + " H" + Svg.n(x)
                                + " L" + Svg.n(x + tip) + "," + Svg.n(cy) + " Z";
                svg.path("box", d);
                svg.text("op", cx, cy + 4, "middle", Svg.elide(String.valueOf(e.name()), MAX_TEXT), e.name(), null);
            }
            case JUMP -> {
                svg.path(
                        "sym",
                        "M" + Svg.n(x) + "," + Svg.n(cy) + " H" + Svg.n(x + 14) + " M" + Svg.n(x + 8) + ","
                                + Svg.n(cy - 5) + " L" + Svg.n(x + 14) + "," + Svg.n(cy) + " L" + Svg.n(x + 8) + ","
                                + Svg.n(cy + 5));
                svg.text("op", x + 18, cy + 4, null, ">>" + e.name(), null, null);
            }
            case LABEL -> svg.text("mn", x, cy + 4, null, e.name() + ":", null, null);
            case RETURN -> {
                svg.rect("box", x, y, w, h, 2);
                svg.text("mn", cx, cy + 4, "middle", "RETURN", null, null);
            }
        }
        if (e.order() != null && e.kind() == Kind.BLOCK) {
            svg.circle("box", x + w, y, 7);
            svg.text("num", x + w, y + 4, "middle", e.order().toString(), null, null);
        }
        svg.close();
    }

    private void label(double cx, double y, String value, Element e) {
        if (value != null) {
            svg.text(
                    "op",
                    cx,
                    y,
                    "middle",
                    Svg.elide(value, MAX_TEXT),
                    value,
                    e.value().span());
        }
    }

    /** The letter inside a contact or coil: negation, set/reset, edge. */
    private void inner(Element e, double cx, double cy) {
        String letter = e.storage() == Modifier.SET
                ? "S"
                : e.storage() == Modifier.RESET
                        ? "R"
                        : e.edge() == Modifier.RISING ? "P" : e.edge() == Modifier.FALLING ? "N" : null;
        if (e.negated()) {
            svg.line("sym", cx - 4, cy + 7, cx + 4, cy - 7);
        }
        if (letter != null) {
            svg.text("mn", cx, cy + 4, "middle", letter, null, null);
        }
    }

    private void pinLabel(Pin p, double[] at, boolean input) {
        if (p.name() != null) {
            svg.text("lbl", at[0] + (input ? 4 : -4), at[1] + 4, input ? null : "end", p.name(), null, null);
        }
        if (p.negated()) {
            svg.circle("sym", at[0] + (input ? -4 : 4), at[1], 4);
        }
    }

    private void negation(Element e) {
        for (int k = 0; k < e.inputs().size(); k++) {
            if (e.inputs().get(k).negated()) {
                double[] p = pin(e, true, k);
                svg.circle("sym", p[0] - 4, p[1], 4);
            }
        }
        for (int k = 0; k < e.outputs().size(); k++) {
            if (e.outputs().get(k).negated()) {
                double[] p = pin(e, false, k);
                svg.circle("sym", p[0] + 4, p[1], 4);
            }
        }
    }
}
