package dev.plca.frontend.l5x;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.Argument;
import dev.plca.ast.Expression.ArgumentKind;
import dev.plca.ast.Expression.BinaryOp;
import dev.plca.ast.Identifier;
import dev.plca.ast.Ladder;
import dev.plca.ast.Statement;
import dev.plca.ast.ladder.InstructionSet;
import dev.plca.ast.ladder.InstructionSet.Instruction;
import dev.plca.ast.ladder.InstructionSet.Kind;
import dev.plca.ast.ladder.InstructionSet.Role;
import dev.plca.core.source.Span;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lowers a ladder rung to ST-style statements (design rule 4) by following power flow from left to right: input
 * instructions AND a term onto the rung condition, a branch ORs its legs, output instructions become statements that
 * use the condition ({@code docs/grammar/rockwell.md} §4).
 *
 * <p>The rung is first turned into a small condition DAG; then statements are emitted in rung order. A condition used
 * by more than one consumer is stored in a synthetic power-flow variable ({@code $rc1}) unless it is trivial, so every
 * instruction appears exactly once in the result: rules never see it twice, and side-effecting inputs ({@code ONS})
 * keep their scan order.
 *
 * <p>Not thread-safe; one instance per routine (it numbers the power-flow variables).
 */
final class RungLowering {

    /** An AOI known from the export: its {@code Required} parameters in definition order (the rung operands). */
    record AoiSignature(String name, List<Param> operands) {
        record Param(String name, String usage) {}
    }

    private final Map<String, AoiSignature> aois;
    private int nextTemp;

    RungLowering(Map<String, AoiSignature> aois) {
        this.aois = aois;
    }

    // ---- the condition DAG ----------------------------------------------------------------------

    private sealed interface Cond {}

    /** The left rail. */
    private record True() implements Cond {}

    /**
     * {@code left AND term}: an input instruction (or an AOI's EnableOut) after {@code left}. {@code effects}: this
     * term or one before it has a side effect (cached: the DAG shares subgraphs, so walking it would be exponential).
     */
    private record And(Cond left, Expression term, boolean sideEffect, boolean effects) implements Cond {
        And(Cond left, Expression term, boolean sideEffect) {
            this(left, term, sideEffect, sideEffect || hasSideEffects(left));
        }
    }

    /** A branch: legs that all start at {@code root}, the condition entering the branch. */
    private record Or(Cond root, List<Cond> legs, boolean effects) implements Cond {
        Or(Cond root, List<Cond> legs) {
            this(root, legs, hasSideEffects(root) || legs.stream().anyMatch(RungLowering::hasSideEffects));
        }
    }

    private static final True TRUE = new True();

    /** An output instruction with the condition it sees. */
    private record Output(Ladder.Instruction instruction, Cond cond) {}

    private final List<Output> outputs = new ArrayList<>();
    private final Map<Cond, Integer> uses = new IdentityHashMap<>();
    private final Map<Cond, Expression> emitted = new IdentityHashMap<>();
    private final Map<Cond, Boolean> built = new IdentityHashMap<>();
    private final Map<Cond, Boolean> factored = new IdentityHashMap<>();
    private final Set<String> written = new HashSet<>();
    private final List<Statement> out = new ArrayList<>();
    private Span rungSpan;

    // consecutive outputs under the same condition share one IF: the rung condition is evaluated once
    private Cond pendingCond;
    private Expression pendingCondition;
    private final List<Statement> pending = new ArrayList<>();

    /** The statements of one rung. */
    List<Statement> lower(Ladder ladder) {
        outputs.clear();
        uses.clear();
        emitted.clear();
        built.clear();
        factored.clear();
        written.clear();
        out.clear();
        pending.clear();
        pendingCond = null;
        rungSpan = Span.at(ladder.span().textId(), ladder.span().start());
        Cond end = series(ladder.elements(), TRUE);
        Output previous = null;
        for (Output o : outputs) {
            // outputs that will share one IF evaluate their condition once
            if (!(previous != null && previous.cond() == o.cond() && conditional(previous) && conditional(o))) {
                count(o.cond(), null);
            }
            collectWrites(o.instruction());
            previous = o;
        }
        boolean endHasEffects = hasSideEffects(end);
        if (endHasEffects) {
            count(end, null);
        }
        for (Output o : outputs) {
            output(o);
        }
        flush();
        if (endHasEffects && !emitted.containsKey(end) && hasUnemittedEffects(end)) {
            materialize(end);
        }
        return List.copyOf(out);
    }

