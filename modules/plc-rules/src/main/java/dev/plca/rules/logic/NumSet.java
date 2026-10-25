package dev.plca.rules.logic;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A set of numbers: sorted, disjoint ranges with open or closed ends ({@code null} = unbounded). An integer set keeps
 * only closed ranges with integral ends, so {@code x > 5} is {@code [6, +inf)} and the ranges {@code [1, 3]} and
 * {@code [4, 5]} merge. Immutable.
 */
final class NumSet {

    /** {@code lo == null} is minus infinity, {@code hi == null} plus infinity. */
    record Range(BigDecimal lo, boolean loClosed, BigDecimal hi, boolean hiClosed) {
        boolean isPoint() {
            return lo != null && hi != null && lo.compareTo(hi) == 0;
        }
    }

    private static final Comparator<Range> BY_LOW = (a, b) -> {
        if (a.lo() == null || b.lo() == null) {
            return a.lo() == null ? (b.lo() == null ? 0 : -1) : 1;
        }
        int c = a.lo().compareTo(b.lo());
        return c != 0 ? c : Boolean.compare(b.loClosed(), a.loClosed());
    };

    private final List<Range> ranges;
    private final boolean integer;

    private NumSet(List<Range> ranges, boolean integer) {
        this.ranges = List.copyOf(ranges);
        this.integer = integer;
    }

    static NumSet all(boolean integer) {
        return new NumSet(List.of(new Range(null, false, null, false)), integer);
    }

    static NumSet empty(boolean integer) {
        return new NumSet(List.of(), integer);
    }

    /** {@code [lo, hi]}; either end may be null (unbounded). */
    static NumSet closed(BigDecimal lo, BigDecimal hi, boolean integer) {
        return normalized(List.of(new Range(lo, lo != null, hi, hi != null)), integer);
    }

    /** The values {@code x} with {@code x rel v}. */
    static NumSet of(Rel rel, BigDecimal v, boolean integer) {
        List<Range> r = switch (rel) {
            case EQ -> List.of(new Range(v, true, v, true));
            case NE -> List.of(new Range(null, false, v, false), new Range(v, false, null, false));
            case LT -> List.of(new Range(null, false, v, false));
            case LE -> List.of(new Range(null, false, v, true));
            case GT -> List.of(new Range(v, false, null, false));
            case GE -> List.of(new Range(v, true, null, false));
        };
        return normalized(r, integer);
    }

    boolean isInteger() {
        return integer;
    }

    List<Range> ranges() {
        return ranges;
    }

    boolean isEmpty() {
        return ranges.isEmpty();
    }

    boolean isAll() {
        return ranges.size() == 1
                && ranges.getFirst().lo() == null
                && ranges.getFirst().hi() == null;
    }

    boolean contains(BigDecimal v) {
        for (Range r : ranges) {
            boolean aboveLo = r.lo() == null || (r.loClosed() ? v.compareTo(r.lo()) >= 0 : v.compareTo(r.lo()) > 0);
            boolean belowHi = r.hi() == null || (r.hiClosed() ? v.compareTo(r.hi()) <= 0 : v.compareTo(r.hi()) < 0);
            if (aboveLo && belowHi) {
                return true;
            }
        }
        return false;
    }

    NumSet intersect(NumSet o) {
        List<Range> out = new ArrayList<>();
        for (Range a : ranges) {
            for (Range b : o.ranges) {
                Range lo = BY_LOW.compare(a, b) >= 0 ? a : b;
                Range hi = upper(a, b) <= 0 ? a : b;
                Range r = new Range(lo.lo(), lo.loClosed(), hi.hi(), hi.hiClosed());
                if (!emptyRange(r)) {
                    out.add(r);
                }
            }
        }
        return normalized(out, integer && o.integer);
    }

    NumSet union(NumSet o) {
        List<Range> all = new ArrayList<>(ranges);
        all.addAll(o.ranges);
        return normalized(all, integer && o.integer);
    }

    /** Everything not in this set. */
    NumSet complement() {
        List<Range> out = new ArrayList<>();
        BigDecimal from = null;
        boolean fromClosed = false;
        boolean open = true; // whether the gap before the next range starts at minus infinity / after the last range
        for (Range r : ranges) {
            if (r.lo() != null) {
                Range gap = new Range(open ? null : from, !open && fromClosed, r.lo(), !r.loClosed());
                if (!emptyRange(gap)) {
                    out.add(gap);
                }
            }
            if (r.hi() == null) {
                return normalized(out, integer);
            }
            from = r.hi();
            fromClosed = !r.hiClosed();
            open = false;
        }
        out.add(new Range(open ? null : from, !open && fromClosed, null, false));
        return normalized(out, integer);
    }

