package dev.plca.rules.logic;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.BinaryOp;
import dev.plca.ast.StText;
import dev.plca.ast.VarBlock;
import dev.plca.rules.Walk;
import dev.plca.semantic.Constant;
import dev.plca.semantic.ElementaryKind;
import dev.plca.semantic.EnumValueSymbol;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Type;
import dev.plca.semantic.VariableSymbol;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns expressions into {@link Cond}s. Comparisons with a constant become {@link Cond.Cmp} / {@link Cond.TextEq} on an
 * operand whose domain comes from its declared type (integer ranges, subranges, REAL as dense, {@code STRING(n)}
 * lengths); a comparison of two values becomes one on their difference. A BOOL designator is a proposition.
 *
 * <p>Kept opaque (a proposition keyed by its text): ordering on texts, BOOL ordering, arithmetic other than the
 * difference of two values (its type does not bound it: CODESYS evaluates {@code INT + INT} in 32 bits), and anything
 * over {@link #MAX_ATOMS}. A call or an ExST assignment makes a proposition that equals nothing else. A condition fixed
 * by constants ({@code TRUE}, {@code cDebug}, {@code cMode = 2}) is a free proposition: a configuration switch is not
 * folded, so the code it guards is analysed as live (PLCA-Q009 reports constant conditions).
 *
 * <p>REAL values are assumed not to be NaN, except in {@code x = x} / {@code x <> x}, the NaN test, which stays opaque.
 * Not thread-safe; one per POU.
 */
final class Normalizer {

    static final int MAX_ATOMS = 16;

    private final SemanticModel model;
    private final PouSymbol pou;
    private final Set<VariableSymbol> aliased;
    private final Map<Expression, Cond> conds = new IdentityHashMap<>();
    private final Map<Expression, Operand> operands = new IdentityHashMap<>();
    private final Map<Expression, Optional<Constant>> constants = new IdentityHashMap<>();
    private int unique;

    /** {@code aliased}: variables whose address the POU takes; a write through a pointer may change them. */
    Normalizer(SemanticModel model, PouSymbol pou, Set<VariableSymbol> aliased) {
        this.model = model;
        this.pou = pou;
        this.aliased = aliased;
    }

    SemanticModel model() {
        return model;
    }

    /** The condition {@code e} (a BOOL expression). */
    /**
     * {@link SemanticModel#constant}, remembered per node. An operator is constant only when its operands are (the
     * evaluator does not short-circuit), so a deep condition is evaluated once, not once per level.
     */
    Optional<Constant> constant(Expression e) {
        Optional<Constant> c = constants.get(e);
        if (c == null) {
            c = switch (e) {
                case Expression.Binary b
                when constant(b.left()).isEmpty() || constant(b.right()).isEmpty() -> Optional.empty();
                case Expression.Unary u when constant(u.operand()).isEmpty() -> Optional.empty();
                default -> model.constant(e);
            };
            constants.put(e, c);
        }
        return c;
    }

    Cond cond(Expression e) {
        Cond c = conds.get(e);
        if (c == null) {
            c = build(e);
            if (c.size() > MAX_ATOMS) {
                c = Cond.lit(new Cond.Prop(opaque(e)));
            }
            conds.put(e, c);
        }
        return c;
    }

    private Cond build(Expression e) {
        if (constant(e).orElse(null) instanceof Constant.Bool) {
            return switchProp(e);
        }
        return switch (e) {
            case Expression.Binary b
            when b.op() == BinaryOp.AND || b.op() == BinaryOp.AND_THEN -> Cond.and(cond(b.left()), cond(b.right()));
            case Expression.Binary b
            when b.op() == BinaryOp.OR || b.op() == BinaryOp.OR_ELSE -> Cond.or(cond(b.left()), cond(b.right()));
            case Expression.Binary b
            when b.op() == BinaryOp.XOR && isBool(b.left()) && isBool(b.right()) ->
                xor(cond(b.left()), cond(b.right()));
            case Expression.Binary b when isComparison(b.op()) -> compare(b);
            case Expression.Unary u
            when u.op() == Expression.UnaryOp.NOT -> cond(u.operand()).negate();
            default -> {
                Operand o = operand(e);
                yield Cond.lit(new Cond.Prop(o.kind() == Operand.Kind.BOOL ? o : opaque(e)));
            }
        };
    }

    static boolean isComparison(BinaryOp op) {
        return switch (op) {
            case LT, GT, LE, GE, EQ, NE -> true;
            default -> false;
        };
    }

    static boolean isLogical(Expression e) {
        return switch (e) {
            case Expression.Binary b ->
                isComparison(b.op())
                        || b.op() == BinaryOp.AND
                        || b.op() == BinaryOp.AND_THEN
                        || b.op() == BinaryOp.OR
                        || b.op() == BinaryOp.OR_ELSE
                        || b.op() == BinaryOp.XOR;
            case Expression.Unary u -> u.op() == Expression.UnaryOp.NOT;
            default -> false;
        };
    }

    private static Cond xor(Cond a, Cond b) {
        return Cond.or(Cond.and(a, b.negate()), Cond.and(a.negate(), b));
    }

    private Cond compare(Expression.Binary b) {
        Rel rel = rel(b.op());
        Constant lc = value(b.left());
        Constant rc = value(b.right());
        String ls = text(b.left());
        String rs = text(b.right());
        if ((lc != null || ls != null) && (rc != null || rs != null)) {
            return switchProp(b);
        }
        if (lc != null || ls != null) {
            return compareWith(operand(b.right()), rel.mirror(), lc, ls, b);
        }
        if (rc != null || rs != null) {
            return compareWith(operand(b.left()), rel, rc, rs, b);
        }
        Operand l = operand(b.left());
        Operand r = operand(b.right());
        if (l.key().equals(r.key())) {
            if (isReal(model.type(b.left()))) {
                return Cond.lit(new Cond.Prop(opaque(b))); // x = x is the NaN test
            }
            return Cond.lit(new Cond.Cmp(self(l), rel, BigDecimal.ZERO));
        }
        if (l.kind() == Operand.Kind.NUMBER && r.kind() == Operand.Kind.NUMBER) {
            boolean swap = l.key().compareTo(r.key()) > 0;
            Operand d = difference(swap ? r : l, swap ? l : r);
            return Cond.lit(new Cond.Cmp(d, swap ? rel.mirror() : rel, BigDecimal.ZERO));
        }
        if (l.kind() == Operand.Kind.BOOL && r.kind() == Operand.Kind.BOOL && (rel == Rel.EQ || rel == Rel.NE)) {
            Cond eq = Cond.or(
                    Cond.and(Cond.lit(new Cond.Prop(l)), Cond.lit(new Cond.Prop(r))),
                    Cond.and(
                            Cond.lit(new Cond.Prop(l)).negate(),
                            Cond.lit(new Cond.Prop(r)).negate()));
            return rel == Rel.EQ ? eq : eq.negate();
        }
        if (l.kind() == Operand.Kind.TEXT && r.kind() == Operand.Kind.TEXT && (rel == Rel.EQ || rel == Rel.NE)) {
            boolean swap = l.key().compareTo(r.key()) > 0;
            Operand a = swap ? r : l;
            Operand c = swap ? l : r;
            Operand p = combined(
                    a, c, "(" + a.key() + ")=(" + c.key() + ")", a.display() + " = " + c.display(), null, false);
            Cond eq = Cond.lit(new Cond.Prop(p));
            return rel == Rel.EQ ? eq : eq.negate();
        }
        return Cond.lit(new Cond.Prop(opaque(b)));
    }

    private Cond compareWith(Operand o, Rel rel, Constant c, String text, Expression.Binary b) {
        switch (o.kind()) {
            case NUMBER -> {
                BigDecimal v = c == null ? null : number(c, o);
                if (v != null) {
                    return Cond.lit(new Cond.Cmp(o, rel, v));
                }
            }
            case BOOL -> {
                if (c instanceof Constant.Bool k && (rel == Rel.EQ || rel == Rel.NE)) {
                    Cond p = Cond.lit(new Cond.Prop(o));
                    return k.value() == (rel == Rel.EQ) ? p : p.negate();
                }
            }
            case TEXT -> {
                if (text != null && (rel == Rel.EQ || rel == Rel.NE)) {
                    Cond eq = Cond.lit(new Cond.TextEq(o, text));
                    return rel == Rel.EQ ? eq : eq.negate();
                }
            }
        }
        return Cond.lit(new Cond.Prop(opaque(b)));
    }

    /** The numeric value of {@code c} as {@code o} sees it (REAL rounds to 32 bits), or null. */
    private static BigDecimal number(Constant c, Operand o) {
        return switch (c) {
            case Constant.Int i -> new BigDecimal(i.value());
            case Constant.Duration d -> new BigDecimal(d.nanos());
            case Constant.Real r -> {
                double v = o.size() == 32 ? (double) (float) r.value() : r.value();
                yield Double.isFinite(v) ? new BigDecimal(v) : null;
            }
            case Constant.Bool b -> null;
        };
    }

    private Constant value(Expression e) {
        if (model.symbol(e).orElse(null) instanceof EnumValueSymbol v) {
            return new Constant.Int(v.value());
        }
        return constant(e).orElse(null);
    }

    /** The decoded value of a string literal, or null. */
    static String text(Expression e) {
        if (!(e instanceof Expression.Literal l)
                || l.kind() != Expression.LiteralKind.STRING && l.kind() != Expression.LiteralKind.WSTRING) {
            return null;
        }
        String t = l.text();
        if (t.length() < 2) {
            return null;
        }
        boolean wide = l.kind() == Expression.LiteralKind.WSTRING;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < t.length() - 1; i++) {
            char ch = t.charAt(i);
            if (ch == '$' && i + 1 < t.length() - 1) {
                char n = t.charAt(i + 1);
                int hex = wide ? 4 : 2;
                if (isHex(t, i + 1, hex, t.length() - 1)) {
                    sb.append((char) Integer.parseInt(t.substring(i + 1, i + 1 + hex), 16));
                    i += hex;
                } else {
                    sb.append(
                            switch (Character.toUpperCase(n)) {
                                case 'L', 'N' -> '\n';
                                case 'P' -> '\f';
                                case 'R' -> '\r';
                                case 'T' -> '\t';
                                default -> n;
                            });
                    i++;
                }
            } else if (ch == t.charAt(0) && i + 1 < t.length() - 1 && t.charAt(i + 1) == ch) {
                sb.append(ch); // '' inside '…' (Rockwell, some exports)
                i++;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static boolean isHex(String t, int from, int n, int end) {
        if (from + n > end) {
            return false;
        }
        for (int i = from; i < from + n; i++) {
            if (Character.digit(t.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static Rel rel(BinaryOp op) {
        return switch (op) {
            case LT -> Rel.LT;
            case GT -> Rel.GT;
            case LE -> Rel.LE;
            case GE -> Rel.GE;
            case EQ -> Rel.EQ;
            case NE -> Rel.NE;
            default -> throw new IllegalArgumentException(op.toString());
        };
    }

    /** A condition fixed by constants: a proposition of its own, which nothing writes. */
    private Cond switchProp(Expression e) {
        String k = key(e);
        return Cond.lit(new Cond.Prop(Operand.proposition("switch:" + k, StText.expression(e))));
    }

    /** {@code x - x}: always 0 (for the non-REAL types). */
    private static Operand self(Operand x) {
        return new Operand(
                "(" + x.key() + ")-(" + x.key() + ")",
                x.display() + " - " + x.display(),
                Operand.Kind.NUMBER,
                NumSet.closed(BigDecimal.ZERO, BigDecimal.ZERO, true),
                -1,
                true,
                x.deps(),
                x.volatileValue(),
                x.nonLocal(),
                true);
    }

    private static Operand difference(Operand a, Operand b) {
        boolean integer = a.domain().isInteger() && b.domain().isInteger();
        return combined(
                a,
                b,
                "(" + a.key() + ")-(" + b.key() + ")",
                a.display() + " - " + b.display(),
                NumSet.all(integer),
                true);
    }

    /** An operand made of two others: a NUMBER when {@code domain} is given, else a proposition. */
    private static Operand combined(
            Operand a, Operand b, String key, String display, NumSet domain, boolean difference) {
        Set<VariableSymbol> deps = new HashSet<>(a.deps());
        deps.addAll(b.deps());
        return new Operand(
                key,
                display,
                domain == null ? Operand.Kind.BOOL : Operand.Kind.NUMBER,
                domain,
                domain == null ? -1 : 0,
                false,
                deps,
                a.volatileValue() || b.volatileValue(),
                a.nonLocal() || b.nonLocal(),
                difference);
    }

    /** {@code e} as a proposition the reasoner does not look into (a call makes it unique). */
    private Operand opaque(Expression e) {
        Operand o = operand(e);
        return new Operand(
                "?" + o.key(),
                o.display(),
                Operand.Kind.BOOL,
                null,
                -1,
                false,
                o.deps(),
                o.volatileValue(),
                o.nonLocal(),
                false);
    }

    /** The value {@code e} as an operand. */
    Operand operand(Expression e) {
        Operand o = operands.get(e);
        if (o == null) {
            o = buildOperand(e);
            operands.put(e, o);
        }
        return o;
    }

    private Operand buildOperand(Expression e) {
        Set<VariableSymbol> deps = new HashSet<>();
        boolean[] flags = new boolean[3]; // volatile, nonLocal, has call
        Walk.deep(e, x -> {
            switch (x) {
                case Expression.Call c -> flags[2] = true;
                case Expression.Assign a -> flags[2] = true;
                case Expression.Deref d -> flags[0] = true;
                case Expression.DirectAddress d -> flags[0] = true;
                case Expression.Invalid i -> flags[2] = true;
                case Expression.ThisRef t -> flags[1] = true;
                case Expression.SuperRef t -> flags[1] = true;
                case Expression.NameRef n -> {
                    switch (model.symbol(n).orElse(null)) {
                        case VariableSymbol v -> {
                            deps.add(v);
                            if (v.section() == VarBlock.Section.IN_OUT
                                    || v.overlayOf() != null
                                    || aliased.contains(v)
                                    || v.type().resolved() instanceof Type.Pointer) {
                                flags[0] = true;
                            }
                            if (v.isGlobal() || v.section() == VarBlock.Section.EXTERNAL || v.owner() != pou) {
                                flags[1] = true;
                            }
                        }
                        case null -> flags[0] = true; // unresolved: writes to it are not seen
                        default -> {}
                    }
                }
                case Expression.MemberAccess m -> {
                    if (model.symbol(m).orElse(null) instanceof VariableSymbol v
                            && v.type().resolved() instanceof Type.Pointer) {
                        flags[0] = true;
                    }
                }
                default -> {}
            }
        });
        String display = StText.expression(e);
        String key = keyOf(display);
        if (flags[2]) {
            return new Operand(
                    "#" + (++unique) + ":" + key, display, Operand.Kind.BOOL, null, -1, false, deps, true, true, false);
        }
        Type t = model.type(e);
        Type r = t == null ? null : t.resolved();
        boolean designator = designator(e);
        if (r instanceof Type.StringType s) {
            return new Operand(
                    key,
                    display,
                    Operand.Kind.TEXT,
                    null,
                    designator ? s.length() : -1,
                    designator,
                    deps,
                    flags[0],
                    flags[1],
                    false);
        }
        if (r instanceof Type.Subrange s) {
            // only a variable is bounded by its type: x + u on a subrange x has the type of x, not its range
            NumSet domain = designator ? NumSet.closed(dec(s.low()), dec(s.high()), true) : NumSet.all(true);
            return number(key, display, domain, designator, deps, flags, 0);
        }
        if (r instanceof Type.Enum) {
            return number(key, display, NumSet.all(true), false, deps, flags, 0);
        }
        ElementaryKind k = r == null ? null : Type.elementary(r);
        if (k == null) {
            if (r instanceof Type.Unknown || r == null || r instanceof Type.Void) {
                // unknown type: a dense, unbounded number (sound for proofs; never claimed exact)
                return number(key, display, NumSet.all(false), false, deps, flags, 0);
            }
            return new Operand(key, display, Operand.Kind.BOOL, null, -1, false, deps, true, flags[1], false);
        }
        return switch (k.category()) {
            case BOOL -> new Operand(key, display, Operand.Kind.BOOL, null, -1, true, deps, flags[0], flags[1], false);
            case SIGNED, UNSIGNED, BIT_STRING ->
                number(key, display, designator ? range(k) : NumSet.all(true), designator, deps, flags, 0);
            case REAL -> number(key, display, NumSet.all(false), designator, deps, flags, k.bits());
            case DURATION, DATE -> number(key, display, NumSet.all(true), false, deps, flags, 0);
            case CHAR -> new Operand(key, display, Operand.Kind.BOOL, null, -1, false, deps, true, flags[1], false);
        };
    }

    private static Operand number(
            String key,
            String display,
            NumSet domain,
            boolean typed,
            Set<VariableSymbol> deps,
            boolean[] flags,
            int realBits) {
        return new Operand(key, display, Operand.Kind.NUMBER, domain, realBits, typed, deps, flags[0], flags[1], false);
    }

    private static NumSet range(ElementaryKind k) {
        BigInteger lo;
        BigInteger hi;
        if (k.signed()) {
            lo = BigInteger.ONE.shiftLeft(k.bits() - 1).negate();
            hi = BigInteger.ONE.shiftLeft(k.bits() - 1).subtract(BigInteger.ONE);
        } else {
            lo = BigInteger.ZERO;
            hi = BigInteger.ONE.shiftLeft(k.bits()).subtract(BigInteger.ONE);
        }
        return NumSet.closed(new BigDecimal(lo), new BigDecimal(hi), true);
    }

    private static BigDecimal dec(long v) {
        return new BigDecimal(v);
    }

    static boolean designator(Expression e) {
        return switch (e) {
            case Expression.NameRef n -> true;
            case Expression.MemberAccess m -> designator(m.target()) || m.target() instanceof Expression.ThisRef;
            case Expression.Index i -> designator(i.target());
            case Expression.BitAccess b -> designator(b.target());
            case Expression.IndirectBit b -> designator(b.target());
            case Expression.Slice s -> designator(s.target());
            case Expression.Deref d -> designator(d.target());
            case Expression.DirectAddress d -> true;
            default -> false;
        };
    }

    private boolean isBool(Expression e) {
        ElementaryKind k = Type.elementary(model.type(e).resolved());
        return k == null || k.category() == ElementaryKind.Category.BOOL;
    }

    private static boolean isReal(Type t) {
        ElementaryKind k = t == null ? null : Type.elementary(t.resolved());
        return k == null || k.isReal();
    }

    /** Normalised text: case and blanks do not matter. */
    static String key(Expression e) {
        return keyOf(StText.expression(e));
    }

    /** {@code text} in lower case without blanks (no regex: this runs for every operand). */
    private static String keyOf(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r' && c != '\f' && c != '\u000B') {
                sb.append(c);
            }
        }
        return sb.length() == t.length() ? t : sb.toString();
    }

    /** Whether any part of {@code e} is a call or an ExST assignment. */
    static boolean hasCall(Expression e) {
        boolean[] r = {false};
        Walk.deep(e, x -> r[0] |= x instanceof Expression.Call || x instanceof Expression.Assign);
        return r[0];
    }

    static List<Expression> parts(Expression e, boolean and) {
        if (e instanceof Expression.Binary b
                && (and
                        ? b.op() == BinaryOp.AND || b.op() == BinaryOp.AND_THEN
                        : b.op() == BinaryOp.OR || b.op() == BinaryOp.OR_ELSE)) {
            List<Expression> l = new ArrayList<>(parts(b.left(), and));
            l.addAll(parts(b.right(), and));
            return l;
        }
        return List.of(e);
    }
}
