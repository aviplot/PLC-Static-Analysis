package dev.plca.frontend.st;

import dev.plca.ast.Expression;
import dev.plca.ast.Identifier;
import dev.plca.ast.Sfc;
import dev.plca.ast.Statement;
import dev.plca.ast.VarBlock;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.Span;
import dev.plca.frontend.st.kit.ArchiveNode;
import dev.plca.frontend.st.kit.ArchiveReader;
import dev.plca.frontend.st.kit.GraphContext;
import dev.plca.frontend.st.kit.SfcLowering;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * TwinCAT/CODESYS SFC bodies ({@code <Implementation><SFC>}, {@code docs/grammar/codesys.md} §9.4): a tree of segments
 * (sequences of steps, transitions, branches and jumps) turned into the {@link Sfc} IR and lowered by the shared
 * {@link SfcLowering}. Element properties are attributes keyed by GUID; the meaning of each GUID is inferred from the
 * corpus ({@code TODO(verify-format)}).
 */
final class SfcArchiveTranslator {

    static final String NAME = "{38391c6d-6d4a-42f8-8ee7-9f45e5adafa8}";
    static final String INITIAL = "{6844a48e-46c2-4cc8-a185-a478f3b99cc0}";
    static final String QUALIFIER = "{e174fc0d-80b0-4a9e-a530-ca239c249a50}";
    static final String ENTRY_ACTION = "{a6b08bd8-b696-47e3-9cbf-7408b61c9ff8}";
    static final String ACTIVE_ACTION = "{700a583f-b4d4-43e4-8c14-629c7cd3bec8}";
    static final String EXIT_ACTION = "{a2621e18-7de3-4ea6-ae6d-89e9e0b7befd}";
    /** On a branch: TRUE for a simultaneous (parallel) branch. Only a hint: the first element decides. */
    static final String PARALLEL = "{23bdaa98-72ec-41f7-817b-9dede5697086}";

    /** The chart's statement and the implicit step variables to declare in the POU. */
    record Result(List<Statement> body, VarBlock stepVariables) {}

    private final GraphContext g;
    private final ArchiveReader ar;
    /** Lower-case names of the POU's actions and transitions, and of its declared variables. */
    private final Set<String> actions;

    private final Set<String> transitionNames;
    private final Set<String> variables;

    private final List<Sfc.Step> steps = new ArrayList<>();
    private final List<TransitionDraft> transitions = new ArrayList<>();
    private final List<Sfc.Element> drawing = new ArrayList<>();
    private int nextDrawId = 1;

    SfcArchiveTranslator(
            GraphContext g, ArchiveReader ar, Set<String> actions, Set<String> transitionNames, Set<String> variables) {
        this.g = g;
        this.ar = ar;
        this.actions = actions;
        this.transitionNames = transitionNames;
        this.variables = variables;
    }

    /** A transition while its targets are being collected. */
    private static final class TransitionDraft {
        final List<Identifier> from;
        final List<Identifier> to = new ArrayList<>();
        final Expression condition;
        final Span span;

        TransitionDraft(List<Identifier> from, Expression condition, Span span) {
            this.from = from;
            this.condition = condition;
            this.span = span;
        }
    }

    /**
     * An open end of the chart walked so far: steps waiting for their transition, or transitions waiting for their
     * target steps; {@code draw} is the drawn element the end leaves from.
     */
    private record End(List<Identifier> steps, List<TransitionDraft> transitions, int draw) {}

