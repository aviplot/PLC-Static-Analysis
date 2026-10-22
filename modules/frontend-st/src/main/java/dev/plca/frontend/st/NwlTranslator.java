package dev.plca.frontend.st;

import dev.plca.ast.Expression;
import dev.plca.ast.Network;
import dev.plca.ast.Network.Element;
import dev.plca.ast.Network.Kind;
import dev.plca.ast.Network.Link;
import dev.plca.ast.Network.Modifier;
import dev.plca.ast.Network.Pin;
import dev.plca.ast.Statement;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.Span;
import dev.plca.frontend.st.kit.ArchiveNode;
import dev.plca.frontend.st.kit.ArchiveReader;
import dev.plca.frontend.st.kit.GraphContext;
import dev.plca.frontend.st.kit.NetworkLowering;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TwinCAT/CODESYS LD and FBD bodies ({@code <Implementation><NWL>}, {@code docs/grammar/codesys.md} §9.2): each network
 * is a <em>box tree</em> (assignments whose right side is a tree of boxes and operands), turned into a {@link Network}
 * and lowered by the shared {@link NetworkLowering}.
 *
 * <ul>
 *   <li>LD view: AND boxes are contacts in series, OR boxes and parallel trees are parallel branches, the outputs of an
 *       assignment are coils; boxes take the power flow on their first input (or EN);
 *   <li>FBD view: every box is a block, operands are input variables, outputs output variables;
 *   <li>a demultiplexer ({@code BoxTreeDemux}) is a value shared by several trees, defined once with its input.
 * </ul>
 *
 * <p>Networks carry no coordinates; viewers lay them out.
 */
final class NwlTranslator {

    /** Operand flags. TODO(verify-format): inferred from the corpus (codesys.md §9.2), not documented. */
    static final int NEGATED = 1;

    static final int RISING = 16;
    static final int FALLING = 32;
    /** Output flags: 2 set coil, 3 reset coil, 4 jump to the label named by the operand, 8 return. */
    static final int SET = 2;

    static final int RESET = 3;
    static final int JUMP = 4;
    static final int RETURN = 8;

    private final GraphContext g;
    private final ArchiveReader ar;

    NwlTranslator(GraphContext g, ArchiveReader ar) {
        this.g = g;
        this.ar = ar;
    }

    /** The networks of an {@code NWLImplementationObject}, numbered from 1; commented-out networks are left out. */
    List<Statement> translate(ArchiveNode root) {
        boolean ld = ar.string(root, "DefaultViewMode").equalsIgnoreCase("Ld");
        List<Statement> out = new ArrayList<>();
        int number = 0;
        for (ArchiveNode net : root.list("NetworkList")) {
            number++;
            if (ar.bool(net, "OutCommented") || ar.bool(net, "ILActive")) {
                continue;
            }
            Network n = new Net(ld, net).build();
            if (n.elements().isEmpty()) {
                continue;
            }
            List<Statement> lowered = new NetworkLowering(g.sink, g::nextTemp, n).lower();
            out.add(new Statement.Network(number, n, lowered, n.span()));
        }
        return out;
    }

    /** One network under construction. */
    private final class Net {
        private final boolean ld;
        private final ArchiveNode net;
        private final List<Element> elements = new ArrayList<>();
        private final Map<Long, List<Link>> demux = new HashMap<>();
        private int nextId = 1;
        private int order;
        private int rail = -1;

        Net(boolean ld, ArchiveNode net) {
            this.ld = ld;
            this.net = net;
        }

        Network build() {
            String label = ar.string(net, "Label").strip();
            if (!label.isEmpty()) {
                ArchiveNode v = net.member("Label");
                add(
                        Kind.LABEL,
                        label,
                        null,
                        null,
                        false,
                        Modifier.NONE,
                        Modifier.NONE,
                        true,
                        List.of(),
                        List.of(),
                        span(v));
            }
            for (ArchiveNode item : net.list("NetworkItems")) {
                item(item, ld ? rail() : null);
            }
            Span span =
                    net.contentEnd() > net.offset ? g.span(net.offset, net.contentEnd()) : g.elementSpan(net.offset);
            return new Network(ld ? Network.Language.LD : Network.Language.FBD, elements, span);
        }

