package dev.plca.rules.logic;

import dev.plca.ast.Identifier;
import dev.plca.core.source.Span;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Equality of AST nodes as written, ignoring where they are: spans are skipped and names compared without case. Cheaper
 * than printing both sides, and it stops at the first difference.
 */
final class Structural {

    private static final ClassValue<MethodHandle[]> COMPONENTS = new ClassValue<>() {
        @Override
        protected MethodHandle[] computeValue(Class<?> type) {
            RecordComponent[] rc = type.getRecordComponents();
            MethodHandle[] r = new MethodHandle[rc.length];
            try {
                for (int i = 0; i < rc.length; i++) {
                    r[i] = MethodHandles.publicLookup().unreflect(rc[i].getAccessor());
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            return r;
        }
    };

    private Structural() {}

    static boolean equal(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            return listEqual(x, y);
        }
        if (a == null || b == null || a.getClass() != b.getClass()) {
            return false;
        }
        return switch (a) {
            case Span ignored -> true;
            case Identifier x ->
                x.name().equalsIgnoreCase(((Identifier) b).name()) && x.form() == ((Identifier) b).form();
            case Record x -> recordEqual(x, b);
            default -> a.equals(b);
        };
    }

    /** A hash consistent with {@link #equal}. */
    static int hash(Object a) {
        return switch (a) {
            case null -> 0;
            case Span ignored -> 0;
            case Identifier x -> x.name().toLowerCase(Locale.ROOT).hashCode();
            case List<?> x -> {
                int h = 1;
                for (Object o : x) {
                    h = 31 * h + hash(o);
                }
                yield h;
            }
            case Record x -> {
                int h = x.getClass().hashCode();
                for (MethodHandle m : COMPONENTS.get(x.getClass())) {
                    h = 31 * h + hash(get(m, x));
                }
                yield h;
            }
            default -> Objects.hashCode(a);
        };
    }

    private static boolean listEqual(List<?> a, List<?> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!equal(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean recordEqual(Record a, Object b) {
        for (MethodHandle m : COMPONENTS.get(a.getClass())) {
            if (!equal(get(m, a), get(m, b))) {
                return false;
            }
        }
        return true;
    }

    private static Object get(MethodHandle m, Object o) {
        try {
            return m.invoke(o);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
