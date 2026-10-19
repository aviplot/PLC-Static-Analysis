package dev.plca.ast;

import dev.plca.core.source.Location;
import dev.plca.core.source.Span;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.function.Function;

/**
 * Deterministic, human-readable dump of an AST for golden tests and debugging. Generic over records: every node prints
 * its simple type name, its position, scalar components inline and child nodes indented, so new node types need no
 * printer changes.
 *
 * <pre>
 * Pou kind=FUNCTION_BLOCK name=FB_Motor @3:1-12:19
 *   varBlocks:
 *     VarBlock section=INPUT @4:1-6:8
 * </pre>
 */
public final class AstPrinter {

    private final Function<Span, Location> resolver;
    private final StringBuilder out = new StringBuilder();

    private AstPrinter(Function<Span, Location> resolver) {
        this.resolver = resolver;
    }

    /** Prints {@code unit}; {@code resolver} turns spans into positions (typically {@code SourceRegistry::resolve}). */
    public static String print(SourceUnit unit, Function<Span, Location> resolver) {
        AstPrinter p = new AstPrinter(resolver);
        p.out.append("SourceUnit dialect=").append(unit.dialect()).append('\n');
        for (Declaration d : unit.declarations()) {
            p.node(d, 1);
        }
        if (!unit.pragmas().isEmpty()) {
            p.indent(1).append("pragmas:\n");
            for (Pragma pr : unit.pragmas()) {
                p.node(pr, 2);
            }
        }
        p.indent(1).append("comments: ").append(unit.comments().size()).append('\n');
        return p.out.toString();
    }

    /** Prints a single node (used by tests of individual constructs). */
    public static String print(Node node, Function<Span, Location> resolver) {
        AstPrinter p = new AstPrinter(resolver);
        p.node(node, 0);
        return p.out.toString();
    }

    private void node(Object node, int depth) {
        Class<?> type = node.getClass();
        indent(depth).append(type.getSimpleName());
        RecordComponent[] components = type.isRecord() ? type.getRecordComponents() : new RecordComponent[0];
        // inline scalars first, then children
        for (RecordComponent c : components) {
            Object v = value(c, node);
            if (v == null || v instanceof Span || isChild(v)) {
                continue;
            }
            if (v instanceof Collection<?> col && col.isEmpty()) {
                continue;
            }
            if (node instanceof Pragma && c.getName().equals("text")) {
                continue;
            }
            out.append(' ').append(c.getName()).append('=').append(scalar(v));
        }
        if (node instanceof Node n) {
            out.append(' ').append(position(n.span()));
        }
        out.append('\n');
        for (RecordComponent c : components) {
            Object v = value(c, node);
            if (v instanceof Collection<?> col && !col.isEmpty() && isChild(v)) {
                indent(depth + 1).append(c.getName()).append(":\n");
                for (Object item : col) {
                    node(item, depth + 2);
                }
            } else if (v instanceof Node child && isChild(v)) {
                indent(depth + 1).append(c.getName()).append(":\n");
                node(child, depth + 2);
            }
        }
    }

    /** Identifiers and qualified names print inline; other nodes are children. */
    private static boolean isChild(Object v) {
        if (v instanceof Collection<?> col) {
            return !col.isEmpty() && col.iterator().next() instanceof Node n && isChild(n);
        }
        return v instanceof Node && !(v instanceof Identifier) && !(v instanceof QualifiedName);
    }

    private static String scalar(Object v) {
        if (v instanceof QualifiedName q) {
            // spelled with each part's form (`"FB_Motor"`, `#x`), unlike QualifiedName.toString used for lookups
            return q.parts().stream().map(Identifier::toString).collect(java.util.stream.Collectors.joining("."));
        }
        if (v instanceof String s) {
            return '"' + s.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + '"';
        }
        if (v instanceof Collection<?> col) {
            return col.stream().map(AstPrinter::scalar).sorted().toList().toString();
        }
        return String.valueOf(v);
    }

    private String position(Span s) {
        Location l = resolver.apply(s);
        return "@" + l.startLine() + ":" + l.startColumn() + "-" + l.endLine() + ":" + l.endColumn();
    }

    private static Object value(RecordComponent c, Object node) {
        try {
            return c.getAccessor().invoke(node);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private StringBuilder indent(int depth) {
        return out.append("  ".repeat(depth));
    }
}
