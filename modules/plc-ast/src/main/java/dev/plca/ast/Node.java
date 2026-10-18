package dev.plca.ast;

import dev.plca.core.source.Span;

/** Every AST node knows the source range it was built from. */
public interface Node {
    Span span();
}
