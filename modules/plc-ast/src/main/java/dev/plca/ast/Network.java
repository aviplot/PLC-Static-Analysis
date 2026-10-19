package dev.plca.ast;

import dev.plca.core.source.Span;
import java.util.List;

/**
 * A graphical LD or FBD network as authored (PLCopen XML): elements with their pins, the connections stored at the
 * inputs, and coordinates. General enough for bridges and feedback, which the series/parallel {@link Ladder} cannot
 * hold. Analysis works on the network's lowered statements ({@link Statement.Network}); this keeps the drawing for
 * viewers and network-level rules.
 */
public record Network(Language language, List<Element> elements, Span span) implements Node {

    public Network {
        elements = List.copyOf(elements);
    }

    public enum Language {
        LD,
        FBD
    }

    public enum Kind {
        LEFT_RAIL,
        RIGHT_RAIL,
        CONTACT,
        COIL,
        BLOCK,
        IN_VARIABLE,
        OUT_VARIABLE,
        IN_OUT_VARIABLE,
        CONNECTOR,
        CONTINUATION,
        JUMP,
        LABEL,
        RETURN
    }

    /** {@code storage} of a coil: none, {@code SET}, {@code RESET}; {@code edge} of a contact/coil. */
    public enum Modifier {
        NONE,
        SET,
        RESET,
        RISING,
        FALLING
    }

    /**
     * One element. {@code name}: block type, connector/continuation name, jump/label target. {@code instance}: FB
     * instance of a block (null for functions). {@code value}: the variable or expression of contacts, coils and
     * variables. {@code order}: {@code executionOrderId} when the producer set one (else null).
     */
    public record Element(
            int localId,
            Kind kind,
            String name,
            String instance,
            Expression value,
            boolean negated,
            Modifier storage,
            Modifier edge,
            Integer order,
            int x,
            int y,
            List<Pin> inputs,
            List<Pin> outputs,
            Span span)
            implements Node {
        public Element {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }
    }

    /** A pin; {@code name} is the formal parameter (null for the single pin of contacts, coils, variables). */
    public record Pin(String name, boolean negated, List<Link> links) {
        public Pin {
            links = List.copyOf(links);
        }
    }

    /** A wire into an input: from element {@code source}, its output pin {@code sourcePin} (null: the only one). */
    public record Link(int source, String sourcePin) {}

    /** Compact summary for dumps: {@code LD 5 elements}. */
    public String summary() {
        return language + " " + elements.size() + " elements";
    }
}
