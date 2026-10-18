package dev.plca.ast;

import dev.plca.core.source.Span;
import java.util.List;
import java.util.stream.Collectors;

/** A dotted name: {@code Tc2_Standard.TON}, {@code E_State.Idle}. */
public record QualifiedName(List<Identifier> parts, Span span) implements Node {

    public QualifiedName {
        parts = List.copyOf(parts);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("empty qualified name");
        }
    }

    public Identifier last() {
        return parts.getLast();
    }

    public String key() {
        return parts.stream().map(Identifier::key).collect(Collectors.joining("."));
    }

    @Override
    public String toString() {
        return parts.stream().map(Identifier::name).collect(Collectors.joining("."));
    }
}
