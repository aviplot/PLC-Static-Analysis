package dev.plca.frontend.plcopen;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.BinaryOp;
import dev.plca.ast.Identifier;
import dev.plca.ast.Initializer;
import dev.plca.ast.Network;
import dev.plca.ast.QualifiedName;
import dev.plca.ast.Sfc;
import dev.plca.ast.Statement;
import dev.plca.ast.TypeRef;
import dev.plca.ast.VarBlock;
import dev.plca.ast.VarDecl;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.source.Span;
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
            graphical = new NetworkLowering(run, net);
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
        Sfc chart = new Sfc(steps, transitions, span);
        List<Statement> lowered = new ArrayList<>();
        if (graphical != null) {
            lowered.addAll(graphical.statements());
        }
        lowered.addAll(lower(chart));
        return new Bodies.Translated(
                List.of(new Statement.Chart(chart, lowered, span)), "SFC", List.of(stepVariables(steps, span)));
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
        List<Statement> out = new ArrayList<>();
        Map<String, List<Statement>> stored = new LinkedHashMap<>();
        Map<String, Span> storedSpans = new HashMap<>();
        for (Sfc.Step step : chart.steps()) {
            Expression active = flag(step.name(), "X");
            int index = 0;
            for (Sfc.Action a : step.actions()) {
                Span s = a.span();
                List<Statement> action = actionBody(a);
                index++;
                // a referenced action is one flag wherever it is stored; an inline one belongs to its step
                String key = a.reference() != null
                        ? a.reference().key()
                        : step.name().key() + "_" + index;
                switch (a.qualifier()) {
                    case "S", "SD", "DS", "SL" -> {
                        Expression f = storedFlag(key, s);
                        out.add(guard(active, assign(f, bool(true, s), s), s));
                        stored.putIfAbsent(key, action);
                        storedSpans.putIfAbsent(key, s);
                    }
                    case "R" -> out.add(guard(active, assign(storedFlag(key, s), bool(false, s), s), s));
                    case "P", "P1" ->
                        out.add(guard(NetworkLowering.edge(Network.Modifier.RISING, active, s), action, s));
                    case "P0" -> out.add(guard(NetworkLowering.edge(Network.Modifier.FALLING, active, s), action, s));
                    // N, none, and the timed L/D (timing not modelled: notes §9.3)
                    default -> out.add(guard(active, action, s));
                }
            }
        }
        stored.forEach((key, action) -> {
            Span s = storedSpans.get(key);
            out.add(guard(storedFlag(key, s), action, s));
        });
        for (Sfc.Transition t : chart.transitions()) {
            Span s = t.span();
            Expression cond = null;
            for (Identifier from : t.from()) {
                Expression x = flag(from, "X");
                cond = cond == null ? x : new Expression.Binary(BinaryOp.AND, cond, x, s);
            }
            Expression c = t.condition();
            cond = cond == null ? c : new Expression.Binary(BinaryOp.AND, cond, c, s);
            List<Statement> fire = new ArrayList<>();
            for (Identifier from : t.from()) {
                fire.add(assign(flag(from, "X"), bool(false, s), s));
            }
            for (Identifier to : t.to()) {
                fire.add(assign(flag(to, "X"), bool(true, s), s));
            }
            out.add(new Statement.If(List.of(new Statement.Branch(cond, fire, s)), null, s));
        }
        return out;
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

    private Expression storedFlag(String key, Span s) {
        String name = "act_" + key.replaceAll("[^A-Za-z0-9_]", "_");
        return new Expression.NameRef(new Identifier(name, Identifier.Form.SYNTHETIC, s), false, s);
    }

    /** {@code S.X}: the step's implicit flag. */
    private static Expression flag(Identifier step, String member) {
        Expression s = new Expression.NameRef(step, false, step.span());
        return new Expression.MemberAccess(s, new Identifier(member, step.span()), step.span());
    }

    /** Implicit step variables {@code S : STRUCT X : BOOL; T : TIME; END_STRUCT} (initial step: X := TRUE). */
    private static VarBlock stepVariables(List<Sfc.Step> steps, Span span) {
        List<VarDecl> vars = new ArrayList<>();
        for (Sfc.Step st : steps) {
            Span s = st.span();
            List<VarDecl> members = List.of(member("X", "BOOL", s), member("T", "TIME", s));
            Initializer init = st.initial()
                    ? new Initializer.StructInit(
                            List.of(new Initializer.FieldInit(
                                    new Identifier("X", s),
                                    new Expression.Literal(Expression.LiteralKind.BOOL, "TRUE", null, s),
                                    s)),
                            s)
                    : null;
            vars.add(new VarDecl(st.name(), null, null, new TypeRef.StructType(members, s), init, false, List.of(), s));
        }
        return new VarBlock(VarBlock.Section.VAR, Set.of(), vars, List.of(), span);
    }

    private static VarDecl member(String name, String type, Span s) {
        TypeRef t = new TypeRef.Named(
                new QualifiedName(List.of(new Identifier(type, s)), s), List.of(), null, null, null, s);
        return new VarDecl(new Identifier(name, s), null, null, t, null, false, List.of(), s);
    }

    private static Statement guard(Expression cond, Statement s, Span span) {
        return new Statement.If(List.of(new Statement.Branch(cond, List.of(s), span)), null, span);
    }

    private static Statement guard(Expression cond, List<Statement> body, Span span) {
        return new Statement.If(List.of(new Statement.Branch(cond, body, span)), null, span);
    }

    private static Statement assign(Expression t, Expression v, Span s) {
        return new Statement.Assignment(t, Statement.AssignOp.ASSIGN, v, s);
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