    private Cond series(List<Ladder.Element> elements, Cond in) {
        Cond rc = in;
        for (Ladder.Element e : elements) {
            rc = switch (e) {
                case Ladder.Instruction i -> instruction(i, rc);
                case Ladder.Branch b -> branch(b, rc);
            };
        }
        return rc;
    }

    private Cond branch(Ladder.Branch b, Cond in) {
        List<Cond> legs = new ArrayList<>();
        for (Ladder.Leg leg : b.legs()) {
            Cond l = series(leg.elements(), in);
            if (legs.stream().noneMatch(x -> x == l)) {
                legs.add(l);
            }
        }
        // every leg implies the incoming condition: a leg that leaves it unchanged (a wire, or outputs only) makes the
        // OR equal to it, unless another leg evaluates a side effect that must stay in the rung
        boolean passThrough = legs.stream().anyMatch(l -> l == in);
        if (passThrough && legs.stream().noneMatch(RungLowering::hasSideEffects)) {
            return in;
        }
        return legs.size() == 1 ? legs.getFirst() : new Or(in, legs);
    }

    private Cond instruction(Ladder.Instruction i, Cond rc) {
        String mnemonic = i.mnemonic().name().toUpperCase(Locale.ROOT);
        AoiSignature aoi = aois.get(mnemonic);
        if (aoi != null) {
            outputs.add(new Output(i, rc));
            Expression instance = operand(i, 0);
            if (instance == null) {
                return rc;
            }
            Identifier enableOut = new Identifier("EnableOut", i.mnemonic().span());
            return new And(rc, new Expression.MemberAccess(instance, enableOut, i.span()), false);
        }
        Instruction def = InstructionSet.lookup(mnemonic).orElse(null);
        if (def == null) {
            // unknown: it may condition the rung or not; keeping what follows conditional never invents an
            // unconditional write, and the call is still evaluated (with possibly-written operands)
            return new And(rc, call(i, null, null), true);
        }
        if (def.kind() != Kind.INPUT) {
            outputs.add(new Output(i, rc));
            return rc;
        }
        return switch (mnemonic) {
            case "LBL" -> rc; // a jump target: always true
            case "AFI" -> new And(rc, literal("FALSE", Expression.LiteralKind.BOOL, i.span()), false);
            case "XIC" -> new And(rc, value(i, 0), false);
            case "XIO" -> new And(rc, new Expression.Unary(Expression.UnaryOp.NOT, value(i, 0), i.span()), false);
            case "EQU" -> compare(i, BinaryOp.EQ, rc);
            case "NEQ" -> compare(i, BinaryOp.NE, rc);
            case "GRT" -> compare(i, BinaryOp.GT, rc);
            case "GEQ" -> compare(i, BinaryOp.GE, rc);
            case "LES" -> compare(i, BinaryOp.LT, rc);
            case "LEQ" -> compare(i, BinaryOp.LE, rc);
            case "CMP" -> new And(rc, value(i, 0), false);
            default -> new And(rc, call(i, def, null), writes(def, i.operands().size()));
        };
    }

    private Cond compare(Ladder.Instruction i, BinaryOp op, Cond rc) {
        return new And(rc, new Expression.Binary(op, value(i, 0), value(i, 1), i.span()), false);
    }

    // ---- use counts --------------------------------------------------------------------------------

    /**
     * Counts a reference to {@code c}. {@code stop} is the root of an enclosing factored branch leg: a leg is built
     * relative to its branch's root ({@code root AND (leg1 OR leg2)}), so the root is not referenced again by the leg.
     */
    private void count(Cond c, Cond stop) {
        if (c == stop || c instanceof True) {
            return;
        }
        if (uses.merge(c, 1, Integer::sum) > 1) {
            return; // children were counted on the first visit
        }
        switch (c) {
            case And a -> count(a.left(), stop);
            case Or o -> {
                // a branch factors when nothing else (an output inside a leg) uses the legs' nodes
                if (o.legs().stream().allMatch(l -> fresh(l, o.root()))) {
                    factored.put(o, Boolean.TRUE);
                    count(o.root(), stop);
                    o.legs().forEach(l -> count(l, o.root()));
                } else {
                    o.legs().forEach(l -> count(l, stop));
                }
            }
            case True t -> {}
        }
    }

    /** No node of the chain from {@code c} down to {@code root} is referenced yet. */
    private boolean fresh(Cond c, Cond root) {
        for (int guard = 0; c != root && guard < 100_000; guard++) {
            if (uses.getOrDefault(c, 0) > 0) {
                return false;
            }
            if (c instanceof And a) {
                c = a.left();
            } else if (c instanceof Or o) {
                c = o.root();
            } else {
                return false;
            }
        }
        return true;
    }