    Result translate(ArchiveNode root) {
        ArchiveNode segment = root.member("Root");
        Span span =
                root.contentEnd() > root.offset ? g.span(root.offset, root.contentEnd()) : g.elementSpan(root.offset);
        List<End> open = walk(elements(segment), List.of());
        // the end of the main sequence goes back to the initial step
        Identifier initial = steps.stream()
                .filter(Sfc.Step::initial)
                .map(Sfc.Step::name)
                .findFirst()
                .orElse(steps.isEmpty() ? null : steps.getFirst().name());
        if (initial != null) {
            boolean dangling = false;
            for (End e : open) {
                for (TransitionDraft t : e.transitions()) {
                    if (t.to.isEmpty()) {
                        t.to.add(initial);
                        dangling = true;
                    }
                }
            }
            if (dangling) {
                draw(Sfc.Kind.JUMP, initial.name(), false, List.of(), sources(open), initial.span());
            }
        }
        List<Sfc.Transition> ts = new ArrayList<>();
        for (TransitionDraft t : transitions) {
            ts.add(new Sfc.Transition(t.from, t.to, t.condition, t.span));
        }
        Sfc chart = new Sfc(steps, ts, new Sfc.Drawing(drawing), span);
        List<Statement> lowered = SfcLowering.lower(chart, this::actionBody);
        return new Result(List.of(new Statement.Chart(chart, lowered, span)), SfcLowering.stepVariables(steps, span));
    }

    private List<End> walk(List<ArchiveNode> elements, List<End> in) {
        List<End> cur = in;
        for (ArchiveNode el : elements) {
            if (el.type == null) {
                continue;
            }
            switch (el.type) {
                case "SFCStep", "SFCMacro" -> cur = step(el, cur);
                case "SFCTransition" -> cur = transition(el, cur);
                case "SFCJump" -> {
                    Identifier target = name(el);
                    for (End e : cur) {
                        for (TransitionDraft t : e.transitions()) {
                            t.to.add(target);
                        }
                    }
                    draw(Sfc.Kind.JUMP, target.name(), false, List.of(), sources(cur), target.span());
                    cur = List.of();
                }
                case "SFCBranch" -> cur = branch(el, cur);
                default -> {}
            }
        }
        return cur;
    }

    private List<End> step(ArchiveNode el, List<End> cur) {
        Identifier name = name(el);
        boolean initial = ar.string(attribute(el, INITIAL)).equalsIgnoreCase("true");
        if (el.type.equals("SFCMacro")) {
            g.sink.report(Diagnostic.of(
                    "PLC-XML-105",
                    Severity.INFO,
                    "macro step '" + name.name() + "' is read as a plain step; its inner chart is not analysed",
                    g.elementSpan(el.offset)));
        }
        List<Sfc.Action> acts = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        addStepAction(el, ENTRY_ACTION, "P1", acts, lines);
        addStepAction(el, ACTIVE_ACTION, "N", acts, lines);
        for (ArchiveNode a : elements(el)) {
            if ("SFCActionAssociation".equals(a.type)) {
                ArchiveNode v = attribute(a, NAME);
                String q = ar.string(attribute(a, QUALIFIER)).strip();
                String qualifier = q;
                String duration = null;
                int sp = q.indexOf(' ');
                if (sp > 0) {
                    qualifier = q.substring(0, sp);
                    duration = q.substring(sp + 1).strip();
                }
                Sfc.Action act = action(v, qualifier.toUpperCase(Locale.ROOT), duration, g.elementSpan(a.offset));
                if (act != null) {
                    acts.add(act);
                    lines.add((q.isEmpty() ? "N" : q) + " " + ar.string(v));
                }
            }
        }
        addStepAction(el, EXIT_ACTION, "P0", acts, lines);
        Span span = g.elementSpan(el.offset);
        steps.add(new Sfc.Step(name, initial, acts, span));
        for (End e : cur) {
            for (TransitionDraft t : e.transitions()) {
                t.to.add(name);
            }
        }
        Sfc.Kind kind = el.type.equals("SFCMacro") ? Sfc.Kind.MACRO_STEP : Sfc.Kind.STEP;
        int id = draw(kind, name.name(), initial, List.of(), sources(cur), span);
        if (!lines.isEmpty()) {
            draw(Sfc.Kind.ACTION_BLOCK, null, false, lines, List.of(id), span);
        }
        return List.of(new End(List.of(name), List.of(), id));
    }

