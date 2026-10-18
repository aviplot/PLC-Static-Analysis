package dev.plca.semantic;

import dev.plca.ast.TypeRef.PointerKind;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Resolved types. {@link Alias} and {@link Subrange} keep the declared view; {@link #resolved()} strips aliases for
 * structural questions. {@link Unknown} is used for anything the project does not define (library types, errors), so
 * rules can skip it instead of reporting noise.
 */
public sealed interface Type {

    /** The type after following aliases. */
    default Type resolved() {
        return this;
    }

    String display();

    record Elementary(ElementaryKind kind) implements Type {
        @Override
        public String display() {
            return kind.name();
        }
    }

    /**
     * Type of an untyped integer literal (IEC {@code ANY_INT} constant): converts to any integer type it fits in.
     * {@code value} lets rules check the fit.
     */
    record IntLiteral(long value) implements Type {
        @Override
        public String display() {
            return "ANY_INT(" + value + ")";
        }
    }

    /** Type of an untyped real literal ({@code ANY_REAL}). */
    record RealLiteral(double value) implements Type {
        @Override
        public String display() {
            return "ANY_REAL";
        }
    }

    /** {@code STRING}/{@code WSTRING}; {@code length} is the declared or default length (80). */
    record StringType(boolean wide, long length) implements Type {
        @Override
        public String display() {
            return (wide ? "WSTRING(" : "STRING(") + length + ")";
        }
    }

    /** One dimension; bounds are null when not constant (or {@code [*]}). */
    record Dimension(Long low, Long high) {
        public boolean isVariable() {
            return low == null || high == null;
        }
    }

    record Array(List<Dimension> dimensions, Type element) implements Type {
        public Array {
            dimensions = List.copyOf(dimensions);
        }

        @Override
        public String display() {
            return "ARRAY["
                    + dimensions.stream()
                            .map(d -> d.isVariable() ? "*" : d.low() + ".." + d.high())
                            .collect(Collectors.joining(","))
                    + "] OF " + element.display();
        }
    }

    record Pointer(PointerKind kind, Type target) implements Type {
        @Override
        public String display() {
            return switch (kind) {
                        case POINTER -> "POINTER TO ";
                        case REFERENCE -> "REFERENCE TO ";
                        case REF_TO -> "REF_TO ";
                    }
                    + target.display();
        }
    }

    /** STRUCT or UNION (see {@link TypeSymbol#kind()}). */
    record Struct(TypeSymbol symbol) implements Type {
        @Override
        public String display() {
            return symbol.name();
        }
    }

    record Enum(TypeSymbol symbol) implements Type {
        @Override
        public String display() {
            return symbol.name();
        }
    }

    /** An instance of a function block, class, program or interface. */
    record Instance(PouSymbol pou) implements Type {
        @Override
        public String display() {
            return pou.name();
        }
    }

    record Alias(TypeSymbol symbol, Type target) implements Type {
        @Override
        public Type resolved() {
            return target.resolved();
        }

        @Override
        public String display() {
            return symbol.name();
        }
    }

    record Subrange(Type base, long low, long high) implements Type {
        @Override
        public String display() {
            return base.display() + "(" + low + ".." + high + ")";
        }
    }

    /** No value: statements, instance calls, functions without a result type. */
    record Void() implements Type {
        @Override
        public String display() {
            return "VOID";
        }
    }

    /** A type the project does not define (library, typo) or an expression that could not be typed. */
    record Unknown(String name) implements Type {
        @Override
        public String display() {
            return name == null ? "?" : name + "?";
        }
    }

    Type BOOL = new Elementary(ElementaryKind.BOOL);
    Type VOID = new Void();
    Type UNKNOWN = new Unknown(null);

    static Type of(ElementaryKind kind) {
        return new Elementary(kind);
    }

    /** The elementary kind after resolving aliases and subranges, or null. */
    static ElementaryKind elementary(Type t) {
        Type r = t.resolved();
        if (r instanceof Subrange s) {
            r = s.base().resolved();
        }
        return r instanceof Elementary e ? e.kind() : null;
    }

    static boolean isUnknown(Type t) {
        return t.resolved() instanceof Unknown;
    }
}