    // ---- building expressions -------------------------------------------------------------------

    /** The condition as an expression, or null for TRUE (unconditional). */
    private Expression cond(Cond c) {
        return ref(c, null);
    }

    /** A reference to {@code c} where {@code stop} counts as TRUE (inside a factored leg). */
    private Expression ref(Cond c, Cond stop) {
        if (c == stop || c instanceof True) {
            return null;
        }
        Expression done = emitted.get(c);
        if (done != null) {
            return done;
        }
        if (uses.getOrDefault(c, 0) > 1 && !trivial(c)) {
            return materialize(c);
        }
        return build(c, stop);
    }

    private Expression build(Cond c, Cond stop) {
        Expression e = switch (c) {
            case True t -> literal("TRUE", Expression.LiteralKind.BOOL, rungSpan);
            case And a -> {
                Expression left = ref(a.left(), stop);
                yield left == null ? a.term() : and(left, a.term());
            }
            case Or o -> {
                boolean f = factored.containsKey(o);
                Expression root = f ? ref(o.root(), stop) : null;
                Expression legs = null;
                for (Cond leg : o.legs()) {
                    Expression x = ref(leg, f ? o.root() : stop);
                    if (x == null) {
                        x = literal("TRUE", Expression.LiteralKind.BOOL, rungSpan);
                    }
                    legs = legs == null
                            ? x
                            : new Expression.Binary(
                                    BinaryOp.OR, legs, x, legs.span().union(x.span()));
                }
                yield root == null ? legs : and(root, legs);
            }
        };
        built.put(c, Boolean.TRUE);
        return e;
    }

    private static Expression and(Expression left, Expression right) {
        return new Expression.Binary(BinaryOp.AND, left, right, left.span().union(right.span()));
    }

    /** {@code $rcN := <c>;} — returns the reference to use from now on. */
    private Expression materialize(Cond c) {
        Expression value = build(c, null);
        Identifier name = new Identifier("rc" + (++nextTemp), Identifier.Form.SYNTHETIC, rungSpan);
        flush();
        out.add(new Statement.Assignment(
                new Expression.NameRef(name, false, rungSpan), Statement.AssignOp.ASSIGN, value, value.span()));
        Expression ref = new Expression.NameRef(name, false, rungSpan);
        emitted.put(c, ref);
        return ref;
    }

    /**
     * Conditions cheap and harmless to repeat: TRUE, one tag, NOT one tag — unless an output of the rung writes that
     * tag, because the rung condition is computed before the output changes it.
     */
    private boolean trivial(Cond c) {
        if (c instanceof True) {
            return true;
        }
        if (!(c instanceof And a) || !(a.left() instanceof True) || a.sideEffect()) {
            return false;
        }
        Expression t =
                a.term() instanceof Expression.Unary u && u.op() == Expression.UnaryOp.NOT ? u.operand() : a.term();
        return t instanceof Expression.Literal
                || t instanceof Expression.NameRef n
                        && !written.contains(n.name().key());
    }

    /** Root names of every operand an output of the rung may write. */
    private void collectWrites(Ladder.Instruction i) {
        for (Ladder.Operand op : i.operands()) {
            if (op.value() != null) {
                Expression root = op.value();
                while (true) {
                    if (root instanceof Expression.MemberAccess m) {
                        root = m.target();
                    } else if (root instanceof Expression.Index x) {
                        root = x.target();
                    } else if (root instanceof Expression.BitAccess b) {
                        root = b.target();
                    } else if (root instanceof Expression.IndirectBit b) {
                        root = b.target();
                    } else {
                        break;
                    }
                }
                if (root instanceof Expression.NameRef n) {
                    written.add(n.name().key());
                }
            }
        }
    }

    private static boolean hasSideEffects(Cond c) {
        return switch (c) {
            case True t -> false;
            case And a -> a.effects();
            case Or o -> o.effects();
        };
    }

