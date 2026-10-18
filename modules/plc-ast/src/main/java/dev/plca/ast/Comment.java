package dev.plca.ast;

import dev.plca.core.source.Span;

/** A comment's location; its text is the span's slice of the source text. */
public record Comment(Kind kind, Span span) implements Node {

    public enum Kind {
        /** {@code (* … *)} or a C-style block comment, possibly nested. */
        BLOCK,
        /** {@code // …} */
        LINE
    }
}
