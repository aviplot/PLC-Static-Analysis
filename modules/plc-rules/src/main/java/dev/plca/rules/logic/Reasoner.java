package dev.plca.rules.logic;

import dev.plca.semantic.VariableSymbol;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides conditions by disjunctive normal form (at most {@link #MAX_TERMS} conjunctions) and, per conjunction, by
 * intersecting what each operand may be: a {@link NumSet} for numbers, equal / unequal values for texts, a truth value
 * for propositions. Operands are independent of each other, so an answer of {@link Tri#UNSAT} is a proof;
 * {@link Tri#SAT} is given only when the check was exact (every operand typed and not opaque, no two operands reading
 * the same variable — {@code a} and {@code a - b} are not related), else {@link Tri#UNKNOWN}.
 */
final class Reasoner {

    static final int MAX_TERMS = 256;

    enum Tri {
        UNSAT,
        SAT,
        UNKNOWN
    }

    private Reasoner() {}

    static Tri sat(Cond c) {
        List<List<Cond.Lit>> terms = dnf(c);
        if (terms == null) {
            return Tri.UNKNOWN;
        }
        Tri r = Tri.UNSAT;
        for (List<Cond.Lit> t : terms) {
            Tri x = conjunction(t);
            if (x == Tri.SAT) {
                return Tri.SAT;
            }
            if (x == Tri.UNKNOWN) {
                r = Tri.UNKNOWN;
            }
        }
        return r;
    }

    /** {@code c} is never true. */
    static boolean unsat(Cond c) {
        return sat(c) == Tri.UNSAT;
    }

    /** {@code c} is always true. */
    static boolean valid(Cond c) {
        return unsat(c.negate());
    }

    /** Whenever {@code a} holds, {@code b} holds. */
    static boolean implies(Cond a, Cond b) {
        return unsat(Cond.and(a, b.negate()));
    }

    static boolean equivalent(Cond a, Cond b) {
        return a.equals(b) || implies(a, b) && implies(b, a);
    }

    /**
     * The values of {@code o} for which {@code c} holds, when {@code c} talks only about the number {@code o}; else
     * null.
     */
    static NumSet values(Cond c, Operand o) {
        if (o.kind() != Operand.Kind.NUMBER || !c.operands().equals(Set.of(o))) {
            return null;
        }
        List<List<Cond.Lit>> terms = dnf(c);
        if (terms == null) {
            return null;
        }
        NumSet out = NumSet.empty(o.domain().isInteger());
        for (List<Cond.Lit> t : terms) {
            NumSet s = o.domain();
            for (Cond.Lit l : t) {
                Cond.Cmp cmp = (Cond.Cmp) l.atom();
                s = s.intersect(NumSet.of(cmp.rel(), cmp.value(), s.isInteger()));
            }
            out = out.union(s);
        }
        return out;
    }

    /** Conjunctions of literals, or null over {@link #MAX_TERMS}. */
    static List<List<Cond.Lit>> dnf(Cond c) {
        return switch (c) {
            case Cond.Const k -> k.value() ? List.of(List.of()) : List.of();
            case Cond.Lit l -> List.of(List.of(l));
            case Cond.Or o -> {
                List<List<Cond.Lit>> out = new ArrayList<>();
                for (Cond p : o.parts()) {
                    List<List<Cond.Lit>> d = dnf(p);
                    if (d == null || out.size() + d.size() > MAX_TERMS) {
                        yield null;
                    }
                    out.addAll(d);
                }
                yield out;
            }
            case Cond.And a -> {
                List<List<Cond.Lit>> out = List.of(List.of());
                for (Cond p : a.parts()) {
                    List<List<Cond.Lit>> d = dnf(p);
                    if (d == null || (long) out.size() * d.size() > MAX_TERMS) {
                        yield null;
                    }
                    List<List<Cond.Lit>> next = new ArrayList<>();
                    for (List<Cond.Lit> x : out) {
                        for (List<Cond.Lit> y : d) {
                            List<Cond.Lit> t = new ArrayList<>(x);
                            t.addAll(y);
                            next.add(t);
                        }
                    }
                    out = next;
                }
                yield out;
            }
        };
    }

    private static Tri conjunction(List<Cond.Lit> lits) {
        Map<Operand, List<Cond.Lit>> byOperand = new LinkedHashMap<>();
        for (Cond.Lit l : lits) {
            byOperand
                    .computeIfAbsent(l.atom().operand(), k -> new ArrayList<>())
                    .add(l);
        }
        boolean exact = true;
        Map<VariableSymbol, Operand> readers = new HashMap<>();
        for (Map.Entry<Operand, List<Cond.Lit>> e : byOperand.entrySet()) {
            Operand o = e.getKey();
            boolean ok = switch (o.kind()) {
                case NUMBER -> number(o, e.getValue());
                case TEXT -> text(o, e.getValue());
                case BOOL -> bool(e.getValue());
            };
            if (!ok) {
                return Tri.UNSAT;
            }
            if (!o.typed() || o.key().startsWith("?") || o.key().startsWith("#")) {
                exact = false;
            }
            for (VariableSymbol v : o.deps()) {
                Operand other = readers.putIfAbsent(v, o);
                if (other != null && !other.equals(o)) {
                    exact = false;
                }
            }
        }
        return exact ? Tri.SAT : Tri.UNKNOWN;
    }

    private static boolean number(Operand o, List<Cond.Lit> lits) {
        NumSet s = o.domain();
        for (Cond.Lit l : lits) {
            if (l.atom() instanceof Cond.Cmp c) {
                s = s.intersect(NumSet.of(c.rel(), c.value(), s.isInteger()));
                if (s.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean text(Operand o, List<Cond.Lit> lits) {
        String eq = null;
        Set<String> ne = new HashSet<>();
        for (Cond.Lit l : lits) {
            if (!(l.atom() instanceof Cond.TextEq t)) {
                continue;
            }
            if (l.positive()) {
                if (eq != null && !eq.equals(t.value())
                        || o.size() >= 0 && t.value().length() > o.size()) {
                    return false;
                }
                eq = t.value();
            } else {
                ne.add(t.value());
            }
        }
        return eq == null || !ne.contains(eq);
    }

    private static boolean bool(List<Cond.Lit> lits) {
        Boolean value = null;
        for (Cond.Lit l : lits) {
            if (value != null && value != l.positive()) {
                return false;
            }
            value = l.positive();
        }
        return true;
    }
}