        /**
         * The wires carrying a tree's value. {@code flow}: the power flow into it (LD, where the tree sits on the
         * rung), or null where it is a data input.
         */
        private List<Link> item(ArchiveNode n, List<Link> flow) {
            if (n == null || n.isNull() || n.type == null) {
                return flow == null ? List.of() : flow;
            }
            return switch (n.type) {
                case "BoxTreeOperand" -> operand(n.member("Operand"), flow);
                case "BoxTreeBox" -> box(n, flow);
                case "BoxTreeParallel" -> parallel(n, flow);
                case "BoxTreeAssign" -> assign(n, flow);
                case "BoxTreeDemux" -> demux(n, flow);
                case "BoxTreeTerminator" -> flow == null ? List.of() : flow;
                default -> {
                    g.sink.report(Diagnostic.of(
                            SyntaxCodes.UNPARSED_LANGUAGE,
                            Severity.INFO,
                            "network element " + n.type + " is not analysed",
                            g.elementSpan(n.offset)));
                    yield List.of();
                }
            };
        }

        private List<Link> operand(ArchiveNode op, List<Link> flow) {
            if (op == null || op.isNull()) {
                return flow == null ? List.of() : flow;
            }
            Expression value = value(op.member("Operand"));
            if (value == null) {
                return flow == null ? List.of() : flow;
            }
            int flags = flags(op);
            Modifier edge =
                    (flags & RISING) != 0 ? Modifier.RISING : (flags & FALLING) != 0 ? Modifier.FALLING : Modifier.NONE;
            boolean negated = (flags & NEGATED) != 0;
            boolean bool = isBool(ar.string(op, "Type"));
            if (flow != null && ld && bool) {
                int id = add(
                        Kind.CONTACT,
                        null,
                        null,
                        value,
                        negated,
                        Modifier.NONE,
                        edge,
                        false,
                        List.of(new Pin(null, false, flow)),
                        List.of(out(null)),
                        value.span());
                return List.of(new Link(id, null));
            }
            if (edge != Modifier.NONE) {
                // an edge on a data input: a contact straight from the rail
                int id = add(
                        Kind.CONTACT,
                        null,
                        null,
                        value,
                        negated,
                        Modifier.NONE,
                        edge,
                        false,
                        List.of(new Pin(null, false, rail())),
                        List.of(out(null)),
                        value.span());
                return List.of(new Link(id, null));
            }
            int id = add(
                    Kind.IN_VARIABLE,
                    null,
                    null,
                    value,
                    false,
                    Modifier.NONE,
                    Modifier.NONE,
                    false,
                    List.of(),
                    List.of(new Pin(null, negated, List.of())),
                    value.span());
            return List.of(new Link(id, null));
        }

