package dev.plca.ast;

import dev.plca.core.source.Span;
import java.util.List;

/** ST expressions (IEC 7.3.2). Parentheses are not represented; precedence is in the tree shape. */
public sealed interface Expression extends Node, Initializer {

    enum LiteralKind {
        INTEGER,
        REAL,
        BOOL,
        STRING,
        WSTRING,
        TIME,
        DATE,
        TIME_OF_DAY,
        DATE_AND_TIME,
        /** {@code E_Color#Red}: a typed literal whose value is a name. */
        ENUM
    }

    /**
     * A literal; {@code text} is the source text of the value without the type prefix, {@code typePrefix} the
     * {@code INT} of {@code INT#5} (null when absent). Values are decoded by the semantic model.
     */
    record Literal(LiteralKind kind, String text, String typePrefix, Span span) implements Expression {}

    /** A name; {@code globalScope} for CODESYS {@code .name} (global scope operator). */
    record NameRef(Identifier name, boolean globalScope, Span span) implements Expression {}

    /** A type used as an operand: {@code SIZEOF(POINTER TO T)}, {@code SIZEOF(STRING)}. */
    record TypeOperand(TypeRef type, Span span) implements Expression {}

    record ThisRef(Span span) implements Expression {}

    record SuperRef(Span span) implements Expression {}

    /** {@code %IX0.1}, {@code %QW10}, {@code %I*}; SCL also {@code %DB1.DBX0.0} and classic {@code DB10.DBW2}. */
    record DirectAddress(String text, Span span) implements Expression {}

    record MemberAccess(Expression target, Identifier member, Span span) implements Expression {}

    /** {@code word.3} */
    record BitAccess(Expression target, int bit, Span span) implements Expression {}

    /** Rockwell indirect bit {@code word.[i]}: the bit selected by an expression. */
    record IndirectBit(Expression target, Expression bit, Span span) implements Expression {}

    /** {@code p^} */
    record Deref(Expression target, Span span) implements Expression {}

    /** SCL slice access {@code w.%X3}, {@code d.%B1}: element {@code index} of the given width inside a bit string. */
    record Slice(Expression target, SliceWidth width, int index, Span span) implements Expression {}

    enum SliceWidth {
        BIT,
        BYTE,
        WORD,
        DWORD
    }

    record Index(Expression target, List<Expression> indices, Span span) implements Expression {
        public Index {
            indices = List.copyOf(indices);
        }
    }

    record Call(Expression callee, List<Argument> arguments, Span span) implements Expression {
        public Call {
            arguments = List.copyOf(arguments);
        }
    }

    enum ArgumentKind {
        POSITIONAL,
        /** {@code name := value} */
        INPUT,
        /** {@code name => target}; {@code negated} for {@code NOT name => target} */
        OUTPUT
    }

    /** A call argument; {@code value} may be null for {@code Q => } (output ignored). */
    record Argument(ArgumentKind kind, Identifier name, boolean negated, Expression value, Span span) implements Node {}

    enum UnaryOp {
        NEGATE,
        PLUS,
        NOT
    }

    record Unary(UnaryOp op, Expression operand, Span span) implements Expression {}

    enum BinaryOp {
        POWER,
        MUL,
        DIV,
        MOD,
        ADD,
        SUB,
        LT,
        GT,
        LE,
        GE,
        EQ,
        NE,
        AND,
        AND_THEN,
        XOR,
        OR,
        OR_ELSE
    }

    record Binary(BinaryOp op, Expression left, Expression right, Span span) implements Expression {}

    /**
     * CODESYS ExST: an assignment used as an expression, {@code IF (n := FIND(s, 'x')) > 0 THEN}. Writes {@code target}
     * and yields {@code value}. Statement-level assignments are {@link Statement.Assignment}, never this.
     */
    record Assign(Expression target, Expression value, Span span) implements Expression {}

    /** Placeholder for a region that did not parse; a diagnostic has been reported. */
    record Invalid(Span span) implements Expression {}
}
