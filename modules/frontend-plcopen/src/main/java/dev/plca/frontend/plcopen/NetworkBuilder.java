package dev.plca.frontend.plcopen;

import dev.plca.ast.Expression;
import dev.plca.ast.Network;
import dev.plca.ast.Network.Element;
import dev.plca.ast.Network.Kind;
import dev.plca.ast.Network.Link;
import dev.plca.ast.Network.Modifier;
import dev.plca.ast.Network.Pin;
import dev.plca.core.source.Span;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link Network}s from the graphical elements of an LD or FBD body (or the graphical transition conditions of
 * an SFC body): one network per connected component, numbered top to bottom ({@code docs/grammar/plcopen.md} §5). Power
 * rails are not followed when grouping, since Beremiz draws one left rail for all rungs of a POU; a rail belongs to
 * every network it touches.
 */
final class NetworkBuilder {

    private NetworkBuilder() {}

    static List<Network> build(PlcOpenFrontend.Run run, XNode body, Network.Language language) {
        return components(run, elements(run, body), language);
    }

    /** The graphical elements among {@code body}'s children (SFC elements and comments are left out). */
    static List<Element> elements(PlcOpenFrontend.Run run, XNode body) {
        List<Element> out = new ArrayList<>();
        for (XNode n : body.children) {
            Element e = element(run, n);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    static Element element(PlcOpenFrontend.Run run, XNode n) {
        Kind kind = switch (n.tag) {
            case "leftPowerRail" -> Kind.LEFT_RAIL;
            case "rightPowerRail" -> Kind.RIGHT_RAIL;
            case "contact" -> Kind.CONTACT;
            case "coil" -> Kind.COIL;
            case "block" -> Kind.BLOCK;
            case "inVariable" -> Kind.IN_VARIABLE;
            case "outVariable" -> Kind.OUT_VARIABLE;
            case "inOutVariable" -> Kind.IN_OUT_VARIABLE;
            case "connector" -> Kind.CONNECTOR;
            case "continuation" -> Kind.CONTINUATION;
            case "jump" -> Kind.JUMP;
            case "label" -> Kind.LABEL;
            case "return" -> Kind.RETURN;
            default -> null;
        };
        if (kind == null) {
            return null;
        }
        int id = intAttr(n, "localId", -1);
        Integer order = intAttr(n, "executionOrderId", 0) > 0 ? intAttr(n, "executionOrderId", 0) : null;
        List<Pin> inputs = new ArrayList<>();
        List<Pin> outputs = new ArrayList<>();
        Expression value = null;
        String name = null;
        String instance = null;
        boolean negated = n.flag("negated");
        switch (kind) {
            case LEFT_RAIL -> outputs.add(out(null, false, n.child("connectionPointOut")));
            case RIGHT_RAIL -> {
                List<Link> links = new ArrayList<>();
                for (XNode in : n.children("connectionPointIn")) {
                    links.addAll(links(in));
                }
                XNode first = n.child("connectionPointIn");
                inputs.add(new Pin(null, false, links, first == null ? null : at(first)));
            }
            case CONTACT, COIL -> {
                inputs.add(in(null, false, n.child("connectionPointIn")));
                outputs.add(out(null, false, n.child("connectionPointOut")));
                XNode v = n.child("variable");
                value = v == null ? new Expression.Invalid(run.elementSpan(n)) : run.expressions.content(v);
            }
            case BLOCK -> {
                name = n.attr("typeName");
                String inst = n.attr("instanceName");
                instance = inst == null || inst.isBlank() ? null : inst;
                XNode ins = n.child("inputVariables");
                if (ins != null) {
                    for (XNode v : ins.children("variable")) {
                        inputs.add(in(v.attr("formalParameter"), v.flag("negated"), v.child("connectionPointIn")));
                    }
                }
                XNode inouts = n.child("inOutVariables");
                if (inouts != null) {
                    for (XNode v : inouts.children("variable")) {
                        inputs.add(in(v.attr("formalParameter"), v.flag("negated"), v.child("connectionPointIn")));
                        outputs.add(out(v.attr("formalParameter"), false, v.child("connectionPointOut")));
                    }
                }
                XNode outs = n.child("outputVariables");
                if (outs != null) {
                    for (XNode v : outs.children("variable")) {
                        outputs.add(out(v.attr("formalParameter"), v.flag("negated"), v.child("connectionPointOut")));
                    }
                }
                if (instance != null) {
                    value = run.expressions.attribute(n.attribute("instanceName"), run.elementSpan(n));
                }
            }
            case IN_VARIABLE, OUT_VARIABLE, IN_OUT_VARIABLE -> {
                XNode ex = n.child("expression");
                value = ex == null ? new Expression.Invalid(run.elementSpan(n)) : run.expressions.content(ex);
                if (kind != Kind.IN_VARIABLE) {
                    boolean neg = kind == Kind.OUT_VARIABLE ? negated : n.flag("negatedIn");
                    inputs.add(in(null, neg, n.child("connectionPointIn")));
                }
                if (kind != Kind.OUT_VARIABLE) {
                    boolean neg = kind == Kind.IN_VARIABLE ? negated : n.flag("negatedOut");
                    outputs.add(out(null, neg, n.child("connectionPointOut")));
                }
            }
            case CONNECTOR -> {
                name = n.attr("name");
                inputs.add(in(null, false, n.child("connectionPointIn")));
            }
            case CONTINUATION -> {
                name = n.attr("name");
                outputs.add(out(null, false, n.child("connectionPointOut")));
            }
            case JUMP, RETURN -> {
                name = n.attr("label");
                inputs.add(in(null, false, n.child("connectionPointIn")));
            }
            case LABEL -> name = n.attr("label");
        }
        return new Element(
                id,
                kind,
                name,
                instance,
                value,
                negated,
                modifier(n.attr("storage")),
                modifier(n.attr("edge")),
                order,
                n.x == Integer.MIN_VALUE ? 0 : n.x,
                n.y == Integer.MIN_VALUE ? 0 : n.y,
                size(n, "width"),
                size(n, "height"),
                inputs,
                outputs,
                run.elementSpan(n));
    }

    private static Pin in(String name, boolean negated, XNode connectionPointIn) {
        return new Pin(name, negated, links(connectionPointIn), at(connectionPointIn));
    }

    private static Pin out(String name, boolean negated, XNode connectionPointOut) {
        return new Pin(name, negated, List.of(), at(connectionPointOut));
    }

    /** A connection point's {@code relPosition}, null when absent. */
    private static Network.Point at(XNode connectionPoint) {
        return connectionPoint == null || connectionPoint.x == Integer.MIN_VALUE
                ? null
                : new Network.Point(connectionPoint.x, connectionPoint.y);
    }

    static int size(XNode n, String name) {
        String v = n.attr(name);
        try {
            return v == null ? 0 : Math.max(0, (int) Math.round(Double.parseDouble(v.strip())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static List<Link> links(XNode connectionPointIn) {
        List<Link> out = new ArrayList<>();
        if (connectionPointIn == null) {
            return out;
        }
        for (XNode c : connectionPointIn.children("connection")) {
            int ref = intAttr(c, "refLocalId", Integer.MIN_VALUE);
            if (ref != Integer.MIN_VALUE) {
                String p = c.attr("formalParameter");
                List<Network.Point> route = new ArrayList<>(c.routeLength / 2);
                for (int i = 0; i + 1 < c.routeLength; i += 2) {
                    route.add(new Network.Point(c.route[i], c.route[i + 1]));
                }
                out.add(new Link(ref, p == null || p.isBlank() || p.equalsIgnoreCase("none") ? null : p, route));
            }
        }
        return out;
    }

    private static Modifier modifier(String s) {
        return switch (PlcOpenFrontend.lower(s)) {
            case "set" -> Modifier.SET;
            case "reset" -> Modifier.RESET;
            case "rising" -> Modifier.RISING;
            case "falling" -> Modifier.FALLING;
            default -> Modifier.NONE;
        };
    }

    private static int intAttr(XNode n, String name, int fallback) {
        String v = n.attr(name);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Connected components (not through rails; connectors join their continuations), top to bottom. */
    static List<Network> components(PlcOpenFrontend.Run run, List<Element> elements, Network.Language language) {
        Map<Integer, Integer> index = new HashMap<>();
        for (int i = 0; i < elements.size(); i++) {
            index.putIfAbsent(elements.get(i).localId(), i);
        }
        int[] parent = new int[elements.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        Map<String, List<Integer>> named = new HashMap<>();
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            if (isRail(e)) {
                continue;
            }
            for (Pin p : e.inputs()) {
                for (Link l : p.links()) {
                    Integer j = index.get(l.source());
                    if (j != null && !isRail(elements.get(j))) {
                        union(parent, i, j);
                    }
                }
            }
            if ((e.kind() == Kind.CONNECTOR || e.kind() == Kind.CONTINUATION) && e.name() != null) {
                named.computeIfAbsent(e.name().toLowerCase(java.util.Locale.ROOT), k -> new ArrayList<>())
                        .add(i);
            }
        }
        for (List<Integer> group : named.values()) {
            for (int k = 1; k < group.size(); k++) {
                union(parent, group.get(0), group.get(k));
            }
        }
        Map<Integer, List<Element>> groups = new LinkedHashMap<>();
        for (int i = 0; i < elements.size(); i++) {
            if (!isRail(elements.get(i))) {
                groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(elements.get(i));
            }
        }
        // rails join every network that touches them, for drawing
        for (List<Element> g : groups.values()) {
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            g.forEach(e -> ids.add(e.localId()));
            List<Element> rails = new ArrayList<>();
            for (Element r : elements) {
                if (!isRail(r)) {
                    continue;
                }
                boolean touches =
                        r.inputs().stream().flatMap(p -> p.links().stream()).anyMatch(l -> ids.contains(l.source()))
                                || g.stream()
                                        .flatMap(e -> e.inputs().stream())
                                        .flatMap(p -> p.links().stream())
                                        .anyMatch(l -> l.source() == r.localId());
                if (touches) {
                    rails.add(r);
                }
            }
            g.addAll(rails);
        }
        List<List<Element>> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparingInt(NetworkBuilder::top)
                .thenComparingInt(
                        g -> g.stream().mapToInt(Element::localId).min().orElse(0)));
        List<Network> out = new ArrayList<>();
        for (List<Element> g : ordered) {
            out.add(new Network(language, g, span(g)));
        }
        return out;
    }

    private static boolean isRail(Element e) {
        return e.kind() == Kind.LEFT_RAIL || e.kind() == Kind.RIGHT_RAIL;
    }

    private static int top(List<Element> g) {
        return g.stream().filter(e -> !isRail(e)).mapToInt(Element::y).min().orElse(0);
    }

    private static Span span(List<Element> g) {
        Span s = null;
        for (Element e : g) {
            if (isRail(e)) {
                continue;
            }
            s = s == null ? e.span() : s.union(e.span());
        }
        return s != null ? s : g.getFirst().span();
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }
}
