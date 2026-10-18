package dev.plca.ast;

import dev.plca.core.source.Span;
import java.util.List;

/** A type as written in a declaration. Names are resolved by the semantic model. */
public sealed interface TypeRef extends Node {

    /**
     * {@code INT}, {@code Tc2_Standard.TON}; with a subrange for {@code INT(0..100)}, TwinCAT generic arguments
     * {@code FB<5>}, or FB_init constructor arguments {@code FB_X(1, b := 2)} ({@code init}, null when absent).
     */
    record Named(
            QualifiedName name,
            List<Expression> genericArguments,
            Expression rangeLow,
            Expression rangeHigh,
            ConstructorArgs init,
            Span span)
            implements TypeRef {
        public Named {
            genericArguments = List.copyOf(genericArguments);
        }

        public boolean isSubrange() {
            return rangeLow != null;
        }
    }

    /** FB_init arguments of an instance declaration. */
    record ConstructorArgs(List<Expression.Argument> arguments, Span span) implements Node {
        public ConstructorArgs {
            arguments = List.copyOf(arguments);
        }
    }

    /** {@code STRING}, {@code STRING(80)}, {@code WSTRING[20]}; {@code length} null when unspecified. */
    record StringType(boolean wide, Expression length, Span span) implements TypeRef {}

    /** {@code elementInits}: per-element FB_init arguments, {@code OF FB_X[(a := 1), (a := 2)]}. */
    record ArrayType(List<Dimension> dimensions, TypeRef element, List<ConstructorArgs> elementInits, Span span)
            implements TypeRef {
        public ArrayType {
            dimensions = List.copyOf(dimensions);
            elementInits = List.copyOf(elementInits);
        }
    }

    /** {@code lo..hi}, or {@code *} (variable-length, both bounds null). */
    record Dimension(Expression low, Expression high, Span span) implements Node {
        public boolean isVariable() {
            return low == null;
        }
    }

    enum PointerKind {
        /** CODESYS {@code POINTER TO} */
        POINTER,
        /** CODESYS {@code REFERENCE TO} */
        REFERENCE,
        /** IEC {@code REF_TO} */
        REF_TO
    }

    record PointerType(PointerKind kind, TypeRef target, Span span) implements TypeRef {}

    record StructType(List<VarDecl> members, Span span) implements TypeRef {
        public StructType {
            members = List.copyOf(members);
        }
    }

    record UnionType(List<VarDecl> members, Span span) implements TypeRef {
        public UnionType {
            members = List.copyOf(members);
        }
    }

    /** {@code (A, B := 5) INT}: {@code baseType} null when not given. */
    record EnumType(List<EnumValue> values, Named baseType, Span span) implements TypeRef {
        public EnumType {
            values = List.copyOf(values);
        }
    }

    record EnumValue(Identifier name, Expression value, Span span) implements Node {}

    record Invalid(Span span) implements TypeRef {}
}
