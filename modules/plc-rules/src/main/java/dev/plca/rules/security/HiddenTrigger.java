package dev.plca.rules.security;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.BinaryOp;
import dev.plca.ast.Statement;
import dev.plca.rules.ProjectContext;
import dev.plca.rules.ProjectRule;
import dev.plca.rules.Walk;
import dev.plca.semantic.Access;
import dev.plca.semantic.Constant;
import dev.plca.semantic.ElementaryKind;
import dev.plca.semantic.InstructionSymbol;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Statements;
import dev.plca.semantic.Type;
import dev.plca.semantic.VariableSymbol;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PLCA-S003: logic that waits for a moment or a value nobody would set by accident — the shape of a logic bomb or a
 * backdoor, worth a reviewer's look:
 *
 * <ul>
 *   <li>a comparison of a date or date-and-time with an absolute date ({@code dtNow >= DT#2027-01-01-00:00:00});
 *   <li>a comparison of a value read from the real-time clock (RTC, SysTimeRtcGet, NT_GetTime, FB_LocalSystemTime,
 *       RD_LOC_T, RD_SYS_T, Rockwell {@code GSV(WallClockTime, …)}) with a calendar year (2000–2100);
 *   <li>an equality comparison with a "magic" constant: hexspeak ({@code 16#DEADBEEF}, {@code 16#C0FFEE}) or leet
 *       numbers (1337, 31337);
 *   <li>a timer that runs from start-up ({@code IN := TRUE}) with a delay of at least {@code minDelay}.
 * </ul>
 *
 * <p>Daily schedules (time-of-day comparisons) and short start-up delays are normal and not reported.
 */
public final class HiddenTrigger implements ProjectRule {

    @Override
    public String id() {
        return "PLCA-S003";
    }

    private static final Set<String> CLOCK_CALLS = Set.of(
            "RTC",
            "SYSTIMERTCGET",
            "SYSTIMERTCCONVERTUTCTODATE",
            "SYSTIMERTCCONVERTUTCTOLOCAL",
            "NT_GETTIME",
            "FB_LOCALSYSTEMTIME",
            "GETSYSTEMTIME",
            "F_GETSYSTEMTIME",
            "RD_LOC_T",
            "RD_SYS_T",
            "RD_LOC_DT",
            "DTU_GETDATEANDTIME",
            "SYSRTCGETTIME",
            "NOW");
    private static final Pattern HEXSPEAK =
            Pattern.compile("(?i)DEAD|BEEF|CAFE|BABE|FACE|C0DE|F00D|D00D|FEED|C0FFEE|B00B|BAAD|DEFACE|1337");
    private static final Set<Long> LEET = Set.of(1337L, 31337L, 0x1337L, 0x31337L);
    private static final Set<String> TIMERS = Set.of("TON", "TOF", "TP", "TON_TIME", "TOF_TIME", "TP_TIME");

    @Override
    public void check(ProjectContext ctx) {
        SemanticModel m = ctx.model();
        long minDelay = parseDelay(ctx.stringParam("minDelay"));
        Set<VariableSymbol> clock = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PouSymbol p : m.pous()) {
            if (p.declaration() != null && p.declaration().body() != null) {
                Statements.forEachDeep(p.declaration().body(), s -> clockTargets(m, s, clock));
            }
        }
        for (PouSymbol p : m.pous()) {
            if (p.declaration() == null || p.declaration().body() == null) {
                continue;
            }
            // comparisons inside test assertions (Assert.Equal, ASSERT) check fixed dates on purpose
            Set<Expression> asserted = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<String> resetTimers = new java.util.HashSet<>();
            Walk.expressions(p.declaration().body(), e -> {
                if (e instanceof Expression.Call c) {
                    String name = String.valueOf(Walk.lastName(c.callee())).toLowerCase(Locale.ROOT);
                    if (name.contains("assert") || name.equals("equal") || name.equals("equals")) {
                        c.arguments().forEach(a -> Walk.deep(a.value(), asserted::add));
                    }
                    if (resetCall(m, c)) {
                        resetTimers.add(ctx.text(c.callee()).strip().toLowerCase(Locale.ROOT));
                    }
                }
            });
            Statements.forEachDeep(
                    p.declaration().body(),
                    s -> Walk.ownExpressions(
                            s,
                            root -> Walk.deep(root, e -> {
                                switch (e) {
                                    case Expression.Binary b
                                    when comparison(b.op()) && !asserted.contains(b) -> compare(ctx, p, b, clock);
                                    case Expression.Call c -> timer(ctx, p, c, minDelay, resetTimers);
                                    default -> {}
                                }
                            })));
        }
    }

    /** Variables a statement fills from the real-time clock. */
    private static void clockTargets(SemanticModel m, Statement s, Set<VariableSymbol> clock) {
        Walk.ownExpressions(
                s,
                root -> Walk.deep(root, e -> {
                    if (!(e instanceof Expression.Call c)) {
                        return;
                    }
                    String name = String.valueOf(Walk.lastName(c.callee())).toUpperCase(Locale.ROOT);
                    boolean rockwellGsv = m.symbol(c.callee()).orElse(null) instanceof InstructionSymbol i
                            && i.name().equalsIgnoreCase("GSV")
                            && !c.arguments().isEmpty()
                            && "WALLCLOCKTIME"
                                    .equalsIgnoreCase(Walk.lastName(
                                            c.arguments().getFirst().value()));
                    boolean clockInstance = m.type(c.callee()).resolved() instanceof Type.Instance inst
                            && CLOCK_CALLS.contains(inst.pou().name().toUpperCase(Locale.ROOT));
                    if (!CLOCK_CALLS.contains(name) && !rockwellGsv && !clockInstance) {
                        return;
                    }
                    if (clockInstance && m.symbol(rootName(c.callee())).orElse(null) instanceof VariableSymbol inst) {
                        clock.add(inst);
                    }
                    // outputs passed positionally (RD_LOC_T(#now)) are not always known to the model as writes
                    for (Expression.Argument a : c.arguments()) {
                        if (a.value() != null
                                && m.symbol(rootName(a.value())).orElse(null) instanceof VariableSymbol v) {
                            clock.add(v);
                        }
                    }
                    for (Access a : m.effects(s).accesses()) {
                        if (a.isWrite()) {
                            clock.add(a.variable());
                        }
                    }
                }));
    }

    private static Expression rootName(Expression e) {
        Expression x = e;
        while (true) {
            switch (x) {
                case Expression.MemberAccess ma -> x = ma.target();
                case Expression.Index i -> x = i.target();
                case Expression.BitAccess b -> x = b.target();
                default -> {
                    return x;
                }
            }
        }
    }

    private void compare(ProjectContext ctx, PouSymbol p, Expression.Binary b, Set<VariableSymbol> clock) {
        SemanticModel m = ctx.model();
        Expression[][] sides = {{b.left(), b.right()}, {b.right(), b.left()}};
        for (Expression[] side : sides) {
            Expression value = side[0];
            Expression constant = side[1];
            // only literals can be dates, years or magic values; skip everything else before asking the model
            Expression lit = constant instanceof Expression.Unary u ? u.operand() : constant;
            if (!(lit instanceof Expression.Literal) || value instanceof Expression.Literal) {
                continue;
            }
            ElementaryKind kind = Type.elementary(m.type(constant));
            boolean date = kind == ElementaryKind.DATE
                    || kind == ElementaryKind.DATE_AND_TIME
                    || kind == ElementaryKind.LDATE
                    || kind == ElementaryKind.LDATE_AND_TIME;
            if (date
                    && constant instanceof Expression.Literal l
                    && !(value instanceof Expression.Literal)
                    && year(l.text()) > SENTINEL_YEARS) {
                ctx.report(
                        p,
                        b.span(),
                        "Condition on the absolute date " + ctx.text(constant).strip() + ": logic that changes on a"
                                + " calendar date can be a time bomb; review what it enables",
                        List.of());
                return;
            }
            Constant k = m.constant(constant).orElse(null);
            if (k == null || m.constant(value).isPresent()) {
                continue;
            }
            if (k instanceof Constant.Int i) {
                boolean fromClock =
                        m.symbol(rootName(value)).orElse(null) instanceof VariableSymbol v && clock.contains(v);
                if (fromClock && i.value() >= 2000 && i.value() <= 2100) {
                    ctx.report(
                            p,
                            b.span(),
                            "Real-time clock value compared with the year " + i.value() + ": logic that changes"
                                    + " in a given year can be a time bomb; review what it enables",
                            List.of());
                    return;
                }
                if ((b.op() == BinaryOp.EQ || b.op() == BinaryOp.NE) && magic(ctx.text(constant), i.value())) {
                    ctx.report(
                            p,
                            b.span(),
                            "Comparison with the magic value "
                                    + ctx.text(constant).strip() + ": a value nobody"
                                    + " sets by accident can unlock hidden behaviour; review what it enables",
                            List.of());
                    return;
                }
            }
        }
    }

    private static boolean magic(String text, long value) {
        String t = text.strip().replace("_", "");
        boolean hex = t.contains("#") && t.toUpperCase(Locale.ROOT).contains("16#");
        return hex && HEXSPEAK.matcher(t.substring(t.indexOf('#') + 1)).find() || LEET.contains(value);
    }

    /** {@code tmr(IN := TRUE, PT := T#30D)}: fires once, a fixed time after start-up. */
    private void timer(ProjectContext ctx, PouSymbol p, Expression.Call c, long minDelay, Set<String> resetTimers) {
        SemanticModel m = ctx.model();
        if (!(m.type(c.callee()).resolved() instanceof Type.Instance inst)
                || !TIMERS.contains(inst.pou().name().toUpperCase(Locale.ROOT))
                || resetTimers.contains(ctx.text(c.callee()).strip().toLowerCase(Locale.ROOT))) {
            return; // a timer the POU also resets runs periodically, not once after start-up
        }
        boolean alwaysOn = false;
        Long delay = null;
        for (Expression.Argument a : c.arguments()) {
            if (a.name() == null || a.value() == null) {
                continue;
            }
            Constant k = m.constant(a.value()).orElse(null);
            if (a.name().is("IN") && k instanceof Constant.Bool b && b.value()) {
                alwaysOn = true;
            }
            if (a.name().is("PT") && k instanceof Constant.Duration d) {
                delay = d.nanos();
            }
        }
        // the largest 32-bit TIME (T#49D17H2M47S295MS) is the usual "no timeout" value
        boolean maxTime = delay != null && Math.abs(delay - MAX_TIME_NANOS) < 1_000_000_000L;
        if (alwaysOn && delay != null && delay >= minDelay && !maxTime) {
            ctx.report(
                    p,
                    c.span(),
                    "Timer runs from start-up (IN := TRUE) and fires after " + delayText(ctx, c)
                            + ": logic that wakes up long after commissioning can be a time bomb; review what it"
                            + " enables",
                    List.of());
        }
    }

    private static final long MAX_TIME_NANOS = 4_294_967_295L * 1_000_000L;

    /** {@code tmr(IN := FALSE)}. */
    private static boolean resetCall(SemanticModel m, Expression.Call c) {
        for (Expression.Argument a : c.arguments()) {
            if (a.name() != null
                    && a.name().is("IN")
                    && a.value() != null
                    && m.constant(a.value()).orElse(null) instanceof Constant.Bool b
                    && !b.value()) {
                return true;
            }
        }
        return false;
    }

    private static String delayText(ProjectContext ctx, Expression.Call c) {
        for (Expression.Argument a : c.arguments()) {
            if (a.name() != null && a.name().is("PT") && a.value() != null) {
                return ctx.text(a.value()).strip();
            }
        }
        return "a long delay";
    }

    /** Dates up to this year are "not set" sentinels (1970 epoch, S7's DT#1990-01-01), not triggers. */
    private static final int SENTINEL_YEARS = 2000;

    /** The year of a date literal's text ({@code 2027-01-01-00:00:00}), or 0. */
    static int year(String text) {
        java.util.regex.Matcher y = Pattern.compile("(\\d{4})-").matcher(text);
        return y.find() ? Integer.parseInt(y.group(1)) : 0;
    }

    private static boolean comparison(BinaryOp op) {
        return op == BinaryOp.EQ
                || op == BinaryOp.NE
                || op == BinaryOp.GT
                || op == BinaryOp.GE
                || op == BinaryOp.LT
                || op == BinaryOp.LE;
    }

    /** {@code T#24h}, {@code 1d}, {@code 86400s}: nanoseconds. */
    static long parseDelay(String s) {
        String t = s.strip()
                .toLowerCase(Locale.ROOT)
                .replaceFirst("^(t|time)#", "")
                .replace("_", "");
        long total = 0;
        java.util.regex.Matcher mm = Pattern.compile("(\\d+)(ms|d|h|m|s)").matcher(t);
        while (mm.find()) {
            long n = Long.parseLong(mm.group(1));
            total += switch (mm.group(2)) {
                case "d" -> n * 86_400_000_000_000L;
                case "h" -> n * 3_600_000_000_000L;
                case "m" -> n * 60_000_000_000L;
                case "s" -> n * 1_000_000_000L;
                default -> n * 1_000_000L;
            };
        }
        return total;
    }
}
