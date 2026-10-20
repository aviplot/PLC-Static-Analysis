package dev.plca.ast;

import dev.plca.core.source.Span;
import java.util.List;

/**
 * A sequential function chart (IEC 61131-3 §6.7): steps with their action blocks and the transitions between steps,
 * divergences and jump steps already resolved into the source and target steps of each transition. Analysis works on
 * the chart's lowered statements ({@link Statement.Chart}). {@code drawing}: the chart as its author drew it (M10), for
 * viewers; null when the producer gave nothing to draw from.
 */
public record Sfc(List<Step> steps, List<Transition> transitions, Drawing drawing, Span span) implements Node {

    public Sfc {
        steps = List.copyOf(steps);
        transitions = List.copyOf(transitions);
    }

    public Sfc(List<Step> steps, List<Transition> transitions, Span span) {
        this(steps, transitions, null, span);
    }

    /** What a chart element is drawn as. */
    public enum Kind {
        STEP,
        MACRO_STEP,
        TRANSITION,
        SELECTION_DIVERGENCE,
        SELECTION_CONVERGENCE,
        SIMULTANEOUS_DIVERGENCE,
        SIMULTANEOUS_CONVERGENCE,
        /** A jump to the step {@code name}. */
        JUMP,
        /** The actions of the step it is linked from, one per {@code lines} entry. */
        ACTION_BLOCK,
        /** Rockwell: the end of a chart's path. */
        STOP
    }

    /**
     * The chart as drawn: its elements and, per element, the links into it. Coordinates are the producer's, the box
     * top-left; {@code width}/{@code height} 0 when not given, and all zero when the producer gave no position (a
     * viewer lays those out).
     */
    public record Drawing(List<Element> elements) {
        public Drawing {
            elements = List.copyOf(elements);
        }
    }

    /**
     * One drawn element. {@code name}: a step's or macro step's name, a jump's target. {@code lines}: text shown with
     * it (a transition's condition, an action block's actions as {@code "N Action_1"}, …). {@code initial}: an initial
     * step. {@code inputs}: links into the element, each from another element.
     */
    public record Element(
            int id,
            Kind kind,
            String name,
            boolean initial,
            List<String> lines,
            int x,
            int y,
            int width,
            int height,
            List<Link> inputs,
            Span span)
            implements Node {
        public Element {
            lines = List.copyOf(lines);
            inputs = List.copyOf(inputs);
        }
    }

    /** A link from element {@code source}; {@code route}: its points as drawn, absolute, empty when not given. */
    public record Link(int source, List<Network.Point> route) {
        public Link {
            route = List.copyOf(route);
        }
    }

    public record Step(Identifier name, boolean initial, List<Action> actions, Span span) implements Node {
        public Step {
            actions = List.copyOf(actions);
        }
    }

    /**
     * An action association: qualifier ({@code N}, {@code S}, {@code R}, {@code P}, {@code L}, …; empty means N),
     * optional duration, and either a referenced action ({@code reference}) or an inline body.
     */
    public record Action(String qualifier, String duration, Identifier reference, List<Statement> inline, Span span)
            implements Node {
        public Action {
            inline = inline == null ? null : List.copyOf(inline);
        }
    }

    /**
     * A transition: fires when all {@code from} steps are active and {@code condition} holds (a referenced transition
     * member is a {@code NameRef} to it); then the {@code from} steps are left and the {@code to} steps entered.
     */
    public record Transition(List<Identifier> from, List<Identifier> to, Expression condition, Span span)
            implements Node {
        public Transition {
            from = List.copyOf(from);
            to = List.copyOf(to);
        }
    }

    public String summary() {
        return steps.size() + " steps, " + transitions.size() + " transitions";
    }
}