    /**
     * The members when there are at most {@code limit} of them (an integer set with finite ranges, or a dense set made
     * of single points); else null.
     */
    List<BigDecimal> points(int limit) {
        List<BigDecimal> out = new ArrayList<>();
        for (Range r : ranges) {
            if (r.lo() == null || r.hi() == null) {
                return null;
            }
            if (!integer && !r.isPoint()) {
                return null;
            }
            for (BigDecimal v = r.lo(); v.compareTo(r.hi()) <= 0; v = v.add(BigDecimal.ONE)) {
                if (out.size() == limit) {
                    return null;
                }
                out.add(v);
                if (!integer) {
                    break;
                }
            }
        }
        return out;
    }

    /** -1 when {@code a} ends below {@code b}, 1 above, 0 at the same end. */
    private static int upper(Range a, Range b) {
        if (a.hi() == null || b.hi() == null) {
            return a.hi() == null ? (b.hi() == null ? 0 : 1) : -1;
        }
        int c = a.hi().compareTo(b.hi());
        return c != 0 ? c : Boolean.compare(a.hiClosed(), b.hiClosed());
    }

    private static boolean emptyRange(Range r) {
        if (r.lo() == null || r.hi() == null) {
            return false;
        }
        int c = r.lo().compareTo(r.hi());
        return c > 0 || c == 0 && !(r.loClosed() && r.hiClosed());
    }

    /** Integral closed ends for integer sets, empty ranges dropped, sorted, overlapping and touching ranges merged. */
    private static NumSet normalized(List<Range> in, boolean integer) {
        List<Range> rs = new ArrayList<>();
        for (Range r : in) {
            if (integer) {
                BigDecimal lo = r.lo() == null ? null : ceilInt(r.lo(), r.loClosed());
                BigDecimal hi = r.hi() == null ? null : floorInt(r.hi(), r.hiClosed());
                r = new Range(lo, lo != null, hi, hi != null);
            }
            if (!emptyRange(r)) {
                rs.add(r);
            }
        }
        rs.sort(BY_LOW);
        List<Range> out = new ArrayList<>();
        for (Range r : rs) {
            if (out.isEmpty()) {
                out.add(r);
                continue;
            }
            Range last = out.getLast();
            if (touches(last, r, integer)) {
                Range hi = upper(last, r) >= 0 ? last : r;
                out.set(out.size() - 1, new Range(last.lo(), last.loClosed(), hi.hi(), hi.hiClosed()));
            } else {
                out.add(r);
            }
        }
        return new NumSet(out, integer);
    }

    /** Whether {@code b} (starting at or after {@code a}) overlaps or touches {@code a}. */
    private static boolean touches(Range a, Range b, boolean integer) {
        if (a.hi() == null || b.lo() == null) {
            return true;
        }
        int c = b.lo().compareTo(integer ? a.hi().add(BigDecimal.ONE) : a.hi());
        if (integer) {
            return c <= 0;
        }
        return c < 0 || c == 0 && (a.hiClosed() || b.loClosed());
    }

    /** Smallest integer above {@code v} (at or above when {@code closed}). */
    private static BigDecimal ceilInt(BigDecimal v, boolean closed) {
        BigDecimal c = v.setScale(0, RoundingMode.CEILING);
        return !closed && c.compareTo(v) == 0 ? c.add(BigDecimal.ONE) : c;
    }

    private static BigDecimal floorInt(BigDecimal v, boolean closed) {
        BigDecimal f = v.setScale(0, RoundingMode.FLOOR);
        return !closed && f.compareTo(v) == 0 ? f.subtract(BigDecimal.ONE) : f;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Range r : ranges) {
            if (!sb.isEmpty()) {
                sb.append(" | ");
            }
            if (r.isPoint()) {
                sb.append(r.lo().toPlainString());
                continue;
            }
            sb.append(r.loClosed() ? '[' : '(')
                    .append(r.lo() == null ? "-inf" : r.lo().toPlainString())
                    .append(", ")
                    .append(r.hi() == null ? "+inf" : r.hi().toPlainString())
                    .append(r.hiClosed() ? ']' : ')');
        }
        return sb.isEmpty() ? "{}" : sb.toString();
    }
}
