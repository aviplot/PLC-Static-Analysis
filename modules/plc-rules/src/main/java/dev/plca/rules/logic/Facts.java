package dev.plca.rules.logic;

import dev.plca.core.source.Span;
import dev.plca.semantic.Access;
import dev.plca.semantic.CallSite;
import dev.plca.semantic.Effects;
import dev.plca.semantic.VariableSymbol;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What is known to hold at a point of a POU body: the conditions of the enclosing IF / ELSIF / CASE / WHILE (negated
 * for the branches not taken), as long as nothing changed their operands. Immutable; {@link #INFEASIBLE} is the state
 * after RETURN, EXIT, CONTINUE or JMP (no path continues).
 */
final class Facts {

    static final int MAX_FACTS = 32;

    /**
     * One known condition.
     *
     * @param origin the condition in the source
     * @param note how it holds ({@code enclosing condition}, {@code earlier condition, false here})
     */
    record Fact(Cond cond, Span origin, String note) {}

    static final Facts EMPTY = new Facts(List.of(), false);
    static final Facts INFEASIBLE = new Facts(List.of(), true);

    private final List<Fact> facts;
    private final boolean infeasible;

    private Facts(List<Fact> facts, boolean infeasible) {
        this.facts = List.copyOf(facts);
        this.infeasible = infeasible;
    }

    boolean infeasible() {
        return infeasible;
    }

    List<Fact> all() {
        return facts;
    }

    Facts and(Cond c, Span origin, String note) {
        if (infeasible || c instanceof Cond.Const) {
            return this;
        }
        List<Fact> out = new ArrayList<>(facts);
        out.add(new Fact(c, origin, note));
        while (out.size() > MAX_FACTS) {
            out.removeFirst();
        }
        return new Facts(out, false);
    }

    /** The facts that still hold after a statement with these effects. */
    Facts without(Effects e) {
        if (infeasible || facts.isEmpty() || e.accesses().isEmpty() && e.calls().isEmpty()) {
            return this;
        }
        Set<VariableSymbol> written = new HashSet<>();
        for (Access a : e.accesses()) {
            if (a.isWrite() || a.kind() == Access.Kind.ADDRESS || a.kind() == Access.Kind.UNKNOWN) {
                written.add(a.variable());
                if (a.variable().overlayOf() != null) {
                    written.add(a.variable().overlayOf());
                }
            }
        }
        boolean userCall = false;
        for (CallSite c : e.calls()) {
            if (c.instance() != null) {
                written.add(c.instance().variable());
            }
            userCall |= !c.callee().isBuiltin();
        }
        boolean anything = !written.isEmpty() || !e.calls().isEmpty();
        boolean calls = userCall;
        List<Fact> out = new ArrayList<>();
        for (Fact f : facts) {
            boolean keep = true;
            for (Operand o : f.cond().operands()) {
                if (anything && o.volatileValue()
                        || calls && o.nonLocal()
                        || o.deps().stream().anyMatch(written::contains)) {
                    keep = false;
                    break;
                }
            }
            if (keep) {
                out.add(f);
            }
        }
        return out.size() == facts.size() ? this : new Facts(out, false);
    }

    /** The facts that hold after either path: both, when both continue. */
    Facts join(Facts o) {
        if (infeasible) {
            return o;
        }
        if (o.infeasible) {
            return this;
        }
        Set<Cond> theirs = new HashSet<>();
        o.facts.forEach(f -> theirs.add(f.cond()));
        List<Fact> out = new ArrayList<>();
        for (Fact f : facts) {
            if (theirs.contains(f.cond())) {
                out.add(f);
            }
        }
        return out.size() == facts.size() ? this : new Facts(out, false);
    }

    /** The facts that share an operand or a variable with {@code c}, directly or through other facts. */
    List<Fact> relevant(Cond c) {
        if (facts.isEmpty()) {
            return List.of();
        }
        Set<Object> tokens = new HashSet<>();
        addTokens(c, tokens);
        List<Fact> rest = new ArrayList<>(facts);
        List<Fact> out = new ArrayList<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (int i = 0; i < rest.size(); i++) {
                Fact f = rest.get(i);
                if (shares(f.cond(), tokens)) {
                    addTokens(f.cond(), tokens);
                    out.add(f);
                    rest.remove(i--);
                    grew = true;
                }
            }
        }
        out.sort((a, b) -> Integer.compare(facts.indexOf(a), facts.indexOf(b)));
        return out;
    }

    private static void addTokens(Cond c, Set<Object> tokens) {
        for (Operand o : c.operands()) {
            tokens.add(o.key());
            tokens.addAll(o.deps());
        }
    }

    private static boolean shares(Cond c, Set<Object> tokens) {
        for (Operand o : c.operands()) {
            if (tokens.contains(o.key())) {
                return true;
            }
            for (VariableSymbol v : o.deps()) {
                if (tokens.contains(v)) {
                    return true;
                }
            }
        }
        return false;
    }
}
