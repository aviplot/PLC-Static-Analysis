package dev.plca.semantic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Static instance trees rooted at programs (or the task programs) and global FB instances. */
public final class InstanceGraph {

    private final List<Instance> roots = new ArrayList<>();
    private boolean truncated;

    void addRoot(Instance i) {
        roots.add(i);
    }

    void markTruncated() {
        truncated = true;
    }

    public List<Instance> roots() {
        return Collections.unmodifiableList(roots);
    }

    /** True if the tree was cut at the depth or size limit (recursive or very large types). */
    public boolean truncated() {
        return truncated;
    }

    /** All instances, depth-first. */
    public List<Instance> all() {
        List<Instance> out = new ArrayList<>();
        Deque<Instance> todo = new ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) {
            todo.push(roots.get(i));
        }
        while (!todo.isEmpty()) {
            Instance i = todo.pop();
            out.add(i);
            List<Instance> c = i.children();
            for (int k = c.size() - 1; k >= 0; k--) {
                todo.push(c.get(k));
            }
        }
        return out;
    }

    /** Instances of a given FB type. */
    public List<Instance> instancesOf(PouSymbol pou) {
        return all().stream().filter(i -> i.pou() == pou).toList();
    }
}
