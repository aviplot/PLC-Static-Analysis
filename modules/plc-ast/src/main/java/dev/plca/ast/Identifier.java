package dev.plca.ast;

import dev.plca.core.source.Span;
import java.util.Locale;
import java.util.Objects;

/** A name as written in the source. IEC names are case-insensitive: compare with {@link #is} or {@link #key}. */
public record Identifier(String name, Span span) implements Node {

    public Identifier {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(span, "span");
    }

    /** Case-folded form for symbol tables. */
    public String key() {
        return name.toLowerCase(Locale.ROOT);
    }

    public boolean is(String other) {
        return name.equalsIgnoreCase(other);
    }

    @Override
    public String toString() {
        return name;
    }
}
