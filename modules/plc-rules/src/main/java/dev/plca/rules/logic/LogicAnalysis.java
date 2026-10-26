package dev.plca.rules.logic;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.BinaryOp;
import dev.plca.ast.Identifier;
import dev.plca.ast.Statement;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.source.Span;
import dev.plca.rules.PouContext;
import dev.plca.rules.Spans;
import dev.plca.rules.Walk;
import dev.plca.semantic.Access;
import dev.plca.semantic.Constant;
import dev.plca.semantic.ElementaryKind;
import dev.plca.semantic.EnumValueSymbol;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Statements;
import dev.plca.semantic.Type;
import dev.plca.semantic.VariableSymbol;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The condition analysis of one POU body, shared by the engine-based PLCA-L rules: computed once per POU (the rule
 * engine runs a POU's rules one after the other on one thread) and read by each rule.
 *
 * <p>Two passes. The <b>expression pass</b> looks at every condition on its own, including the conditions lowered from
 * ladder rungs and FBD networks (L004 contradiction, L007 tautology, L010 redundant part, L012 comparison outside the
 * type's range). The <b>walk</b> goes once over the structured code with the {@link Facts} that hold at each point: IF
 * / ELSIF / ELSE, CASE, WHILE, the join after a block, writes and calls dropping what they may change (L001, L002,
 * L003, L005, L006, L008, L009, L011). The walk does not enter graphical bodies: their IF structure is synthetic.
 *
 * <p>A proof that needs a fact about a global written by another task (PLCA-P004's task model) is reported at
 * {@code info}, naming the task: the other task may change the value between the two checks.
 */
public final class LogicAnalysis {

    /** A finding of one rule; {@code race} names the task the proof assumes quiet, or null. */
    public record Issue(Span span, String message, boolean race, List<Diagnostic.Related> related) {}

    private record Cached(SemanticModel model, PouSymbol pou, LogicAnalysis analysis) {}

    private static final ThreadLocal<Cached> CACHE = new ThreadLocal<>();

    /** The analysis of {@code ctx}'s POU, computed on the first call for that POU on this thread. */
    public static LogicAnalysis of(PouContext ctx) {
        Cached c = CACHE.get();
        if (c == null || c.model() != ctx.model() || c.pou() != ctx.pou()) {
            c = new Cached(ctx.model(), ctx.pou(), new LogicAnalysis(ctx));
            CACHE.set(c);
        }
        return c.analysis();
    }

    private final PouContext ctx;
    private final SemanticModel model;
    private final Normalizer norm;
    private final Races races;
    private final Map<String, List<Issue>> issues = new HashMap<>();
    /** The expression pass is inside a rung or network: messages do not quote the (lowered) source. */
    private boolean inGraphical;

    private LogicAnalysis(PouContext ctx) {
        this.ctx = ctx;
        this.model = ctx.model();
        this.races = Races.of(model, ctx.pou());
        this.norm = new Normalizer(model, ctx.pou(), aliased(ctx));
        scan(ctx.body(), false);
        block(ctx.body(), Facts.EMPTY);
    }

    /** The findings of rule {@code id} ({@code PLCA-L001}). */
    public List<Issue> issues(String id) {
        return issues.getOrDefault(id, List.of());
    }

    private void add(String rule, Span span, String message, String race, List<Diagnostic.Related> related) {
        issues.computeIfAbsent(rule, k -> new ArrayList<>())
                .add(new Issue(span, race == null ? message : message + race, race != null, related));
    }

    /** Variables whose address is taken anywhere in the body: a write through a pointer may change them. */
    private static Set<VariableSymbol> aliased(PouContext ctx) {
        Set<VariableSymbol> out = new HashSet<>();
        ctx.forEachStatement(s -> {
            for (Access a : ctx.model().effects(s).accesses()) {
                if (a.kind() == Access.Kind.ADDRESS) {
                    out.add(a.variable());
                }
            }
        });
        return out;
    }

    static boolean graphical(Statement s) {
        return s instanceof Statement.Rung || s instanceof Statement.Network || s instanceof Statement.Chart;
    }

    // ---------------------------------------------------------------- expression pass (L004, L007, L010, L012)

    private void scan(List<Statement> body, boolean graphical) {
        if (body == null) {
            return;
        }
        for (Statement s : body) {
            inGraphical = graphical;
            Walk.ownExpressions(s, this::roots);
            boolean g = graphical || graphical(s);
            Statements.forEachBody(s, b -> scan(b, g));
        }
    }

    /** Finds the outermost conditions in {@code e} and checks each. */
    private void roots(Expression e) {
        if (isCondition(e)) {
            root(e);
            inner(e);
            return;
        }
        children(e, this::roots);
    }

    /** Conditions nested in the non-logical parts of a condition ({@code SEL(a > 5 AND a < 3, …) = x}). */
    private void inner(Expression e) {
        children(e, c -> {
            if (isCondition(c) && logicalChild(e)) {
                inner(c);
            } else {
                roots(c);
            }
        });
    }

    private boolean logicalChild(Expression parent) {
        return parent instanceof Expression.Unary
                || parent instanceof Expression.Binary b && !Normalizer.isComparison(b.op());
    }

    private static void children(Expression e, java.util.function.Consumer<Expression> action) {
        switch (e) {
            case Expression.MemberAccess m -> action.accept(m.target());
            case Expression.BitAccess b -> action.accept(b.target());
            case Expression.IndirectBit b -> {
                action.accept(b.target());
                action.accept(b.bit());
            }
            case Expression.Slice s -> action.accept(s.target());
            case Expression.Deref d -> action.accept(d.target());
            case Expression.Index i -> {
                action.accept(i.target());
                i.indices().forEach(action);
            }
            case Expression.Call c ->
                c.arguments().stream().filter(a -> a.value() != null).forEach(a -> action.accept(a.value()));
            case Expression.Unary u -> action.accept(u.operand());
            case Expression.Binary b -> {
                action.accept(b.left());
                action.accept(b.right());
            }
            case Expression.Assign a -> action.accept(a.value());
            default -> {}
        }
    }

    /** A comparison, or AND / OR / XOR / NOT on BOOL. */
    private boolean isCondition(Expression e) {
        return switch (e) {
            case Expression.Binary b when Normalizer.isComparison(b.op()) -> true;
            case Expression.Binary b when Normalizer.isLogical(b) -> isBool(e);
            case Expression.Unary u when u.op() == Expression.UnaryOp.NOT -> isBool(e);
            default -> false;
        };
    }

    private boolean isBool(Expression e) {
        Type t = model.type(e);
        ElementaryKind k = t == null ? null : Type.elementary(t.resolved());
        return k != null && k.category() == ElementaryKind.Category.BOOL;
    }

    private void root(Expression e) {
        if (e instanceof Expression.Binary b && Normalizer.isComparison(b.op()) && plain(b)) {
            return;
        }
        if (readOnce(e, new HashSet<>())) {
            return; // AND / OR / NOT over distinct BOOL names: satisfiable, not valid, every part needed
        }
        if (norm.constant(e).isPresent() || hasInvalid(e)) {
            return;
        }
        boolean fixed = rangeChecks(e);
        if (fixed) {
            return; // the comparison outside its range is the finding (L012); the rest follows from it
        }
        Cond c = norm.cond(e);
        if (c instanceof Cond.Const || c.size() < 1) {
            return;
        }
        if (c.size() == 1 && !selfComparison(c) && !hasChain(e)) {
            return; // one literal: nothing to contradict or repeat (most lowered ladder contacts)
        }
        if (independent(e)) {
            return;
        }
        if (Reasoner.unsat(c)) {
            if (c.size() > 1 || selfComparison(c)) {
                add(
                        "PLCA-L004",
                        e.span(),
                        what(e) + " is never true: its parts contradict each other",
                        null,
                        List.of());
            }
            return;
        }
        if (Reasoner.valid(c)) {
            if (c.size() > 1 || selfComparison(c)) {
                add("PLCA-L007", e.span(), what(e) + " is always true", null, List.of());
            }
            return;
        }
        redundancy(e);
    }

    /**
     * Whether {@code e} is AND / OR / XOR / NOT over plain names, members and bits, each used once. Such a formula
     * depends on every name, so none of L004, L007, L010 applies, and it holds no comparison for L012. Most conditions
     * lowered from ladder rungs are of this form. A name that is a constant is left to the full check.
     */
    private boolean readOnce(Expression e, Set<String> seen) {
        return switch (e) {
            case Expression.Unary u when u.op() == Expression.UnaryOp.NOT -> readOnce(u.operand(), seen);
            case Expression.Binary b
            when Normalizer.isLogical(b) && !Normalizer.isComparison(b.op()) ->
                readOnce(b.left(), seen) && readOnce(b.right(), seen);
            case Expression.NameRef n
            when n.name().form() == Identifier.Form.SYNTHETIC -> seen.add(path(n)); // ladder power flow
            case Expression.NameRef n -> variable(n) && seen.add(path(n));
            case Expression.Call c -> true; // a call is a value of its own each time (see Normalizer)
            case Expression.MemberAccess m -> variable(base(m)) && seen.add(path(m));
            case Expression.BitAccess b -> variable(base(b)) && seen.add(path(b));
            default -> false;
        };
    }

    /**
     * A comparison of two values that are neither constants nor enum values, different from each other, with no
     * condition inside: one literal over a difference, which none of L004, L007, L010, L012 can report. Most compare
     * two tags ({@code Stn.Cnt > Stn.Sp}).
     */
    private boolean plain(Expression.Binary b) {
        return !fixed(b.left())
                && !fixed(b.right())
                && !hasCondition(b.left())
                && !hasCondition(b.right())
                && !Structural.equal(b.left(), b.right());
    }

    private boolean fixed(Expression e) {
        return e instanceof Expression.Literal
                || norm.constant(e).isPresent()
                || model.symbol(e).orElse(null) instanceof EnumValueSymbol;
    }

    private static boolean hasCondition(Expression e) {
        boolean[] r = {false};
        Walk.deep(
                e,
                x -> r[0] |=
                        x instanceof Expression.Binary b && (Normalizer.isLogical(b) || Normalizer.isComparison(b.op()))
                                || x instanceof Expression.Unary u && u.op() == Expression.UnaryOp.NOT
                                || x instanceof Expression.Invalid);
        return r[0];
    }

    /** A name that is a variable, not a constant. */
    private boolean variable(Expression e) {
        return e != null
                && model.symbol(e).orElse(null) instanceof VariableSymbol v
                && !v.isConstant()
                && (!(e instanceof Expression.NameRef n) || path(n) != null);
    }

    /** The name a path of members and bits starts with, or null. */
    private static Expression base(Expression e) {
        return switch (e) {
            case Expression.NameRef n -> n;
            case Expression.MemberAccess m -> base(m.target());
            case Expression.BitAccess b -> base(b.target());
            default -> null;
        };
    }

    /** {@code a.b.3} in lower case, or null when the path holds anything but names, members and bits. */
    private static String path(Expression e) {
        return switch (e) {
            case Expression.NameRef n -> n.name().key();
            case Expression.MemberAccess m -> {
                String t = path(m.target());
                yield t == null ? null : t + "." + m.member().key();
            }
            case Expression.BitAccess b -> {
                String t = path(b.target());
                yield t == null ? null : t + "." + b.bit();
            }
            default -> null;
        };
    }

    /**
     * Whether the parts of {@code e}'s AND / OR / XOR / NOT structure talk about different operands, and each of their
     * literals can be both true and false. The reasoner links literals only through shared operands, so such a
     * condition is neither contradictory nor always true and no part of it is redundant. Typical of lowered ladder:
     * {@code $rc16 AND Tag.Ready AND ONS(Tag.Ons)}.
     */
    private boolean independent(Expression e) {
        Set<String> keys = new HashSet<>();
        boolean[] ok = {true};
        parts(e, part -> {
            Cond c = ok[0] ? norm.cond(part) : null;
            if (c == null || c instanceof Cond.Const) {
                ok[0] = false;
                return;
            }
            c.literals(l -> ok[0] &= keys.add(l.atom().operand().key()) && !Reasoner.unsat(l) && !Reasoner.valid(l));
        });
        return ok[0];
    }

    /** The operands of the logical operators in {@code e}, down to the first part that is not AND / OR / XOR / NOT. */
    private static void parts(Expression e, java.util.function.Consumer<Expression> action) {
        switch (e) {
            case Expression.Unary u when u.op() == Expression.UnaryOp.NOT -> parts(u.operand(), action);
            case Expression.Binary b
            when Normalizer.isLogical(b) && !Normalizer.isComparison(b.op()) -> {
                parts(b.left(), action);
                parts(b.right(), action);
            }
            default -> action.accept(e);
        }
    }

    private static boolean hasChain(Expression e) {
        boolean[] r = {false};
        Walk.deep(
                e,
                x -> r[0] |= x instanceof Expression.Binary b
                        && Normalizer.isLogical(b)
                        && !Normalizer.isComparison(b.op()));
        return r[0];
    }

    private String what(Expression e) {
        return inGraphical ? "This ladder/FBD condition" : "Condition '" + display(e) + "'";
    }

    private static boolean selfComparison(Cond c) {
        return c instanceof Cond.Lit l
                && l.atom().operand().difference()
                && l.atom().operand().typed();
    }

    /** L010 for every AND / OR chain in the condition {@code e}. */
    private void redundancy(Expression e) {
        if (!isCondition(e)) {
            return;
        }
        if (e instanceof Expression.Binary b && Normalizer.isLogical(b) && !Normalizer.isComparison(b.op())) {
            boolean and = b.op() == BinaryOp.AND || b.op() == BinaryOp.AND_THEN;
            boolean or = b.op() == BinaryOp.OR || b.op() == BinaryOp.OR_ELSE;
            if (and || or) {
                List<Expression> parts = Normalizer.parts(e, and);
                chain(parts, and);
                parts.forEach(this::redundancy);
                return;
            }
        }
        children(e, c -> {
            if (isCondition(c)) {
                redundancy(c);
            }
        });
    }

    private void chain(List<Expression> parts, boolean and) {
        Set<Integer> removed = new HashSet<>();
        for (int i = parts.size() - 1; i >= 0; i--) {
            Expression p = parts.get(i);
            Cond pc = norm.cond(p);
            if (pc.operands().stream()
                    .anyMatch(o -> o.key().startsWith("switch:") || o.key().startsWith("#"))) {
                continue;
            }
            List<Cond> others = new ArrayList<>();
            Expression same = null;
            for (int j = 0; j < parts.size(); j++) {
                if (j != i && !removed.contains(j)) {
                    Cond oc = norm.cond(parts.get(j));
                    others.add(oc);
                    if (same == null && j < i && oc.equals(pc)) {
                        same = parts.get(j);
                    }
                }
            }
            if (others.isEmpty()) {
                continue;
            }
            boolean redundant = and ? Reasoner.implies(Cond.and(others), pc) : Reasoner.implies(pc, Cond.or(others));
            if (!redundant) {
                continue;
            }
            removed.add(i);
            String op = inGraphical ? (and ? "series" : "parallel") : and ? "this AND" : "this OR";
            if (same != null) {
                add(
                        "PLCA-L010",
                        p.span(),
                        "'" + display(p) + "' appears twice in " + op,
                        null,
                        List.of(new Diagnostic.Related(same.span(), "first occurrence")));
            } else {
                add(
                        "PLCA-L010",
                        p.span(),
                        "'" + display(p) + "' is redundant: "
                                + (and
                                        ? "the other parts in " + op + " already imply it"
                                        : "the other parts in " + op + " already cover it"),
                        null,
                        List.of());
            }
        }
    }

    /** L012 for each comparison in {@code e}; true when one was found. */
    private boolean rangeChecks(Expression e) {
        boolean[] found = {false};
        Walk.deep(e, x -> {
            if (x instanceof Expression.Binary b && Normalizer.isComparison(b.op())) {
                String why = fixedByType(b);
                if (why != null) {
                    add("PLCA-L012", b.span(), why, null, List.of());
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    /** Why the comparison {@code b} has the same result for every value its operand's type can hold, or null. */
    private String fixedByType(Expression.Binary b) {
        if (norm.constant(b).isPresent()) {
            return null;
        }
        if (!(norm.cond(b) instanceof Cond.Lit l)) {
            return null;
        }
        Operand o = l.atom().operand();
        if (!o.typed() || o.difference()) {
            return null;
        }
        String type = typeName(o, b);
        switch (l.atom()) {
            case Cond.Cmp c -> {
                NumSet s = NumSet.of(c.rel(), c.value(), o.domain().isInteger());
                if (o.domain().intersect(s).isEmpty()) {
                    return "'" + display(b) + "' is always FALSE: " + o.display() + " (" + type + ") holds only "
                            + range(o.domain());
                }
                if (o.domain().intersect(s.complement()).isEmpty()) {
                    return "'" + display(b) + "' is always TRUE: " + o.display() + " (" + type + ") holds only "
                            + range(o.domain());
                }
            }
            case Cond.TextEq t -> {
                if (o.size() >= 0 && t.value().length() > o.size()) {
                    return "'" + display(b) + "' is always " + (l.positive() ? "FALSE" : "TRUE") + ": the text has "
                            + t.value().length() + " characters, " + o.display() + " (" + type + ") holds at most "
                            + o.size();
                }
            }
            case Cond.Prop p -> {}
        }
        return null;
    }

    private String typeName(Operand o, Expression.Binary b) {
        Expression side = Normalizer.key(b.left()).equals(o.key()) ? b.left() : b.right();
        return model.type(side).display();
    }

    private static String range(NumSet s) {
        NumSet.Range r = s.ranges().getFirst();
        return r.lo().toPlainString() + ".." + r.hi().toPlainString();
    }

    private static boolean hasInvalid(Expression e) {
        boolean[] r = {false};
        Walk.deep(e, x -> r[0] |= x instanceof Expression.Invalid);
        return r[0];
    }

    private String display(Expression e) {
        String t = ctx.text(e).replaceAll("\\s+", " ").trim();
        return t.length() > 80 ? t.substring(0, 77) + "..." : t;
    }

    // ---------------------------------------------------------------- walk (L001, L002, L003, L005, L006, L008, L009,
    // L011)

    private Facts block(List<Statement> body, Facts in) {
        Facts f = in;
        if (body == null) {
            return f;
        }
        for (Statement s : body) {
            if (f.infeasible()) {
                f = Facts.EMPTY; // unreachable code (PLCA-Q009): analysed without facts
            }
            f = statement(s, f);
        }
        return f;
    }

    private Facts statement(Statement s, Facts f) {
        return switch (s) {
            case Statement.If i -> ifChain(i, f.without(model.effects(i)));
            case Statement.Case c -> caseOf(c, f.without(model.effects(c)));
            case Statement.While w -> {
                Facts loop = f.without(model.deepEffects(w));
                Cond c = norm.cond(w.condition());
                nested(w.condition(), c, loop, "WHILE condition", "the loop body never runs");
                block(w.body(), loop.and(c, w.condition().span(), "WHILE condition"));
                yield exits(w.body())
                        ? loop
                        : loop.and(c.negate(), w.condition().span(), "WHILE condition, false after the loop");
            }
            case Statement.Repeat r -> {
                Facts loop = f.without(model.deepEffects(r));
                block(r.body(), loop);
                Cond c = norm.cond(r.condition());
                yield exits(r.body()) ? loop : loop.and(c, r.condition().span(), "UNTIL condition");
            }
            case Statement.For fo -> {
                Facts loop = f.without(model.deepEffects(fo));
                block(fo.body(), loop);
                yield loop;
            }
            case Statement.Region r -> block(r.body(), f);
            case Statement.Return r -> Facts.INFEASIBLE;
            case Statement.Exit x -> Facts.INFEASIBLE;
            case Statement.Continue x -> Facts.INFEASIBLE;
            case Statement.Jump j -> Facts.INFEASIBLE;
            case Statement.Label l -> Facts.EMPTY;
            case Statement.Empty e -> f;
            case Statement.Assignment a -> f.without(model.effects(s));
            case Statement.CallStatement c -> f.without(model.effects(s));
            default -> {
                // TRY, graphical bodies, invalid code: walk nested ST bodies without facts, keep nothing after
                if (!graphical(s)) {
                    Statements.forEachBody(s, b -> block(b, Facts.EMPTY));
                }
                yield Facts.EMPTY;
            }
        };
    }

    /** Whether {@code body} has an EXIT for its own loop. */
    private static boolean exits(List<Statement> body) {
        boolean[] r = {false};
        exits(body, r);
        return r[0];
    }

    private static void exits(List<Statement> body, boolean[] r) {
        if (body == null) {
            return;
        }
        for (Statement s : body) {
            if (s instanceof Statement.Exit || s instanceof Statement.Jump) {
                r[0] = true;
            } else if (!(s instanceof Statement.For || s instanceof Statement.While || s instanceof Statement.Repeat)) {
                Statements.forEachBody(s, b -> exits(b, r));
            }
        }
    }

    private Facts ifChain(Statement.If s, Facts f) {
        List<Cond> conds = new ArrayList<>();
        List<Facts> outs = new ArrayList<>();
        Facts chain = f;
        boolean settled = false; // an earlier branch is always taken: the rest is its consequence
        for (int i = 0; i < s.branches().size(); i++) {
            Statement.Branch b = s.branches().get(i);
            Cond c = norm.cond(b.condition());
            if (!settled && checkable(b.condition(), c)) {
                settled = branch(s, i, c, conds, f, chain);
            }
            conds.add(c);
            outs.add(block(
                    b.body(),
                    chain.and(
                            c, b.condition().span(), i == 0 ? "enclosing IF condition" : "enclosing ELSIF condition")));
            chain = chain.and(c.negate(), b.condition().span(), "earlier condition, false here");
        }
        if (s.elseBody() != null) {
            if (!settled) {
                elseUnreachable(s, f, conds);
            }
            outs.add(block(s.elseBody(), chain));
        } else {
            if (!settled) {
                gaps(s, conds, f);
            }
            outs.add(chain);
        }
        Facts out = Facts.INFEASIBLE;
        for (Facts o : outs) {
            out = out.join(o);
        }
        return out;
    }

    private void elseUnreachable(Statement.If s, Facts f, List<Cond> conds) {
        Cond none = Cond.and(conds.stream().map(Cond::negate).toList());
        Proof p = unsatUnder(f.relevant(none), none);
        if (p == null) {
            return;
        }
        add(
                "PLCA-L003",
                Structured.elseSpan(ctx, s),
                "ELSE never runs: the conditions before it cover every case",
                p.race(),
                related(
                        s.branches().stream().map(b -> b.condition().span()).toList(),
                        "covering condition",
                        p.facts()));
    }

    /** Checks branch {@code i}; true when it is always taken (the rest of the chain is a consequence). */
    private boolean branch(Statement.If s, int i, Cond c, List<Cond> earlier, Facts outer, Facts chain) {
        Expression cond = s.branches().get(i).condition();
        for (int j = 0; j < earlier.size(); j++) {
            if (Reasoner.equivalent(earlier.get(j), c)) {
                add(
                        "PLCA-L005",
                        cond.span(),
                        "Same condition as an earlier branch of this IF; this branch never runs",
                        null,
                        List.of(new Diagnostic.Related(
                                s.branches().get(j).condition().span(), "first branch with it")));
                return false;
            }
        }
        if (nested(cond, c, outer, i == 0 ? "Condition" : "ELSIF condition", "the branch never runs")) {
            return false;
        }
        if (i > 0) {
            Proof p = unsatUnder(chain.relevant(c), c);
            if (p != null) {
                add(
                        "PLCA-L002",
                        cond.span(),
                        "ELSIF condition '" + display(cond) + "' is never true when the earlier conditions are false;"
                                + " the branch never runs",
                        p.race(),
                        related(List.of(), null, p.facts()));
                return false;
            }
        }
        Proof always = unsatUnder(outer.relevant(c.negate()), c.negate());
        if (always != null && !always.facts().isEmpty()) {
            add(
                    "PLCA-L008",
                    cond.span(),
                    "Condition '" + display(cond) + "' is always true here: the enclosing conditions imply it"
                            + (i < s.branches().size() - 1 || s.elseBody() != null
                                    ? "; the branches after it never run"
                                    : ""),
                    always.race(),
                    related(List.of(), null, always.facts()));
            return true;
        }
        if (i > 0) {
            Proof p = unsatUnder(chain.relevant(c.negate()), c.negate());
            if (p != null) {
                if (i == s.branches().size() - 1 && s.elseBody() == null) {
                    add(
                            "PLCA-L009",
                            cond.span(),
                            "ELSIF condition '" + display(cond) + "' is always true when the earlier conditions are"
                                    + " false; write ELSE",
                            p.race(),
                            related(List.of(), null, p.facts()));
                    return true;
                }
                // what follows is dead: report it there (the ELSIF itself is then the ELSE)
                List<Diagnostic.Related> why = related(List.of(cond.span()), "always true when reached", p.facts());
                for (int j = i + 1; j < s.branches().size(); j++) {
                    add(
                            "PLCA-L002",
                            s.branches().get(j).condition().span(),
                            "ELSIF never runs: the ELSIF before it is always true when reached",
                            p.race(),
                            why);
                }
                if (s.elseBody() != null) {
                    add(
                            "PLCA-L003",
                            Structured.elseSpan(ctx, s),
                            "ELSE never runs: the conditions before it cover every case",
                            p.race(),
                            why);
                }
                return true;
            }
        }
        return false;
    }

    /** L001: {@code c} can never hold under the enclosing facts. */
    private boolean nested(Expression cond, Cond c, Facts f, String what, String consequence) {
        if (!checkable(cond, c)) {
            return false;
        }
        List<Facts.Fact> rel = f.relevant(c);
        if (rel.isEmpty()) {
            return false;
        }
        Proof p = unsatUnder(rel, c);
        if (p == null) {
            return false;
        }
        add(
                "PLCA-L001",
                cond.span(),
                what + " '" + display(cond) + "' is never true here: the enclosing conditions exclude it; "
                        + consequence,
                p.race(),
                related(List.of(), null, p.facts()));
        return true;
    }

    /** Not decided by itself (L004 / L007 / L012 report those) and not fixed by constants. */
    private boolean checkable(Expression e, Cond c) {
        return !(c instanceof Cond.Const)
                && norm.constant(e).isEmpty()
                && !hasInvalid(e)
                && !Reasoner.unsat(c)
                && !Reasoner.valid(c);
    }

    /** L011: a chain without ELSE over one number that leaves out only a few values. */
    private void gaps(Statement.If s, List<Cond> conds, Facts f) {
        if (conds.size() < 2) {
            return;
        }
        Set<Operand> ops = new LinkedHashSet<>();
        conds.forEach(c -> ops.addAll(c.operands()));
        if (ops.size() != 1) {
            return;
        }
        Operand o = ops.iterator().next();
        if (o.kind() != Operand.Kind.NUMBER
                || !o.typed() && !o.difference()
                || o.key().startsWith("#")) {
            return;
        }
        NumSet covered = NumSet.empty(o.domain().isInteger());
        for (Cond c : conds) {
            NumSet v = Reasoner.values(c, o);
            if (v == null) {
                return;
            }
            covered = covered.union(v);
        }
        List<BigDecimal> missing = o.domain().intersect(covered.complement()).points(3);
        if (missing == null || missing.isEmpty()) {
            return;
        }
        List<String> shown = new ArrayList<>();
        for (BigDecimal v : missing) {
            Cond eq = Cond.lit(new Cond.Cmp(o, Rel.EQ, v));
            if (!Reasoner.unsat(Cond.and(conditions(f.relevant(eq), eq)))) {
                shown.add(
                        o.difference() && v.signum() == 0
                                ? o.display().replace(" - ", " = ")
                                : o.display() + " = " + v.toPlainString());
            }
        }
        if (shown.isEmpty()) {
            return;
        }
        String over = o.difference() ? o.display().replace(" - ", " and ") : o.display();
        add(
                "PLCA-L011",
                Spans.header(s),
                "IF/ELSIF over " + over + " has no branch for " + String.join(", ", shown)
                        + " and no ELSE: a boundary case is missing",
                null,
                List.of());
    }

    private Facts caseOf(Statement.Case s, Facts f) {
        Operand sel = norm.operand(s.selector());
        boolean usable = sel.kind() == Operand.Kind.NUMBER && !sel.key().startsWith("#");
        NumSet seen = NumSet.empty(true);
        List<Statement.CaseLabel> seenLabels = new ArrayList<>();
        List<Facts> outs = new ArrayList<>();
        Facts rest = f;
        for (Statement.CaseBranch b : s.branches()) {
            List<Cond> labels = new ArrayList<>();
            for (Statement.CaseLabel l : b.labels()) {
                BigDecimal lo = value(l.low());
                BigDecimal hi = l.high() == null ? lo : value(l.high());
                if (lo == null || hi == null) {
                    labels = null;
                    break;
                }
                NumSet values = NumSet.closed(lo, hi, true);
                if (!values.intersect(seen).isEmpty()) {
                    Statement.CaseLabel first = seenLabels.stream()
                            .filter(x -> !NumSet.closed(
                                            value(x.low()), x.high() == null ? value(x.low()) : value(x.high()), true)
                                    .intersect(values)
                                    .isEmpty())
                            .findFirst()
                            .orElse(null);
                    add(
                            "PLCA-L006",
                            l.span(),
                            "CASE label " + ctx.text(l).trim()
                                    + (isSubset(values, seen)
                                            ? " is already handled by an earlier label; it never matches"
                                            : " overlaps an earlier label; the shared values never reach this branch"),
                            null,
                            first == null ? List.of() : List.of(new Diagnostic.Related(first.span(), "earlier label")));
                }
                seen = seen.union(values);
                seenLabels.add(l);
                if (usable) {
                    Cond lc = l.high() == null
                            ? Cond.lit(new Cond.Cmp(sel, Rel.EQ, lo))
                            : Cond.and(
                                    Cond.lit(new Cond.Cmp(sel, Rel.GE, lo)), Cond.lit(new Cond.Cmp(sel, Rel.LE, hi)));
                    labels.add(lc);
                    if (sel.typed() || sel.domain().isAll()) {
                        List<Facts.Fact> rel = f.relevant(lc);
                        Proof p = rel.isEmpty() || Reasoner.unsat(lc) ? null : unsatUnder(rel, lc);
                        if (p != null) {
                            add(
                                    "PLCA-L001",
                                    l.span(),
                                    "CASE label " + ctx.text(l).trim()
                                            + " never matches here: the enclosing conditions exclude it",
                                    p.race(),
                                    related(List.of(), null, p.facts()));
                        }
                    }
                }
            }
            if (!usable || labels == null) {
                outs.add(block(b.body(), f));
                usable = false;
                continue;
            }
            Cond any = Cond.or(labels);
            outs.add(block(b.body(), rest.and(any, b.span(), "CASE label")));
            rest = rest.and(any.negate(), b.span(), "earlier CASE label, not matched here");
        }
        outs.add(s.elseBody() != null ? block(s.elseBody(), usable ? rest : f) : usable ? rest : f);
        Facts out = Facts.INFEASIBLE;
        for (Facts o : outs) {
            out = out.join(o);
        }
        return out;
    }

    private static boolean isSubset(NumSet a, NumSet b) {
        return a.intersect(b.complement()).isEmpty();
    }

    private BigDecimal value(Expression e) {
        if (model.symbol(e).orElse(null) instanceof EnumValueSymbol v) {
            return new BigDecimal(v.value());
        }
        return norm.constant(e).orElse(null) instanceof Constant.Int i ? new BigDecimal(i.value()) : null;
    }

    // ---------------------------------------------------------------- proofs

    /** A proof; {@code facts} are the facts it used, {@code race} the note when one is about another task's global. */
    private record Proof(List<Facts.Fact> facts, String race) {}

    /** Proves that {@code c} cannot hold together with {@code facts}; null when it can (or the engine cannot tell). */
    private Proof unsatUnder(List<Facts.Fact> facts, Cond c) {
        if (!Reasoner.unsat(Cond.and(conditions(facts, c)))) {
            return null;
        }
        List<Facts.Fact> firm = new ArrayList<>();
        Set<String> notes = new TreeSet<>();
        for (Facts.Fact f : facts) {
            Set<String> r = races.of(f.cond());
            if (r.isEmpty()) {
                firm.add(f);
            } else {
                notes.addAll(r);
            }
        }
        if (notes.isEmpty() || Reasoner.unsat(Cond.and(conditions(firm, c)))) {
            return new Proof(facts, null);
        }
        return new Proof(facts, " (assuming " + String.join(" and ", notes) + " in between)");
    }

    private static List<Cond> conditions(List<Facts.Fact> facts, Cond c) {
        List<Cond> all = new ArrayList<>();
        facts.forEach(f -> all.add(f.cond()));
        all.add(c);
        return all;
    }

    private static List<Diagnostic.Related> related(List<Span> spans, String note, List<Facts.Fact> facts) {
        List<Diagnostic.Related> out = new ArrayList<>();
        Set<Span> seen = new HashSet<>();
        for (Span s : spans) {
            if (seen.add(s)) {
                out.add(new Diagnostic.Related(s, note));
            }
        }
        for (Facts.Fact f : facts) {
            if (seen.add(f.origin())) {
                out.add(new Diagnostic.Related(f.origin(), f.note()));
            }
        }
        return out;
    }
}
