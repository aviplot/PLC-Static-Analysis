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
import java.util.Map;

/**
 * TwinCAT/CODESYS CFC bodies ({@code <Implementation><CFC>}, {@code docs/grammar/codesys.md} §9.3): one chart of boxes,
 * input and output elements and connections between pins, read as one FBD {@link Network} and lowered by the shared
 * {@link NetworkLowering}. Elements are executed in the order of the item list ({@code TODO(verify-format)}: the
 * archive stores no explicit execution number; CODESYS keeps the list sorted by it).
 */
final class CfcTranslator {

    /** CFC coordinates are grid units; drawings use the PLCopen scale of the same charts. */
    static final int GRID = 10;

    private final GraphContext g;
    private final ArchiveReader ar;

    CfcTranslator(GraphContext g, ArchiveReader ar) {
        this.g = g;
        this.ar = ar;
    }

    /** The chart as one network statement (none when it is empty). */
    List<Statement> translate(ArchiveNode root) {
        List<ArchiveNode> items =
                root.path("Items") == null ? List.of() : root.path("Items").list("InnerList");
        // pin id -> (element, pin name); connections by destination pin
        Map<Long, Link> sources = new HashMap<>();
        Map<Long, List<Long>> into = new HashMap<>();
        for (ArchiveNode it : items) {
            if ("CFCConnection".equals(it.type)) {
                into.computeIfAbsent(ar.number(it, "DestPinId", -1), k -> new ArrayList<>())
                        .add(ar.number(it, "SourcePinId", -1));
            }
        }
        // first pass: ids of every element's output pins
        Map<ArchiveNode, Integer> ids = new HashMap<>();
        int next = 1;
        for (ArchiveNode it : items) {
            if (it.type == null || it.type.equals("CFCConnection") || it.type.equals("CFCCommentElement")) {
                continue;
            }
            int id = next++;
            ids.put(it, id);
            switch (it.type) {
                case "CFCInputElement" -> sources.put(pinId(it.member("Output")), new Link(id, null));
                case "CFCBoxElement" -> {
                    List<ArchiveNode> outs = pins(it, "Outputs");
                    List<String> names = boxTexts(it);
                    int ni = pins(it, "Inputs").size();
                    for (int i = 0; i < outs.size(); i++) {
                        sources.put(pinId(outs.get(i)), new Link(id, name(names, ni + i)));
                    }
                }
                default -> {}
            }
        }
        List<Element> elements = new ArrayList<>();
        int order = 0;
        for (ArchiveNode it : items) {
            Integer id = ids.get(it);
            if (id == null) {
                continue;
            }
            Span span = g.elementSpan(it.offset);
            int[] xy = bounds(it);
            switch (it.type) {
                case "CFCInputElement" -> {
                    Expression v = text(it.member("Text"), span);
                    boolean neg = ar.bool(it.member("Output"), "Negated");
                    elements.add(element(
                            id,
                            Kind.IN_VARIABLE,
                            null,
                            null,
                            v,
                            Modifier.NONE,
                            null,
                            xy,
                            List.of(),
                            List.of(new Pin(null, neg, List.of())),
                            v.span()));
                }
                case "CFCOutputElement" -> {
                    Expression v = text(it.member("Text"), span);
                    ArchiveNode in = it.member("Input");
                    String sr = ar.string(in, "SetReset");
                    Modifier storage =
                            sr.equals("Set") ? Modifier.SET : sr.equals("Reset") ? Modifier.RESET : Modifier.NONE;
                    boolean neg = ar.bool(in, "Negated");
                    List<Link> links = links(in, into, sources);
                    if (storage != Modifier.NONE) {
                        elements.add(element(
                                id,
                                Kind.COIL,
                                null,
                                null,
                                v,
                                storage,
                                ++order,
                                xy,
                                List.of(new Pin(null, false, links)),
                                List.of(new Pin(null, false, List.of())),
                                v.span()));
                    } else {
                        elements.add(element(
                                id,
                                Kind.OUT_VARIABLE,
                                null,
                                null,
                                v,
                                Modifier.NONE,
                                ++order,
                                xy,
                                List.of(new Pin(null, neg, links)),
                                List.of(),
                                v.span()));
                    }
                }
                case "CFCBoxElement" -> elements.add(box(it, id, ++order, xy, into, sources));
                case "CFCJumpElement", "CFCReturnElement" -> {
                    boolean jump = it.type.equals("CFCJumpElement");
                    String target = jump ? ar.string(it.member("Text"), "Text").strip() : null;
                    elements.add(element(
                            id,
                            jump ? Kind.JUMP : Kind.RETURN,
                            target,
                            null,
                            null,
                            Modifier.NONE,
                            ++order,
                            xy,
                            List.of(new Pin(null, false, links(it.member("Input"), into, sources))),
                            List.of(),
                            span));
                }
                case "CFCLabelElement" ->
                    elements.add(element(
                            id,
                            Kind.LABEL,
                            ar.string(it.member("Text"), "Text").strip(),
                            null,
                            null,
                            Modifier.NONE,
                            ++order,
                            xy,
                            List.of(),
                            List.of(),
                            span));
                default ->
                    g.sink.report(Diagnostic.of(
                            SyntaxCodes.UNPARSED_LANGUAGE,
                            Severity.INFO,
                            "CFC element " + it.type + " is not analysed",
                            span));
            }
        }
        if (elements.isEmpty()) {
            return List.of();
        }
        Span span =
                root.contentEnd() > root.offset ? g.span(root.offset, root.contentEnd()) : g.elementSpan(root.offset);
        Network n = new Network(Network.Language.FBD, elements, span);
        List<Statement> lowered = new NetworkLowering(g.sink, g::nextTemp, n).lower();
        return List.of(new Statement.Network(1, n, lowered, span));
    }

