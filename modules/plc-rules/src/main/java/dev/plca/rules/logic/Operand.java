package dev.plca.rules.logic;

import dev.plca.semantic.VariableSymbol;
import java.util.Set;

/**
 * A value a condition talks about: a variable, a member, an array element, a difference {@code a - b}, or any other
 * expression without calls (keyed by its normalised text, so the same text in two places is the same value as long as
 * nothing writes in between). Equal by {@code key}.
 *
 * @param key normalised text ({@code fb.q}, {@code (a)-(b)}); never shared by two different values
 * @param display how messages show it
 * @param kind what can be said about it
 * @param domain {@link Kind#NUMBER}: the values its type can hold (integer or dense)
 * @param size {@link Kind#TEXT}: declared length, or -1; {@link Kind#NUMBER}: 32 for a REAL (constants compared with it
 *     are rounded to single precision), else 0
 * @param typed whether {@code domain} / {@code size} come from a known type (else they are a sound over-estimate)
 * @param deps the variables it reads; a write to one of them changes it
 * @param volatileValue may change without a visible write to {@code deps} (pointer, reference, VAR_IN_OUT, overlay,
 *     direct address, unresolved name, call): never kept as a fact past a statement that writes or calls anything
 * @param nonLocal reads a global or a member of the owning instance: a call to user code may change it
 * @param difference {@code a - b} of two operands (comparisons between two values)
 */
record Operand(
        String key,
        String display,
        Kind kind,
        NumSet domain,
        long size,
        boolean typed,
        Set<VariableSymbol> deps,
        boolean volatileValue,
        boolean nonLocal,
        boolean difference) {

    enum Kind {
        NUMBER,
        TEXT,
        BOOL
    }

    Operand {
        deps = Set.copyOf(deps);
    }

    /** A free proposition with no variables: a condition fixed by constants (a configuration switch). */
    static Operand proposition(String key, String display) {
        return new Operand(key, display, Kind.BOOL, null, -1, true, Set.of(), false, false, false);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Operand other && other.key.equals(key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }
}
