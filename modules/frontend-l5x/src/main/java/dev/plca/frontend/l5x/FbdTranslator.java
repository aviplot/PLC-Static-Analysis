package dev.plca.frontend.l5x;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.Argument;
import dev.plca.ast.Expression.ArgumentKind;
import dev.plca.ast.Identifier;
import dev.plca.ast.Network;
import dev.plca.ast.Network.Element;
import dev.plca.ast.Network.Kind;
import dev.plca.ast.Network.Link;
import dev.plca.ast.Network.Modifier;
import dev.plca.ast.Network.Pin;
import dev.plca.ast.Statement;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.Span;
import dev.plca.frontend.l5x.L5xReader.Attr;
import dev.plca.frontend.l5x.L5xReader.FbdNode;
import dev.plca.frontend.st.kit.NetworkLowering;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * An FBD routine of an L5X export ({@code <FBDContent>}, {@code docs/grammar/rockwell.md} §6b): one {@link Network} per
 * sheet, lowered by the shared {@link NetworkLowering}.
 *
 * <ul>
 *   <li>{@code IRef}/{@code ORef}: tag reads and writes;
 *   <li>{@code Block}: an instruction with a backing tag ({@code SCL Scl1}), lowered as in Logix ST: wired inputs are
 *       written to the tag's members, then {@code SCL(Scl1);}; outputs are read as {@code Scl1.Out};
 *   <li>{@code AddOnInstruction}: an AOI call on its backing tag, as in ladder ({@code tag(In := …)}), its
 *       {@code InOutParameter}s passed by name;
 *   <li>{@code Function}: {@code BAND__F} and friends as expressions (IEC operators where one exists);
 *   <li>{@code OCon}/{@code ICon}: a wire continued under a name, on the same sheet or a later one (then through a
 *       synthetic variable, since sheets run in order);
 *   <li>{@code JSR}/{@code SBR}/{@code RET}: calls as in ladder; {@code GSV}/{@code SSV} and text boxes are not read.
 * </ul>
 */
final class FbdTranslator {

    private final DiagnosticSink sink;
    /** An operand attribute of the element at an offset, as a Rockwell expression. */
    private final BiFunction<Attr, Integer, Expression> operand;

    private final IntFunction<Span> elementSpan;
    private final Set<String> aois;
    private int temps;
    /** Ids for elements the export does not have (AOI in-out arguments). */
    private int extraId = -1;

    FbdTranslator(
            DiagnosticSink sink,
            BiFunction<Attr, Integer, Expression> operand,
            IntFunction<Span> elementSpan,
            Set<String> aois) {
        this.sink = sink;
        this.operand = operand;
        this.elementSpan = elementSpan;
        this.aois = aois;
    }

