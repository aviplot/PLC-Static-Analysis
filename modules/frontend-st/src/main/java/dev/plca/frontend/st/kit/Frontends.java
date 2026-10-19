package dev.plca.frontend.st.kit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The frontends of a run, chosen by file extension. Each thread gets its own instance of every frontend (lexer and
 * parser objects are reused per thread, design rule 7). Thread-safe.
 */
public final class Frontends {

    private final List<Supplier<? extends Frontend>> suppliers;
    private final Map<String, Integer> byExtension = new HashMap<>();
    private final Set<String> extensions = new LinkedHashSet<>();
    private final ThreadLocal<Frontend[]> instances;

    private Frontends(List<Supplier<? extends Frontend>> suppliers) {
        this.suppliers = List.copyOf(suppliers);
        for (int i = 0; i < this.suppliers.size(); i++) {
            for (String ext : this.suppliers.get(i).get().extensions()) {
                if (byExtension.putIfAbsent(ext, i) != null) {
                    throw new IllegalArgumentException("two frontends read " + ext);
                }
                extensions.add(ext);
            }
        }
        this.instances = ThreadLocal.withInitial(() -> new Frontend[this.suppliers.size()]);
    }

    @SafeVarargs
    public static Frontends of(Supplier<? extends Frontend>... suppliers) {
        List<Supplier<? extends Frontend>> list = new ArrayList<>();
        for (Supplier<? extends Frontend> s : suppliers) {
            list.add(s);
        }
        return new Frontends(list);
    }

    /** Every extension some frontend reads (lower case, with dot). */
    public Set<String> extensions() {
        return Set.copyOf(extensions);
    }

    public boolean accepts(String path) {
        return byExtension.containsKey(Frontend.extension(path));
    }

    /** This thread's frontend for {@code path}, or null if no frontend reads its extension. */
    public Frontend forPath(String path) {
        Integer i = byExtension.get(Frontend.extension(path));
        if (i == null) {
            return null;
        }
        Frontend[] mine = instances.get();
        if (mine[i] == null) {
            mine[i] = suppliers.get(i).get();
        }
        return mine[i];
    }
}