    private void addStepAction(
            ArchiveNode step, String guid, String qualifier, List<Sfc.Action> acts, List<String> lines) {
        ArchiveNode v = attribute(step, guid);
        if (v != null && !ar.string(v).isBlank()) {
            Sfc.Action a = action(v, qualifier, null, g.elementSpan(v.offset));
            if (a != null) {
                acts.add(a);
                lines.add(qualifier + " " + ar.string(v).strip());
            }
        }
    }

    /**
     * An action association: an action of the POU (by reference), a declared BOOL variable (set while the step is
     * active), or anything else as a call ({@code fbInstance.Action}).
     */
    private Sfc.Action action(ArchiveNode v, String qualifier, String duration, Span span) {
        String text = ar.string(v).strip();
        if (text.isEmpty()) {
            return null;
        }
        int[] r = ar.stringRange(v);
        Span s = r == null ? span : g.span(r[0], r[1]);
        String key = text.toLowerCase(Locale.ROOT);
        if (actions.contains(key)) {
            return new Sfc.Action(qualifier, duration, new Identifier(text, s), null, span);
        }
        Expression target = r == null ? new Expression.Invalid(s) : g.expression(r[0], r[1]);
        Statement st = variables.contains(key)
                ? new Statement.Assignment(
                        target,
                        Statement.AssignOp.ASSIGN,
                        new Expression.Literal(Expression.LiteralKind.BOOL, "TRUE", null, s),
                        s)
                : new Statement.CallStatement(new Expression.Call(target, List.of(), s), s);
        return new Sfc.Action(qualifier, duration, null, List.of(st), span);
    }

    private List<End> transition(ArchiveNode el, List<End> cur) {
        ArchiveNode v = attribute(el, NAME);
        Span span = g.elementSpan(el.offset);
        Expression condition = condition(v, span);
        List<TransitionDraft> made = new ArrayList<>();
        for (End e : cur) {
            if (!e.steps().isEmpty()) {
                TransitionDraft t = new TransitionDraft(e.steps(), condition, span);
                transitions.add(t);
                made.add(t);
            }
        }
        if (made.isEmpty()) {
            // a transition at the start of the chart or after another transition: nothing to fire from
            TransitionDraft t = new TransitionDraft(List.of(), condition, span);
            made.add(t);
        }
        int id = draw(Sfc.Kind.TRANSITION, null, false, List.of(ar.string(v)), sources(cur), span);
        return List.of(new End(List.of(), made, id));
    }

    /** A transition's condition: a transition of the POU by name, else an ST expression. */
    private Expression condition(ArchiveNode v, Span span) {
        String text = ar.string(v).strip();
        int[] r = ar.stringRange(v);
        if (r == null || text.isEmpty()) {
            return new Expression.Literal(Expression.LiteralKind.BOOL, "TRUE", null, span);
        }
        if (transitionNames.contains(text.toLowerCase(Locale.ROOT))) {
            Span s = g.span(r[0], r[1]);
            return new Expression.NameRef(new Identifier(text, s), false, s);
        }
        return g.expression(r[0], r[1]);
    }