    /** The routine's sheets as network statements, numbered by sheet. */
    List<Statement> translate(L5xReader.FbdDef fbd) {
        Map<Integer, List<FbdNode>> sheets = new LinkedHashMap<>();
        for (FbdNode n : fbd.nodes) {
            sheets.computeIfAbsent(n.sheet, k -> new ArrayList<>()).add(n);
        }
        // connector names whose OCon and ICon are not on one sheet: carried in a synthetic variable
        Map<String, Set<Integer>> oconSheets = new HashMap<>();
        Map<String, Set<Integer>> iconSheets = new HashMap<>();
        for (FbdNode n : fbd.nodes) {
            String name = n.attr("Name").value();
            if (name != null && n.tag.equals("OCon")) {
                oconSheets.computeIfAbsent(key(name), k -> new HashSet<>()).add(n.sheet);
            } else if (name != null && n.tag.equals("ICon")) {
                iconSheets.computeIfAbsent(key(name), k -> new HashSet<>()).add(n.sheet);
            }
        }
        Set<String> crossSheet = new HashSet<>();
        for (var e : iconSheets.entrySet()) {
            if (!oconSheets.getOrDefault(e.getKey(), Set.of()).containsAll(e.getValue())) {
                crossSheet.add(e.getKey());
            }
        }
        List<Statement> out = new ArrayList<>();
        for (var sheet : sheets.entrySet()) {
            Statement s = sheet(sheet.getKey(), sheet.getValue(), crossSheet);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private Statement sheet(int number, List<FbdNode> nodes, Set<String> crossSheet) {
        Map<Integer, Map<String, List<Link>>> into = wires(nodes);
        List<Element> elements = new ArrayList<>();
        Set<Integer> instructionBlocks = new HashSet<>();
        List<Statement> calls = new ArrayList<>();
        for (FbdNode n : nodes) {
            int id = n.intAttr("ID", Integer.MIN_VALUE);
            Span span = elementSpan.apply(n.offset);
            Map<String, List<Link>> ins = into.getOrDefault(id, Map.of());
            int x = n.intAttr("X", 0);
            int y = n.intAttr("Y", 0);
            switch (n.tag) {
                case "IRef" -> {
                    Expression v = value(n, "Operand");
                    elements.add(element(id, Kind.IN_VARIABLE, null, v, x, y, List.of(), true, v.span()));
                }
                case "ORef" -> {
                    Expression v = value(n, "Operand");
                    elements.add(element(id, Kind.OUT_VARIABLE, null, v, x, y, all(ins), false, v.span()));
                }
                case "Block", "AddOnInstruction" -> {
                    if (n.tag.equals("Block")) {
                        instructionBlocks.add(id);
                    }
                    block(n, id, x, y, ins, elements);
                }
                case "Function" -> {
                    List<String> names = new ArrayList<>(ins.keySet());
                    names.sort(Comparator.nullsFirst(FbdTranslator::natural));
                    List<Pin> pins = new ArrayList<>();
                    for (String pin : names) {
                        pins.add(new Pin(pin, false, ins.get(pin)));
                    }
                    String type = function(n.attr("Type").value());
                    elements.add(new Element(
                            id,
                            Kind.BLOCK,
                            type,
                            null,
                            null,
                            false,
                            Modifier.NONE,
                            Modifier.NONE,
                            null,
                            x,
                            y,
                            0,
                            0,
                            pins,
                            List.of(out(null)),
                            span));
                }
                case "OCon", "ICon" -> elements.add(connector(n, id, x, y, ins, crossSheet, span));
                case "JSR", "SBR", "RET" -> calls.add(routineCall(n, span));
                case "GSV", "SSV" ->
                    sink.report(Diagnostic.of(
                            L5xCodes.UNPARSED_ROUTINE,
                            Severity.INFO,
                            n.tag + " in a function block diagram is not analysed",
                            span));
                default -> {} // Wire, FeedbackWire, TextBox, Attachment: not elements of the logic
            }
        }
        if (elements.isEmpty() && calls.isEmpty()) {
            return null;
        }
        Span span = elementSpan.apply(nodes.getFirst().offset);
        for (FbdNode n : nodes) {
            span = span.union(elementSpan.apply(n.offset));
        }
        Network network = new Network(Network.Language.FBD, elements, span);
        List<Statement> lowered = new ArrayList<>(new NetworkLowering(sink, p -> p + (++temps), network)
                .memberCalls(e -> instructionBlocks.contains(e.localId()))
                .lower());
        lowered.addAll(calls);
        return new Statement.Network(number, network, lowered, span);
    }

    /** The wires into each element, by destination pin (null: the element's only input). */
    private static Map<Integer, Map<String, List<Link>>> wires(List<FbdNode> nodes) {
        Map<Integer, FbdNode> byId = new HashMap<>();
        for (FbdNode n : nodes) {
            int id = n.intAttr("ID", Integer.MIN_VALUE);
            if (id != Integer.MIN_VALUE && !isWire(n)) {
                byId.putIfAbsent(id, n);
            }
        }
        Map<Integer, Map<String, List<Link>>> into = new HashMap<>();
        for (FbdNode w : nodes) {
            if (isWire(w)) {
                int from = w.intAttr("FromID", Integer.MIN_VALUE);
                FbdNode source = byId.get(from);
                // a function has one result (Out/Dest)
                String fromPin = source != null && source.tag.equals("Function")
                        ? null
                        : w.attr("FromParam").value();
                into.computeIfAbsent(w.intAttr("ToID", Integer.MIN_VALUE), k -> new LinkedHashMap<>())
                        .computeIfAbsent(w.attr("ToParam").value(), k -> new ArrayList<>())
                        .add(new Link(from, fromPin));
            }
        }
        return into;
    }

    /** An instruction block or AOI on its backing tag; an AOI's in-out arguments become input variables. */
    private void block(FbdNode n, int id, int x, int y, Map<String, List<Link>> ins, List<Element> elements) {
        boolean aoi = n.tag.equals("AddOnInstruction");
        String type = (aoi ? n.attr("Name") : n.attr("Type")).value();
        Expression tag = value(n, "Operand");
        List<Pin> pins = new ArrayList<>();
        ins.forEach((pin, links) -> pins.add(new Pin(pin, false, links)));
        if (aoi) {
            for (Attr[] p : n.params) {
                if (p[0].present() && p[1].present()) {
                    Expression arg = operand.apply(p[1], n.offset);
                    int argId = extraId--;
                    elements.add(element(argId, Kind.IN_VARIABLE, null, arg, x, y, List.of(), true, arg.span()));
                    pins.add(new Pin(p[0].value(), false, List.of(new Link(argId, null))));
                }
            }
        }
        String instance =
                tag instanceof Expression.Invalid ? null : n.attr("Operand").value();
        elements.add(new Element(
                id,
                Kind.BLOCK,
                type == null ? "<missing>" : type,
                instance,
                tag,
                false,
                Modifier.NONE,
                Modifier.NONE,
                null,
                x,
                y,
                0,
                0,
                pins,
                List.of(out("Out")),
                tag.span()));
    }

    /** OCon/ICon: a connector and continuation on one sheet, else a synthetic variable written and read. */
    private static Element connector(
            FbdNode n, int id, int x, int y, Map<String, List<Link>> ins, Set<String> crossSheet, Span span) {
        String name = n.attr("Name").value();
        boolean ocon = n.tag.equals("OCon");
        if (name != null && crossSheet.contains(key(name))) {
            Expression carrier = carrier(name, span);
            return ocon
                    ? element(id, Kind.OUT_VARIABLE, null, carrier, x, y, all(ins), false, span)
                    : element(id, Kind.IN_VARIABLE, null, carrier, x, y, List.of(), true, span);
        }
        return ocon
                ? element(id, Kind.CONNECTOR, name, null, x, y, all(ins), false, span)
                : element(id, Kind.CONTINUATION, name, null, x, y, List.of(), true, span);
    }

    /** {@code JSR(Routine, n, in…, ret…)}, {@code SBR(param…)}, {@code RET(value…)} as in rung text. */
    private Statement routineCall(FbdNode n, Span span) {
        List<Argument> args = new ArrayList<>();
        Attr routine = n.attr("Routine");
        if (routine.present() && n.tag.equals("JSR")) {
            args.add(positional(operand.apply(routine, n.offset)));
        }
        List<Expression> in = words(n.attr("In"), n.offset);
        List<Expression> ret = words(n.attr(n.tag.equals("RET") ? "In" : "Ret"), n.offset);
        if (n.tag.equals("JSR")) {
            args.add(positional(
                    new Expression.Literal(Expression.LiteralKind.INTEGER, Integer.toString(in.size()), null, span)));
            in.forEach(e -> args.add(positional(e)));
            words(n.attr("Ret"), n.offset).forEach(e -> args.add(positional(e)));
        } else {
            ret.forEach(e -> args.add(positional(e)));
        }
        Identifier name = new Identifier(n.tag, span);
        return new Statement.CallStatement(
                new Expression.Call(new Expression.NameRef(name, false, span), args, span), span);
    }

    /** Space-separated tag names of an attribute, each with its own span. */
    private List<Expression> words(Attr a, int offset) {
        List<Expression> out = new ArrayList<>();
        if (!a.present() || a.range() == L5xReader.Range.NONE) {
            return out;
        }
        String v = a.value();
        int i = 0;
        while (i < v.length()) {
            while (i < v.length() && Character.isWhitespace(v.charAt(i))) {
                i++;
            }
            int j = i;
            while (j < v.length() && !Character.isWhitespace(v.charAt(j))) {
                j++;
            }
            if (j > i) {
                int start = a.range().start() + i;
                Attr word = new Attr(v.substring(i, j), new L5xReader.Range(start, start + (j - i)));
                out.add(operand.apply(word, offset));
            }
            i = j;
        }
        return out;
    }

    private Expression value(FbdNode n, String attr) {
        Attr a = n.attr(attr);
        return a.present() ? operand.apply(a, n.offset) : new Expression.Invalid(elementSpan.apply(n.offset));
    }

    /** The synthetic variable carrying a connector's value between sheets. */
    private static Expression carrier(String name, Span span) {
        String id = "con_" + name.replaceAll("[^A-Za-z0-9_]", "_");
        return new Expression.NameRef(new Identifier(id, Identifier.Form.SYNTHETIC, span), false, span);
    }

    /** {@code BOR__F} → {@code OR}: Logix function blocks as the block types the lowering knows. */
    static String function(String type) {
        String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (t.endsWith("__F")) {
            t = t.substring(0, t.length() - 3);
        }
        return switch (t) {
            case "BAND" -> "AND";
            case "BOR" -> "OR";
            case "BXOR" -> "XOR";
            case "BNOT" -> "NOT";
            case "GRT" -> "GT";
            case "GEQ" -> "GE";
            case "LES" -> "LT";
            case "LEQ" -> "LE";
            case "EQU" -> "EQ";
            case "NEQ" -> "NE";
            default -> t;
        };
    }

    /** {@code In2} before {@code In10}. */
    private static int natural(String a, String b) {
        String pa = a.replaceAll("\\d+$", "");
        String pb = b.replaceAll("\\d+$", "");
        if (!pa.equalsIgnoreCase(pb)) {
            return a.compareToIgnoreCase(b);
        }
        String na = a.substring(pa.length());
        String nb = b.substring(pb.length());
        if (na.isEmpty() || nb.isEmpty()) {
            return na.length() - nb.length();
        }
        return Long.compare(Long.parseLong(na.length() > 18 ? "0" : na), Long.parseLong(nb.length() > 18 ? "0" : nb));
    }

    private static List<Link> all(Map<String, List<Link>> ins) {
        List<Link> out = new ArrayList<>();
        ins.values().forEach(out::addAll);
        return out;
    }

    private static boolean isWire(FbdNode n) {
        return n.tag.equals("Wire") || n.tag.equals("FeedbackWire");
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static Argument positional(Expression e) {
        return new Argument(ArgumentKind.POSITIONAL, null, false, e, e.span());
    }

    private static Pin out(String name) {
        return new Pin(name, false, List.of());
    }

    /** A single-pin element: {@code links} into its input, or an output pin when {@code source}. */
    private static Element element(
            int id,
            Kind kind,
            String name,
            Expression value,
            int x,
            int y,
            List<Link> links,
            boolean source,
            Span span) {
        List<Pin> inputs = source ? List.of() : List.of(new Pin(null, false, links));
        List<Pin> outputs = source ? List.of(out(null)) : List.of();
        return new Element(
                id,
                kind,
                name,
                null,
                value,
                false,
                Modifier.NONE,
                Modifier.NONE,
                null,
                x,
                y,
                0,
                0,
                inputs,
                outputs,
                span);
    }
}