        private List<Link> box(ArchiveNode n, List<Link> flow) {
            String call = ar.string(n, "CallType");
            List<ArchiveNode> inputs = n.list("InputItems");
            if (ld && flow != null && call.equals("And")) {
                List<Link> f = flow;
                for (ArchiveNode in : inputs) {
                    f = item(in, f);
                }
                return f;
            }
            if (ld && flow != null && call.equals("Or")) {
                List<Link> union = new ArrayList<>();
                for (ArchiveNode in : inputs) {
                    union.addAll(item(in, flow));
                }
                return union;
            }
            String type = blockType(ar.string(n, "BoxType"), call);
            boolean en = ar.bool(n, "EN");
            boolean eno = ar.bool(n, "ENO");
            List<String> inNames = names(n.path("InputParam", "Names"));
            List<String> outNames = names(n.path("OutputParam", "Names"));
            String instance = null;
            Expression instanceValue = null;
            switch (call) {
                case "FunctionBlock" -> {
                    ArchiveNode inst = n.member("Instance");
                    String name = ar.string(inst, "Operand").strip();
                    Expression v = name.isEmpty() ? null : value(inst.member("Operand"));
                    if (v != null) {
                        instance = name;
                        instanceValue = v;
                    }
                }
                case "Program", "Action", "Method" -> {
                    // called by name: the box type is the callee ({@code prgX}, {@code fb.M})
                    instanceValue = value(n.member("BoxType"));
                    instance = instanceValue == null ? null : type;
                }
                default -> {}
            }
            boolean powered = ld && flow != null;
            List<Pin> ins = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                String pin = i < inNames.size() ? inNames.get(i) : null;
                boolean first = i == 0;
                List<Link> links = item(inputs.get(i), powered && first ? flow : null);
                ins.add(new Pin(pin, false, links));
            }
            List<Pin> outs = new ArrayList<>();
            for (String o : outNames) {
                outs.add(out(o));
            }
            if (eno) {
                outs.add(out("ENO"));
            }
            if (outs.isEmpty()) {
                outs.add(out(null));
            }
            Span span = instanceValue != null ? instanceValue.span() : span(n.member("BoxType"));
            int id = add(
                    Kind.BLOCK,
                    type,
                    instance,
                    instanceValue,
                    false,
                    Modifier.NONE,
                    Modifier.NONE,
                    true,
                    ins,
                    outs,
                    span);
            // outputs written by the box ({@code ET => tElapsed})
            ArchiveNode outputItems = n.member("OutputItems");
            List<ArchiveNode> written = outputItems == null ? List.of() : outputItems.list("OutputItems");
            for (int i = 0; i < written.size(); i++) {
                ArchiveNode op = written.get(i);
                if (op.isNull() || ar.string(op, "Operand").isBlank()) {
                    continue;
                }
                String pin = i < outNames.size() ? outNames.get(i) : null;
                output(op, List.of(new Link(id, pin)), false);
            }
            String main = eno ? "ENO" : instance != null && !outNames.isEmpty() ? outNames.getFirst() : null;
            return List.of(new Link(id, main));
        }

        private List<Link> parallel(ArchiveNode n, List<Link> flow) {
            ArchiveNode input = n.member("Input");
            boolean hasInput = input != null && !input.isNull();
            if (ld && flow != null) {
                List<Link> f = hasInput ? item(input, flow) : flow;
                List<Link> union = new ArrayList<>();
                for (ArchiveNode t : n.list("Trees")) {
                    union.addAll(item(t, f));
                }
                return union;
            }
            List<Pin> trees = new ArrayList<>();
            for (ArchiveNode t : n.list("Trees")) {
                trees.add(new Pin(null, false, item(t, null)));
            }
            Span s = g.elementSpan(n.offset);
            int or = add(
                    Kind.BLOCK,
                    "OR",
                    null,
                    null,
                    false,
                    Modifier.NONE,
                    Modifier.NONE,
                    false,
                    trees,
                    List.of(out(null)),
                    s);
            if (!hasInput) {
                return List.of(new Link(or, null));
            }
            List<Pin> both =
                    List.of(new Pin(null, false, item(input, null)), new Pin(null, false, List.of(new Link(or, null))));
            int and = add(
                    Kind.BLOCK,
                    "AND",
                    null,
                    null,
                    false,
                    Modifier.NONE,
                    Modifier.NONE,
                    false,
                    both,
                    List.of(out(null)),
                    s);
            return List.of(new Link(and, null));
        }

        private List<Link> assign(ArchiveNode n, List<Link> flow) {
            List<Link> value = item(n.member("RValue"), flow);
            ArchiveNode outputs = n.member("OutputItems");
            for (ArchiveNode op : outputs == null ? List.<ArchiveNode>of() : outputs.list("OutputItems")) {
                if (!op.isNull()) {
                    output(op, value, true);
                }
            }
            return value;
        }

        private List<Link> demux(ArchiveNode n, List<Link> flow) {
            long id = ar.number(n, "VarId", -1);
            ArchiveNode input = n.member("Input");
            if (input != null && !input.isNull()) {
                List<Link> v = item(input, flow);
                demux.put(id, v);
                return v;
            }
            List<Link> v = demux.get(id);
            return v != null ? v : flow == null ? List.of() : flow;
        }

