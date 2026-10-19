package dev.plca.ast;

import dev.plca.core.source.Span;
import java.util.Locale;
import java.util.Objects;

/**
 * A name as written in the source. IEC names are case-insensitive: compare with {@link #is} or {@link #key}.
 *
 * <p>{@code name} never includes quotes or prefixes; {@code form} records how it was written, which decides scoping in
 * Siemens SCL ({@code #x} is a block-local, {@code "x"} a global block, DB or tag). Quoted names are case-insensitive
 * too.
 */
public record Identifier(String name, Form form, Span span) implements Node {

    public enum Form {
        /** {@code x}: every IEC dialect. */
        PLAIN,
        /** SCL {@code "x"}: a global name (block, DB, UDT, PLC tag). */
        QUOTED,
        /** SCL {@code #x} or {@code #"x"}: a local of the enclosing block. */
        LOCAL
    }

    public Identifier {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(span, "span");
    }

    public Identifier(String name, Span span) {
        this(name, Form.PLAIN, span);
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
        return switch (form) {
            case PLAIN -> name;
            case QUOTED -> '"' + name + '"';
            case LOCAL -> name.matches("[\\p{L}_][\\p{L}\\p{N}_]*") ? "#" + name : "#\"" + name + '"';
        };
    }
}
