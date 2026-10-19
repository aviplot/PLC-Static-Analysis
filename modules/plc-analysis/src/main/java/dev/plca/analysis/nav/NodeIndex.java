package dev.plca.analysis.nav;

import dev.plca.ast.Declaration;
import dev.plca.ast.Node;
import dev.plca.ast.SourceUnit;
import dev.plca.core.source.Span;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Every AST node of a unit by text and offset, with its parent: what is under the cursor. Built by a generic walk over
 * the record components (like {@link dev.plca.ast.AstPrinter}), so new node types need no changes here.
 *
 * <p>A position can lie in several unrelated subtrees: a Rockwell rung keeps its drawn {@link dev.plca.ast.Ladder} and
 * the statements lowered from it over the same text. {@link #at} therefore returns every node there, not one chain.
 */
public final class NodeIndex {

    private final Map<Integer, List<Node>> byText = new HashMap<>();
    private final Map<Node, Node> parents = new IdentityHashMap<>();

    private NodeIndex(SourceUnit unit) {
        for (Declaration d : unit.declarations()) {
            visit(d, null);
        }
        unit.comments().forEach(c -> visit(c, null));
        unit.pragmas().forEach(p -> visit(p, null));
    }

    private static final Map<Class<?>, RecordComponent[]> COMPONENTS = new ConcurrentHashMap<>();

    /** Indexes {@code unit} (linear in its size; callers keep the index while the unit lives). */
    public static NodeIndex of(SourceUnit unit) {
        return new NodeIndex(unit);
    }

    /**
     * The nodes whose span covers {@code offset} in text {@code textId}, smallest first. A node ending exactly at the
     * offset counts too: a cursor directly after a name still names it.
     */
    public List<Node> at(int textId, int offset) {
        List<Node> out = new ArrayList<>();
        for (Node n : byText.getOrDefault(textId, List.of())) {
            if (n.span().start() <= offset && offset <= n.span().end()) {
                out.add(n);
            }
        }
        // smallest first: a name ending at the cursor beats the expression around it; on equal size, containing wins
        out.sort(Comparator.<Node>comparingInt(n -> n.span().length())
                .thenComparingInt(n -> n.span().contains(offset) ? 0 : 1));
        return out;
    }

    /** The node directly containing {@code node} in the AST, or null for a top-level declaration. */
    public Node parent(Node node) {
        return parents.get(node);
    }

    /** Every node of the unit, in walk order (parents before children). */
    public void forEach(Consumer<Node> action) {
        parents.keySet().forEach(action);
    }

    private void visit(Object value, Node parent) {
        switch (value) {
            case null -> {}
            case Collection<?> c -> c.forEach(v -> visit(v, parent));
            case Record r -> {
                Node self = parent;
                if (r instanceof Node n && n.span() != null) {
                    if (parents.containsKey(n)) {
                        return; // shared subtree (e.g. the type of `a, b : INT`)
                    }
                    parents.put(n, parent);
                    byText.computeIfAbsent(n.span().textId(), k -> new ArrayList<>())
                            .add(n);
                    self = n;
                }
                for (RecordComponent c : components(r.getClass())) {
                    Object v = read(c, r);
                    if ((v instanceof Record && !(v instanceof Span)) || v instanceof Collection<?>) {
                        visit(v, self);
                    }
                }
            }
            default -> {}
        }
    }

    private static RecordComponent[] components(Class<?> type) {
        return COMPONENTS.computeIfAbsent(type, Class::getRecordComponents);
    }

    private static Object read(RecordComponent c, Object owner) {
        try {
            return c.getAccessor().invoke(owner);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("cannot read " + c, e);
        }
    }
}