        /** An operand written from {@code value}: coil, output variable, jump or return. */
        private void output(ArchiveNode op, List<Link> value, boolean flowing) {
            int flags = flags(op);
            Span s = span(op.member("Operand"));
            if (flags == JUMP || flags == RETURN) {
                Kind kind = flags == JUMP ? Kind.JUMP : Kind.RETURN;
                String target = flags == JUMP ? ar.string(op, "Operand").strip() : null;
                add(
                        kind,
                        target,
                        null,
                        null,
                        false,
                        Modifier.NONE,
                        Modifier.NONE,
                        true,
                        List.of(new Pin(null, false, value)),
                        List.of(),
                        s);
                return;
            }
            Expression target = value(op.member("Operand"));
            if (target == null) {
                return;
            }
            Modifier storage = flags == SET ? Modifier.SET : flags == RESET ? Modifier.RESET : Modifier.NONE;
            boolean negated = flags == NEGATED;
            if ((ld && flowing) || storage != Modifier.NONE) {
                add(
                        Kind.COIL,
                        null,
                        null,
                        target,
                        negated,
                        storage,
                        Modifier.NONE,
                        true,
                        List.of(new Pin(null, false, value)),
                        List.of(out(null)),
                        target.span());
            } else {
                add(
                        Kind.OUT_VARIABLE,
                        null,
                        null,
                        target,
                        false,
                        Modifier.NONE,
                        Modifier.NONE,
                        true,
                        List.of(new Pin(null, negated, value)),
                        List.of(),
                        target.span());
            }
        }

        private List<Link> rail() {
            if (rail < 0) {
                rail = nextId++;
                elements.add(new Element(
                        rail,
                        Kind.LEFT_RAIL,
                        null,
                        null,
                        null,
                        false,
                        Modifier.NONE,
                        Modifier.NONE,
                        null,
                        0,
                        0,
                        0,
                        0,
                        List.of(),
                        List.of(out(null)),
                        g.elementSpan(net.offset)));
            }
            return List.of(new Link(rail, null));
        }

        private int add(
                Kind kind,
                String name,
                String instance,
                Expression value,
                boolean negated,
                Modifier storage,
                Modifier edge,
                boolean sink,
                List<Pin> inputs,
                List<Pin> outputs,
                Span span) {
            int id = nextId++;
            elements.add(new Element(
                    id,
                    kind,
                    name,
                    instance,
                    value,
                    negated,
                    storage,
                    edge,
                    sink ? ++order : null,
                    0,
                    0,
                    0,
                    0,
                    inputs,
                    outputs,
                    span));
            return id;
        }

        private Span span(ArchiveNode v) {
            return v == null ? g.elementSpan(net.offset) : g.elementSpan(v.offset);
        }
    }

    /** The operand's expression, or null when it is empty ({@code ""}, {@code "???"}: not filled in). */
    private Expression value(ArchiveNode v) {
        int[] r = ar.stringRange(v);
        if (r == null) {
            return null;
        }
        String t = g.file.text().substring(r[0], r[1]).strip();
        if (t.isEmpty() || t.equals("???")) {
            return null;
        }
        return g.expression(r[0], r[1]);
    }

    private int flags(ArchiveNode owner) {
        ArchiveNode f = owner.member("Flags");
        return (int) ar.number(f, "Flags", 0);
    }

    private List<String> names(ArchiveNode list) {
        List<String> out = new ArrayList<>();
        if (list != null) {
            for (ArchiveNode v : list.children()) {
                String s = ar.string(v).strip();
                out.add(s.isEmpty() || s.equals("None") ? null : s);
            }
        }
        return out;
    }

    private static Pin out(String name) {
        return new Pin(name, false, List.of());
    }

    private static boolean isBool(String type) {
        String t = type.strip().toUpperCase(Locale.ROOT);
        return t.isEmpty() || t.equals("BOOL") || t.equals("BIT");
    }

    /** The block type the lowering knows: IEC names for CODESYS operator call types. */
    private static String blockType(String boxType, String callType) {
        return switch (callType) {
            case "Ampersand" -> "AND";
            case "Greater" -> "GT";
            case "Less" -> "LT";
            case "Equal" -> "EQ";
            default -> boxType.strip();
        };
    }
}