    private List<End> branch(ArchiveNode el, List<End> cur) {
        List<ArchiveNode> segments = elements(el);
        boolean parallel = ar.string(attribute(el, PARALLEL)).equalsIgnoreCase("true");
        for (ArchiveNode seg : segments) {
            List<ArchiveNode> first = elements(seg);
            if (!first.isEmpty() && first.getFirst().type != null) {
                parallel = first.getFirst().type.equals("SFCStep")
                        || first.getFirst().type.equals("SFCMacro");
                break;
            }
        }
        Span span = g.elementSpan(el.offset);
        int div = draw(
                parallel ? Sfc.Kind.SIMULTANEOUS_DIVERGENCE : Sfc.Kind.SELECTION_DIVERGENCE,
                null,
                false,
                List.of(),
                sources(cur),
                span);
        List<End> into = new ArrayList<>();
        for (End e : cur) {
            into.add(new End(e.steps(), e.transitions(), div));
        }
        List<End> outs = new ArrayList<>();
        for (ArchiveNode seg : segments) {
            outs.addAll(walk(elements(seg), into));
        }
        if (!parallel || outs.isEmpty()) {
            return outs;
        }
        // simultaneous convergence: the next transition fires from every branch's last step
        LinkedHashSet<Identifier> union = new LinkedHashSet<>();
        List<End> rest = new ArrayList<>();
        for (End e : outs) {
            if (!e.steps().isEmpty()) {
                union.addAll(e.steps());
            } else {
                rest.add(e);
            }
        }
        int conv = draw(Sfc.Kind.SIMULTANEOUS_CONVERGENCE, null, false, List.of(), sources(outs), span);
        if (!union.isEmpty()) {
            rest.add(new End(List.copyOf(union), List.of(), conv));
        }
        return rest;
    }

    private List<Statement> actionBody(Sfc.Action a) {
        if (a.inline() != null) {
            return a.inline();
        }
        Identifier r = a.reference();
        Expression call = new Expression.Call(new Expression.NameRef(r, false, r.span()), List.of(), r.span());
        return List.of(new Statement.CallStatement(call, r.span()));
    }

    // ---- drawing ----------------------------------------------------------------------------------

    /**
     * Adds a drawn element linked from {@code from}; several open ends meet in a selection convergence first. No
     * coordinates are stored: viewers lay the chart out.
     */
    private int draw(Sfc.Kind kind, String name, boolean initial, List<String> lines, List<Integer> from, Span span) {
        List<Integer> sources = from;
        if (sources.size() > 1 && kind != Sfc.Kind.SIMULTANEOUS_CONVERGENCE) {
            int conv = nextDrawId++;
            drawing.add(new Sfc.Element(
                    conv, Sfc.Kind.SELECTION_CONVERGENCE, null, false, List.of(), 0, 0, 0, 0, links(sources), span));
            sources = List.of(conv);
        }
        int id = nextDrawId++;
        drawing.add(new Sfc.Element(id, kind, name, initial, lines, 0, 0, 0, 0, links(sources), span));
        return id;
    }

    private static List<Sfc.Link> links(List<Integer> ids) {
        List<Sfc.Link> out = new ArrayList<>();
        for (int i : ids) {
            out.add(new Sfc.Link(i, List.of()));
        }
        return out;
    }

    private static List<Integer> sources(List<End> ends) {
        List<Integer> out = new ArrayList<>();
        for (End e : ends) {
            if (!out.contains(e.draw())) {
                out.add(e.draw());
            }
        }
        return out;
    }

    // ---- archive access ---------------------------------------------------------------------------

    /** The items of an element's {@code ElementList}. */
    private static List<ArchiveNode> elements(ArchiveNode owner) {
        ArchiveNode l = owner == null ? null : owner.member("ElementList");
        return l == null ? List.of() : l.list("InnerList");
    }

    /** The value element ({@code <v n="Value">}) of the attribute {@code guid}, or null. */
    private ArchiveNode attribute(ArchiveNode owner, String guid) {
        for (ArchiveNode a : owner.list("Attributes")) {
            if (guid.equalsIgnoreCase(ar.string(a, "AttributeId").strip())) {
                ArchiveNode value = a.member("Value");
                return value == null ? null : value.member("Value");
            }
        }
        return null;
    }

    private Identifier name(ArchiveNode el) {
        ArchiveNode v = attribute(el, NAME);
        int[] r = ar.stringRange(v);
        String text = ar.string(v).strip();
        Span s = r == null ? g.elementSpan(el.offset) : g.span(r[0], r[1]);
        return new Identifier(text.isEmpty() ? "<unnamed>" : text, s);
    }
}
