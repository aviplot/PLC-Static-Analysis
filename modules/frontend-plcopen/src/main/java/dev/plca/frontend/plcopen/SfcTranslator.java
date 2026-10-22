package dev.plca.frontend.plcopen;

import dev.plca.ast.Expression;
import dev.plca.ast.Identifier;
import dev.plca.ast.Network;
import dev.plca.ast.Sfc;
import dev.plca.ast.Statement;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.source.Span;
import dev.plca.frontend.st.kit.NetworkLowering;
import dev.plca.frontend.st.kit.SfcLowering;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An SFC body ({@code docs/grammar/plcopen.md} §6): the {@link Sfc} IR (divergences and jump steps resolved into the
 * source and target steps of each transition) and its lowering to guarded statements — step flags {@code S.X} as
 * implicit variables, actions under {@code IF S.X}, stored actions in synthetic flags, transitions as {@code IF A.X AND
 * cond THEN A.X := FALSE; B.X := TRUE;}.
 */
final class SfcTranslator {

    private final PlcOpenFrontend.Run run;
    private final XNode body;
    private final Map<Integer, XNode> byId = new HashMap<>();
    private final Map<Integer, List<Integer>> successors = new HashMap<>();
    private final Map<String, XNode> stepsByName = new HashMap<>();
    private NetworkLowering graphical;

    private SfcTranslator(PlcOpenFrontend.Run run, XNode body) {
        this.run = run;
        this.body = body;
    }

    static Bodies.Translated translate(PlcOpenFrontend.Run run, XNode body, String owner) {
        return new SfcTranslator(run, body).run();
    }

    private Bodies.Translated run() {
        for (XNode n : body.children) {
            int id = localId(n);
            if (id != Integer.MIN_VALUE) {
                byId.putIfAbsent(id, n);
            }
            for (int pred : predecessors(n)) {
                successors.computeIfAbsent(pred, k -> new ArrayList<>()).add(id);
            }
            if (n.tag.equals("step") && n.attr("name") != null) {
                stepsByName.putIfAbsent(n.attr("name").toLowerCase(Locale.ROOT), n);
            }
            if (n.tag.equals("macroStep")) {
                run.sink.report(Diagnostic.warning(
                        PlcOpenCodes.SFC_UNSUPPORTED, "macro steps are not analysed", run.elementSpan(n)));
            }
        }
        // transition conditions drawn as graphics (contacts, variables) in the chart itself
        List<Network.Element> graphics = NetworkBuilder.elements(run, body);
        if (!graphics.isEmpty()) {
            Network net = new Network(Network.Language.LD, graphics, run.elementSpan(body));
            graphical = new NetworkLowering(run.sink, run::nextTemp, net);
        }

        List<Sfc.Step> steps = new ArrayList<>();
        Map<String, List<Sfc.Action>> actionsByStep = new LinkedHashMap<>();
        for (XNode n : body.children) {
            if (n.tag.equals("actionBlock")) {
                for (int pred : predecessors(n)) {
                    XNode step = byId.get(pred);
                    if (step != null && step.tag.equals("step")) {
                        actionsByStep
                                .computeIfAbsent(lower(step.attr("name")), k -> new ArrayList<>())
                                .addAll(actions(n));
                    }
                }
            }
        }
        for (XNode n : body.children) {
            if (n.tag.equals("step")) {
                steps.add(new Sfc.Step(
                        run.name(n, "name"),
                        n.flag("initialStep"),
                        actionsByStep.getOrDefault(lower(n.attr("name")), List.of()),
                        run.elementSpan(n)));
            }
        }
        List<Sfc.Transition> transitions = new ArrayList<>();
        for (XNode n : body.children) {
            if (n.tag.equals("transition")) {
                transitions.add(new Sfc.Transition(
                        stepNames(sources(localId(n), new LinkedHashSet<>())),
                        stepNames(targets(localId(n), new LinkedHashSet<>())),
                        condition(n),
                        run.elementSpan(n)));
            }
        }
        Span span = run.elementSpan(body);
        Sfc chart = new Sfc(steps, transitions, drawing(), span);
        List<Statement> lowered = new ArrayList<>();
        if (graphical != null) {
            lowered.addAll(graphical.statements());
        }
        lowered.addAll(lower(chart));
        return new Bodies.Translated(
                List.of(new Statement.Chart(chart, lowered, span)),
                "SFC",
                List.of(SfcLowering.stepVariables(steps, span)));
    }

    // ---- drawing (M10) -----------------------------------------------------------------------------

