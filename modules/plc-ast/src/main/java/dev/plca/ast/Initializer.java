package dev.plca.ast;

import dev.plca.core.source.Span;
import java.util.List;

/** Initial value of a variable or type: an expression, {@code [1, 2, 3(0)]} or {@code (a := 1, b := 2)}. */
public sealed interface Initializer extends Node permits Expression, Initializer.ArrayInit, Initializer.StructInit {

    record ArrayInit(List<Element> elements, Span span) implements Initializer {
        public ArrayInit {
            elements = List.copyOf(elements);
        }
    }

    /** {@code repeat} is the count of {@code 3(0)} (1 when absent); {@code value} null for {@code 3()}. */
    record Element(int repeat, Initializer value, Span span) implements Node {}

    record StructInit(List<FieldInit> fields, Span span) implements Initializer {
        public StructInit {
            fields = List.copyOf(fields);
        }
    }

    record FieldInit(Identifier name, Initializer value, Span span) implements Node {}
}