    private boolean hasUnemittedEffects(Cond c) {
        return hasUnemittedEffects(c, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /** Each node once: the DAG shares subgraphs. */
    private boolean hasUnemittedEffects(Cond c, Set<Cond> seen) {
        if (!hasSideEffects(c) || emitted.containsKey(c) || built.containsKey(c) || !seen.add(c)) {
            return false;
        }
        return switch (c) {
            case True t -> false;
            case And a -> a.sideEffect() || hasUnemittedEffects(a.left(), seen);
            case Or o ->
                hasUnemittedEffects(o.root(), seen) || o.legs().stream().anyMatch(l -> hasUnemittedEffects(l, seen));
        };
    }

    // ---- output instructions ---------------------------------------------------------------------

    private void output(Output o) {
        Ladder.Instruction i = o.instruction();
        String mnemonic = i.mnemonic().name().toUpperCase(Locale.ROOT);
        Span span = i.span();
        AoiSignature aoi = aois.get(mnemonic);
        if (aoi != null) {
            Expression c = cond(o.cond());
            emit(new Statement.CallStatement(aoiCall(i, aoi, enableIn(c, span)), span));
            return;
        }
        Instruction def = InstructionSet.lookup(mnemonic).orElse(null);
        if (def != null && def.kind() == Kind.ALWAYS) {
            Expression c = cond(o.cond());
            emit(new Statement.CallStatement(call(i, def, enableIn(c, span)), span));
            return;
        }
        Expression c = cond(o.cond());
        if (mnemonic.equals("OTE")) {
            Expression value = c == null ? literal("TRUE", Expression.LiteralKind.BOOL, span) : c;
            emit(new Statement.Assignment(value(i, 0), Statement.AssignOp.ASSIGN, value, span));
            return;
        }
        Statement s = def == null ? callStatement(i, null) : statement(i, mnemonic, def);
        if (c == null) {
            emit(s);
        } else {
            if (pendingCond != o.cond()) {
                flush();
                pendingCond = o.cond();
                pendingCondition = c;
            }
            pending.add(s);
        }
    }

    /**
     * Outputs lowered to {@code IF cond THEN …} (as opposed to {@code OTE}, timers and AOIs, which use it as a value).
     */
    private boolean conditional(Output o) {
        String mnemonic = o.instruction().mnemonic().name().toUpperCase(Locale.ROOT);
        if (aois.containsKey(mnemonic) || mnemonic.equals("OTE")) {
            return false;
        }
        Instruction def = InstructionSet.lookup(mnemonic).orElse(null);
        return def == null || def.kind() == Kind.OUTPUT;
    }

    private void emit(Statement s) {
        flush();
        out.add(s);
    }

    /** Closes the IF collecting conditional outputs. */
    private void flush() {
        if (pending.isEmpty()) {
            pendingCond = null;
            return;
        }
        Span span = pending.getFirst().span().union(pending.getLast().span());
        out.add(new Statement.If(
                List.of(new Statement.Branch(pendingCondition, List.copyOf(pending), span)), null, span));
        pending.clear();
        pendingCond = null;
    }

    /** What an output instruction does when its rung condition is true. */
    private Statement statement(Ladder.Instruction i, String mnemonic, Instruction def) {
        Span span = i.span();
        return switch (mnemonic) {
            case "OTL" -> assign(value(i, 0), literal("TRUE", Expression.LiteralKind.BOOL, span), span);
            case "OTU" -> assign(value(i, 0), literal("FALSE", Expression.LiteralKind.BOOL, span), span);
            case "MOV" -> assign(value(i, 1), value(i, 0), span);
            case "CLR" -> assign(value(i, 0), literal("0", Expression.LiteralKind.INTEGER, span), span);
            case "CPT" -> assign(value(i, 0), value(i, 1), span);
            case "NEG" -> assign(value(i, 1), new Expression.Unary(Expression.UnaryOp.NEGATE, value(i, 0), span), span);
            case "NOT" -> assign(value(i, 1), new Expression.Unary(Expression.UnaryOp.NOT, value(i, 0), span), span);
            case "ADD" -> arithmetic(i, BinaryOp.ADD);
            case "SUB" -> arithmetic(i, BinaryOp.SUB);
            case "MUL" -> arithmetic(i, BinaryOp.MUL);
            case "DIV" -> arithmetic(i, BinaryOp.DIV);
            case "MOD" -> arithmetic(i, BinaryOp.MOD);
            case "XPY" -> arithmetic(i, BinaryOp.POWER);
            case "AND" -> arithmetic(i, BinaryOp.AND);
            case "OR" -> arithmetic(i, BinaryOp.OR);
            case "XOR" -> arithmetic(i, BinaryOp.XOR);
            default -> callStatement(i, def);
        };
    }

    private Statement arithmetic(Ladder.Instruction i, BinaryOp op) {
        Span span = i.span();
        return assign(value(i, 2), new Expression.Binary(op, value(i, 0), value(i, 1), span), span);
    }

    private static Statement assign(Expression target, Expression value, Span span) {
        return new Statement.Assignment(target, Statement.AssignOp.ASSIGN, value, span);
    }

    private Statement callStatement(Ladder.Instruction i, Instruction def) {
        return new Statement.CallStatement(call(i, def, null), i.span());
    }

    /**
     * {@code MNEMONIC(op1, op2, …)} with every operand in place (a {@code ?} or empty operand is an argument without
     * value), so the semantic model can match operands to the instruction table by position.
     */
    private Expression call(Ladder.Instruction i, Instruction def, Argument enableIn) {
        List<Argument> args = new ArrayList<>();
        for (Ladder.Operand op : i.operands()) {
            args.add(new Argument(ArgumentKind.POSITIONAL, null, false, op.value(), op.span()));
        }
        if (enableIn != null) {
            args.add(enableIn);
        }
        return new Expression.Call(
                new Expression.NameRef(i.mnemonic(), false, i.mnemonic().span()), args, i.span());
    }

    /**
     * An AOI used in a rung, {@code Aoi(tag, a, b)}, as the FB call it is: {@code tag(In := a, Out => b, EnableIn :=
     * cond)}. The operands after the backing tag are the AOI's {@code Required} parameters in definition order
     * (verified against the corpus, {@code docs/grammar/rockwell.md} §9).
     */
    static Expression aoiCall(Ladder.Instruction i, AoiSignature aoi, Argument enableIn) {
        List<Expression> values = new ArrayList<>();
        List<Span> spans = new ArrayList<>();
        for (Ladder.Operand op : i.operands()) {
            values.add(op.value());
            spans.add(op.span());
        }
        return aoiCall(aoi, values, spans, enableIn, i.mnemonic(), i.span());
    }

    /** Shared with ST routines, where an AOI call is written {@code Aoi(tag, a, b);} too. */
    static Expression aoiCall(
            AoiSignature aoi,
            List<Expression> values,
            List<Span> spans,
            Argument enableIn,
            Identifier mnemonic,
            Span span) {
        Expression instance = values.isEmpty() || values.getFirst() == null
                ? new Expression.NameRef(mnemonic, false, mnemonic.span())
                : values.getFirst();
        List<Argument> args = new ArrayList<>();
        for (int k = 1; k < values.size(); k++) {
            Span s = spans.get(k);
            if (k - 1 < aoi.operands().size()) {
                AoiSignature.Param p = aoi.operands().get(k - 1);
                Identifier name = new Identifier(p.name(), s);
                boolean output = "Output".equalsIgnoreCase(p.usage());
                args.add(
                        new Argument(output ? ArgumentKind.OUTPUT : ArgumentKind.INPUT, name, false, values.get(k), s));
            } else {
                args.add(new Argument(ArgumentKind.POSITIONAL, null, false, values.get(k), s));
            }
        }
        if (enableIn != null) {
            args.add(enableIn);
        }
        return new Expression.Call(instance, args, span);
    }

    private Argument enableIn(Expression cond, Span span) {
        Expression v = cond == null ? literal("TRUE", Expression.LiteralKind.BOOL, span) : cond;
        return new Argument(ArgumentKind.INPUT, new Identifier("EnableIn", span), false, v, v.span());
    }

    /** Does the instruction write any of its operands (then evaluating it twice would change the result)? */
    private static boolean writes(Instruction def, int operands) {
        for (int k = 0; k < Math.max(operands, def.roles().size()); k++) {
            Role r = def.role(k);
            if (r == Role.WRITE || r == Role.READ_WRITE || r == Role.INSTANCE || r == Role.PARAMETER) {
                return true;
            }
        }
        return false;
    }

    // ---- operands -------------------------------------------------------------------------------

    /** Operand {@code k}'s expression, or null for {@code ?}, an empty or a missing operand. */
    private static Expression operand(Ladder.Instruction i, int k) {
        return k < i.operands().size() ? i.operands().get(k).value() : null;
    }

    /** Operand {@code k}'s expression; {@code Invalid} at the instruction when it is not there. */
    private static Expression value(Ladder.Instruction i, int k) {
        Expression e = operand(i, k);
        if (e != null) {
            return e;
        }
        Span at = k < i.operands().size()
                ? i.operands().get(k).span()
                : Span.at(i.span().textId(), i.span().end());
        return new Expression.Invalid(at);
    }

    private static Expression literal(String text, Expression.LiteralKind kind, Span span) {
        return new Expression.Literal(kind, text, null, span);
    }
}