    /** The chart's elements at the author's coordinates, with the links into them as drawn. */
    private Sfc.Drawing drawing() {
        List<Sfc.Element> out = new ArrayList<>();
        for (XNode n : body.children) {
            Sfc.Kind kind = switch (n.tag) {
                case "step" -> Sfc.Kind.STEP;
                case "macroStep" -> Sfc.Kind.MACRO_STEP;
                case "transition" -> Sfc.Kind.TRANSITION;
                case "selectionDivergence" -> Sfc.Kind.SELECTION_DIVERGENCE;
                case "selectionConvergence" -> Sfc.Kind.SELECTION_CONVERGENCE;
                case "simultaneousDivergence" -> Sfc.Kind.SIMULTANEOUS_DIVERGENCE;
                case "simultaneousConvergence" -> Sfc.Kind.SIMULTANEOUS_CONVERGENCE;
                case "jumpStep" -> Sfc.Kind.JUMP;
                case "actionBlock" -> Sfc.Kind.ACTION_BLOCK;
                default -> null;
            };
            if (kind == null) {
                continue;
            }
            String name = switch (kind) {
                case STEP, MACRO_STEP -> n.attr("name");
                case JUMP -> n.attr("targetName");
                default -> null;
            };
            List<String> lines = switch (kind) {
                case TRANSITION -> List.of(conditionText(n));
                case ACTION_BLOCK -> actionLines(n);
                default -> List.of();
            };
            List<Sfc.Link> inputs = new ArrayList<>();
            for (XNode in : n.children("connectionPointIn")) {
                for (Network.Link l : NetworkBuilder.links(in)) {
                    inputs.add(new Sfc.Link(l.source(), l.route()));
                }
            }
            out.add(new Sfc.Element(
                    localId(n),
                    kind,
                    name,
                    n.flag("initialStep"),
                    lines,
                    n.x == Integer.MIN_VALUE ? 0 : n.x,
                    n.y == Integer.MIN_VALUE ? 0 : n.y,
                    NetworkBuilder.size(n, "width"),
                    NetworkBuilder.size(n, "height"),
                    inputs,
                    run.elementSpan(n)));
        }
        return new Sfc.Drawing(out);
    }

    /** A transition's condition as text: the referenced transition, the inline ST, or that it is drawn. */
    private String conditionText(XNode transition) {
        XNode c = transition.child("condition");
        if (c == null) {
            return "";
        }
        XNode ref = c.child("reference");
        if (ref != null) {
            return PlcOpenFrontend.nonNull(ref.attr("name"));
        }
        XNode inline = c.child("inline");
        XNode st = inline == null ? null : inline.child("ST");
        if (st != null) {
            return run.text(run.textElement(st)).strip().replaceAll("\\s+", " ");
        }
        // drawn: the expression of the variable wired to the condition, if that is what it is
        XNode in = c.child("connectionPointIn");
        XNode conn = in == null ? null : in.child("connection");
        XNode source = conn == null ? null : byId.get(intAttr(conn, "refLocalId"));
        XNode ex = source == null ? null : source.child("expression");
        if (ex != null) {
            String t = run.text(ex).strip().replaceAll("\\s+", " ");
            if (!t.isEmpty()) {
                return t;
            }
        }
        return "(graphical condition)";
    }

    /** An action block's actions, one line each: qualifier, duration and the action's name (or "inline"). */
    private static List<String> actionLines(XNode block) {
        List<String> out = new ArrayList<>();
        for (XNode a : block.children("action")) {
            String q = PlcOpenFrontend.nonNull(a.attr("qualifier")).toUpperCase(Locale.ROOT);
            String d = PlcOpenFrontend.nonNull(a.attr("duration"));
            XNode ref = a.child("reference");
            String what = ref != null ? PlcOpenFrontend.nonNull(ref.attr("name")) : "(inline)";
            out.add(((q.isEmpty() ? "N" : q) + " " + (d.isEmpty() ? "" : d + " ") + what).strip());
        }
        return out;
    }

    // ---- topology -------------------------------------------------------------------------------

    /** Steps a transition leaves: back through selection divergences and simultaneous convergences. */
    private Set<XNode> sources(int id, Set<Integer> seen) {
        Set<XNode> out = new LinkedHashSet<>();
        XNode n = byId.get(id);
        if (n == null || !seen.add(id)) {
            return out;
        }
        for (int pred : predecessors(n)) {
            XNode p = byId.get(pred);
            if (p == null) {
                continue;
            }
            if (p.tag.equals("step")) {
                out.add(p);
            } else if (!p.tag.equals("transition")) {
                out.addAll(sources(pred, seen));
            }
        }
        return out;
    }

