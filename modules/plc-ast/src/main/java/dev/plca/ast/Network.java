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
     * variables. {@code order}: {@code executionOrderId} when the producer set one (else null). {@code x}, {@code y},
     * {@code width}, {@code height}: the element's box as drawn (0 when the producer gave none).
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
            int width,
            int height,
            List<Pin> inputs,
            List<Pin> outputs,
            Span span)
            implements Node {
        public Element {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }
    }

    /**
     * A pin; {@code name} is the formal parameter (null for the single pin of contacts, coils, variables). {@code at}:
     * its position relative to the element ({@code relPosition}), null when not given.
     */
    public record Pin(String name, boolean negated, List<Link> links, Point at) {
        public Pin {
            links = List.copyOf(links);
        }

        public Pin(String name, boolean negated, List<Link> links) {
            this(name, negated, links, null);
        }
    }

    /**
     * A wire into an input: from element {@code source}, its output pin {@code sourcePin} (null: the only one).
     * {@code route}: the wire's points as drawn, absolute, from the input back to the source (empty when not given).
     */
    public record Link(int source, String sourcePin, List<Point> route) {
        public Link {
            route = List.copyOf(route);
        }

        public Link(int source, String sourcePin) {
            this(source, sourcePin, List.of());
        }
    }

    /** A point of the drawing, in the producer's units. */
    public record Point(int x, int y) {}

    /** Compact summary for dumps: {@code LD 5 elements}. */
    public String summary() {
        return language + " " + elements.size() + " elements";
    }
}
