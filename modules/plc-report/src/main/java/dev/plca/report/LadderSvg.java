package dev.plca.report;

import dev.plca.ast.Comment;
import dev.plca.ast.Ladder;
import dev.plca.ast.Ladder.Branch;
import dev.plca.ast.Ladder.Element;
import dev.plca.ast.Ladder.Instruction;
import dev.plca.ast.Ladder.Leg;
import dev.plca.ast.Ladder.Operand;
import dev.plca.ast.StText;
import dev.plca.ast.Statement;
import dev.plca.ast.ladder.InstructionSet;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.SourceText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Draws a Rockwell rung ({@link Ladder}) as SVG, the way Studio 5000 shows it: left and right rails, a series of
 * elements from left to right, branches as stacked legs, outputs pushed against the right rail. Contacts and coils get
 * their symbols with the tag above; every other instruction is a box with its operands.
 *
 * <p>Layout is two passes over the series/parallel tree: {@link #measure} gives each element its width, height and the
 * height of its wire; {@link #draw} places it. Instructions and operands carry {@code data-range} ({@link Svg}).
 */
public final class LadderSvg {

    static final int GUTTER = 44;
    static final int GAP = 12;
    static final int LABEL = 16;
    static final int BRANCH_PAD = 10;
    static final int LEG_GAP = 6;
    static final int ROW = 16;
    static final int MAX_TAG = 28;
    static final int MIN_WIDTH = 480;
    static final int COMMENT_CHARS = 110;

    /** Operand labels of common instructions, as Studio 5000 prints them in the box. */
    private static final Map<String, List<String>> LABELS = Map.ofEntries(
            Map.entry("TON", List.of("Timer", "Preset", "Accum")),
            Map.entry("TOF", List.of("Timer", "Preset", "Accum")),
            Map.entry("RTO", List.of("Timer", "Preset", "Accum")),
            Map.entry("CTU", List.of("Counter", "Preset", "Accum")),
            Map.entry("CTD", List.of("Counter", "Preset", "Accum")),
            Map.entry("MOV", List.of("Source", "Dest")),
            Map.entry("ADD", List.of("Source A", "Source B", "Dest")),
            Map.entry("SUB", List.of("Source A", "Source B", "Dest")),
            Map.entry("MUL", List.of("Source A", "Source B", "Dest")),
            Map.entry("DIV", List.of("Source A", "Source B", "Dest")),
            Map.entry("MOD", List.of("Source A", "Source B", "Dest")),
            Map.entry("EQU", List.of("Source A", "Source B")),
            Map.entry("NEQ", List.of("Source A", "Source B")),
            Map.entry("LES", List.of("Source A", "Source B")),
            Map.entry("GRT", List.of("Source A", "Source B")),
            Map.entry("LEQ", List.of("Source A", "Source B")),
            Map.entry("GEQ", List.of("Source A", "Source B")),
            Map.entry("LIM", List.of("Low Limit", "Test", "High Limit")),
            Map.entry("COP", List.of("Source", "Dest", "Length")),
            Map.entry("CPS", List.of("Source", "Dest", "Length")),
            Map.entry("CLR", List.of("Dest")),
            Map.entry("CPT", List.of("Dest", "Expression")),
            Map.entry("OSR", List.of("Storage Bit", "Output Bit")),
            Map.entry("OSF", List.of("Storage Bit", "Output Bit")),
            Map.entry("MSG", List.of("Message Control")),
            Map.entry("GSV", List.of("Class Name", "Instance Name", "Attribute Name", "Dest")),
            Map.entry("SSV", List.of("Class Name", "Instance Name", "Attribute Name", "Source")),
            Map.entry("JSR", List.of("Routine Name", "Input Par Count")));

    /** Width, height and wire height of a laid-out element (local coordinates). */
    record Size(int w, int h, int wire) {}

    private enum Shape {
        CONTACT,
        COIL,
        BOX
    }

    private final Svg svg;

    private LadderSvg(SourceRegistry registry) {
        this.svg = new Svg(registry);
    }

    /** One rung as a standalone SVG, at least {@code minWidth} wide (so the rungs of a routine line up). */
    public static String rung(Statement.Rung rung, SourceRegistry registry, int minWidth) {
        LadderSvg l = new LadderSvg(registry);
        return l.render(rung, registry, minWidth);
    }

    /** The natural width of a rung, to pick a common {@code minWidth} for a routine. */
    public static int naturalWidth(Statement.Rung rung) {
        if (rung.ladder() == null) {
            return MIN_WIDTH;
        }
        Size s = series(rung.ladder().elements());
        return Math.max(MIN_WIDTH, GUTTER + GAP + s.w() + GAP * 3 + 8);
    }

    private String render(Statement.Rung rung, SourceRegistry registry, int minWidth) {
        List<String> comment = commentLines(rung.comment(), registry);
        int top = 6 + comment.size() * 15 + (comment.isEmpty() ? 0 : 4);
        for (int i = 0; i < comment.size(); i++) {
            svg.text("cmt", GUTTER, 16 + i * 15, null, comment.get(i), null, null);
        }
        Ladder ladder = rung.ladder();
        if (ladder == null) {
            int width = Math.max(minWidth, MIN_WIDTH);
            int wire = top + 14;
            svg.text("num", GUTTER - 6, wire + 4, "end", Integer.toString(rung.number()), null, null);
            svg.line("rail", GUTTER, top, GUTTER, top + 28);
            svg.line("rail", width - 8, top, width - 8, top + 28);
            svg.text("err", GUTTER + GAP, wire + 4, null, "rung text could not be parsed", null, rung.span());
            return svg.document(width, top + 34, "ladder");
        }
        List<Element> elements = ladder.elements();
        Size body = series(elements);
        int natural = GUTTER + GAP + body.w() + GAP * 3 + 8;
        int width = Math.max(Math.max(minWidth, MIN_WIDTH), natural);
        int right = width - 8;
        int wire = top + body.wire();
        int bottom = top + Math.max(body.h(), body.wire() + 14);
        svg.text("num", GUTTER - 8, wire + 4, "end", Integer.toString(rung.number()), null, null);
        svg.line("rail", GUTTER, top, GUTTER, bottom + 4);
        svg.line("rail", right, top, right, bottom + 4);
        svg.open("rung", ladder.span());
        // outputs at the end of the rung sit against the right rail: the slack goes before them
        int split = trailingOutputs(elements);
        int slack = right - GAP - (GUTTER + GAP + body.w());
        int x = GUTTER;
        svg.line("w", x, wire, x + GAP, wire);
        x += GAP;
        x = drawSeries(elements.subList(0, split), x, top, body.wire());
        if (slack > 0) {
            svg.line("w", x, wire, x + slack, wire);
            x += slack;
        }
        x = drawSeries(elements.subList(split, elements.size()), x, top, body.wire());
        svg.line("w", x, wire, right, wire);
        svg.close();
        return svg.document(width, bottom + 8, "ladder");
    }

    private static List<String> commentLines(Comment c, SourceRegistry registry) {
        List<String> out = new ArrayList<>();
        if (c == null || registry == null) {
            return out;
        }
        String text;
        try {
            SourceText t = registry.text(c.span().textId());
            text = t.text().substring(c.span().start(), c.span().end());
        } catch (RuntimeException e) {
            return out;
        }
        for (String line : text.strip().split("\\R")) {
            String s = line.stripTrailing();
            while (s.length() > COMMENT_CHARS) {
                int cut = s.lastIndexOf(' ', COMMENT_CHARS);
                cut = cut <= 0 ? COMMENT_CHARS : cut;
                out.add(s.substring(0, cut));
                s = s.substring(cut).stripLeading();
            }
            out.add(s);
            if (out.size() >= 12) {
                out.add("…");
                break;
            }
        }
        return out;
    }

    /** Index of the first element of the rung's trailing run of outputs (elements.size() when there is none). */
    private static int trailingOutputs(List<Element> elements) {
        int k = elements.size();
        while (k > 0 && outputOnly(elements.get(k - 1))) {
            k--;
        }
        return k == 0 ? elements.size() : k; // a rung of outputs only: no slack in front of everything
    }

    private static boolean outputOnly(Element e) {
        return switch (e) {
            case Instruction i ->
                InstructionSet.lookup(i.mnemonic().name())
                        .map(d -> d.kind() != InstructionSet.Kind.INPUT)
                        .orElse(false);
            case Branch b ->
                b.legs().stream()
                        .allMatch(l ->
                                !l.elements().isEmpty() && l.elements().stream().allMatch(LadderSvg::outputOnly));
        };
    }

    // ---- measure -------------------------------------------------------------------------------

    static Size series(List<Element> elements) {
        int w = 0;
        int wire = 0;
        int below = 0;
        for (int k = 0; k < elements.size(); k++) {
            Size s = measure(elements.get(k));
            w += s.w() + (k > 0 ? GAP : 0);
            wire = Math.max(wire, s.wire());
            below = Math.max(below, s.h() - s.wire());
        }
        if (elements.isEmpty()) {
            return new Size(0, LABEL + 14, LABEL + 6);
        }
        return new Size(w, wire + below, wire);
    }

    static Size measure(Element e) {
        return switch (e) {
            case Instruction i -> instruction(i);
            case Branch b -> {
                int w = 0;
                int h = 0;
                int wire = -1;
                for (Leg leg : b.legs()) {
                    Size s = series(leg.elements());
                    w = Math.max(w, s.w());
                    if (wire < 0) {
                        wire = s.wire();
                    }
                    h += s.h() + (h > 0 ? LEG_GAP : 0);
                }
                yield new Size(w + 2 * BRANCH_PAD, h, Math.max(wire, 0));
            }
        };
    }

    private static Shape shape(String mnemonic) {
        return switch (mnemonic.toUpperCase(Locale.ROOT)) {
            case "XIC", "XIO", "ONS" -> Shape.CONTACT;
            case "OTE", "OTL", "OTU", "RES" -> Shape.COIL;
            default -> Shape.BOX;
        };
    }

    private static Size instruction(Instruction i) {
        String mn = i.mnemonic().name();
        if (shape(mn) != Shape.BOX) {
            String tag =
                    i.operands().isEmpty() ? "" : Svg.elide(operand(i.operands().getFirst()), MAX_TAG);
            int w = Math.max(44, Svg.textWidth(tag) + 8);
            return new Size(w, LABEL + 22, LABEL + 10);
        }
        int rows = i.operands().size();
        List<String> labels = LABELS.getOrDefault(mn.toUpperCase(Locale.ROOT), List.of());
        int lw = 0;
        int vw = 0;
        for (int k = 0; k < rows; k++) {
            lw = Math.max(lw, Svg.textWidth(k < labels.size() ? labels.get(k) : ""));
            vw = Math.max(vw, Svg.textWidth(Svg.elide(operand(i.operands().get(k)), MAX_TAG)));
        }
        int w = Math.max(Math.max(96, Svg.textWidth(mn) + 24), lw + vw + (lw > 0 ? 28 : 16));
        return new Size(w, 22 + rows * ROW + 6, 11);
    }

    private static String operand(Operand o) {
        return switch (o.kind()) {
            case VALUE -> StText.expression(o.value());
            case PLACEHOLDER -> "?";
            case EMPTY -> "";
        };
    }

    // ---- draw ----------------------------------------------------------------------------------

    /** Draws a series whose wire is at {@code y + wire}; returns the x after the last element. */
    private int drawSeries(List<Element> elements, int x, int y, int wire) {
        for (int k = 0; k < elements.size(); k++) {
            Element e = elements.get(k);
            Size s = measure(e);
            if (k > 0) {
                svg.line("w", x, y + wire, x + GAP, y + wire);
                x += GAP;
            }
            draw(e, s, x, y + wire - s.wire());
            x += s.w();
        }
        return x;
    }

    private void draw(Element e, Size s, int x, int y) {
        switch (e) {
            case Instruction i -> drawInstruction(i, s, x, y);
            case Branch b -> {
                int legY = y;
                int firstWire = -1;
                int lastWire = y + s.wire();
                for (Leg leg : b.legs()) {
                    Size ls = series(leg.elements());
                    int wy = legY + ls.wire();
                    firstWire = firstWire < 0 ? wy : firstWire;
                    lastWire = wy;
                    svg.line("w", x, wy, x + BRANCH_PAD, wy);
                    int end = drawSeries(leg.elements(), x + BRANCH_PAD, legY, ls.wire());
                    svg.line("w", end, wy, x + s.w(), wy);
                    legY += ls.h() + LEG_GAP;
                }
                if (firstWire >= 0 && lastWire > firstWire) {
                    svg.line("w", x, firstWire, x, lastWire);
                    svg.line("w", x + s.w(), firstWire, x + s.w(), lastWire);
                }
            }
        }
    }

    private void drawInstruction(Instruction i, Size s, int x, int y) {
        String mn = i.mnemonic().name();
        String upper = mn.toUpperCase(Locale.ROOT);
        InstructionSet.Kind kind =
                InstructionSet.lookup(mn).map(InstructionSet.Instruction::kind).orElse(null);
        String cls = "ins " + (kind == null ? "unknown" : kind.name().toLowerCase(Locale.ROOT)) + " m-"
                + upper.replaceAll("[^A-Z0-9_]", "_");
        svg.open(cls, i.span());
        svg.rect("hit", x - 2, y, s.w() + 4, s.h(), 0);
        Shape shape = shape(mn);
        if (shape != Shape.BOX) {
            int wy = y + s.wire();
            int cx = x + s.w() / 2;
            if (!i.operands().isEmpty()) {
                Operand o = i.operands().getFirst();
                String full = operand(o);
                svg.text("op", cx, y + LABEL - 4, "middle", Svg.elide(full, MAX_TAG), full, o.span());
            }
            if (shape == Shape.CONTACT) {
                svg.line("w", x, wy, cx - 6, wy);
                svg.line("w", cx + 6, wy, x + s.w(), wy);
                svg.line("sym", cx - 6, wy - 9, cx - 6, wy + 9);
                svg.line("sym", cx + 6, wy - 9, cx + 6, wy + 9);
                if (upper.equals("XIO")) {
                    svg.line("sym", cx - 4, wy + 7, cx + 4, wy - 7);
                } else if (upper.equals("ONS")) {
                    svg.text("lbl", cx, wy + 20, "middle", "ONS", null, null);
                }
            } else {
                int half = upper.equals("RES") ? 18 : 9; // room for the three letters
                svg.line("w", x, wy, cx - half, wy);
                svg.line("w", cx + half, wy, x + s.w(), wy);
                svg.path("sym", "M" + (cx - half + 4) + "," + (wy - 9) + " a12,12 0 0,0 0,18");
                svg.path("sym", "M" + (cx + half - 4) + "," + (wy - 9) + " a12,12 0 0,1 0,18");
                String letter = switch (upper) {
                    case "OTL" -> "L";
                    case "OTU" -> "U";
                    case "RES" -> "RES";
                    default -> null;
                };
                if (letter != null) {
                    svg.text("mn", cx, wy + 4, "middle", letter, null, null);
                }
            }
            svg.close();
            return;
        }
        svg.rect("box", x, y + 2, s.w(), s.h() - 4, 2);
        svg.text("mn", x + s.w() / 2.0, y + 16, "middle", mn, null, i.mnemonic().span());
        List<String> labels = LABELS.getOrDefault(upper, List.of());
        for (int k = 0; k < i.operands().size(); k++) {
            Operand o = i.operands().get(k);
            int ry = y + 22 + k * ROW + 12;
            String label = k < labels.size() ? labels.get(k) : "";
            int vx = x + 8;
            if (!label.isEmpty()) {
                svg.text("lbl", vx, ry, null, label, null, null);
                vx = x + s.w() - 8;
            }
            String full = operand(o);
            svg.text("op", vx, ry, label.isEmpty() ? null : "end", Svg.elide(full, MAX_TAG), full, o.span());
        }
        svg.close();
    }
}
