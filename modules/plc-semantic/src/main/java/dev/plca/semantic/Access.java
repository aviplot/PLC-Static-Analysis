package dev.plca.semantic;

import dev.plca.ast.Expression;
import dev.plca.core.source.Span;

/**
 * One read or write of a variable. {@code path} selects inside it: {@code ""} for the whole variable, {@code ".Q"},
 * {@code "[3].x"}, {@code "[*]"} (non-constant index), {@code "^"} (dereference), {@code ".3"} (bit).
 */
public record Access(VariableSymbol variable, String path, Kind kind, Expression expression, Span span) {

    public enum Kind {
        READ,
        WRITE,
        /** VAR_IN_OUT argument: may be read and written by the callee. */
        READ_WRITE,
        /** Address taken ({@code ADR(x)}, {@code REF(x)}, {@code r REF= x}): aliasing, not a read. */
        ADDRESS,
        /**
         * Operand of an instruction the analyzer does not know (Rockwell): it may be read or written. Counts as a use,
         * never as a write, so rules neither invent nor hide a write by guessing.
         */
        UNKNOWN
    }

    public boolean isWrite() {
        return kind == Kind.WRITE || kind == Kind.READ_WRITE;
    }

    public boolean isRead() {
        return kind == Kind.READ || kind == Kind.READ_WRITE || kind == Kind.UNKNOWN;
    }

    /** {@code var.path}, e.g. {@code fbTimer.Q}. */
    public String target() {
        return variable.name() + path;
    }

    @Override
    public String toString() {
        return kind + " " + target();
    }
}
