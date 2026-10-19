package dev.plca.ast;

import dev.plca.core.source.Span;
import java.util.List;

/**
 * A sequential function chart (IEC 61131-3 §6.7): steps with their action blocks and the transitions between steps,
 * divergences and jump steps already resolved into the source and target steps of each transition. Analysis works on
 * the chart's lowered statements ({@link Statement.Chart}).
 */
public record Sfc(List<Step> steps, List<Transition> transitions, Span span) implements Node {

    public Sfc {
        steps = List.copyOf(steps);
        transitions = List.copyOf(transitions);
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
