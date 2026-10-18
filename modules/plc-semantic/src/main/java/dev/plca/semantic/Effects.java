package dev.plca.semantic;

import java.util.List;

/**
 * What one statement does itself (not counting nested statements): variable accesses and calls. Use
 * {@link SemanticModel#deepEffects} for a compound statement including its body.
 */
public record Effects(List<Access> accesses, List<CallSite> calls) {

    public static final Effects NONE = new Effects(List.of(), List.of());

    public Effects {
        accesses = List.copyOf(accesses);
        calls = List.copyOf(calls);
    }

    public List<Access> reads() {
        return accesses.stream().filter(Access::isRead).toList();
    }

    public List<Access> writes() {
        return accesses.stream().filter(Access::isWrite).toList();
    }
}
