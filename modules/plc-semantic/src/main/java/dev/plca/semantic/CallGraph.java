package dev.plca.semantic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Calls between user POUs (builtins and library symbols are not nodes). */
public final class CallGraph {

    private final Map<PouSymbol, List<CallSite>> out = new LinkedHashMap<>();
    private final Map<PouSymbol, List<CallSite>> in = new LinkedHashMap<>();

    void add(CallSite c) {
        out.computeIfAbsent(c.caller(), k -> new ArrayList<>()).add(c);
        in.computeIfAbsent(c.callee(), k -> new ArrayList<>()).add(c);
    }

    public List<CallSite> callsFrom(PouSymbol caller) {
        return Collections.unmodifiableList(out.getOrDefault(caller, List.of()));
    }

    public List<CallSite> callsTo(PouSymbol callee) {
        return Collections.unmodifiableList(in.getOrDefault(callee, List.of()));
    }

    public Set<PouSymbol> callers() {
        return Collections.unmodifiableSet(out.keySet());
    }

    /** POUs reachable from {@code root} (including itself). */
    public Set<PouSymbol> reachableFrom(PouSymbol root) {
        Set<PouSymbol> seen = new LinkedHashSet<>();
        Deque<PouSymbol> todo = new ArrayDeque<>();
        todo.push(root);
        while (!todo.isEmpty()) {
            PouSymbol p = todo.pop();
            if (seen.add(p)) {
                for (CallSite c : callsFrom(p)) {
                    todo.push(c.callee());
                }
            }
        }
        return seen;
    }

    /**
     * Recursion: strongly connected components with more than one POU, or a POU calling itself (Tarjan, iterative to
     * stay safe on deep graphs).
     */
    public List<List<PouSymbol>> cycles() {
        Map<PouSymbol, Integer> index = new IdentityHashMap<>();
        Map<PouSymbol, Integer> low = new IdentityHashMap<>();
        Set<PouSymbol> onStack = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<PouSymbol> stack = new ArrayDeque<>();
        List<List<PouSymbol>> result = new ArrayList<>();
        int[] counter = {0};
        Set<PouSymbol> nodes = new LinkedHashSet<>(out.keySet());
        nodes.addAll(in.keySet());
        for (PouSymbol start : nodes) {
            if (index.containsKey(start)) {
                continue;
            }
            Deque<Object[]> work = new ArrayDeque<>(); // {node, iterator position}
            work.push(new Object[] {start, 0});
            while (!work.isEmpty()) {
                Object[] frame = work.peek();
                PouSymbol v = (PouSymbol) frame[0];
                int pos = (int) frame[1];
                if (pos == 0 && !index.containsKey(v)) {
                    index.put(v, counter[0]);
                    low.put(v, counter[0]);
                    counter[0]++;
                    stack.push(v);
                    onStack.add(v);
                }
                List<CallSite> edges = callsFrom(v);
                if (pos < edges.size()) {
                    frame[1] = pos + 1;
                    PouSymbol w = edges.get(pos).callee();
                    if (!index.containsKey(w)) {
                        work.push(new Object[] {w, 0});
                    } else if (onStack.contains(w)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                    continue;
                }
                work.pop();
                if (!work.isEmpty()) {
                    PouSymbol parent = (PouSymbol) work.peek()[0];
                    low.put(parent, Math.min(low.get(parent), low.get(v)));
                }
                if (low.get(v).equals(index.get(v))) {
                    List<PouSymbol> scc = new ArrayList<>();
                    PouSymbol w;
                    do {
                        w = stack.pop();
                        onStack.remove(w);
                        scc.add(w);
                    } while (w != v);
                    boolean selfCall = callsFrom(v).stream().anyMatch(c -> c.callee() == v);
                    if (scc.size() > 1 || selfCall) {
                        Collections.reverse(scc);
                        result.add(scc);
                    }
                }
            }
        }
        return result;
    }

    /** Number of call edges (for statistics). */
    public int size() {
        Map<PouSymbol, Integer> n = new HashMap<>();
        out.forEach((k, v) -> n.put(k, v.size()));
        return n.values().stream().mapToInt(Integer::intValue).sum();
    }
}
