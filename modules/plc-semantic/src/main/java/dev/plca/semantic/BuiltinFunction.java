package dev.plca.semantic;

import dev.plca.ast.Node;
import java.util.List;
import java.util.function.Function;

/**
 * A standard function or operator ({@code ABS}, {@code LIMIT}, {@code INT_TO_REAL}, {@code ADR}, {@code SIZEOF}).
 * {@code resultType} computes the result from the argument types (generic IEC functions are overloaded).
 */
public record BuiltinFunction(String name, Function<List<Type>, Type> resultType) implements Symbol {
    @Override
    public Node declaration() {
        return null;
    }
}
