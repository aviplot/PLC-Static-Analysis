package dev.plca.frontend.plcopen;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.Argument;
import dev.plca.ast.Expression.ArgumentKind;
import dev.plca.ast.Expression.BinaryOp;
import dev.plca.ast.Identifier;
import dev.plca.ast.Network;
import dev.plca.ast.Network.Element;
import dev.plca.ast.Network.Kind;
import dev.plca.ast.Network.Link;
import dev.plca.ast.Network.Modifier;
import dev.plca.ast.Network.Pin;
import dev.plca.ast.Statement;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.source.Span;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lowers one LD/FBD {@link Network} to statements ({@code docs/grammar/plcopen.md} §5). Sinks (coils, output variables,
 * FB calls, jumps, returns) are emitted in execution order; each pulls the values it needs, so every element is
 * evaluated after what it reads and exactly once:
 *
 * <ul>
 *   <li>power flow: a contact ANDs its term onto the flow, parallel wires OR, a coil assigns from the flow;
 *   <li>data flow: an FB instance is called once and its outputs read as {@code inst.Q}; a function is an expression
 *       (IEC operators as operators);
 *   <li>a value used by more than one consumer goes into a synthetic {@code $fbdN} first;
 *   <li>feedback: a value needed while it is being computed is the previous cycle's (the variable, or the FB output).
 * </ul>
 *
 * <p>Also evaluates graphical SFC transition conditions ({@link #value(Link)}), whose helper statements
 * ({@link #statements()}) go before the chart's transitions.
 */
final class NetworkLowering {

    private final PlcOpenFrontend.Run run;
    private final Network network;
    private final boolean ld;
    private final Map<Integer, Element> byId = new HashMap<>();
    private final Map<String, Element> connectors = new HashMap<>();
    private final Map<String, Integer> uses = new HashMap<>();
    private final Map<String, Expression> memo = new HashMap<>();
    private final Set<Integer> emitted = new HashSet<>();
    private final Set<Integer> onStack = new HashSet<>();
    private final List<Statement> out = new ArrayList<>();
    /** Functions read in a feedback loop before they are evaluated: their previous value lives in a {@code $fbdN}. */
    private final Map<Integer, Expression> previous = new HashMap<>();

    NetworkLowering(PlcOpenFrontend.Run run, Network network) {
        this.run = run;
        this.network = network;
        this.ld = network.language() == Network.Language.LD;
        for (Element e : network.elements()) {
            byId.putIfAbsent(e.localId(), e);
            if (e.kind() == Kind.CONNECTOR && e.name() != null) {
                connectors.put(e.name().toLowerCase(Locale.ROOT), e);
            }
        }
        for (Element e : network.elements()) {
            for (Pin p : e.inputs()) {
                for (Link l : p.links()) {
                    uses.merge(key(l.source(), l.sourcePin()), 1, Integer::sum);
                }
            }
        }
    }

    /** The network's statements. */
    List<Statement> lower() {
        List<Element> sinks = new ArrayList<>();
        for (Element e : network.elements()) {
            switch (e.kind()) {
                case COIL, OUT_VARIABLE, IN_OUT_VARIABLE, BLOCK, JUMP, RETURN, LABEL -> sinks.add(e);
                default -> {}
            }
        }
        boolean ordered = sinks.stream().anyMatch(e -> e.order() != null);
        Comparator<Element> position =
                Comparator.comparingInt(Element::y).thenComparingInt(Element::x).thenComparingInt(Element::localId);
        sinks.sort(
                ordered
                        ? Comparator.comparing((Element e) -> e.order() == null ? Integer.MAX_VALUE : e.order())
                                .thenComparing(position)
                        : position);
        for (Element e : sinks) {
            sink(e);
        }
        return List.copyOf(out);
    }

    /** Statements emitted so far (helpers of {@link #value(Link)}). */
    List<Statement> statements() {
        return List.copyOf(out);
    }

    /** The value arriving over one wire (SFC graphical conditions). */
    Expression value(Link l) {
        Expression v = value(l.source(), l.sourcePin());
        return v == null ? literalTrue(network.span()) : v;
    }

    // ---- sinks ----------------------------------------------------------------------------------

    private void sink(Element e) {
        Span s = e.span();
        switch (e.kind()) {
            case COIL -> {
                Expression flow = flow(e.inputs().getFirst());
                Expression target = e.value();
                Statement st;
                if (e.storage() == Modifier.SET || e.storage() == Modifier.RESET) {
                    Expression value = bool(e.storage() == Modifier.SET, s);
                    Statement assign = assign(target, value, s);
                    st = flow == null ? assign : guarded(flow, assign, s);
                } else {
                    Expression value = flow == null ? literalTrue(s) : flow;
                    if (e.edge() == Modifier.RISING || e.edge() == Modifier.FALLING) {
                        value = edge(e.edge(), value, s);
                    }
                    st = assign(target, e.negated() ? not(value, s) : value, s);
                }
                out.add(st);
            }
            case OUT_VARIABLE -> {
                Pin in = e.inputs().getFirst();
                Expression v = input(in, e);
                if (v != null) {
                    out.add(assign(e.value(), in.negated() ? not(v, s) : v, s));
                }
            }
            case IN_OUT_VARIABLE -> inOut(e);
            case BLOCK -> {
                if (e.instance() != null) {
                    call(e);
                } else if (!consumed(e) && !emitted.contains(e.localId())) {
                    // a function whose result nobody reads: kept for its reads (and any side effect)
                    emitted.add(e.localId());
                    Expression f = function(e);
                    if (f instanceof Expression.Call) {
                        out.add(new Statement.CallStatement(f, s));
                    }
                }
            }
            case JUMP -> {
                Expression flow = flow(e.inputs().getFirst());
                Statement jump = new Statement.Jump(new Identifier(label(e), s), s);
                out.add(flow == null ? jump : guarded(flow, jump, s));
            }
            case RETURN -> {
                Expression flow = flow(e.inputs().getFirst());
                Statement ret = new Statement.Return(null, s);
                out.add(flow == null ? ret : guarded(flow, ret, s));
            }
            case LABEL -> out.add(new Statement.Label(new Identifier(label(e), s), s));
            default -> {}
        }
    }

    private static String label(Element e) {
        return e.name() == null || e.name().isBlank() ? "<missing>" : e.name();
    }

    // ---- values ---------------------------------------------------------------------------------

    /** The value of an element's output pin; null means TRUE (the left rail). */
    private Expression value(int id, String pin) {
        String k = key(id, pin);
        if (memo.containsKey(k)) {
            return memo.get(k);
        }
        Element e = byId.get(id);
        if (e == null) {
            run.sink.report(Diagnostic.warning(
                    PlcOpenCodes.BROKEN_CONNECTION,
                    "connection to element " + id + ", which does not exist in this network",
                    network.span()));
            return new Expression.Invalid(network.span());
        }
        if (onStack.contains(id)) {
            return feedback(e, pin);
        }
        onStack.add(id);
        Expression v;
        try {
            v = compute(e, pin);
        } finally {
            onStack.remove(id);
        }
        Expression prev = previous.remove(id);
        if (prev != null && v != null) {
            // the loop read the previous value; store this cycle's for the next one
            out.add(new Statement.Assignment(prev, Statement.AssignOp.ASSIGN, v, e.span()));
            v = prev;
        } else if (v != null && uses.getOrDefault(k, 0) > 1 && !trivial(v)) {
            v = materialize(v, e.span());
        }
        memo.put(k, v);
        return v;
    }

    private Expression compute(Element e, String pin) {
        Span s = e.span();
        return switch (e.kind()) {
            case LEFT_RAIL -> null;
            case CONTACT -> {
                Expression term = e.value();
                if (e.edge() == Modifier.RISING || e.edge() == Modifier.FALLING) {
                    term = edge(e.edge(), term, s);
                }
                if (e.negated()) {
                    term = not(term, s);
                }
                Expression flow = flow(e.inputs().getFirst());
                yield flow == null ? term : new Expression.Binary(BinaryOp.AND, flow, term, s);
            }
            case COIL -> flow(e.inputs().getFirst());
            case IN_VARIABLE -> e.outputs().getFirst().negated() ? not(e.value(), s) : e.value();
            case IN_OUT_VARIABLE -> {
                inOut(e);
                yield e.outputs().getFirst().negated() ? not(e.value(), s) : e.value();
            }
            case CONTINUATION -> {
                Element c = e.name() == null ? null : connectors.get(e.name().toLowerCase(Locale.ROOT));
                yield c == null
                        ? missing(e, "no connector named '" + e.name() + "'")
                        : input(c.inputs().getFirst(), c);
            }
            case CONNECTOR -> input(e.inputs().getFirst(), e);
            case BLOCK -> block(e, pin);
            default -> missing(e, e.kind() + " has no output");
        };
    }

    /** A value needed while it is being computed: the previous cycle's (IEC 61131-3 §8.3.1). */
    private Expression feedback(Element e, String pin) {
        return switch (e.kind()) {
            case IN_VARIABLE, IN_OUT_VARIABLE, CONTACT, COIL -> e.value();
            case BLOCK -> {
                if (e.instance() != null && e.value() != null) {
                    yield member(e, pin);
                }
                // a function has no variable of its own: its previous value is kept in a synthetic one
                yield previous.computeIfAbsent(e.localId(), id -> {
                    Identifier name = new Identifier(run.nextTemp("fbd"), Identifier.Form.SYNTHETIC, e.span());
                    return new Expression.NameRef(name, false, e.span());
                });
            }
            default -> new Expression.Invalid(e.span());
        };
    }

    /** Power flow into a pin: the OR of its wires; an open input carries no power (LD). */
    private Expression flow(Pin p) {
        if (p.links().isEmpty()) {
            return ld ? bool(false, network.span()) : null;
        }
        Expression acc = null;
        for (Link l : p.links()) {
            Expression v = value(l.source(), l.sourcePin());
            if (v == null) {
                return null; // straight from the rail: always powered
            }
            acc = acc == null ? v : new Expression.Binary(BinaryOp.OR, acc, v, v.span());
        }
        return acc;
    }

    /** A data input: its single wire (several wires are an LD OR); null when open. */
    private Expression input(Pin p, Element owner) {
        if (p.links().isEmpty()) {
            return null;
        }
        if (p.links().size() > 1 || ld) {
            Expression f = flow(p);
            return f == null ? literalTrue(owner.span()) : f;
        }
        Link l = p.links().getFirst();
        Expression v = value(l.source(), l.sourcePin());
        return v == null ? literalTrue(owner.span()) : v;
    }

    private void inOut(Element e) {
        if (!emitted.add(e.localId())) {
            return;
        }
        Pin in = e.inputs().getFirst();
        Expression v = input(in, e);
        if (v != null) {
            out.add(assign(e.value(), in.negated() ? not(v, e.span()) : v, e.span()));
        }
    }

    // ---- blocks ---------------------------------------------------------------------------------

    private Expression block(Element e, String pin) {
        if (pin != null && pin.equalsIgnoreCase("ENO")) {
            Pin en = pin(e, "EN");
            if (e.instance() != null) {
                call(e);
            }
            return en == null ? null : flow(en);
        }
        if (e.instance() != null) {
            call(e);
            return member(e, pin);
        }
        emitted.add(e.localId());
        return function(e);
    }

    /** {@code inst.pin}; the first output when the wire names none. */
    private Expression member(Element e, String pin) {
        String p = pin;
        if (p == null) {
            p = e.outputs().stream()
                    .map(Pin::name)
                    .filter(n -> n != null && !n.equalsIgnoreCase("ENO"))
                    .findFirst()
                    .orElse("Q");
        }
        return new Expression.MemberAccess(e.value(), new Identifier(p, e.span()), e.span());
    }

    /** An FB call, once: {@code inst(IN1 := …)}, under {@code IF en} when EN is wired to anything but the rail. */
    private void call(Element e) {
        if (!emitted.add(e.localId())) {
            return;
        }
        Span s = e.span();
        List<Argument> args = new ArrayList<>();
        Pin en = null;
        for (Pin p : e.inputs()) {
            if (p.name() != null && p.name().equalsIgnoreCase("EN")) {
                en = p;
                continue;
            }
            Expression v = input(p, e);
            if (v != null && p.name() != null) {
                args.add(new Argument(
                        ArgumentKind.INPUT, new Identifier(p.name(), s), false, p.negated() ? not(v, s) : v, s));
            }
        }
        Expression instance = e.value() != null ? e.value() : new Expression.Invalid(s);
        Statement st = new Statement.CallStatement(new Expression.Call(instance, args, s), s);
        Expression enable = en == null || en.links().isEmpty() ? null : flow(en);
        out.add(enable == null ? st : guarded(enable, st, s));
    }

    /** A function block as an expression: IEC operators as operators, other functions as calls. */
    private Expression function(Element e) {
        Span s = e.span();
        List<Expression> args = new ArrayList<>();
        for (Pin p : e.inputs()) {
            if (p.name() != null && p.name().equalsIgnoreCase("EN")) {
                continue;
            }
            Expression v = input(p, e);
            if (v == null) {
                v = missing(e, "input " + p.name() + " of " + e.name() + " is not connected");
            }
            args.add(p.negated() ? not(v, s) : v);
        }
        String type = e.name() == null ? "" : e.name().toUpperCase(Locale.ROOT);
        BinaryOp op = switch (type) {
            case "ADD" -> BinaryOp.ADD;
            case "SUB" -> BinaryOp.SUB;
            case "MUL" -> BinaryOp.MUL;
            case "DIV" -> BinaryOp.DIV;
            case "MOD" -> BinaryOp.MOD;
            case "EXPT" -> BinaryOp.POWER;
            case "AND" -> BinaryOp.AND;
            case "OR" -> BinaryOp.OR;
            case "XOR" -> BinaryOp.XOR;
            case "EQ" -> BinaryOp.EQ;
            case "NE" -> BinaryOp.NE;
            case "GT" -> BinaryOp.GT;
            case "GE" -> BinaryOp.GE;
            case "LT" -> BinaryOp.LT;
            case "LE" -> BinaryOp.LE;
            default -> null;
        };
        if (op != null && args.size() >= 2) {
            Expression acc = args.getFirst();
            for (int i = 1; i < args.size(); i++) {
                acc = new Expression.Binary(op, acc, args.get(i), s);
            }
            return acc;
        }
        if (type.equals("NOT") && args.size() == 1) {
            return not(args.getFirst(), s);
        }
        if (type.equals("MOVE") && args.size() == 1) {
            return args.getFirst();
        }
        List<Argument> call = new ArrayList<>();
        for (Expression a : args) {
            call.add(new Argument(ArgumentKind.POSITIONAL, null, false, a, a.span()));
        }
        Identifier name = new Identifier(e.name() == null ? "<missing>" : e.name(), s);
        return new Expression.Call(new Expression.NameRef(name, false, s), call, s);
    }

    private boolean consumed(Element e) {
        for (Pin p : e.outputs()) {
            if (uses.getOrDefault(key(e.localId(), p.name()), 0) > 0) {
                return true;
            }
        }
        return uses.getOrDefault(key(e.localId(), null), 0) > 0;
    }

    private static Pin pin(Element e, String name) {
        for (Pin p : e.inputs()) {
            if (name.equalsIgnoreCase(p.name())) {
                return p;
            }
        }
        return null;
    }

    // ---- helpers --------------------------------------------------------------------------------

    private Expression materialize(Expression v, Span s) {
        Identifier name = new Identifier(run.nextTemp("fbd"), Identifier.Form.SYNTHETIC, s);
        out.add(new Statement.Assignment(new Expression.NameRef(name, false, s), Statement.AssignOp.ASSIGN, v, s));
        return new Expression.NameRef(name, false, s);
    }

    /** Cheap and harmless to repeat: names, literals, member chains, NOT of those. */
    private static boolean trivial(Expression e) {
        return switch (e) {
            case Expression.NameRef n -> true;
            case Expression.Literal l -> true;
            case Expression.MemberAccess m -> trivial(m.target());
            case Expression.BitAccess b -> trivial(b.target());
            case Expression.Unary u -> u.op() == Expression.UnaryOp.NOT && trivial(u.operand());
            default -> false;
        };
    }

    private Expression missing(Element e, String message) {
        run.sink.report(Diagnostic.warning(PlcOpenCodes.BROKEN_CONNECTION, message, e.span()));
        return new Expression.Invalid(e.span());
    }

    private static Statement assign(Expression target, Expression value, Span s) {
        return new Statement.Assignment(target, Statement.AssignOp.ASSIGN, value, s);
    }

    private static Statement guarded(Expression cond, Statement s, Span span) {
        return new Statement.If(List.of(new Statement.Branch(cond, List.of(s), span)), null, span);
    }

    private static Expression not(Expression e, Span s) {
        return new Expression.Unary(Expression.UnaryOp.NOT, e, s);
    }

    private static Expression bool(boolean v, Span s) {
        return new Expression.Literal(Expression.LiteralKind.BOOL, v ? "TRUE" : "FALSE", null, s);
    }

    private static Expression literalTrue(Span s) {
        return bool(true, s);
    }

    /** {@code $R_TRIG(x)} / {@code $F_TRIG(x)}: an edge with hidden state, evaluated once per scan. */
    static Expression edge(Modifier m, Expression x, Span s) {
        String name = m == Modifier.FALLING ? "F_TRIG" : "R_TRIG";
        Identifier id = new Identifier(name, Identifier.Form.SYNTHETIC, s);
        return new Expression.Call(
                new Expression.NameRef(id, false, s),
                List.of(new Argument(ArgumentKind.POSITIONAL, null, false, x, x.span())),
                s);
    }

    private static String key(int id, String pin) {
        return id + "/" + (pin == null ? "" : pin.toLowerCase(Locale.ROOT));
    }
}
