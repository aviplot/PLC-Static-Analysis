package dev.plca.rules.logic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A normalised condition: AND / OR over literals, with NOT pushed into the literals. Structural equality, so the same
 * condition written twice ({@code a > 5} and {@code 5 < a}) is equal.
 */
sealed interface Cond {

    /** A comparison or proposition. */
    sealed interface Atom {
        Operand operand();
    }

    /** {@code operand rel value} on a number. */
    record Cmp(Operand operand, Rel rel, BigDecimal value) implements Atom {
        public Cmp {
            value = value.stripTrailingZeros();
        }
    }

    /** {@code operand = 'value'} on a text. */
    record TextEq(Operand operand, String value) implements Atom {}

    /** A BOOL value, or anything the reasoner does not look into. */
    record Prop(Operand operand) implements Atom {}

    record Const(boolean value) implements Cond {}

    /** An atom, or its negation ({@link Cmp} is always positive: its negation flips the operator). */
    record Lit(Atom atom, boolean positive) implements Cond {}

    record And(List<Cond> parts) implements Cond {
        public And {
            parts = List.copyOf(parts);
        }
    }

    record Or(List<Cond> parts) implements Cond {
        public Or {
            parts = List.copyOf(parts);
        }
    }

    Cond TRUE = new Const(true);
    Cond FALSE = new Const(false);

    static Cond lit(Atom a) {
        return new Lit(a, true);
    }

    /** Conjunction, flattened, with TRUE dropped, duplicates removed and FALSE absorbing. */
    static Cond and(List<Cond> parts) {
        Set<Cond> out = new LinkedHashSet<>();
        for (Cond p : parts) {
            switch (p) {
                case Const c -> {
                    if (!c.value()) {
                        return FALSE;
                    }
                }
                case And a -> out.addAll(a.parts());
                default -> out.add(p);
            }
        }
        return out.isEmpty() ? TRUE : out.size() == 1 ? out.iterator().next() : new And(new ArrayList<>(out));
    }

    static Cond and(Cond a, Cond b) {
        return and(List.of(a, b));
    }

    /** Disjunction, flattened, with FALSE dropped, duplicates removed and TRUE absorbing. */
    static Cond or(List<Cond> parts) {
        Set<Cond> out = new LinkedHashSet<>();
        for (Cond p : parts) {
            switch (p) {
                case Const c -> {
                    if (c.value()) {
                        return TRUE;
                    }
                }
                case Or o -> out.addAll(o.parts());
                default -> out.add(p);
            }
        }
        return out.isEmpty() ? FALSE : out.size() == 1 ? out.iterator().next() : new Or(new ArrayList<>(out));
    }

    static Cond or(Cond a, Cond b) {
        return or(List.of(a, b));
    }

    default Cond negate() {
        return switch (this) {
            case Const c -> new Const(!c.value());
            case Lit l
            when l.atom() instanceof Cmp c -> lit(new Cmp(c.operand(), c.rel().negate(), c.value()));
            case Lit l -> new Lit(l.atom(), !l.positive());
            case And a -> or(a.parts().stream().map(Cond::negate).toList());
            case Or o -> and(o.parts().stream().map(Cond::negate).toList());
        };
    }

    /** Every literal, left to right. */
    default void literals(Consumer<Lit> action) {
        switch (this) {
            case Const c -> {}
            case Lit l -> action.accept(l);
            case And a -> a.parts().forEach(p -> p.literals(action));
            case Or o -> o.parts().forEach(p -> p.literals(action));
        }
    }

    default int size() {
        int[] n = {0};
        literals(l -> n[0]++);
        return n[0];
    }

    /** The operands, without duplicates, in order of appearance. */
    default Set<Operand> operands() {
        Set<Operand> out = new LinkedHashSet<>();
        literals(l -> out.add(l.atom().operand()));
        return out;
    }
}
