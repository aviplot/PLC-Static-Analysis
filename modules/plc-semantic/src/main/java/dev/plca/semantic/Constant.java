package dev.plca.semantic;

/** A compile-time constant value (IEC literal or folded expression). Durations are in nanoseconds. */
public sealed interface Constant {

    record Int(long value) implements Constant {}

    record Real(double value) implements Constant {}

    record Bool(boolean value) implements Constant {}

    record Duration(long nanos) implements Constant {}

    /** Numeric view (ints, reals, bools as 0/1, durations in ns), or null. */
    default Double asDouble() {
        return switch (this) {
            case Int i -> (double) i.value();
            case Real r -> r.value();
            case Bool b -> b.value() ? 1.0 : 0.0;
            case Duration d -> (double) d.nanos();
        };
    }

    default boolean isZero() {
        Double d = asDouble();
        return d != null && d == 0.0;
    }
}