    private Element box(
            ArchiveNode it, int id, int order, int[] xy, Map<Long, List<Long>> into, Map<Long, Link> sources) {
        List<String> texts = boxTexts(it);
        List<ArchiveNode> ins = pins(it, "Inputs");
        List<ArchiveNode> outs = pins(it, "Outputs");
        int fixed = ins.size() + outs.size();
        // after the pin names: the box type, then the instance
        ArchiveNode typeText = textNode(it, fixed);
        ArchiveNode instText = textNode(it, fixed + 1);
        String type = typeText == null ? "" : ar.string(typeText, "Text").strip();
        String kind = ar.string(it, "KindOfCall");
        Span span = g.elementSpan(it.offset);
        String instance = null;
        Expression value = null;
        switch (kind) {
            case "FunctionBlock" -> {
                if (instText != null && !ar.string(instText, "Text").isBlank()) {
                    value = text(instText, span);
                    instance = ar.string(instText, "Text").strip();
                }
            }
            case "Program", "Method", "LocalMethod", "Action" -> {
                // called by name ({@code prgX}, {@code fb.M})
                if (typeText != null && !type.isEmpty()) {
                    value = text(typeText, span);
                    instance = type;
                }
            }
            default -> {}
        }
        List<Pin> inputs = new ArrayList<>();
        for (int i = 0; i < ins.size(); i++) {
            ArchiveNode p = ins.get(i);
            inputs.add(new Pin(name(texts, i), ar.bool(p, "Negated"), links(p, into, sources)));
        }
        List<Pin> outputs = new ArrayList<>();
        for (int i = 0; i < outs.size(); i++) {
            outputs.add(new Pin(name(texts, ins.size() + i), ar.bool(outs.get(i), "Negated"), List.of()));
        }
        if (outputs.isEmpty()) {
            outputs.add(new Pin(null, false, List.of()));
        }
        return element(
                id,
                Kind.BLOCK,
                type,
                instance,
                value,
                Modifier.NONE,
                order,
                xy,
                inputs,
                outputs,
                value != null ? value.span() : span);
    }

    private List<Link> links(ArchiveNode pin, Map<Long, List<Long>> into, Map<Long, Link> sources) {
        List<Link> out = new ArrayList<>();
        for (long src : into.getOrDefault(pinId(pin), List.of())) {
            Link l = sources.get(src);
            if (l != null) {
                out.add(l);
            }
        }
        return out;
    }

    private Expression text(ArchiveNode textObject, Span fallback) {
        int[] r = textObject == null ? null : ar.stringRange(textObject.member("Text"));
        return r == null ? new Expression.Invalid(fallback) : g.expression(r[0], r[1]);
    }

    private long pinId(ArchiveNode pin) {
        return pin == null ? Long.MIN_VALUE : ar.number(pin, "Id", Long.MIN_VALUE);
    }

    private static List<ArchiveNode> pins(ArchiveNode box, String list) {
        ArchiveNode l = box.member(list);
        return l == null ? List.of() : l.list("InnerList");
    }

    private ArchiveNode textNode(ArchiveNode box, int i) {
        List<ArchiveNode> t = pins(box, "Texts");
        return i < t.size() ? t.get(i) : null;
    }

    private List<String> boxTexts(ArchiveNode box) {
        List<String> out = new ArrayList<>();
        for (ArchiveNode t : pins(box, "Texts")) {
            out.add(ar.string(t, "Text").strip());
        }
        return out;
    }

    private static String name(List<String> texts, int i) {
        String s = i < texts.size() ? texts.get(i) : "";
        return s.isEmpty() ? null : s;
    }

    /** The element's position from {@code Bounds="x, y, w, h"}, scaled; zeros when absent. */
    private int[] bounds(ArchiveNode it) {
        String[] parts = ar.string(it, "Bounds").split(",");
        int[] xy = new int[2];
        for (int i = 0; i < 2 && i < parts.length; i++) {
            try {
                xy[i] = Integer.parseInt(parts[i].strip()) * GRID;
            } catch (NumberFormatException e) {
                xy[i] = 0;
            }
        }
        return xy;
    }

    private static Element element(
            int id,
            Kind kind,
            String name,
            String instance,
            Expression value,
            Modifier storage,
            Integer order,
            int[] xy,
            List<Pin> inputs,
            List<Pin> outputs,
            Span span) {
        return new Element(
                id,
                kind,
                name,
                instance,
                value,
                false,
                storage,
                Modifier.NONE,
                order,
                xy[0],
                xy[1],
                0,
                0,
                inputs,
                outputs,
                span);
    }
}
