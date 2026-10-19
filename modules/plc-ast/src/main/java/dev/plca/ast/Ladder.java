package dev.plca.ast;

import dev.plca.core.source.Span;
import java.util.List;

/**
 * A ladder rung as drawn (the "Ladder IR" of the architecture): a series of elements from the left rail to the right,
 * where an element is an instruction or a branch of parallel legs. Kept next to the rung's lowered statements
 * ({@link Statement.Rung}) for viewers and rung-level rules; analysis works on the lowered form.
 */
public record Ladder(List<Element> elements, Span span) implements Node {

    public Ladder {
        elements = List.copyOf(elements);
    }

    public sealed interface Element extends Node {}

    /** {@code XIC(Start)}, {@code TON(T1,?,?)}, an AOI call: mnemonic and operands as written. */
    public record Instruction(Identifier mnemonic, List<Operand> operands, Span span) implements Element {
        public Instruction {
            operands = List.copyOf(operands);
        }
    }

    /** {@code [leg ,leg ]}: parallel paths between the same two points of the rung. */
    public record Branch(List<Leg> legs, Span span) implements Element {
        public Branch {
            legs = List.copyOf(legs);
        }
    }

    /** One path of a branch; may be empty (a plain wire). */
    public record Leg(List<Element> elements, Span span) implements Node {
        public Leg {
            elements = List.copyOf(elements);
        }
    }

    public enum OperandKind {
        /** A tag, literal or expression ({@code value} is set). */
        VALUE,
        /** {@code ?}: not set (display values of timers and counters, unconfigured operands). */
        PLACEHOLDER,
        /** Nothing between two commas: {@code GSV(ControllerDevice,,DeviceName,x)}. */
        EMPTY
    }

    public record Operand(OperandKind kind, Expression value, Span span) implements Node {}

    /** The rung in neutral text form, normalised ({@code XIC(a)[XIC(b),XIO(c)]OTE(d);}). */
    public String text() {
        StringBuilder sb = new StringBuilder();
        series(elements, sb);
        return sb.append(';').toString();
    }

    private static void series(List<Element> elements, StringBuilder sb) {
        for (Element e : elements) {
            switch (e) {
                case Instruction i -> {
                    sb.append(i.mnemonic().name()).append('(');
                    for (int k = 0; k < i.operands().size(); k++) {
                        Operand o = i.operands().get(k);
                        sb.append(k > 0 ? "," : "");
                        switch (o.kind()) {
                            case VALUE -> sb.append(StText.expression(o.value()));
                            case PLACEHOLDER -> sb.append('?');
                            case EMPTY -> {}
                        }
                    }
                    sb.append(')');
                }
                case Branch b -> {
                    sb.append('[');
                    for (int k = 0; k < b.legs().size(); k++) {
                        sb.append(k > 0 ? "," : "");
                        series(b.legs().get(k).elements(), sb);
                    }
                    sb.append(']');
                }
            }
        }
    }
}
