package dev.plca.rules.logic;

/** A comparison operator, after normalisation (the operand is always on the left). */
enum Rel {
    EQ("="),
    NE("<>"),
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">=");

    private final String text;

    Rel(String text) {
        this.text = text;
    }

    String text() {
        return text;
    }

    /** {@code NOT (x rel v)} is {@code x negate(rel) v}. */
    Rel negate() {
        return switch (this) {
            case EQ -> NE;
            case NE -> EQ;
            case LT -> GE;
            case LE -> GT;
            case GT -> LE;
            case GE -> LT;
        };
    }

    /** {@code v rel x} is {@code x mirror(rel) v}. */
    Rel mirror() {
        return switch (this) {
            case EQ, NE -> this;
            case LT -> GT;
            case LE -> GE;
            case GT -> LT;
            case GE -> LE;
        };
    }
}
