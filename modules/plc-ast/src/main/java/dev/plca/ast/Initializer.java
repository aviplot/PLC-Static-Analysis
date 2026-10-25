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

    /**
     * {@code repeat} is the count of {@code 3(0)} (1 when absent); {@code value} null for {@code 3()}. A count that is
     * a constant expression (CODESYS {@code [GVL.N + 1(0)]}) is {@code count}, and {@code repeat} is then 0: not known
     * before constants are evaluated.
     */
    record Element(int repeat, Expression count, Initializer value, Span span) implements Node {
        public Element(int repeat, Initializer value, Span span) {
            this(repeat, null, value, span);
        }
    }

    record StructInit(List<FieldInit> fields, Span span) implements Initializer {
        public StructInit {
            fields = List.copyOf(fields);
        }
    }

    /**
     * {@code a := 1}; {@code name} is null in Siemens' positional form {@code (1, (), 2)}, where {@code ()} (an empty
     * {@code StructInit}) keeps the member's default.
     */
    record FieldInit(Identifier name, Initializer value, Span span) implements Node {}
}