    /** Steps a transition enters: forward through divergences, convergences and jump steps. */
    private Set<XNode> targets(int id, Set<Integer> seen) {
        Set<XNode> out = new LinkedHashSet<>();
        if (!seen.add(id)) {
            return out;
        }
        for (int succ : successors.getOrDefault(id, List.of())) {
            XNode s = byId.get(succ);
            if (s == null) {
                continue;
            }
            switch (s.tag) {
                case "step" -> out.add(s);
                case "jumpStep" -> {
                    XNode target = stepsByName.get(lower(s.attr("targetName")));
                    if (target != null) {
                        out.add(target);
                    }
                }
                case "transition", "actionBlock" -> {}
                default -> out.addAll(targets(succ, seen));
            }
        }
        return out;
    }

    private List<Identifier> stepNames(Set<XNode> steps) {
        List<Identifier> out = new ArrayList<>();
        for (XNode s : steps) {
            out.add(run.name(s, "name"));
        }
        return out;
    }

    /** {@code inline} ST expression, {@code reference} to a transition member, or a wire to graphics. */
    private Expression condition(XNode transition) {
        XNode c = transition.child("condition");
        Span s = run.elementSpan(transition);
        if (c == null) {
            return bool(true, s);
        }
        XNode ref = c.child("reference");
        if (ref != null) {
            Identifier name = run.name(ref, "name");
            return new Expression.NameRef(name, false, name.span());
        }
        XNode inline = c.child("inline");
        if (inline != null) {
            XNode st = inline.child("ST");
            if (st != null) {
                return run.expressions.content(run.textElement(st));
            }
            run.sink.report(Diagnostic.warning(
                    PlcOpenCodes.SFC_UNSUPPORTED, "a graphical inline transition condition is not analysed", s));
            return new Expression.Invalid(s);
        }
        XNode in = c.child("connectionPointIn");
        if (in != null && graphical != null) {
            XNode conn = in.child("connection");
            int ref2 = conn == null ? Integer.MIN_VALUE : intAttr(conn, "refLocalId");
            if (ref2 != Integer.MIN_VALUE) {
                String pin = conn.attr("formalParameter");
                return graphical.value(new Network.Link(ref2, pin == null || pin.isBlank() ? null : pin));
            }
        }
        return bool(true, s);
    }

    private List<Sfc.Action> actions(XNode block) {
        List<Sfc.Action> out = new ArrayList<>();
        for (XNode a : block.children("action")) {
            String qualifier = PlcOpenFrontend.nonNull(a.attr("qualifier")).toUpperCase(Locale.ROOT);
            XNode ref = a.child("reference");
            Identifier reference = ref == null ? null : run.name(ref, "name");
            List<Statement> inline = null;
            XNode in = a.child("inline");
            if (in != null) {
                XNode lang = in.first();
                if (lang != null && lang.tag.equals("ST")) {
                    inline = Bodies.st(run, lang);
                } else if (lang != null && (lang.tag.equals("LD") || lang.tag.equals("FBD"))) {
                    inline = Bodies.networks(run, lang);
                }
            }
            out.add(new Sfc.Action(qualifier, a.attr("duration"), reference, inline, run.elementSpan(a)));
        }
        return out;
    }

    // ---- lowering -------------------------------------------------------------------------------

    private List<Statement> lower(Sfc chart) {
        return SfcLowering.lower(chart, this::actionBody);
    }

    private List<Statement> actionBody(Sfc.Action a) {
        if (a.inline() != null) {
            return a.inline();
        }
        if (a.reference() != null) {
            Identifier r = a.reference();
            Expression call = new Expression.Call(new Expression.NameRef(r, false, r.span()), List.of(), r.span());
            return List.of(new Statement.CallStatement(call, r.span()));
        }
        return List.of();
    }

    private static Expression bool(boolean v, Span s) {
        return new Expression.Literal(Expression.LiteralKind.BOOL, v ? "TRUE" : "FALSE", null, s);
    }

    private static List<Integer> predecessors(XNode n) {
        List<Integer> out = new ArrayList<>();
        for (XNode in : n.children("connectionPointIn")) {
            for (XNode c : in.children("connection")) {
                int ref = intAttr(c, "refLocalId");
                if (ref != Integer.MIN_VALUE) {
                    out.add(ref);
                }
            }
        }
        return out;
    }

    private static int localId(XNode n) {
        return intAttr(n, "localId");
    }

    private static int intAttr(XNode n, String name) {
        String v = n.attr(name);
        try {
            return v == null ? Integer.MIN_VALUE : Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static String lower(String s) {
        return PlcOpenFrontend.lower(s);
    }
}
